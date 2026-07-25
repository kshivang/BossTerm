package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.Base64

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
    /** 0..1 microphone loudness, for the level meter. */
    val level: Float = 0f,
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
) {

    private val log = LoggerFactory.getLogger(HostVoiceCallController::class.java)

    private val _state = MutableStateFlow(HostCallState())
    val state: StateFlow<HostCallState> = _state.asStateFlow()

    // Tool-round bookkeeping, mirroring the viewer: a response.create while a response is still
    // generating is rejected (conversation_already_has_active_response), and one response can ask
    // for several tools — so exactly one follow-up turn per round, once all of them have answered.
    private val seenCalls = HashSet<String>()
    private val pendingCalls = HashSet<String>()
    private var responseOpen = false
    private var outputsOwed = 0
    private val roundLock = Any()

    /** Start a call. No-op when one is already up. */
    fun start() {
        if (_state.value.active) return
        val key = loadKey() ?: run {
            fail("Add an OpenAI API key in Settings → Session Sharing → Boss Calling.")
            return
        }
        if (!settings().voiceCallEnabled) {
            fail("Boss Calling is turned off in Settings.")
            return
        }
        _state.value = HostCallState(phase = HostCallPhase.Connecting)
        synchronized(roundLock) {
            seenCalls.clear(); pendingCalls.clear(); responseOpen = false; outputsOwed = 0
        }
        scope.launch {
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
            } catch (e: Exception) {
                fail("Couldn't reach OpenAI (${e.javaClass.simpleName})")
                return@launch
            }
            transport.send(sessionUpdate(s).toString())
            try {
                audio.startCapture(
                    onChunk = { chunk ->
                        if (!_state.value.muted) {
                            transport.send(
                                buildJsonObject {
                                    put("type", "input_audio_buffer.append")
                                    put("audio", Base64.getEncoder().encodeToString(chunk))
                                }.toString()
                            )
                        }
                    },
                    onLevel = { level -> _state.value = _state.value.copy(level = level) },
                )
            } catch (e: VoiceAudioException) {
                fail(e.message ?: "No microphone available")
                return@launch
            }
            _state.value = _state.value.copy(phase = HostCallPhase.Live, activity = null)
        }
    }

    fun toggleMute() {
        if (!_state.value.active) return
        _state.value = _state.value.copy(muted = !_state.value.muted)
    }

    /** End the call and release the mic + speaker. */
    fun end() {
        val wasActive = _state.value.active
        runCatching { audio.stop() }
        runCatching { transport.close() }
        _state.value = HostCallState()
        if (wasActive) log.info("Boss Calling: in-app call ended")
    }

    private fun fail(message: String) {
        runCatching { audio.stop() }
        runCatching { transport.close() }
        _state.value = HostCallState(phase = HostCallPhase.Error, error = message)
    }

    /** One server event. Runs on the transport's callback thread. */
    private fun onEvent(text: String) {
        val event = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (event["type"]?.jsonPrimitive?.content) {
            "response.created" -> synchronized(roundLock) { responseOpen = true }

            "response.output_audio.delta" -> {
                val b64 = event["delta"]?.jsonPrimitive?.content ?: return
                val pcm = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return
                if (!_state.value.speaking) _state.value = _state.value.copy(speaking = true)
                audio.play(pcm)
            }

            "response.output_audio.done" -> _state.value = _state.value.copy(speaking = false)

            // Barge-in: the user started talking, so drop whatever the agent still has queued
            // instead of letting the two of them talk over each other.
            "input_audio_buffer.speech_started" -> {
                audio.flushPlayback()
                _state.value = _state.value.copy(speaking = false)
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
                val message = runCatching {
                    event["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                log.warn("Realtime error: {}", message ?: "(none)")
                // Session-level errors are fatal; a per-turn hiccup isn't worth dropping a call.
                if (message?.contains("session", ignoreCase = true) == true) fail(message)
            }
        }
    }

    private fun handleFunctionCall(callId: String?, name: String?, argsJson: String?) {
        if (callId == null || name == null) return
        synchronized(roundLock) {
            if (!seenCalls.add(callId)) return
            pendingCalls.add(callId)
            responseOpen = true // a function call only exists inside a response
        }
        _state.value = _state.value.copy(working = true, activity = describeTool(name, argsJson))
        scope.launch {
            val args = runCatching { json.parseToJsonElement(argsJson ?: "{}").jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            val result = runCatching { executor.execute(name, args, defaultTabId = null) }
                .getOrElse { e ->
                    buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) }.toString()
                }
            transport.send(
                buildJsonObject {
                    put("type", "conversation.item.create")
                    putJsonObject("item") {
                        put("type", "function_call_output")
                        put("call_id", callId)
                        put("output", result)
                    }
                }.toString()
            )
            val done = synchronized(roundLock) {
                pendingCalls.remove(callId)
                outputsOwed += 1
                pendingCalls.isEmpty()
            }
            if (done) _state.value = _state.value.copy(working = false, activity = null)
            maybeRequestResponse()
        }
    }

    /** Ask for the follow-up turn exactly once per round — see [responseOpen]/[outputsOwed]. */
    private fun maybeRequestResponse() {
        val ask = synchronized(roundLock) {
            if (responseOpen || pendingCalls.isNotEmpty() || outputsOwed == 0) false
            else { outputsOwed = 0; true }
        }
        if (ask) transport.send("""{"type":"response.create"}""")
    }

    private fun sessionUpdate(s: TerminalSettings): JsonObject {
        val tools = executor.tools()
        return buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "realtime")
                put("model", s.voiceCallModel)
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", 24_000)
                        }
                        putJsonObject("turn_detection") { put("type", "semantic_vad") }
                    }
                    putJsonObject("output") {
                        putJsonObject("format") { put("type", "audio/pcm") }
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
            appendLine("Rules:")
            appendLine("- Inspect before you answer: read_scrollback" +
                    (if ("search_output" in names) " / search_output" else "") +
                    (if ("get_last_command" in names) " / get_last_command" else "") + ".")
            if ("run_command" in names) {
                appendLine("- Run shell commands with run_command; send_input only for interactive " +
                        "programs (TUIs, prompts); send_signal ctrl_c to interrupt.")
            }
            appendLine("- Say briefly what you are about to do before a slow tool call, and summarize " +
                    "results conversationally — never read raw terminal output verbatim.")
            appendLine("- For destructive commands (rm, kill, force-push, reset --hard), say the exact " +
                    "command and get spoken confirmation first.")
            append("- Keep replies short. This is a voice conversation.")
        }
    }

    private fun describeTool(name: String, argsJson: String?): String {
        val args = runCatching { json.parseToJsonElement(argsJson ?: "{}").jsonObject }.getOrNull()
        fun arg(key: String) = args?.get(key)?.jsonPrimitive?.content
        return when (name) {
            "run_command" -> arg("script")?.let { "Running: ${it.take(48)}" } ?: "Running a command…"
            "read_scrollback" -> "Reading the terminal…"
            "search_output" -> arg("pattern")?.let { "Searching for “${it.take(32)}”…" } ?: "Searching…"
            "send_input" -> "Typing…"
            "send_signal" -> arg("signal")?.let { "Sending $it…" } ?: "Sending a signal…"
            else -> "Working…"
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
