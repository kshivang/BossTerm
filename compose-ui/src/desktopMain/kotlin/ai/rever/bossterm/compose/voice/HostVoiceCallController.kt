package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** What the host window shows while a call is up. */
internal enum class HostCallPhase { Idle, Connecting, Live, Error }

/** Everything the call UI renders, in one snapshot. */
internal data class HostCallState(
    val phase: HostCallPhase = HostCallPhase.Idle,
    /** The agent is talking right now. */
    val speaking: Boolean = false,
    /** One or more tool calls are running. */
    val working: Boolean = false,
    val muted: Boolean = false,
    /** What the agent is doing, for the bar's caption. */
    val activity: String? = null,
    /** Set with [HostCallPhase.Error]. */
    val error: String? = null,
) {
    val active: Boolean get() = phase == HostCallPhase.Connecting || phase == HostCallPhase.Live
}

/**
 * An in-app voice call: the host talks to its own BossTerm agent with no browser involved.
 *
 * Unlike the share-viewer path (browser ↔ OpenAI over WebRTC, ephemeral secret, tool calls relayed
 * over the share socket), this connects **directly** over the Realtime API's WebSocket transport
 * using the host's own key, streams PCM16 both ways through [VoiceAudioIo], and runs tool calls
 * in-process through the same [VoiceToolExecutor] the share path uses — so both surfaces expose
 * exactly one curated tool set.
 *
 * The transport and audio are injected, so the whole protocol state machine is testable without a
 * socket or a microphone.
 */
internal class HostVoiceCallController(
    private val scope: CoroutineScope,
    private val executor: VoiceToolExecutor,
    private val transport: RealtimeTransport = JdkRealtimeTransport(),
    private val audio: VoiceAudioIo = JavaSoundVoiceAudioIo(),
    private val settings: () -> TerminalSettings = { SettingsManager.instance.settings.value },
    private val loadKey: () -> String? = { VoiceAgentStorage.load()?.openaiApiKey?.takeIf { it.isNotBlank() } },
    /** Injected in tests so the ceilings can be exercised without waiting them out. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /**
     * How long to wait for a tool before answering on its behalf. Injected rather than exposing a
     * "fire the watchdog now" hook in production code — an internal seam like that quietly becomes
     * load-bearing. The values, and the ladder they sit in, are [VoiceToolTimeouts].
     */
    private val toolTimeoutMs: (String) -> Long = { name -> VoiceToolTimeouts.inAppMs(name) },
    /**
     * Called once when this call reaches a terminal state, however it got there — the user hung up,
     * a ceiling fired, or it failed.
     *
     * An explicit event because the owner cannot reliably infer one from [state]: `endWith` writes
     * Idle (inside [end]) and then Error back-to-back on one thread, and a conflating StateFlow
     * hands a collector on another dispatcher only the Error — so "saw it go Idle" never fired for
     * the ceiling path it was written for. Inferring a lifecycle from a conflating flow is the wrong
     * shape; this is the event the owner actually wants.
     */
    private val onTerminal: () -> Unit = {},
) {

    private val log = LoggerFactory.getLogger(HostVoiceCallController::class.java)

    private val _state = MutableStateFlow(HostCallState())
    val state: StateFlow<HostCallState> = _state.asStateFlow()

    /**
     * Mic loudness, deliberately a SEPARATE flow from [state]: it updates ~25×/second for the whole
     * call, and folding it into the snapshot made every state reader — including the composable that
     * owns terminal rendering — recompose at that rate. Only the level meter collects this.
     */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    // Tool-round bookkeeping, mirroring the viewer: a response.create while a response is still
    // generating is rejected (conversation_already_has_active_response), and one response can ask
    // for several tools — so exactly one follow-up turn per round, once all of them have answered.
    private val seenCalls = HashSet<String>()
    private val pendingCalls = HashSet<String>()
    private var responseOpen = false
    private var outputsOwed = 0
    private val roundLock = Any()

    /**
     * The connect coroutine. Stored so [end]/[fail] can cancel it: it was previously unstored, so
     * ending during "Connecting…" (which the bar offers) let it resume afterwards, open the mic, and
     * flip the state to Live — with the owner already dropped, nothing could ever stop that capture
     * again and the mic stayed hot for the rest of the process.
     */
    @Volatile private var startJob: Job? = null

    /**
     * Enforces the same ceilings the share path has ([VoiceCallService.MAX_CALL_DURATION_MS] and
     * friends): a hard cap, plus an idle cut-off. This surface holds the microphone AND bills the
     * host's account for the whole session, so "an abandoned call can't bill all day" applies at
     * least as strongly here.
     */
    @Volatile private var limitJob: Job? = null

    /** Last moment the call did anything — agent audio, user speech, or a tool call. */
    @Volatile private var lastActivityMs = 0L

    /**
     * Parent of everything this call launches — tool coroutines and their watchdogs. Cancelled by
     * [end]/[fail], so hanging up during a run_command doesn't leave it executing against the
     * terminal with a 630s watchdog parked behind it.
     */
    @Volatile private var callJob: CompletableJob? = null

    /** In-flight tool calls, capped like the share path's MAX_IN_FLIGHT_TOOL_CALLS. */
    private val inFlightTools = AtomicInteger(0)

    /**
     * The coroutine running each pending tool, so the watchdog can CANCEL it rather than just answer
     * the model.
     *
     * Answering alone left the work running: four wedged reads answered at 120s each and then held
     * all four slots for the executor's remaining ~8 minutes, so every later tool call got "wait for
     * one to finish" when nothing was going to finish. The cap protecting the host became an outage.
     */
    private val toolJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Fires [onTerminal] exactly once per call — end(), fail() and endWith() all funnel here. */
    private val terminalReported = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun reportTerminal() {
        if (terminalReported.compareAndSet(false, true)) runCatching { onTerminal() }
    }

    /** Start a call. No-op when one is already up. */
    fun start() {
        if (_state.value.active) return
        // Same precedence as VoiceCallService.status(): the switch, then the key — otherwise a
        // host with the feature off AND no key is told to add a key.
        if (!settings().voiceCallEnabled) {
            fail("Boss Calling is turned off in Settings.")
            return
        }
        _state.value = HostCallState(phase = HostCallPhase.Connecting)
        _level.value = 0f
        terminalReported.set(false)
        callJob = SupervisorJob()
        inFlightTools.set(0)
        synchronized(roundLock) {
            seenCalls.clear(); pendingCalls.clear(); responseOpen = false; outputsOwed = 0
        }
        startJob = scope.launch {
            // Read the key HERE, not on the click: this is a file read + JSON parse, and
            // VoiceAgentStorage's probe thread exists precisely to keep that off the Compose thread.
            val key = loadKey()
            if (key == null) {
                fail("Add an OpenAI API key in Settings → Session Sharing → Boss Calling.")
                return@launch
            }
            val s = settings()
            try {
                transport.connect(
                    model = s.voiceCallModel,
                    apiKey = key,
                    events = ::onEvent,
                    onClosed = { reason ->
                        if (_state.value.active) fail(reason?.let { "Connection closed ($it)" } ?: "Connection closed")
                    },
                )
            } catch (e: CancellationException) {
                // A hangup during connect cancels this job — that is a clean teardown, not a
                // failure. Catching it as an Exception below would surface "Couldn't reach OpenAI".
                throw e
            } catch (e: Exception) {
                fail("Couldn't reach OpenAI (${e.javaClass.simpleName})")
                return@launch
            }
            // Re-check after every suspension point: the user may have hung up while we waited.
            if (_state.value.phase != HostCallPhase.Connecting) return@launch
            // Guarded for the same reason the share path guards it: sessionUpdate() calls
            // executor.tools(), which forces the private MCP server's construction on first use. A
            // throw here escaped onto a SupervisorJob scope with no handler — the state stayed pinned
            // at "Calling…", the Realtime socket was already open and BILLING, and startLimits()
            // never ran, so neither ceiling was armed. The one failure mode with nothing behind it.
            try {
                // Checked like every other guaranteed-lane send: the queue is empty here so a
                // refusal isn't realistic, but an unconfigured session is a call that never works,
                // and the asymmetry is what drifts.
                if (!transport.send(sessionUpdate(s).toString())) {
                    fail("Couldn't configure the call.")
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Voice session config failed: {}", e.javaClass.simpleName)
                fail("Couldn't configure the call (${e.javaClass.simpleName})")
                return@launch
            }
            try {
                audio.startCapture(
                    onChunk = { chunk ->
                        if (!_state.value.muted) {
                            transport.send(
                                buildJsonObject {
                                    put("type", "input_audio_buffer.append")
                                    put("audio", Base64.getEncoder().encodeToString(chunk))
                                }.toString(),
                                evictable = true, // audio may be dropped; protocol frames may not
                            )
                        }
                    },
                    onLevel = { level -> _level.value = level },
                    // Losing the mic used to be silent: the bar kept saying "Listening…" with the
                    // meter frozen while the call billed on, deaf. End it and say so instead.
                    onEnded = { reason -> endWith("$reason — call ended.") },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Deliberately broad: VoiceAudioIo also rejects reuse with IllegalStateException, and
                // an unhandled throw here would leave the state pinned at Connecting with nothing
                // shown while landing on the shared scope.
                fail(e.message ?: "No microphone available")
                return@launch
            }
            // Only Connecting may become Live; anything else means this call was already torn down
            // and the capture we just started has to go with it.
            if (_state.value.phase != HostCallPhase.Connecting) {
                runCatching { audio.stop() }
                return@launch
            }
            // Re-check the master switch with the mic KNOWN open. The owner arms its kill-switch
            // collector around this coroutine, so a flip that lands while we are connecting can be
            // missed by both — and the failure mode is an open microphone nothing holds a reference
            // to. Cheap, and this is the last moment before the call is live.
            if (!settings().voiceCallEnabled) {
                fail("Boss Calling was turned off.")
                return@launch
            }
            _state.update { it.copy(phase = HostCallPhase.Live, activity = null) }
            startLimits()
        }
    }

    /** Hard cap + idle cut-off, checked on a slow tick so neither needs a precise timer. */
    private fun startLimits() {
        val startedAt = nowMs()
        touchActivity()
        limitJob?.cancel()
        limitJob = scope.launch {
            while (_state.value.active) {
                delay(LIMIT_TICK_MS)
                val now = nowMs()
                if (now - startedAt >= MAX_CALL_DURATION_MS) {
                    log.info("Boss Calling: ending in-app call at the {} min ceiling", MAX_CALL_DURATION_MS / 60_000)
                    endWith("Call ended — reached the ${MAX_CALL_DURATION_MS / 60_000} minute limit.")
                    return@launch
                }
                // A tool still running IS the call doing something. toolTimeoutMs("run_command") is
                // 10.5 min by design — longer than this cut-off — so an idle check that only looks
                // at "when did an event last arrive" hung up on the caller who asked for a long
                // build and then waited quietly for it, at almost the same instant the command's own
                // clamp expired. Touching (rather than merely skipping) also covers the seconds
                // between the tool answering and the agent starting to speak about it.
                if (toolsPending()) touchActivity()
                else if (now - lastActivityMs >= IDLE_TIMEOUT_MS) {
                    log.info("Boss Calling: ending idle in-app call")
                    endWith("Call ended — nothing happened for ${IDLE_TIMEOUT_MS / 60_000} minutes.")
                    return@launch
                }
            }
        }
    }

    private fun touchActivity() {
        lastActivityMs = nowMs()
    }

    /** Is a tool call still outstanding? Read by the idle cut-off — see [startLimits]. */
    private fun toolsPending(): Boolean = synchronized(roundLock) { pendingCalls.isNotEmpty() }

    /** End the call and leave the reason on the bar, rather than vanishing silently. */
    private fun endWith(message: String) {
        end()
        _state.value = HostCallState(phase = HostCallPhase.Error, error = message)
    }

    fun toggleMute() {
        if (!_state.value.active) return
        val muted = !_state.value.muted
        _state.update { it.copy(muted = muted) }
        // Stop the LINE, not just the send: skipping the append was already enough for correctness,
        // but it left the platform's microphone indicator lit for the whole muted stretch — which
        // reads as "still listening" to exactly the user who just pressed Mute.
        runCatching { audio.setCaptureMuted(muted) }
        if (muted) _level.value = 0f
    }

    /** End the call and release the mic + speaker. */
    fun end() {
        val wasActive = _state.value.active
        limitJob?.cancel()
        limitJob = null
        // State FIRST: transport.close() triggers the socket's onClose, and with the state still
        // "active" that callback would flip a deliberately-ended call to Error.
        _state.value = HostCallState()
        _level.value = 0f
        startJob?.cancel()
        startJob = null
        // Takes the tool coroutines and their watchdogs with it.
        callJob?.cancel()
        callJob = null
        runCatching { audio.stop() }
        runCatching { transport.close() }
        runCatching { executor.dispose() } // releases this call's private MCP server
        if (wasActive) log.info("Boss Calling: in-app call ended")
        reportTerminal()
    }

    private fun fail(message: String) {
        // Cancel the ceiling watcher, exactly as end() does. Relying on its `while (active)` check
        // was not enough: that runs AFTER delay(LIMIT_TICK_MS), so one more tick fires against a call
        // that already failed — and since startLimits() is only reached again when a redial goes
        // Live, the stale watcher keeps its old startedAt/lastActivityMs through the next call's
        // Connecting phase. The visible symptom is endWith clobbering a real error ("The host's
        // OpenAI key was rejected") with "Call ended — nothing happened for 10 minutes".
        limitJob?.cancel()
        limitJob = null
        startJob?.cancel()
        startJob = null
        callJob?.cancel()
        callJob = null
        runCatching { audio.stop() }
        runCatching { transport.close() }
        runCatching { executor.dispose() }
        _state.value = HostCallState(phase = HostCallPhase.Error, error = message)
        _level.value = 0f
        reportTerminal()
    }

    /** One server event. Runs on the transport's callback thread. */
    private fun onEvent(text: String) {
        val event = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (event["type"]?.jsonPrimitive?.content) {
            "response.created" -> synchronized(roundLock) { responseOpen = true }

            "response.output_audio.delta" -> {
                val b64 = event["delta"]?.jsonPrimitive?.content ?: return
                val pcm = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return
                touchActivity()
                _state.update { if (it.speaking) it else it.copy(speaking = true) }
                audio.play(pcm)
            }

            "response.output_audio.done" -> _state.update { it.copy(speaking = false) }

            // Barge-in: the user started talking, so drop whatever the agent still has queued
            // instead of letting the two of them talk over each other.
            "input_audio_buffer.speech_started" -> {
                touchActivity()
                audio.flushPlayback()
                _state.update { it.copy(speaking = false) }
            }

            "response.function_call_arguments.done" -> handleFunctionCall(
                callId = event["call_id"]?.jsonPrimitive?.content,
                name = event["name"]?.jsonPrimitive?.content,
                argsJson = event["arguments"]?.jsonPrimitive?.content,
            )

            "response.done" -> {
                val outputs = runCatching {
                    event["response"]?.jsonObject?.get("output")?.jsonArray
                }.getOrNull().orEmpty()
                for (item in outputs) {
                    val obj = runCatching { item.jsonObject }.getOrNull() ?: continue
                    if (obj["type"]?.jsonPrimitive?.content != "function_call") continue
                    handleFunctionCall(
                        callId = obj["call_id"]?.jsonPrimitive?.content,
                        name = obj["name"]?.jsonPrimitive?.content,
                        argsJson = obj["arguments"]?.jsonPrimitive?.content,
                    )
                }
                synchronized(roundLock) { responseOpen = false }
                maybeRequestResponse()
            }

            "error" -> {
                val err = runCatching { event["error"]?.jsonObject }.getOrNull()
                fun field(name: String) = runCatching { err?.get(name)?.jsonPrimitive?.content }.getOrNull()
                val message = field("message")
                val code = field("code")
                val param = field("param")
                log.warn("Realtime error: code={} param={} message={}", code, param, message ?: "(none)")
                // Classify on the structured fields, not by substring-matching prose: a rejected
                // session config names the offending `session.*` param, and auth/quota failures come
                // with a code. Anything else is a per-turn hiccup — but surface it in the bar rather
                // than only logging it, or a call that keeps failing just looks like silence.
                // Fatality is decided by the session param and an explicit code list ONLY. Matching
                // on type was too broad: conversation_already_has_active_response — the very error the
                // round bookkeeping exists to avoid — is an `invalid_request_error`, so a recoverable
                // per-turn rejection would have killed the whole call.
                val fatal = param?.startsWith("session.") == true || code in FATAL_ERROR_CODES
                if (fatal) {
                    fail(message ?: "The call was rejected (${code ?: param ?: "unknown"})")
                } else if (message != null) {
                    _state.update { it.copy(activity = message.take(60)) }
                }
            }
        }
    }

    private fun handleFunctionCall(callId: String?, name: String?, argsJson: String?) {
        if (callId == null || name == null) return
        // This path calls the executor directly, so VoiceCallService's re-read doesn't cover it:
        // check the switch here as well, or a call in flight keeps running tools after it is off.
        if (!settings().voiceCallEnabled) {
            fail("Boss Calling was turned off.")
            return
        }
        synchronized(roundLock) {
            // Trimmed so a long call can't grow it without bound; anything pending is preserved.
            if (seenCalls.size > 400) seenCalls.retainAll(pendingCalls)
            if (!seenCalls.add(callId)) return
            pendingCalls.add(callId)
            responseOpen = true // a function call only exists inside a response
        }
        // Grab the per-call job FIRST: claiming the slot and then bailing on a null job (reachable
        // when a function-call event lands just after end()/fail() nulled it) leaked the slot. Also
        // undo the pending-round bookkeeping done above, or the round stays pending forever — the
        // call is torn down either way, but leaving inconsistent state invites a later bug.
        val parent = callJob ?: run {
            synchronized(roundLock) { pendingCalls.remove(callId); seenCalls.remove(callId) }
            return
        }
        // Same ceiling as the share path: the MCP handlers behind these are not cheap, and an
        // unbounded fan-out onto Dispatchers.Default is exactly what the share path refuses.
        val admitted = inFlightTools.getAndUpdate { n ->
            if (n >= MAX_IN_FLIGHT_TOOLS) n else n + 1
        } < MAX_IN_FLIGHT_TOOLS
        if (!admitted) {
            log.warn("Voice tool {} refused: {} already in flight", name, MAX_IN_FLIGHT_TOOLS)
            answerWithError(callId, "Too many tool calls in flight; wait for one to finish.")
            return
        }
        touchActivity()
        _state.update { it.copy(working = true, activity = describeTool(name, argsJson)) }
        val timeoutMs = toolTimeoutMs(name)
        val job = scope.launch(parent) {
            val watchdog = scope.launch(parent) {
                delay(timeoutMs)
                toolTimedOut(callId, name)
            }
            val args = runCatching { json.parseToJsonElement(argsJson ?: "{}").jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            // Cancellation (end()/fail() cancelling callJob mid-tool) must unwind rather than become
            // a normal error result that then settles the round and sends on a closed transport.
            val result = try {
                clampToolResult(name, executor.execute(name, args, defaultTabId = null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) }.toString()
            }
            watchdog.cancel()
            // Claim the call BEFORE sending: the watchdog may already have answered it, and a second
            // function_call_output for one call_id is a protocol error.
            val done = synchronized(roundLock) {
                if (!pendingCalls.remove(callId)) return@launch // the watchdog already answered
                outputsOwed += 1
                pendingCalls.isEmpty()
            }
            if (!sendToolOutput(callId, result)) return@launch
            if (done) _state.update { it.copy(working = false, activity = null) }
            maybeRequestResponse()
        }
        toolJobs[callId] = job
        job.invokeOnCompletion {
            inFlightTools.decrementAndGet()
            toolJobs.remove(callId)
        }
    }

    /** Answer a call we refused outright, so the round doesn't hang waiting for it. */
    private fun answerWithError(callId: String, message: String) {
        val settled = synchronized(roundLock) {
            if (!pendingCalls.remove(callId)) return
            outputsOwed += 1
            pendingCalls.isEmpty()
        }
        val delivered = sendToolOutput(callId, buildJsonObject { put("error", message) }.toString())
        if (settled) _state.update { it.copy(working = false, activity = null) }
        if (!delivered) return // the call is already failing; don't ask for a turn on a dead socket
        maybeRequestResponse()
    }

    /**
     * Send one function_call_output, ending the call if the guaranteed lane refused it.
     *
     * Every caller has already settled the round locally (pendingCalls cleared, outputsOwed
     * incremented), so a dropped frame is indistinguishable from a hang — the same reasoning the
     * success path applies, now applied by all of them.
     */
    private fun sendToolOutput(callId: String, output: String): Boolean {
        val delivered = transport.send(
            buildJsonObject {
                put("type", "conversation.item.create")
                putJsonObject("item") {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", output)
                }
            }.toString()
        )
        if (!delivered) fail("Lost the connection to OpenAI mid-call.")
        return delivered
    }

    /**
     * Answer a tool call the executor never returned from, so the round can't hang.
     *
     * The browser path has had this since a lost reply could pin it at "Working…"; the in-app path
     * had no watchdog at all, so a wedged tool left the call silent with no recovery but End call.
     */
    private fun toolTimedOut(callId: String, tool: String) {
        // Claim first, same as the success path, so exactly one of the two answers this call.
        val settled = synchronized(roundLock) {
            if (!pendingCalls.remove(callId)) return
            outputsOwed += 1
            pendingCalls.isEmpty()
        }
        log.warn("Voice tool {} did not answer in time", tool)
        // Cancel the work, not just the wait: this is what actually returns the in-flight slot (and
        // stops a wedged run_command holding the terminal after the call has moved on).
        toolJobs.remove(callId)?.cancel()
        val delivered = sendToolOutput(
            callId,
            buildJsonObject { put("error", "This tool did not answer in time.") }.toString(),
        )
        if (settled) _state.update { it.copy(working = false, activity = null) }
        if (!delivered) return
        maybeRequestResponse()
    }

    /** Ask for the follow-up turn exactly once per round — see [responseOpen]/[outputsOwed]. */
    private fun maybeRequestResponse() {
        val ask = synchronized(roundLock) {
            if (responseOpen || pendingCalls.isNotEmpty() || outputsOwed == 0) false
            else { outputsOwed = 0; true }
        }
        // A dropped response.create leaves the agent silent forever — same treatment as a dropped
        // tool output rather than a log line.
        if (ask && !transport.send("""{"type":"response.create"}""")) {
            fail("Lost the connection to OpenAI mid-call.")
        }
    }

    private fun sessionUpdate(s: TerminalSettings): JsonObject {
        val tools = executor.tools()
        return buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "realtime")
                put("model", s.voiceCallModel)
                // Same as the share path's mint body: without it an agent that decides to answer in
                // text would go silent on a voice-only surface, and this is the first place to look.
                putJsonArray("output_modalities") { add("audio") }
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", 24_000)
                        }
                        putJsonObject("turn_detection") { put("type", "semantic_vad") }
                    }
                    putJsonObject("output") {
                        // `rate` is required here too — the docs' example omits it on the output
                        // side, and the session is rejected with
                        // "Missing required parameter: 'session.audio.output.format.rate'".
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", 24_000)
                        }
                        put("voice", s.voiceCallVoice)
                    }
                }
                put("instructions", hostInstructions(tools))
                put("tools", VoiceToolCatalog.openAiToolsJson(tools))
                put("tool_choice", "auto")
            }
        }
    }

    /**
     * The host is talking about their own machine, so the framing differs from the share viewer's:
     * no remote guest, and "the terminal in front of you" is literally true.
     *
     * NOTE: the sibling of [ai.rever.bossterm.compose.voice.VoiceCallService]'s share-viewer template.
     * The framing differs deliberately (owner vs remote guest); the RULES should not drift apart.
     */
    private fun hostInstructions(tools: List<VoiceToolDef>): String {
        val names = tools.map { it.name }.toSet()
        val snapshot = runCatching { executor.contextSnapshot(null) }.getOrDefault("")
        return buildString {
            appendLine("You are Boss, a voice assistant built into the user's own BossTerm terminal.")
            appendLine("They are speaking to you from the app, looking at the terminal you can inspect.")
            appendLine()
            appendLine("Session snapshot (may be stale — use tools for fresh data):")
            appendLine(snapshot.ifBlank { "- (no tabs visible)" })
            appendLine("Default tool calls to the tab they are viewing (omit tab_id).")
            appendLine()
            append(voiceAgentRules(names, confirmationWording = "spoken"))
        }
    }

    /** NOTE: mirrored by `voiceDescribeTool` in viewer.js for the share surface — keep both in step. */
    private fun describeTool(name: String, argsJson: String?): String {
        val args = runCatching { json.parseToJsonElement(argsJson ?: "{}").jsonObject }.getOrNull()
        fun arg(key: String) = args?.get(key)?.jsonPrimitive?.content
        return when (name) {
            "run_command" -> arg("script")?.let { "Running: ${it.take(48)}" } ?: "Running a command…"
            "read_scrollback" -> "Reading the terminal…"
            "search_output" -> arg("pattern")?.let { "Searching for “${it.take(32)}”…" } ?: "Searching…"
            "send_input" -> "Typing…"
            "send_signal" -> arg("signal")?.let { "Sending $it…" } ?: "Sending a signal…"
            "get_last_command" -> "Checking the last command…"
            "list_panes" -> "Looking at the split panes…"
            else -> "Working…"
        }
    }

    internal companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Hard ceiling on one in-app call, mirroring the share path's. */
        const val MAX_CALL_DURATION_MS = 60 * 60 * 1000L

        /** No agent audio, user speech or tool call for this long → hang up. */
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        /** Coarse tick: neither ceiling needs second-level precision. */
        const val LIMIT_TICK_MS = 5_000L

        /** Concurrent tool calls, matching [VoiceCallService]'s cap. */
        const val MAX_IN_FLIGHT_TOOLS = 4

        /** `error.code` values that end a call rather than one turn of it. */
        val FATAL_ERROR_CODES = setOf(
            "invalid_api_key",
            "insufficient_quota",
            "session_expired",
        )
    }
}
