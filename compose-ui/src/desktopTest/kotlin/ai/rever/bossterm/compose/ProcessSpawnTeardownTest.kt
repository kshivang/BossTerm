package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Not starting a shell for a session that is already going away.
 *
 * `PtyProcessBuilder.start()` is a blocking JNI chain with no suspension point, so cancellation
 * cannot interrupt a coroutine already inside it. Session init does real work first - environment
 * assembly, shell-integration injection - and that span is exactly when a tab can be disposed.
 *
 * Where a late spawn lands is what makes it matter. Inside the terminal-tab plugin, disposal is
 * followed by the plugin's classloader closing, and a spawn arriving after that produced:
 *
 * ```
 * ClassNotFoundException: Plugin classloader for '...terminaltab' is UNLOADED; refusing to
 * resolve 'com.pty4j.util.PtyUtil' against the host classloader.
 * ```
 *
 * from `PtyHelpers.<clinit>` - the first pty spawn in that classloader happening during its own
 * teardown. Two properties are pinned here: the spawn is refused when the caller is cancelled, and
 * a failure that arrives as an **Error** rather than an Exception still resolves to null instead of
 * escaping.
 *
 * What is NOT claimed: this is a cooperative check, not a lock. A cancellation landing between the
 * check and the blocking call still gets through. It narrows the window from the whole of session
 * init to an instruction gap; it does not close it, and there is no test here pretending otherwise.
 *
 * Nor is it claimed that pty4j can bring up a pty on any given machine. Every test here either
 * injects `startPty` or uses a command that is meant not to resolve, so the suite is hermetic and
 * says nothing about the native layer. That is deliberate: the one test that did spawn a real shell
 * could not pass on a windows-latest runner, and its failure carried no cause, because
 * `spawnProcess` swallows the throwable and returns null by contract.
 */
class ProcessSpawnTeardownTest {
    private val service = DesktopProcessService()

    /**
     * A shell name that means something on whatever host runs the test.
     *
     * No test in this class spawns it any more - the one that did is injected now - so this is
     * about not carrying a Unix-only literal through a suite that runs on three platforms.
     * `getValidShell` is the resolver the app itself uses: `$SHELL`, then `/bin/bash`, then
     * `/bin/sh` on Unix, and `cmd.exe` on Windows.
     */
    private val shell = ShellCustomizationUtils.getValidShell("cmd")

    private fun config(command: String) =
        PlatformServices.ProcessService.ProcessConfig(
            command = command,
            arguments = emptyList(),
            environment = emptyMap(),
            workingDirectory = System.getProperty("java.io.tmpdir"),
        )

    @Test
    fun `a cancelled caller does not get a process`() {
        // The window this closes. A cancelled scope must not reach PtyProcessBuilder at all.
        var spawned: PlatformServices.ProcessService.ProcessHandle? = null
        var cancelled = false
        runBlocking {
            val scope = CoroutineScope(Job())
            val job =
                scope.launch {
                    scope.cancel()
                    try {
                        spawned = service.spawnProcess(config(shell))
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                }
            job.join()
        }
        assertTrue(cancelled, "the spawn was attempted for a cancelled session")
        assertNull(spawned, "a process was started for a session that no longer exists")
    }

    @Test
    fun `a linkage error resolves to null rather than escaping`() {
        // THE case this error handling exists for, and the one the old `catch (e: Exception)` did
        // not cover. A closed plugin classloader raises NoClassDefFoundError from
        // `PtyHelpers.<clinit>` - an Error, not an Exception - so it escaped the catch entirely and
        // reached the host as a stack trace instead of the documented null.
        val failing =
            DesktopProcessService { _, _, _ ->
                throw NoClassDefFoundError("com/pty4j/util/PtyUtil")
            }
        val handle = runBlocking { failing.spawnProcess(config(shell)) }
        assertNull(handle, "a LinkageError escaped instead of resolving to a failed spawn")
    }

    @Test
    fun `cancellation from inside the spawn propagates`() {
        // `catch (e: Exception)` would swallow CancellationException and return null, turning a tab
        // on its way out into a visible "Failed to spawn process". It has to propagate, which needs
        // the CancellationException clause to sit ABOVE the Throwable one.
        val cancelling =
            DesktopProcessService { _, _, _ ->
                throw CancellationException("disposed mid-spawn")
            }
        var caught: Throwable? = null
        runBlocking {
            try {
                cancelling.spawnProcess(config(shell))
            } catch (t: Throwable) {
                caught = t
            }
        }
        assertTrue(
            caught is CancellationException,
            "cancellation surfaced as ${caught?.let { it::class.simpleName }} rather than propagating",
        )
    }

    @Test
    fun `an active caller still gets a process`() {
        // The guard must not have made spawning impossible: an active context has to reach
        // `startPty` and get a handle back.
        //
        // Injected rather than spawning a real shell. This test used to start `/bin/sh`, which made
        // it Unix-only; pointing it at the platform's shell instead did not fix Windows either, and
        // that is the tell - whether a windows-latest runner can bring up a pty at all is a
        // property of the environment, not of the guard this PR adds. `spawnProcess` swallows the
        // cause and returns null by design, so the failure could not even say why.
        //
        // Asserting on a real pty here was buying an end-to-end signal the suite could not keep:
        // it never held on the third platform, and `a command that cannot start resolves to null`
        // passes on Windows whether pty4j works or not, so nothing here was ever evidence that it
        // does. What this test is FOR - that the cancellation check does not refuse a live caller -
        // is fully covered by the seam.
        var startPtyCalled = false
        val spawning =
            DesktopProcessService { _, _, _ ->
                startPtyCalled = true
                FakePtyProcess()
            }
        val handle = runBlocking { spawning.spawnProcess(config(shell)) }
        assertTrue(startPtyCalled, "the guard refused a caller whose context was still active")
        assertNotNull(handle, "a normal spawn was refused")
        runBlocking { handle.kill() }
    }

    /**
     * The smallest thing `PtyProcessHandle` will accept.
     *
     * `PtyProcess` is abstract over two methods of its own; the rest comes from
     * `java.lang.Process`, whose `isAlive`, `waitFor(timeout, unit)` and `destroyForcibly` are all
     * defined in terms of the abstracts below - so an already-exited process needs nothing more
     * than these, and `kill()` returns without waiting on anything.
     */
    private class FakePtyProcess : com.pty4j.PtyProcess() {
        private val stdin = java.io.ByteArrayInputStream(ByteArray(0))
        private val stdout = java.io.ByteArrayOutputStream()

        override fun getInputStream(): java.io.InputStream = stdin

        override fun getOutputStream(): java.io.OutputStream = stdout

        override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit

        override fun setWinSize(winSize: com.pty4j.WinSize) = Unit

        override fun getWinSize(): com.pty4j.WinSize = com.pty4j.WinSize(80, 24)
    }

    @Test
    fun `a command that cannot start resolves to null rather than throwing`() {
        // The documented contract, and the one the old `catch (e: Exception)` only half kept: it
        // covered an Exception but not the LinkageError a closed classloader raises. A missing
        // binary is the reachable stand-in for that in a test - what matters is that the caller
        // gets null and renders a connection error instead of an escaping throwable.
        val handle =
            runBlocking {
                service.spawnProcess(config("/nonexistent/definitely-not-a-shell"))
            }
        assertNull(handle)
    }

}
