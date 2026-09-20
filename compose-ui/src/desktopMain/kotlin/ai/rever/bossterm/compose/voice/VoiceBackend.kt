package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.TerminalSettings

/**
 * Which service carries a Boss Calling session.
 *
 * Both speak the **same wire protocol** — the OpenAI Realtime event set — which is the only reason
 * this is a provider choice rather than a second voice stack. [HostVoiceCallController] is a state
 * machine over those events and does not change between backends; what changes is the socket it is
 * pointed at and whether a credential is required to open it.
 *
 * Verified against huggingface/speech-to-speech (Apache-2.0) before this was written: it serves
 * `/v1/realtime`, implements every event the controller sends or handles (including the newer
 * `response.output_audio.*` spelling rather than the legacy `response.audio.*`, which would have
 * produced a call that connects and is silent), carries tool calls through `session.update` +
 * `function_call_output`, and speaks PCM16 mono at 24 kHz — byte-identical to [VoiceAudioIo.FORMAT],
 * so no resampling sits between the two.
 */
enum class VoiceBackend {
    /** OpenAI Realtime. Metered, needs a key, and is the only backend a share viewer can use. */
    OPENAI,

    /**
     * A local Realtime-compatible server, managed by [ai.rever.bossterm.compose.voice.local.LocalVoiceRuntime].
     *
     * Audio never leaves the machine once the models are on disk. "Once" is load-bearing: install
     * and model download are network operations, and nothing in this enum should be read as a
     * promise that the *setup* is offline.
     */
    LOCAL,

    ;

    companion object {
        /**
         * Parse a persisted value, falling back to [OPENAI].
         *
         * Settings are a user-editable JSON file, so an unknown string must not throw during
         * settings load — that would take the whole app's configuration down over one typo in a
         * voice field. Falling back to OPENAI is also the safe direction: it is the pre-existing
         * behaviour, so a corrupted value degrades to "what this build did before" rather than to
         * a backend the user never set up.
         */
        fun parse(raw: String?): VoiceBackend =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OPENAI
    }
}

/**
 * Everything [RealtimeTransport.connect] needs to open a session, resolved from settings.
 *
 * [apiKey] is nullable because a local server performs no authentication (verified in
 * `llm_proxy.py`: "The server performs no authentication"). Sending a placeholder Bearer token
 * instead would work today and rot the moment a local server starts checking one, so absence is
 * modelled as absence.
 */
internal data class VoiceEndpoint(
    val url: String,
    val model: String,
    val apiKey: String?,
    val backend: VoiceBackend,
)

/**
 * Why a call could not be started, as something a user can act on.
 *
 * A sealed result rather than a nullable endpoint plus a string: the *reason* decides the UI
 * affordance (open Settings, install the runtime, start the runtime), and a bare message forces
 * every call site to re-derive that by matching on prose.
 */
internal sealed interface VoiceEndpointResult {
    data class Ready(val endpoint: VoiceEndpoint) : VoiceEndpointResult

    /**
     * [message] is shown verbatim on the call bar, so it names the exact place to go.
     * [needsLocalSetup] distinguishes "the local runtime is not installed yet" from every other
     * failure, because that one has a button behind it.
     */
    data class Unavailable(
        val message: String,
        val needsLocalSetup: Boolean = false,
    ) : VoiceEndpointResult
}

/**
 * Turns settings into a [VoiceEndpoint], or into an actionable reason why not.
 *
 * Pure and separated from the controller for the same reason [JdkRealtimeTransport.keepWriting] is:
 * this is the branch that decides whether a call is even attempted, and it is far easier to assert
 * over a function than over a coroutine that owns a microphone and a socket.
 *
 * **There is deliberately no fallback between backends.** If the user selected LOCAL and the local
 * runtime is not ready, this returns [VoiceEndpointResult.Unavailable] — it does not quietly place
 * the call through OpenAI. Silently downgrading from a local backend to a metered cloud one would
 * bill the user and put their microphone audio on the network, both without asking, which is the
 * one outcome someone choosing a local backend is explicitly trying to avoid.
 */
internal object VoiceEndpointResolver {

    /**
     * @param localEndpointUrl the URL the local runtime is actually listening on, or null when it
     *   is not running. Supplied by the caller rather than read here so this stays pure: liveness
     *   is a property of a process, not of settings.
     */
    fun resolve(
        settings: TerminalSettings,
        loadKey: () -> String?,
        localEndpointUrl: String? = null,
    ): VoiceEndpointResult = when (VoiceBackend.parse(settings.voiceBackend)) {
        VoiceBackend.OPENAI -> {
            val key = loadKey()
            if (key.isNullOrBlank()) {
                VoiceEndpointResult.Unavailable(
                    "Add an OpenAI API key in Settings → Session Sharing → Boss Calling."
                )
            } else {
                VoiceEndpointResult.Ready(
                    VoiceEndpoint(
                        url = JdkRealtimeTransport.REALTIME_WS_URL,
                        model = settings.voiceCallModel,
                        apiKey = key,
                        backend = VoiceBackend.OPENAI,
                    )
                )
            }
        }

        VoiceBackend.LOCAL -> {
            if (localEndpointUrl.isNullOrBlank()) {
                VoiceEndpointResult.Unavailable(
                    "The local voice runtime isn't running. Set it up in " +
                        "Settings → Session Sharing → Boss Calling.",
                    needsLocalSetup = true,
                )
            } else {
                VoiceEndpointResult.Ready(
                    VoiceEndpoint(
                        url = localEndpointUrl,
                        // The local server's model is whatever it was started with; naming an
                        // OpenAI Realtime model here would be a lie the server has to ignore.
                        // It is still sent (the protocol carries it) so a server that does route
                        // on it keeps working.
                        model = settings.voiceLocalModel.ifBlank { LOCAL_DEFAULT_MODEL },
                        apiKey = null,
                        backend = VoiceBackend.LOCAL,
                    )
                )
            }
        }
    }

    /**
     * What a LOCAL session reports as its model when the user has not named one.
     *
     * Not an OpenAI model id on purpose: this string reaches logs and the share status, and a
     * `gpt-realtime-*` there would make a local call indistinguishable from a metered one in any
     * report someone later reads.
     */
    const val LOCAL_DEFAULT_MODEL = "local-realtime"
}
