package ai.rever.bossterm.compose

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
 */
class ProcessSpawnTeardownTest {
    private val service = DesktopProcessService()

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
                        spawned = service.spawnProcess(config("/bin/sh"))
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
        val handle = runBlocking { failing.spawnProcess(config("/bin/sh")) }
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
                cancelling.spawnProcess(config("/bin/sh"))
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
        // The guard must not have made spawning impossible. This is the only test here that starts
        // a real shell, and it kills it immediately.
        val handle =
            runBlocking {
                service.spawnProcess(config("/bin/sh"))
            }
        assertNotNull(handle, "a normal spawn was refused")
        runBlocking { handle.kill() }
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
