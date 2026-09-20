package ai.rever.bossterm.compose.voice.local

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.io.File
import java.net.URI

/**
 * Where the managed runtime lives on disk and how it is invoked.
 *
 * Split from [LocalVoiceRuntime] and kept free of process/IO work so the command construction — the
 * part that is easy to get subtly wrong and impossible to notice until a user tries it — can be
 * asserted directly.
 *
 * Grounded in huggingface/speech-to-speech 1.0.0 as published on PyPI (`requires-python >=3.10`):
 * the console script is `speech-to-speech`, the server subcommand is `serve`, and it binds
 * 127.0.0.1 unless told otherwise.
 */
internal object LocalVoiceInstall {

    /** PyPI distribution installed into the managed environment. */
    const val PACKAGE = "speech-to-speech"

    /**
     * Pinned rather than floating.
     *
     * The compatibility this whole feature rests on is a *protocol* match verified against a
     * specific revision. An unpinned install would let a future release change event names or the
     * audio contract under a user who only ever pressed Call, and the failure would surface as a
     * silent call rather than an install error.
     */
    const val VERSION = "1.0.0"

    /** Console script the distribution installs into the environment's bin directory. */
    const val CONSOLE_SCRIPT = "speech-to-speech"

    /** Lowest interpreter the distribution accepts, from its own `requires-python`. */
    const val MIN_PYTHON_MINOR = 10

    /**
     * The major half of that same floor.
     *
     * Named rather than written as a literal at the one place the message is built, so the version
     * a user is told to install cannot drift from the version the check actually enforces.
     */
    const val MIN_PYTHON_MAJOR = 3

    /** Root of the managed environment. Sibling of the rest of BossTerm's state, not a temp dir. */
    fun home(base: File = File(System.getProperty("user.home"), ".bossterm")): File =
        File(base, "voice-local")

    /** The virtualenv inside [home]. */
    fun venv(home: File): File = File(home, "venv")

    /**
     * Executables inside a venv. Windows puts them in `Scripts` with an `.exe` suffix; every other
     * platform uses `bin`. Getting this wrong yields "installed successfully, cannot start".
     */
    fun venvBin(venv: File, name: String, windows: Boolean): File =
        if (windows) File(File(venv, "Scripts"), "$name.exe") else File(File(venv, "bin"), name)

    /** Whether a usable install is already present. */
    fun installed(home: File, windows: Boolean): Boolean =
        venvBin(venv(home), CONSOLE_SCRIPT, windows).canExecute()

    /**
     * Command that starts the server.
     *
     * `--host 127.0.0.1` is passed explicitly even though it is the server's own default. This
     * server performs no authentication, so the bind address is a security boundary, not a
     * preference: stating it here means a future upstream default change cannot quietly expose an
     * unauthenticated microphone-and-tools endpoint to the network.
     *
     * The LLM backend and model are equally explicit. Upstream 1.0.0 defaults to `responses-api`,
     * which is a hosted OpenAI client requiring a key and sending transcripts off-device. Apple
     * Silicon uses upstream's MLX backend and the documented MPS global device; other platforms
     * use Transformers. Both run the named Qwen model locally instead of inheriting a
     * network-backed default.
     */
    fun serveCommand(
        home: File,
        port: Int,
        windows: Boolean,
        macOS: Boolean = ShellCustomizationUtils.isMacOS(),
    ): List<String> = buildList {
        add(venvBin(venv(home), CONSOLE_SCRIPT, windows).absolutePath)
        add("serve")
        addAll(listOf("--host", LOOPBACK, "--port", port.toString()))
        addAll(listOf("--stt", "parakeet-tdt"))
        addAll(listOf("--llm_backend", if (macOS) MAC_LLM_BACKEND else PORTABLE_LLM_BACKEND))
        addAll(listOf("--model_name", LOCAL_LLM_MODEL))
        addAll(listOf("--tts", "qwen3"))
        if (macOS) addAll(listOf("--device", "mps"))
    }

    /**
     * Commands that build the environment, in order.
     *
     * `uv` when present because it resolves and installs an order of magnitude faster than pip on
     * a dependency set this size, with a plain `venv` + `pip` fallback so the feature does not
     * require a tool the user has never heard of.
     */
    fun installCommands(home: File, python: String, uv: String?, windows: Boolean): List<List<String>> {
        val venv = venv(home)
        val spec = "$PACKAGE==$VERSION"
        return if (uv != null) {
            listOf(
                listOf(uv, "venv", "--python", python, venv.absolutePath),
                listOf(uv, "pip", "install", "--python", venvBin(venv, "python", windows).absolutePath, spec),
            )
        } else {
            listOf(
                listOf(python, "-m", "venv", venv.absolutePath),
                listOf(venvBin(venv, "python", windows).absolutePath, "-m", "pip", "install", "--upgrade", "pip"),
                listOf(venvBin(venv, "python", windows).absolutePath, "-m", "pip", "install", spec),
            )
        }
    }

    /**
     * Readiness probe URL.
     *
     * `/v1/pool` rather than the realtime socket: it is a plain GET that reports pipeline-unit
     * state, so it answers as soon as the app is serving without allocating a session. Probing
     * `/v1/realtime` would open and abandon a real session on every poll.
     */
    fun probeUrl(port: Int): String = "http://$LOOPBACK:$port/v1/pool"

    /** The Realtime WebSocket a call connects to. */
    fun realtimeUrl(port: Int): String = "ws://$LOOPBACK:$port/v1/realtime"

    /**
     * Whether an interpreter version string satisfies [MIN_PYTHON_MINOR].
     *
     * Takes the `python --version` output rather than a parsed number because that is what the
     * caller has, and the parsing is the part worth testing: the output has been both
     * "Python 3.12.1" on stdout and on stderr across versions, and a 3.9 interpreter must be
     * rejected with a message rather than failing later inside pip's resolver.
     */
    fun pythonVersionOk(versionOutput: String): Boolean {
        val match = Regex("""Python\s+(\d+)\.(\d+)""").find(versionOutput) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > MIN_PYTHON_MAJOR || (major == MIN_PYTHON_MAJOR && minor >= MIN_PYTHON_MINOR)
    }

    /**
     * Normalize the user's [TerminalSettings.voiceLocalExternalUrl] into a URL a call can use, or
     * null when it is unusable.
     *
     * Two jobs, both about a setting that is hand-edited JSON rather than a UI control:
     *
     * 1. **Scheme.** `JdkRealtimeTransport` builds its socket with
     *    `sharedClient.newWebSocketBuilder().buildAsync(URI.create(url))`, which accepts only
     *    `ws:`/`wss:`. The documented way to point this at another host is to copy the address you
     *    would open in a browser (`http://host:port/v1/realtime`), and that value used to reach the
     *    transport and throw an opaque `IllegalArgumentException` out of `connect`. The sibling
     *    `http(s)` spelling is translated instead, because it is the same origin and the same path.
     * 2. **Rejection.** A value that is not a URL at all — or is a scheme we cannot speak — is
     *    reported as null so the resolver fails the call with a message naming the setting, rather
     *    than handing the transport something that fails one layer down.
     */
    fun parseExternalUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // Everything after "://", which is what both the ws and the http spellings have in common.
        val rest = trimmed.substringAfter("://", missingDelimiterValue = "")
        if (rest.isEmpty()) return null
        val wsScheme = when (trimmed.substringBefore("://").lowercase()) {
            "ws", "wss" -> trimmed.substringBefore("://").lowercase()
            "http" -> "ws"
            "https" -> "wss"
            else -> return null
        }
        // An authority with no host is not a server; reject rather than let the transport decide.
        val host = runCatching { URI("$wsScheme://$rest").host }.getOrNull()
        if (host.isNullOrBlank()) return null
        return "$wsScheme://$rest"
    }

    /**
     * Whether a URL's host is this machine.
     *
     * Used only to describe a configuration, never to permit one: pointing the local backend at
     * another host is a deliberate escape hatch, and the UI warns rather than refuses.
     */
    fun isLoopbackUrl(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.removeSurrounding("[", "]")?.lowercase()
        return host in LOOPBACK_HOSTS
    }

    /** Hosts that mean "this machine". Covers IPv6 loopback with and without its URI brackets. */
    val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0.0.0.0")

    const val LOOPBACK = "127.0.0.1"

    /** Fully local instruction model selected explicitly so upstream cloud defaults cannot leak in. */
    const val LOCAL_LLM_MODEL = "Qwen/Qwen3-4B-Instruct-2507"
    const val MAC_LLM_BACKEND = "mlx-lm"
    const val PORTABLE_LLM_BACKEND = "transformers"
}
