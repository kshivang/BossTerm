package ai.rever.bossterm.compose.voice.local

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * The managed runtime's decisions, without installing Python or binding a port.
 *
 * The command construction is asserted directly because it is the part with no feedback loop: a
 * wrong flag or a wrong venv layout produces "installed successfully, cannot start", and the only
 * place that surfaces is a user's machine.
 */
class LocalVoiceRuntimeTest {

    // ---- command and layout ----

    /**
     * `--host 127.0.0.1` is passed explicitly even though it is the server's own default, because
     * this server performs no authentication: the bind address is a security boundary, not a
     * preference. If upstream ever changes that default, this assertion is what stops an
     * unauthenticated microphone-and-tools endpoint appearing on the user's network.
     */
    @Test
    fun `serve command binds loopback explicitly`() {
        val command = LocalVoiceInstall.serveCommand(File("/tmp/home"), 8765, windows = false)
        assertTrue(command.contains("serve"), "got: $command")
        val hostIndex = command.indexOf("--host")
        assertTrue(hostIndex >= 0, "the bind address must be explicit: $command")
        assertEquals("127.0.0.1", command[hostIndex + 1])
        assertEquals("8765", command[command.indexOf("--port") + 1])
    }

    @Test
    fun `venv layout follows the platform`() {
        val venv = File("/tmp/home/venv")
        assertTrue(LocalVoiceInstall.venvBin(venv, "python", windows = false).path.endsWith("venv/bin/python"))
        // Windows puts scripts in Scripts\ with an .exe suffix; getting this wrong installs fine
        // and then cannot start.
        val win = LocalVoiceInstall.venvBin(venv, "python", windows = true).path
        assertTrue(win.endsWith("python.exe"), "got: $win")
        assertTrue(win.contains("Scripts"), "got: $win")
    }

    /**
     * The install is pinned. An unpinned spec would let a future upstream release change event
     * names or the audio contract under a user who only ever pressed Call, and that failure
     * surfaces as a silent call rather than an install error.
     */
    @Test
    fun `install pins an exact version`() {
        val commands = LocalVoiceInstall.installCommands(File("/tmp/home"), "python3", uv = null, windows = false)
        val spec = commands.flatten().firstOrNull { it.startsWith(LocalVoiceInstall.PACKAGE) }
        assertEquals("${LocalVoiceInstall.PACKAGE}==${LocalVoiceInstall.VERSION}", spec)
    }

    @Test
    fun `uv is preferred when present and pip is the fallback`() {
        val withUv = LocalVoiceInstall.installCommands(File("/tmp/home"), "python3", uv = "/usr/bin/uv", windows = false)
        assertTrue(withUv.all { it.first() == "/usr/bin/uv" }, "got: $withUv")

        val withoutUv = LocalVoiceInstall.installCommands(File("/tmp/home"), "python3", uv = null, windows = false)
        assertTrue(withoutUv.none { it.first() == "/usr/bin/uv" })
        assertTrue(withoutUv.first().containsAll(listOf("python3", "-m", "venv")), "got: ${withoutUv.first()}")
    }

    /**
     * The probe must not be the realtime socket: polling `/v1/realtime` would open and abandon a
     * real session every 500 ms while waiting for startup.
     */
    @Test
    fun `readiness probe is a plain http get, not the realtime socket`() {
        val probe = LocalVoiceInstall.probeUrl(8765)
        assertTrue(probe.startsWith("http://"), "got: $probe")
        assertFalse(probe.contains("/v1/realtime"), "got: $probe")
        assertEquals("ws://127.0.0.1:8765/v1/realtime", LocalVoiceInstall.realtimeUrl(8765))
    }

    /** `python --version` has printed to both stdout and stderr across releases. */
    @Test
    fun `python version gate matches the distribution's own floor`() {
        assertTrue(LocalVoiceInstall.pythonVersionOk("Python 3.12.1"))
        assertTrue(LocalVoiceInstall.pythonVersionOk("Python 3.10.0"))
        assertTrue(LocalVoiceInstall.pythonVersionOk("Python 4.0.0"))
        // 3.9 is below the distribution's requires-python and must be rejected HERE, with a
        // message, rather than thousands of lines later inside pip's resolver.
        assertFalse(LocalVoiceInstall.pythonVersionOk("Python 3.9.18"))
        assertFalse(LocalVoiceInstall.pythonVersionOk("nonsense"))
    }

    // ---- state machine ----

    private class FakeRunner(
        var exitCode: Int = 0,
        val spawned: MutableList<List<String>> = mutableListOf(),
        val process: Process? = null,
    ) : ProcessRunner {
        override suspend fun run(command: List<String>, workingDir: File, timeoutMinutes: Long) =
            ProcessResult(exitCode, "")

        override fun spawn(command: List<String>, workingDir: File): Process {
            spawned += command
            return process ?: error("no process configured")
        }
    }

    private fun tempHome(): File = Files.createTempDirectory("voice-local-test").toFile()

    /**
     * Pressing Call with nothing installed must not silently start a multi-gigabyte download, and
     * must not return a URL either.
     */
    @Test
    fun `ensureRunning refuses when nothing is installed`() = runBlocking {
        val home = tempHome()
        val runtime = LocalVoiceRuntime(home = home, windows = false, exec = FakeRunner(), probe = { false })
        assertNull(runtime.ensureRunning(8765))
        assertIs<LocalVoiceRuntimeState.NotInstalled>(runtime.state.value)
        runtime.dispose()
    }

    @Test
    fun `a fresh runtime reports not installed and offers an install`() {
        val runtime = LocalVoiceRuntime(home = tempHome(), windows = false, exec = FakeRunner(), probe = { false })
        assertTrue(runtime.state.value.needsInstall)
        assertNull(runtime.endpointUrl())
        runtime.dispose()
    }

    /** Only a Running state may hand a URL to a call. */
    @Test
    fun `no state other than Running carries an endpoint url`() {
        assertNull(LocalVoiceRuntimeState.NotInstalled.endpointUrl)
        assertNull(LocalVoiceRuntimeState.Stopped.endpointUrl)
        assertNull(LocalVoiceRuntimeState.Starting.endpointUrl)
        assertNull(LocalVoiceRuntimeState.Installing("x").endpointUrl)
        assertNull(LocalVoiceRuntimeState.Failed("x").endpointUrl)
        assertEquals("ws://host/v1/realtime", LocalVoiceRuntimeState.Running("ws://host/v1/realtime").endpointUrl)
    }

    /**
     * "No Python on this machine" does not become true by pressing Retry, and a UI that offers one
     * anyway teaches users the button does nothing.
     */
    @Test
    fun `an unfixable failure is marked as not retryable`() {
        assertFalse(LocalVoiceRuntimeState.Failed("no python", canRetry = false).let { it.canRetry })
        assertTrue(LocalVoiceRuntimeState.Failed("port busy").canRetry)
    }

    @Test
    fun `busy states are the ones that should disable their button`() {
        assertTrue(LocalVoiceRuntimeState.Installing("x").busy)
        assertTrue(LocalVoiceRuntimeState.Starting.busy)
        assertFalse(LocalVoiceRuntimeState.Stopped.busy)
        assertFalse(LocalVoiceRuntimeState.Running("ws://x").busy)
    }
}
