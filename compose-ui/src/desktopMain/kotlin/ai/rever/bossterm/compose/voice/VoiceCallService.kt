package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Boss Calling glue shared by both share servers ([ai.rever.bossterm.compose.share.MirrorShare]
 * and [ai.rever.bossterm.compose.daemon.DaemonShareServer]): answers the viewer's voice messages
 * — mint an ephemeral Realtime session on `voiceStart`, execute agent tool calls on
 * `voiceToolCall` — enforcing the policy in one place: controller role required to start a call
 * and for every write tool, targets limited to the share's scope (via [executor]), the API key
 * never serialized into any reply.
 */
/**
 * Whether a `voiceToolCall` may proceed on a connection.
 *
 * The token must be the one THIS connection was issued — not merely one live somewhere on the share,
 * or a second viewer presenting it would drive the read tools without passing handleStart's control
 * gate. Extracted from both servers' message handlers so the boundary is unit-testable: it lives
 * outside [VoiceCallService], which is exactly how it could be lost in a refactor.
 */
internal fun voiceCallTokenMatches(issuedToThisConnection: String?, presented: String?): Boolean =
    issuedToThisConnection != null && presented != null && presented == issuedToThisConnection

internal class VoiceCallService(
    private val executor: VoiceToolExecutor,
    private val scope: CoroutineScope,
    private val broker: VoiceSessionBroker = VoiceSessionBroker(),
    private val settings: () -> TerminalSettings = { SettingsManager.instance.settings.value },
    private val loadKey: () -> String? = { VoiceAgentStorage.load()?.openaiApiKey?.takeIf { it.isNotBlank() } },
    /**
     * Whether a key exists, for [status] only — separate from [loadKey] because status is
     * recomputed on every settings emission per share, and the GUI can answer from the in-process
     * flow instead of re-reading and re-parsing voice.json each time. The daemon has no such flow
     * and falls back to the disk read.
     */
    private val keyPresent: () -> Boolean = { loadKey() != null },
    /** The host's display name for this shared session, for the agent's instructions. */
    private val sessionName: () -> String? = { null },
    /** Injected in tests; wall-clock only feeds the mint rate limiter. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Notified when a call is minted (true) or its tools stop being usable (false). */
    private val onCallActivity: (Boolean) -> Unit = {},
    /**
     * Mint timestamps, shared process-wide by default: the budget protects ONE API key, so a host
     * with several shares open must not multiply the ceiling. Injectable so tests get their own.
     */
    private val mintTimestamps: ArrayDeque<Long> = sharedMintTimestamps,
    /**
     * Live calls, likewise process-wide: MAX_LIVE_CALLS is a spend ceiling on the same single key, so
     * per-share accounting let N shares run 8N concurrent billed sessions. Injectable for tests.
     */
    private val sharedCalls: LinkedHashMap<String, LiveCall>? = null,
) {

    private val log = LoggerFactory.getLogger(VoiceCallService::class.java)
    private val inFlightToolCalls = AtomicInteger(0)

    // Mint rate limiting: every voiceStart is a billed request against the host's own key, so a
    // spamming (or reconnect-looping) controller must not be able to run it up.

    /**
     * Tokens for calls this host actually minted. A voiceToolCall must carry one, so the share
     * socket can't be used as a standing tool RPC by a viewer that never started a call.
     */
    private val liveCalls: LinkedHashMap<String, LiveCall> get() = sharedCalls ?: sharedLiveCalls

    /**
     * [announced] records whether the host was told this call STARTED. A reservation that never
     * became a call (the mint failed) must not fire the "call ended" notification — one bad key
     * otherwise produced an "ended" toast per attempt for a call that never began.
     */
    internal class LiveCall(val issuedAt: Long, var announced: Boolean = false)

    /**
     * Current availability, for the viewer's Call button (sent on connect + on change).
     *
     * [withReason] gates the machine code: "disabled" vs "no_key" tells the reader whether the host
     * has an OpenAI key configured, which is host configuration a view-only guest has no business
     * knowing. Controllers get it because it's actionable for them; the host has
     * `VoiceAvailabilityLine` for the same diagnosis.
     */
    fun status(withReason: Boolean = true): ServerMessage.VoiceStatus {
        val s = settings()
        val reason = when {
            // This service only ever serves REMOTE viewers, so both gates apply: the feature switch
            // and the share-surface switch.
            !s.voiceCallEnabled || !s.voiceCallShareEnabled -> "disabled"
            !keyPresent() -> "no_key"
            else -> null
        }
        return ServerMessage.VoiceStatus(
            available = reason == null,
            reason = reason?.takeIf { withReason },
        )
    }

    /**
     * `voiceStart`: validate, then mint an ephemeral session off [scope] and reply
     * [ServerMessage.VoiceSession] (or [ServerMessage.VoiceError]). Non-suspending — both
     * servers call this from their WS receive loop. The caller records `msg.activeTabId` on
     * its connection and passes it back as `defaultTabId` on subsequent tool calls.
     */
    fun handleStart(
        msg: ClientMessage.VoiceStart,
        canControl: Boolean,
        /**
         * Called synchronously the moment a slot is reserved, BEFORE the mint. The caller records it
         * on the connection so a viewer that disconnects mid-mint still has its reservation retired
         * — waiting for the VoiceSession reply meant a drop during the mint leaked the slot for the
         * whole call-duration ceiling, and eight of those wedge the share.
         */
        onTokenReserved: (String) -> Unit = {},
        reply: (ServerMessage) -> Unit,
    ) {
        if (!canControl) {
            reply(ServerMessage.VoiceError(code = "not_controller"))
            return
        }
        val s = settings()
        if (!s.voiceCallEnabled || !s.voiceCallShareEnabled) {
            reply(ServerMessage.VoiceError(code = "disabled"))
            return
        }
        val key = loadKey()
        if (key == null) {
            reply(ServerMessage.VoiceError(code = "no_key"))
            return
        }
        // Slot first, budget second: a too_many_calls refusal costs nothing at OpenAI, so it must
        // not consume one of the mints the budget is there to ration (8 refusals used to leave only
        // 4 real mints in the window).
        val token = openCall()
        if (token == null) {
            log.warn("Voice call refused: {} calls already live", MAX_LIVE_CALLS)
            reply(ServerMessage.VoiceError(code = "too_many_calls"))
            return
        }
        onTokenReserved(token)
        if (!allowMint()) {
            log.warn("Voice session mint refused: rate limit")
            closeCall(token) // hand the slot back; this call never happened
            reply(ServerMessage.VoiceError(code = "rate_limited"))
            return
        }
        scope.launch {
            // Everything from here on must either reply or hand the slot back. executor.tools() can
            // force the GUI executor to build its private MCP server, and a throw there used to kill
            // this coroutine with no reply at all — the viewer sat at "Connecting…" until its own
            // watchdog while the reservation leaked for the whole duration ceiling.
            val tools = try {
                executor.tools()
            } catch (e: CancellationException) {
                closeCall(token)
                throw e
            } catch (e: Exception) {
                log.warn("Voice tool surface unavailable: {}", e.javaClass.simpleName)
                closeCall(token)
                reply(ServerMessage.VoiceError(code = "mint_failed", message = "Tools unavailable"))
                return@launch
            }
            val result = broker.mint(
                apiKey = key,
                model = s.voiceCallModel,
                voice = s.voiceCallVoice,
                instructions = buildInstructions(msg.activeTabId, tools),
                tools = VoiceToolCatalog.openAiToolsJson(tools),
            )
            when (result) {
                is VoiceSessionBroker.MintResult.Ok -> {
                    // The caller may have gone while we were minting (its reservation retired by
                    // VoiceEnd or a disconnect). Announcing then would report a call that doesn't
                    // exist — an unpaired "started" with no "ended" — and hand a secret to nobody.
                    // The mint is already billed either way; that cost is unavoidable.
                    if (!isLiveCall(token)) {
                        log.info("Voice session minted for a caller that already went away; discarding")
                        return@launch
                    }
                    reply(
                        ServerMessage.VoiceSession(
                            clientSecret = result.clientSecret,
                            model = s.voiceCallModel,
                            callToken = token,
                        )
                    )
                    announceCall(token)
                }
                is VoiceSessionBroker.MintResult.Unauthorized -> {
                    closeCall(token) // the reservation didn't become a call
                    reply(ServerMessage.VoiceError(code = "unauthorized"))
                }
                is VoiceSessionBroker.MintResult.Failed -> {
                    closeCall(token)
                    reply(ServerMessage.VoiceError(code = "mint_failed", message = result.message))
                }
            }
        }
    }

    /**
     * `voiceToolCall`: validate + execute off [scope], reply [ServerMessage.VoiceToolResult]
     * echoing the call id. Failures come back as `isError=true` with an `{"error":…}` payload so
     * the agent can recover verbally instead of the call dying.
     */
    fun handleToolCall(
        msg: ClientMessage.VoiceToolCall,
        canControl: Boolean,
        defaultTabId: String?,
        reply: (ServerMessage) -> Unit,
    ) {
        // The master switch is a kill switch: re-read it here, not just at status/start, so
        // turning Boss Calling off stops an agent that is already mid-call.
        val current = settings()
        if (!current.voiceCallEnabled || !current.voiceCallShareEnabled) {
            closeCalls()
            reply(toolError(msg.callId, "Boss Calling was turned off on the host"))
            return
        }
        if (!isLiveCall(msg.callToken)) {
            reply(toolError(msg.callId, "No active call — start a call before using tools"))
            return
        }
        // Resolve against the CATALOG, not the executor, on this thread: executor.tools() can force
        // the GUI executor to build its private MCP server (and apply the disabled set), and this
        // runs on the share socket's receive loop — the reason handleStart warms it asynchronously.
        // The executor still has the last word inside the coroutine below.
        val def = VoiceToolCatalog.ALL.firstOrNull { it.name == msg.name }
        if (def == null) {
            reply(toolError(msg.callId, "Unknown tool: ${msg.name}"))
            return
        }
        if (def.write && !canControl) {
            reply(toolError(msg.callId, "The caller does not have control of this session"))
            return
        }
        if (!scope.isActive) {
            reply(toolError(msg.callId, "This session is shutting down"))
            return
        }
        // Claim the slot atomically — get()-then-increment let two concurrent socket messages both
        // see room and push past the cap. getAndUpdate hands back the PREVIOUS count, so a value
        // below the cap means this call is the one that took the slot.
        val admitted = inFlightToolCalls.getAndUpdate { n ->
            if (n >= MAX_IN_FLIGHT_TOOL_CALLS) n else n + 1
        } < MAX_IN_FLIGHT_TOOL_CALLS
        if (!admitted) {
            reply(toolError(msg.callId, "Too many tool calls in flight; wait for one to finish"))
            return
        }
        val args = runCatching { json.parseToJsonElement(msg.argsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        // If [scope] is already cancelled the body never runs, so the claimed slot would leak;
        // release it from the completion handler in that case.
        val bodyRan = AtomicBoolean(false)
        val job = scope.launch {
            bodyRan.set(true)
            try {
                // Off-thread: this is where an unadvertised tool is actually refused (the surface can
                // differ from the catalog — a read-only embedder config, a daemon share).
                if (executor.tools().none { it.name == def.name }) {
                    reply(toolError(msg.callId, "Unknown tool: ${def.name}"))
                    return@launch
                }
                val resultJson = clampToolResult(def.name, executor.execute(def.name, args, defaultTabId))
                reply(ServerMessage.VoiceToolResult(callId = msg.callId, resultJson = resultJson))
            } catch (e: VoiceToolException) {
                reply(toolError(msg.callId, e.message ?: "Tool call rejected"))
            } catch (e: Exception) {
                log.warn("Voice tool {} failed: {}", def.name, e.javaClass.simpleName)
                reply(toolError(msg.callId, "Tool failed: ${e.javaClass.simpleName}"))
            } finally {
                inFlightToolCalls.decrementAndGet()
            }
        }
        job.invokeOnCompletion { if (!bodyRan.get()) inFlightToolCalls.decrementAndGet() }
    }

    private fun toolError(callId: String, message: String): ServerMessage.VoiceToolResult =
        ServerMessage.VoiceToolResult(
            callId = callId,
            resultJson = buildJsonObject { put("error", message) }.toString(),
            isError = true,
        )

    private fun buildInstructions(activeTabId: String?, tools: List<VoiceToolDef>): String {
        val names = tools.map { it.name }.toSet()
        val snapshot = runCatching { executor.contextSnapshot(activeTabId) }.getOrDefault("")
        return buildString {
            appendLine("You are Boss, a voice assistant inside a shared BossTerm terminal session.")
            appendLine("A remote user is watching this terminal in a browser and talking to you.")
            appendLine()
            sessionName()?.let { appendLine("Session: $it") }
            appendLine("Session snapshot (may be stale — use tools for fresh data):")
            appendLine(snapshot.ifBlank { "- (no tabs visible)" })
            appendLine("Default all tool calls to the tab the user is viewing (omit tab_id).")
            appendLine()
            appendLine("Rules:")
            appendLine("- Inspect before you answer: read_scrollback" +
                    (if ("search_output" in names) " / search_output" else "") +
                    (if ("get_last_command" in names) " / get_last_command" else "") + ".")
            if ("run_command" in names) {
                appendLine("- Run shell commands with run_command; use send_input only for interactive " +
                        "programs (TUIs, prompts); send_signal ctrl_c to interrupt.")
            } else {
                appendLine("- Run shell commands by typing them with send_input (include a trailing \\n " +
                        "to submit), then read_scrollback to see the result; send_signal ctrl_c to interrupt.")
            }
            appendLine("- Say briefly what you are about to do before a slow tool call, and summarize " +
                    "results conversationally — never read raw terminal output verbatim.")
            appendLine("- For destructive commands (rm, kill, force-push, reset --hard), say the exact " +
                    "command and get verbal confirmation first.")
            append("- Keep replies short. This is a voice conversation.")
        }
    }

    /**
     * Whether another mint is allowed: a minimum gap plus a rolling window cap. Each mint is a
     * billed `POST /v1/realtime/client_secrets` against the host's key, and nothing else in the
     * path costs money, so this is the spend limiter — a reconnect loop or a viewer holding the
     * button cannot run up the bill.
     */
    private fun allowMint(): Boolean = synchronized(mintTimestamps) {
        val now = nowMs()
        while (mintTimestamps.isNotEmpty() && now - mintTimestamps.first() > MINT_WINDOW_MS) {
            mintTimestamps.removeFirst()
        }
        val lastMint = mintTimestamps.lastOrNull()
        if (lastMint != null && now - lastMint < MINT_MIN_GAP_MS) return false
        if (mintTimestamps.size >= MAX_MINTS_PER_WINDOW) return false
        mintTimestamps.addLast(now)
        true
    }

    /**
     * Register a freshly minted call and hand back the token the viewer must echo on tool calls.
     * Internal rather than private so tests can open a call without standing up a mint server.
     */
    internal fun openCall(): String? = synchronized(liveCalls) {
        val now = nowMs()
        liveCalls.entries.removeAll { now - it.value.issuedAt > MAX_CALL_DURATION_MS }
        // Refuse rather than evict: dropping the oldest token left that caller's audio running while
        // every tool answered "No active call", with no way back but hanging up and redialling.
        if (liveCalls.size >= MAX_LIVE_CALLS) return@synchronized null
        val token = UUID.randomUUID().toString()
        liveCalls[token] = LiveCall(now)
        token
    }

    /** Mark a reserved call as one the host was told about (so retiring it reports "ended"). */
    private fun announceCall(token: String) {
        synchronized(liveCalls) { liveCalls[token]?.announced = true }
        onCallActivity(true)
    }

    /**
     * True for a token this host minted and that hasn't aged out. Tokens issued before this field
     * existed (an older viewer) are rejected — a tool call with no call behind it has no business
     * reaching the session.
     */
    private fun isLiveCall(token: String?): Boolean = synchronized(liveCalls) {
        if (token == null) return false
        val call = liveCalls[token] ?: return false
        // The duration ceiling lives here: nothing else bounds how long a call runs, and the whole
        // session bills to the host. Past it the tools stop answering. NOTE the audio session itself
        // is browser↔OpenAI, so the host cannot hang that up — a viewer ignoring `voiceStatus` keeps
        // hearing the agent; it just loses every tool.
        if (nowMs() - call.issuedAt > MAX_CALL_DURATION_MS) {
            liveCalls.remove(token)
            return false
        }
        true
    }

    /**
     * Retire ONE call: the viewer hung up, or its connection dropped. Without this a token stayed
     * usable for its whole TTL, so a viewer that hung up (or was later demoted to view-only) kept a
     * working handle for the read tools — which reach further back than the mirrored screen does.
     */
    fun closeCall(token: String?) {
        if (token == null) return
        val wasAnnounced = synchronized(liveCalls) { liveCalls.remove(token)?.announced == true }
        // Per call, not "when the last one goes": with two calls live on a share, hanging one up
        // used to produce no signal at all — and this notification is the counterpart of "call
        // started", which is doing security work.
        if (wasAnnounced) onCallActivity(false)
    }

    /** Invalidate every live call — the host revoked the feature, or the last viewer left. */
    fun closeCalls() {
        val hadAnnounced = synchronized(liveCalls) {
            val any = liveCalls.values.any { it.announced }
            liveCalls.clear()
            any
        }
        if (hadAnnounced) onCallActivity(false)
    }

    private companion object {
        /** Shared by every share in this process — see [mintTimestamps]. */
        val sharedMintTimestamps = ArrayDeque<Long>()

        /** Shared by every share in this process — see [sharedCalls]. */
        val sharedLiveCalls = LinkedHashMap<String, LiveCall>()

        const val MAX_IN_FLIGHT_TOOL_CALLS = 4

        /** Mint budget: no faster than one per gap, and no more than N per window. */
        const val MINT_MIN_GAP_MS = 3_000L
        const val MINT_WINDOW_MS = 10 * 60 * 1000L
        const val MAX_MINTS_PER_WINDOW = 12

        /**
         * Hard ceiling on one call. The mint budget bounds how OFTEN calls start; this bounds how
         * long one runs, which is what actually accrues cost on the host's account. Generous enough
         * for real work, finite so an abandoned call can't bill all day.
         */
        const val MAX_CALL_DURATION_MS = 60 * 60 * 1000L
        const val MAX_LIVE_CALLS = 8

        val json = Json { ignoreUnknownKeys = true }
    }
}
