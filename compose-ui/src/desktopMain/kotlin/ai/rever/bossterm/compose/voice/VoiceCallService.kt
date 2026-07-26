package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
 * Whether a `voiceToolCall` may proceed on a connection.
 *
 * The token must be the one THIS connection was issued — not merely one live somewhere on the share,
 * or a second viewer presenting it would drive the read tools without passing handleStart's control
 * gate. Extracted from both servers' message handlers so the boundary is unit-testable: it lives
 * outside [VoiceCallService], which is exactly how it could be lost in a refactor.
 */
internal fun voiceCallTokenMatches(issuedToThisConnection: String?, presented: String?): Boolean =
    issuedToThisConnection != null && presented != null && presented == issuedToThisConnection

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
    /**
     * A specific call's tools have stopped working — it aged out of the duration ceiling.
     *
     * Separate from [onCallActivity], which is the host-facing count. This one is caller-facing: at
     * the ceiling the viewer otherwise just starts getting "No active call" on every tool with no
     * push at all, and the agent narrates something confusing. The servers turn it into a
     * `stale_call` for whichever connection holds that token, which the viewer already renders as
     * "That call is no longer active — start a new one."
     */
    private val onCallExpired: (token: String) -> Unit = {},
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
    internal class LiveCall(
        val issuedAt: Long,
        /**
         * Which service issued this call. The map is process-wide (one key, one spend ceiling), so
         * per-share teardown has to clear only its own entries — clearing the map stopped OTHER
         * shares' calls, leaving their audio running while every tool answered "No active call".
         */
        val owner: Any,
        var announced: Boolean = false,
    )

    /**
     * Current availability, for the viewer's Call button (sent on connect + on change).
     *
     * [withReason] gates the machine code: "disabled" vs "no_key" tells the reader whether the host
     * has an OpenAI key configured, which is host configuration a view-only guest has no business
     * knowing. Controllers get it because it's actionable for them; the host has
     * `VoiceAvailabilityLine` for the same diagnosis.
     */
    fun status(
        /** Whether the asking viewer may be told WHY — host configuration, not a guest's business. */
        withReason: Boolean,
        /**
         * Whether the asking connection completed the E2E handshake.
         *
         * Reported here as well as enforced in [handleStart] so a non-E2E viewer sees WHY up front
         * instead of a Call button that can only ever come back `insecure_transport`. The gate that
         * matters is still the one at start; this is the same fact, said earlier.
         *
         * NEITHER parameter has a default, deliberately. Both are PER-VIEWER, and a default meant a
         * call site could silently inherit "yes, confidential" — which is exactly what happened on
         * the two push paths: they were correct at admit and then broadcast one shared status on the
         * next settings change, flipping a plain-ws viewer to available with a button that could
         * only fail. Required arguments turn that into a compile error at every site.
         */
        confidential: Boolean,
        /**
         * Whether this viewer could start a call AT ALL on this surface.
         *
         * Defaults true because on a share with a mid-session control upgrade a view-only viewer
         * SHOULD see the button — clicking it asks for control, which is a real path. The daemon has
         * no such path, so offering it there leads to a dialog and then silence.
         */
        callable: Boolean = true,
    ): ServerMessage.VoiceStatus {
        val s = settings()
        val reason = when {
            // This service only ever serves REMOTE viewers, so both gates apply: the feature switch
            // and the share-surface switch.
            !s.voiceCallEnabled || !s.voiceCallShareEnabled -> "disabled"
            !keyPresent() -> "no_key"
            !confidential -> "insecure_transport"
            !callable -> "not_controller"
            else -> null
        }
        return ServerMessage.VoiceStatus(
            available = reason == null,
            // `withReason` redacts HOST CONFIGURATION — whether a key exists, whether the feature is
            // on. Two of these reasons are not that: they describe the asking CONNECTION, which the
            // viewer already knows about itself and can act on. Redacting those told a view-only
            // daemon viewer `available:false, reason:null`, i.e. no bar and no explanation — the
            // exact silence `callable` was added to prevent. (`withReason` is canControl and
            // not_controller needs !callable == !canControl, so the two were mutually exclusive:
            // that branch could never fire at all.)
            reason = reason?.takeIf { withReason || it in SELF_DESCRIBING_REASONS },
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
         * Whether this connection completed the E2E handshake — specifically that, not "is the
         * transport encrypted".
         *
         * The reply to a successful start carries the ephemeral `ek_…` secret. TLS alone is the
         * weaker guarantee for a shared session: these links are routinely published through a
         * tunnel (cloudflared, localhost.run) that TERMINATES TLS, so the relay would see the secret
         * in plaintext. The `#k` fragment never leaves the browser, so an E2E-encrypted frame stream
         * is the only state in which handing over a secret is sound — and a `wss://` viewer that
         * skipped the Kex is refused on purpose, not by oversight.
         *
         * The stock viewer also hides its Call button outside a secure context, but that is the
         * client policing itself; the host will mint for anything that asks.
         *
         * REQUIRED, like [canControl] beside it. A default of `true` on the parameter that decides
         * whether a credential goes onto a possibly-TLS-terminated tunnel is fail-open, and the
         * "existing callers" it would spare are exactly the two this feature introduced.
         */
        confidential: Boolean,
        /**
         * Called with the new token the moment a slot is reserved (BEFORE the mint, so a viewer that
         * drops mid-mint still has its reservation retired). The caller records it on the connection.
         */
        onCallTokenChanged: (String) -> Unit = {},
        /**
         * Clear the connection's token, but ONLY if it still holds [token].
         *
         * A late mint failure used to clear unconditionally: a redial during an in-flight mint left
         * the connection on token B, and mint #1's failure then nulled it — so mint #2 succeeded, the
         * viewer connected and was audible and billed, and every tool call answered stale_call.
         */
        clearCallTokenIfCurrent: (token: String) -> Unit = {},
        /**
         * Retire whatever call this connection already had. Invoked after validation and BEFORE the
         * new reservation, so a redial at capacity reclaims its own slot instead of being refused —
         * which is what "one connection holds at most one call" is supposed to guarantee.
         */
        retirePreviousCall: () -> Unit = {},
        reply: (ServerMessage) -> Unit,
    ) {
        if (!canControl) {
            reply(ServerMessage.VoiceError(code = "not_controller"))
            return
        }
        // Both of these run on the socket's receive loop, and on the DAEMON neither is purely a
        // stat: settings() and keyPresent() go through StampCachedValue, which re-reads and re-parses
        // the file whenever its stamp moved or its trust ticks ran out, under a monitor the 5s status
        // poller also takes. That cost is the price of cross-process revocation working at all, and
        // it is bounded — but the claim that these are the free checks and only loadKey() touches
        // disk was not true here. The key READ (and its decrypt-shaped JSON parse) is still deferred
        // to the coroutine below; what stays here is what has to gate the reservation.
        // Nothing below may reserve anything on a scope that is already going away: the Ktor receive
        // loop runs on the CALL's scope, not this one, so a voiceStart frame can still be dispatched
        // after MirrorShare.stop() cancelled `coro`. handleToolCall has always checked this; here it
        // was the one bail-out that didn't, and the consequences were worse — see the launch below.
        if (!scope.isActive) {
            reply(ServerMessage.VoiceError(code = "disabled"))
            return
        }
        // Before anything that costs money or reveals configuration: this connection cannot be
        // trusted with a secret, whatever its role.
        if (!confidential) {
            log.warn("Voice call refused: the viewer's connection is not encrypted")
            reply(ServerMessage.VoiceError(code = "insecure_transport"))
            return
        }
        val s = settings()
        if (!s.voiceCallEnabled || !s.voiceCallShareEnabled) {
            reply(ServerMessage.VoiceError(code = "disabled"))
            return
        }
        if (!keyPresent()) {
            reply(ServerMessage.VoiceError(code = "no_key"))
            return
        }
        // Order matters three ways here:
        //  1. PEEK the budget before touching anything — a rate_limited refusal used to run after
        //     retirePreviousCall(), so a redial the budget rejected destroyed the caller's live tool
        //     bridge while the viewer deliberately keeps such a call running (its audio carried on
        //     with every later tool call answering stale_call);
        //  2. retire before reserving, so a redial USUALLY reclaims its own slot instead of being
        //     refused at capacity. Not a guarantee: the two are separate critical sections on a
        //     process-wide map, so another share can take the freed slot in between and the
        //     redialling viewer loses its call AND gets too_many_calls. It needs MAX_LIVE_CALLS
        //     concurrent callers to reach, and closing it means holding the map's lock across
        //     retirePreviousCall — a caller-supplied callback — which is a worse trade than the
        //     race. Stated rather than fixed, so the next reader does not assume it is airtight;
        
        //  3. the budget is PEEKED first (see 1) and every refusal below refunds, which is how "no
        //     refusal consumes one of the mints it rations" is actually achieved — this used to say
        //     the budget was spent last, which describes the opposite of the code.
        val mintReservation = tryReserveMint()
        if (mintReservation == null) {
            log.warn("Voice session mint refused: rate limit")
            reply(ServerMessage.VoiceError(code = "rate_limited"))
            return
        }
        retirePreviousCall()
        val token = openCall()
        if (token == null) {
            log.warn("Voice call refused: {} calls already live", MAX_LIVE_CALLS)
            refundMint(mintReservation) // it never reached OpenAI, so it must not spend the budget
            reply(ServerMessage.VoiceError(code = "too_many_calls"))
            return
        }
        onCallTokenChanged(token)
        // Backstop, mirroring handleToolCall's: the check above closes the common case, but a
        // cancellation landing between it and here means `launch` schedules a body that never runs —
        // no reply, no refund, no closeCall. The token then held one of the PROCESS-WIDE live-call
        // slots for the full duration ceiling, denying capacity to every other share, and
        // removeViewer could not recover it because stop() had already emptied `viewers`.
        val bodyRan = AtomicBoolean(false)
        val job = scope.launch {
            bodyRan.set(true)
            val key = loadKey()
            if (key == null) {
                closeCall(token)
                clearCallTokenIfCurrent(token)
                // Same invariant as the too_many_calls refusal above: no request reached OpenAI, so
                // the reservation must go back. Every bail-out below this point holds to it too.
                refundMint(mintReservation)
                reply(ServerMessage.VoiceError(code = "no_key"))
                return@launch
            }
            // Everything from here on must either reply or hand the slot back. executor.tools() can
            // force the GUI executor to build its private MCP server, and a throw there used to kill
            // this coroutine with no reply at all — the viewer sat at "Connecting…" until its own
            // watchdog while the reservation leaked for the whole duration ceiling.
            val tools = try {
                executor.tools()
            } catch (e: CancellationException) {
                closeCall(token)
                refundMint(mintReservation)
                throw e
            } catch (e: Exception) {
                log.warn("Voice tool surface unavailable: {}", e.javaClass.simpleName)
                closeCall(token)
                clearCallTokenIfCurrent(token)
                refundMint(mintReservation)
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
                        clearCallTokenIfCurrent(token)
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
                    clearCallTokenIfCurrent(token)
                    reply(ServerMessage.VoiceError(code = "unauthorized"))
                }
                is VoiceSessionBroker.MintResult.RateLimited -> {
                    closeCall(token)
                    clearCallTokenIfCurrent(token)
                    // Same code as our own budget refusal: the caller's action is identical, and the
                    // viewer already renders it as "wait a moment and try again".
                    reply(ServerMessage.VoiceError(code = "rate_limited"))
                }
                is VoiceSessionBroker.MintResult.Failed -> {
                    closeCall(token)
                    clearCallTokenIfCurrent(token)
                    reply(ServerMessage.VoiceError(code = "mint_failed", message = result.message))
                }
            }
        }
        job.invokeOnCompletion {
            if (!bodyRan.get()) {
                // The body never started, so nothing inside it can have cleaned up.
                closeCall(token)
                clearCallTokenIfCurrent(token)
                refundMint(mintReservation)
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
        // Remote input: the result is clamped, but callId is echoed verbatim into VoiceToolResult and
        // a large enough frame would trip the control lane's overflow (closing the connection).
        if (msg.callId.length > MAX_CALL_ID_CHARS || msg.argsJson.length > MAX_ARGS_CHARS) {
            log.warn("Voice tool call refused: oversized frame")
            reply(toolError(msg.callId.take(MAX_CALL_ID_CHARS), "That tool call was too large."))
            return
        }
        // NOTE the master-switch re-read is NOT here — it moved into the coroutine below, beside
        // executor.tools(). On the daemon, settings() is a stat plus (when the stamp moves or trust
        // ticks expire) a full read+parse of settings.json under a monitor the 5s status poller also
        // takes, and this runs on the share socket's receive loop. It bought almost nothing:
        // pollVoiceStatus already calls closeCalls() within 5s of a toggle, after which isLiveCall
        // below refuses every subsequent call anyway. The check still happens before the tool runs.
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
                // The kill switch, re-read off the receive loop. Still before anything executes, so
                // turning Boss Calling off stops an agent that is already mid-call.
                val current = settings()
                if (!current.voiceCallEnabled || !current.voiceCallShareEnabled) {
                    closeCalls()
                    reply(toolError(msg.callId, "Boss Calling was turned off on the host"))
                    return@launch
                }
                // Off-thread: this is where an unadvertised tool is actually refused (the surface can
                // differ from the catalog — a read-only embedder config, a daemon share).
                if (executor.tools().none { it.name == def.name }) {
                    reply(toolError(msg.callId, "Unknown tool: ${def.name}"))
                    return@launch
                }
                // Bounded here, not just in the viewer: the viewer's own watchdog answers the MODEL
                // but can't reach this coroutine, so a wedged tool used to hold its in-flight slot
                // for the executor's much longer ceiling — four of those and every later call was
                // refused with "wait for one to finish" when nothing was going to finish.
                val resultJson = withTimeout(toolBudgetMs(def.name)) {
                    clampToolResult(def.name, executor.execute(def.name, args, defaultTabId))
                }
                reply(ServerMessage.VoiceToolResult(callId = msg.callId, resultJson = resultJson))
            } catch (e: TimeoutCancellationException) {
                log.warn("Voice tool {} exceeded its budget; releasing the slot", def.name)
                reply(toolError(msg.callId, "That tool took too long and was stopped."))
            } catch (e: VoiceToolException) {
                reply(toolError(msg.callId, e.message ?: "Tool call rejected"))
            } catch (e: CancellationException) {
                throw e // teardown, not a tool failure (CancellationException IS a RuntimeException)
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

    /**
     * The agent's system prompt. EVERY interpolated field goes through [VoiceContextSnapshot.field]:
     * a newline in any of them can close the structure and open what reads like a new instruction.
     * sessionName is host-typed today, so it was the one exemption — but it is the same rule, the
     * sanitizer costs nothing, and an exemption is what the next reader copies.
     */
    private fun buildInstructions(activeTabId: String?, tools: List<VoiceToolDef>): String {
        val names = tools.map { it.name }.toSet()
        val extra = runCatching { settings().voiceAgentExtraInstructions }.getOrNull()
        val snapshot = runCatching { executor.contextSnapshot(activeTabId) }.getOrDefault("")
        return buildString {
            appendLine("You are Boss, a voice assistant inside a shared BossTerm terminal session.")
            appendLine("A remote user is watching this terminal in a browser and talking to you.")
            appendLine()
            sessionName()?.let { appendLine("Session: ${VoiceContextSnapshot.field(it)}") }
            appendLine("Session snapshot (may be stale — use tools for fresh data):")
            appendLine(snapshot.ifBlank { "- (no tabs visible)" })
            appendLine("Default all tool calls to the tab the user is viewing (omit tab_id).")
            appendLine()
            append(voiceAgentRules(names, "verbal", userExtra = extra))
        }
    }

    /**
     * Claim one mint from the budget, or null when the gap or the window cap says no.
     *
     * A minimum gap plus a rolling window cap. Each mint is a billed
     * `POST /v1/realtime/client_secrets` against the host's key, and nothing else in the path costs
     * money, so this is the spend limiter — a reconnect loop or a viewer holding the button cannot
     * run up the bill.
     *
     * Peek and record are ONE critical section: split across two, two concurrent voiceStart frames
     * could both pass the gap check and the cap before either appended, so neither was actually
     * enforced. A caller that then refuses the call [refundMint]s.
     */
    private fun tryReserveMint(): Long? = synchronized(mintTimestamps) {
        val now = nowMs()
        while (mintTimestamps.isNotEmpty() && now - mintTimestamps.first() > MINT_WINDOW_MS) {
            mintTimestamps.removeFirst()
        }
        val lastMint = mintTimestamps.lastOrNull()
        if (lastMint != null && now - lastMint < MINT_MIN_GAP_MS) return null
        if (mintTimestamps.size >= MAX_MINTS_PER_WINDOW) return null
        mintTimestamps.addLast(now)
        now
    }

    /**
     * Hand a specific reserved mint back — the call was refused before it reached OpenAI.
     * By value, not "the last one": two concurrent starts reserving T1 and T2 meant the one refunding
     * T1 removed T2.
     */
    private fun refundMint(reservation: Long) = synchronized(mintTimestamps) {
        // Matching by value, not by a reservation id: two mints reserved in the same millisecond are
        // indistinguishable entries, so removing "the wrong one" leaves an identical deque. An id
        // would be more precise about which slot came back without changing what comes back.
        val at = mintTimestamps.lastIndexOf(reservation)
        if (at >= 0) mintTimestamps.removeAt(at)
    }

    /**
     * Register a freshly minted call and hand back the token the viewer must echo on tool calls.
     * Internal rather than private so tests can open a call without standing up a mint server.
     */
    internal fun openCall(): String? {
        ensureSweeper()
        // Report expiries this sweep retires: nothing else sweeps periodically, so a call that hits
        // the ceiling and isn't followed by a tool call was only reaped here — silently, breaking the
        // "ended always pairs started" invariant for the exact case that pairing exists to cover.
        val token = synchronized(liveCalls) { openCallLocked() }
        flushExpiredAnnouncements()
        return token
    }

    private fun openCallLocked(): String? {
        val now = nowMs()
        expiredAnnouncements += expireCalls(now)
        // Refuse rather than evict: dropping the oldest token left that caller's audio running while
        // every tool answered "No active call", with no way back but hanging up and redialling.
        //
        // Count only UN-EXPIRED entries. The sweep above is owner-filtered on purpose (see
        // [expireCalls]), so the map can still hold another share's aged-out calls — and checking the
        // raw size meant those denied capacity here until that share happened to sweep, which for a
        // share seeing no further voice traffic is never.
        val liveNow = liveCalls.count { now - it.value.issuedAt <= MAX_CALL_DURATION_MS }
        if (liveNow >= MAX_LIVE_CALLS) return null
        val token = UUID.randomUUID().toString()
        liveCalls[token] = LiveCall(issuedAt = now, owner = this)
        return token
    }

    /**
     * Sweeps expired calls while this service has any, then stops.
     *
     * [expireCalls] previously only ran from openCall() and isLiveCall(), both of which need inbound
     * voice traffic — so a purely CONVERSATIONAL call (no tool calls) that hit the duration ceiling
     * on a viewer that never disconnects left the entry live until the next voiceStart on this
     * share. `RemoteVoiceCalls.active` is a security indicator, not a statistic: it reading "on a
     * call" when the call's tools are long dead is worse than the untidy map it looks like.
     *
     * Self-limiting rather than a standing timer: started when a call opens, exits when this service
     * has none left, so a share that never hosts a call never ticks.
     */
    @Volatile private var sweeper: Job? = null

    /** Paired with [ensureSweeper] under the same monitor — the field had one writer outside it. */
    @Synchronized
    private fun stopSweeper() {
        sweeper?.cancel()
        sweeper = null
    }

    @Synchronized
    private fun ensureSweeper() {
        // Check-then-act otherwise: openCall() is reachable from two shares' receive loops at once,
        // so two sweepers could start and one handle be lost — closeCalls() then cancels only the
        // survivor. Benign (the orphan retires itself on its next `mine == 0`), but
        // DaemonShareServer.watchVoiceStatus already solves the identical problem with a lock.
        if (sweeper?.isActive == true) return
        sweeper = scope.launch {
            while (true) {
                delay(EXPIRY_SWEEP_MS)
                synchronized(liveCalls) { expiredAnnouncements += expireCalls(nowMs()) }
                // The one drain point, so the caller notifications queued under the lock cannot be
                // stranded by a path that only remembered the host's counter.
                flushExpiredAnnouncements()
                val mine = synchronized(liveCalls) {
                    liveCalls.count { it.value.owner === this@VoiceCallService }
                }
                if (mine == 0) return@launch
            }
        }
    }

    /**
     * Mark a reserved call as one the host was told about (so retiring it reports "ended"), and tell
     * them — but ONLY if the entry is still there to mark.
     *
     * The mint coroutine checks isLiveCall(token) and then calls this: two separate acquisitions of
     * the same lock. A closeCall from the socket receive loop (the viewer's voiceEnd, or its
     * connection dropping) or a closeCalls from the status poller can land in that window. The close
     * then sees announced == false and correctly reports nothing — and this went on to announce a
     * start for a call that no longer exists, with no end ever to follow it.
     *
     * The unpaired increment sticks: RemoteVoiceCalls has nothing to decrement it, so the Sharing
     * pill reads "on a call" for the rest of the process — on the counter whose entire purpose is
     * being a signal the host can trust.
     */
    private fun announceCall(token: String) {
        val marked = synchronized(liveCalls) {
            val call = liveCalls[token] ?: return@synchronized false
            call.announced = true
            true
        }
        if (marked) onCallActivity(true)
    }

    /**
     * The announcement step alone, for tests that need to interleave it with a close.
     *
     * The race it guards lives BETWEEN two lock acquisitions, so driving it through handleStart would
     * mean racing a real mint — this names the step instead.
     */
    internal fun announceCallForTest(token: String) = announceCall(token)

    /**
     * True for a token this host minted and that hasn't aged out. Tokens issued before this field
     * existed (an older viewer) are rejected — a tool call with no call behind it has no business
     * reaching the session.
     */
    private fun isLiveCall(token: String?): Boolean {
        val live = synchronized(liveCalls) {
            expiredAnnouncements += expireCalls(nowMs())
            isLiveCallLocked(token)
        }
        flushExpiredAnnouncements()
        return live
    }

    /** Pending "call ended" reports from ceiling expiries, fired outside the lock. */
    private var expiredAnnouncements = 0

    /** Tokens whose callers still need telling — drained by [flushExpiredAnnouncements]. */
    private val expiredTokens = ArrayDeque<String>()

    private fun flushExpiredAnnouncements() {
        val (pending, tokens) = synchronized(liveCalls) {
            val n = expiredAnnouncements
            expiredAnnouncements = 0
            val t = expiredTokens.toList()
            expiredTokens.clear()
            n to t
        }
        repeat(pending) { onCallActivity(false) }
        tokens.forEach { token -> runCatching { onCallExpired(token) } }
    }

    /**
     * Retire calls past the duration ceiling; @return how many of OUR OWN were announced, so the
     * caller can pair an "ended" with each "started".
     *
     * Removal is owner-filtered too, and deliberately so. Sweeping other shares' aged-out entries
     * would tidy the map — each holds a strong reference to the service that opened it — but an
     * ANNOUNCED foreign call would then be removed by us and never announced as ended by its owner,
     * which breaks the pairing invariant that several rounds of this branch were spent establishing.
     * A stale entry cannot deny capacity (see [openCallLocked], which counts only un-expired ones)
     * and both share teardown paths retire their own calls, so what is left is a bounded tidiness
     * problem — strictly better than a lost notification.
     */
    private fun expireCalls(now: Long): Int {
        val expired = liveCalls.filterValues { it.owner === this && now - it.issuedAt > MAX_CALL_DURATION_MS }
        expired.keys.forEach { liveCalls.remove(it) }
        // QUEUED, not invoked: this runs under the process-wide liveCalls monitor, and both servers'
        // callbacks scan viewers and take the outbox's own lock (which on overflow closes the
        // connection). A foreign callback plus a second lock under a monitor every share in the
        // process shares is a lock-ordering hazard one refactor from being real. onCallActivity was
        // deferred for exactly this reason — that is what expiredAnnouncements is — and this is its
        // sibling, so it gets the same treatment.
        expiredTokens += expired.keys
        return expired.values.count { it.announced }
    }

    private fun isLiveCallLocked(token: String?): Boolean {
        if (token == null) return false
        val call = liveCalls[token] ?: return false
        // Ownership as well as liveness: a token issued for another share must not drive this one's
        // tools just because both read the same process-wide map.
        if (call.owner !== this) return false
        // The duration ceiling is applied by expireCalls before this runs. NOTE the audio session
        // itself is browser↔OpenAI, so the host cannot hang that up — a viewer ignoring `voiceStatus`
        // keeps hearing the agent; it just loses every tool.
        return true
    }

    /**
     * Retire ONE call: the viewer hung up, or its connection dropped. Without this a token stayed
     * usable for its whole TTL, so a viewer that hung up kept a working handle for the read tools —
     * which reach further back than the mirrored screen does.
     */
    fun closeCall(token: String?) {
        if (token == null) return
        val wasAnnounced = synchronized(liveCalls) {
            // Only retire our own: another share's token isn't ours to end.
            val call = liveCalls[token]
            if (call == null || call.owner !== this) false
            else {
                liveCalls.remove(token)
                call.announced
            }
        }
        // Per call, not "when the last one goes": with two calls live on a share, hanging one up
        // used to produce no signal at all — and this notification is the counterpart of "call
        // started", which is doing security work.
        if (wasAnnounced) onCallActivity(false)
    }

    /**
     * Invalidate the calls THIS service issued — the host revoked the feature, or the share stopped.
     * Deliberately not `liveCalls.clear()`: the map is process-wide, and clearing it took other
     * shares' live calls down with it.
     */
    fun closeCalls() {
        stopSweeper()
        val announced = synchronized(liveCalls) {
            val mine = liveCalls.filterValues { it.owner === this }
            mine.keys.forEach { liveCalls.remove(it) }
            mine.values.count { it.announced }
        }
        // One "ended" per announced call, matching closeCall and the expiry sweep: collapsing to a
        // single notification meant two "started" and one "ended", and this pair is the host's only
        // signal that remote hands are on the machine.
        repeat(announced) { onCallActivity(false) }
    }

    /**
     * Internal, not private, so tests assert against the REAL ceilings — the test file used to carry
     * its own `= 8` mirror, which is a constant that can drift from the one being tested. Matches
     * [HostVoiceCallController]'s companion.
     */
    internal companion object {
        /** Shared by every share in this process — see [mintTimestamps]. */
        val sharedMintTimestamps = ArrayDeque<Long>()

        /** Shared by every share in this process — see [sharedCalls]. */
        val sharedLiveCalls = LinkedHashMap<String, LiveCall>()

        /**
         * Concurrent tool calls PER SHARE (deliberately, unlike the process-wide MAX_LIVE_CALLS):
         * this one protects the host's CPU and MCP handlers, which a single share can saturate, so
         * the unit is the share rather than the key.
         */
        const val MAX_IN_FLIGHT_TOOL_CALLS = 4

        /** See [VoiceToolTimeouts] — the whole deadline ladder, and why the order matters. */
        fun toolBudgetMs(tool: String): Long = VoiceToolTimeouts.hostMs(tool)

        /** OpenAI call ids are ~30 chars; this is generous and bounds what we echo back. */
        const val MAX_CALL_ID_CHARS = 200

        /** Arguments for the curated tools are small; run_command's script is the largest by far. */
        const val MAX_ARGS_CHARS = 32 * 1024

        /** How often a service with live calls checks whether any have aged out. */
        const val EXPIRY_SWEEP_MS = 30_000L

        /**
         * Reasons that describe the ASKING CONNECTION rather than the host's configuration, and so
         * are told to every viewer: they leak nothing a viewer does not already know about itself.
         */
        val SELF_DESCRIBING_REASONS = setOf("insecure_transport", "not_controller")

        /**
         * Mint budget: no faster than one per gap, and no more than N per window.
         *
         * This is the ONLY hard limit on what a remote caller can cost, because a minted session
         * outlives every other ceiling here (see [MAX_CALL_DURATION_MS]) — so it is deliberately
         * tighter than "enough for any plausible user". Twelve per ten minutes allowed ~70 billable
         * sessions an hour, none of them revocable; six still lets someone with a flaky connection
         * redial roughly every 100 seconds indefinitely, which is well past what dialling a phone
         * looks like.
         *
         * Shared PROCESS-WIDE, which makes it a cross-share DENIAL and not merely a spend cap: one
         * viewer redial-looping on share A locks out a legitimate caller on share B for the rest of
         * the window, and both see the same undifferentiated `rate_limited`. Deliberate — the budget
         * protects one API key, and per-share budgets would multiply the ceiling by the number of
         * shares — but a real tradeoff rather than an oversight. A per-connection sub-budget under
         * this one would keep the ceiling while making starvation local to whoever caused it. The
         * in-app surface is unaffected: it does not mint.
         */
        const val MINT_MIN_GAP_MS = 3_000L
        const val MINT_WINDOW_MS = 10 * 60 * 1000L
        const val MAX_MINTS_PER_WINDOW = 6

        /**
         * Hard ceiling on one REMOTE call — of TOOL ACCESS, not of spending. Read that literally:
         *
         * this retires the call token, which is what the tools authorise against. The audio session
         * is browser↔OpenAI and the host is not a party to it, so nothing here — not this ceiling,
         * not [closeCalls], not the master switch — can hang it up. The STOCK viewer cooperates (it
         * ends the call on `voiceStatus available:false` and on its own idle timeout), so for an
         * honest client revocation does stop the billing; for a client that ignores `voiceStatus`,
         * the session it already established keeps running and keeps billing, deaf and blind but
         * paid for.
         *
         * The real bound on what an unfriendly control-link holder can spend is therefore
         * [MAX_MINTS_PER_WINDOW] × session length, NOT this constant. Shorter than the in-app
         * ceiling ([HostVoiceCallController.MAX_CALL_DURATION_MS], 60 min) because that surface's
         * caller is the person at the keyboard and the host really can hang that one up.
         */
        const val MAX_CALL_DURATION_MS = 30 * 60 * 1000L

        /** Concurrent calls holding tool access on one key. Same caveat as [MAX_CALL_DURATION_MS]. */
        const val MAX_LIVE_CALLS = 8

        val json = Json { ignoreUnknownKeys = true }
    }
}
