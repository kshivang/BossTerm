package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Boss Calling glue shared by both share servers ([ai.rever.bossterm.compose.share.MirrorShare]
 * and [ai.rever.bossterm.compose.daemon.DaemonShareServer]): answers the viewer's voice messages
 * — mint an ephemeral Realtime session on `voiceStart`, execute agent tool calls on
 * `voiceToolCall` — enforcing the policy in one place: controller role required to start a call
 * and for every write tool, targets limited to the share's scope (via [executor]), the API key
 * never serialized into any reply.
 */
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
) {

    private val log = LoggerFactory.getLogger(VoiceCallService::class.java)
    private val inFlightToolCalls = AtomicInteger(0)

    // Mint rate limiting: every voiceStart is a billed request against the host's own key, so a
    // spamming (or reconnect-looping) controller must not be able to run it up.
    private val mintTimestamps = ArrayDeque<Long>()

    /**
     * Tokens for calls this host actually minted. A voiceToolCall must carry one, so the share
     * socket can't be used as a standing tool RPC by a viewer that never started a call.
     */
    private val liveCalls = LinkedHashMap<String, Long>()

    /** Current availability, for the viewer's Call button (sent on connect + on change). */
    fun status(): ServerMessage.VoiceStatus {
        val s = settings()
        return when {
            !s.voiceCallEnabled -> ServerMessage.VoiceStatus(available = false, reason = "disabled")
            !keyPresent() -> ServerMessage.VoiceStatus(available = false, reason = "no_key")
            else -> ServerMessage.VoiceStatus(available = true)
        }
    }

    /**
     * `voiceStart`: validate, then mint an ephemeral session off [scope] and reply
     * [ServerMessage.VoiceSession] (or [ServerMessage.VoiceError]). Non-suspending — both
     * servers call this from their WS receive loop. The caller records `msg.activeTabId` on
     * its connection and passes it back as `defaultTabId` on subsequent tool calls.
     */
    fun handleStart(msg: ClientMessage.VoiceStart, canControl: Boolean, reply: (ServerMessage) -> Unit) {
        if (!canControl) {
            reply(ServerMessage.VoiceError(code = "not_controller"))
            return
        }
        val s = settings()
        if (!s.voiceCallEnabled) {
            reply(ServerMessage.VoiceError(code = "disabled"))
            return
        }
        val key = loadKey()
        if (key == null) {
            reply(ServerMessage.VoiceError(code = "no_key"))
            return
        }
        if (!allowMint()) {
            log.warn("Voice session mint refused: rate limit")
            reply(ServerMessage.VoiceError(code = "rate_limited"))
            return
        }
        scope.launch {
            val tools = executor.tools()
            val result = broker.mint(
                apiKey = key,
                model = s.voiceCallModel,
                voice = s.voiceCallVoice,
                instructions = buildInstructions(msg.activeTabId, tools),
                tools = VoiceToolCatalog.openAiToolsJson(tools),
            )
            when (result) {
                is VoiceSessionBroker.MintResult.Ok -> {
                    val token = openCall()
                    if (token == null) {
                        log.warn("Voice call refused: {} calls already live", MAX_LIVE_CALLS)
                        reply(ServerMessage.VoiceError(code = "too_many_calls"))
                    } else {
                        reply(
                            ServerMessage.VoiceSession(
                                clientSecret = result.clientSecret,
                                model = s.voiceCallModel,
                                callToken = token,
                            )
                        )
                        onCallActivity(true)
                    }
                }
                is VoiceSessionBroker.MintResult.Unauthorized ->
                    reply(ServerMessage.VoiceError(code = "unauthorized"))
                is VoiceSessionBroker.MintResult.Failed ->
                    reply(ServerMessage.VoiceError(code = "mint_failed", message = result.message))
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
        if (!settings().voiceCallEnabled) {
            closeCalls()
            reply(toolError(msg.callId, "Boss Calling was turned off on the host"))
            return
        }
        if (!isLiveCall(msg.callToken)) {
            reply(toolError(msg.callId, "No active call — start a call before using tools"))
            return
        }
        val def = executor.tools().firstOrNull { it.name == msg.name }
        if (def == null) {
            reply(toolError(msg.callId, "Unknown tool: ${msg.name}"))
            return
        }
        if (def.write && !canControl) {
            reply(toolError(msg.callId, "The caller does not have control of this session"))
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
        scope.launch {
            try {
                val resultJson = executor.execute(def.name, args, defaultTabId)
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
        liveCalls.entries.removeAll { now - it.value > CALL_TOKEN_TTL_MS }
        // Refuse rather than evict: dropping the oldest token left that caller's audio running while
        // every tool answered "No active call", with no way back but hanging up and redialling.
        if (liveCalls.size >= MAX_LIVE_CALLS) return@synchronized null
        val token = UUID.randomUUID().toString()
        liveCalls[token] = now
        token
    }

    /**
     * True for a token this host minted and that hasn't aged out. Tokens issued before this field
     * existed (an older viewer) are rejected — a tool call with no call behind it has no business
     * reaching the session.
     */
    private fun isLiveCall(token: String?): Boolean = synchronized(liveCalls) {
        if (token == null) return false
        val issued = liveCalls[token] ?: return false
        if (nowMs() - issued > CALL_TOKEN_TTL_MS) {
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
        val remaining = synchronized(liveCalls) {
            liveCalls.remove(token)
            liveCalls.size
        }
        if (remaining == 0) onCallActivity(false)
    }

    /** Invalidate every live call — the host revoked the feature, or the last viewer left. */
    fun closeCalls() {
        val had = synchronized(liveCalls) {
            val any = liveCalls.isNotEmpty()
            liveCalls.clear()
            any
        }
        if (had) onCallActivity(false)
    }

    private companion object {
        const val MAX_IN_FLIGHT_TOOL_CALLS = 4

        /** Mint budget: no faster than one per gap, and no more than N per window. */
        const val MINT_MIN_GAP_MS = 3_000L
        const val MINT_WINDOW_MS = 10 * 60 * 1000L
        const val MAX_MINTS_PER_WINDOW = 12

        /**
         * How long a call token stays usable. Generous — a call can legitimately run much longer
         * than the 600s secret, which is only needed for the initial SDP handshake.
         */
        const val CALL_TOKEN_TTL_MS = 6 * 60 * 60 * 1000L
        const val MAX_LIVE_CALLS = 8

        val json = Json { ignoreUnknownKeys = true }
    }
}
