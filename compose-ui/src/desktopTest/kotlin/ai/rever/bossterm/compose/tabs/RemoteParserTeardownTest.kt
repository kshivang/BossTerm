package ai.rever.bossterm.compose.tabs

import ai.rever.bossterm.compose.settings.TerminalSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteParserTeardownTest {
    @Test
    fun `dispose waits for an in flight remote instruction before allowing classloader unload`() {
        val controller = TabController(TerminalSettings(), {})
        val tab = controller.createRemoteSession("remote")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposing = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        // Hold the real parser immediately before it processes Unicode/ICU input.
        val beginBatch = tab.dataStream.onChunkStart
        tab.dataStream.onChunkStart = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            beginBatch?.invoke()
        }
        try {
            tab.dataStream.append("你好👩🏽‍💻\r\n")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val disposal = executor.submit {
                disposing.countDown()
                tab.dispose()
                // The embedding plugin can now close its classloader.
            }
            assertTrue(disposing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException>(
                "dispose returned while the remote parser could still load ICU classes"
            ) { disposal.get(150, TimeUnit.MILLISECONDS) }
            release.countDown()
            disposal.get(5, TimeUnit.SECONDS)
            assertTrue(tab.remoteParserJob!!.isCompleted)
            tab.dispose() // repeated disposal must preserve the completion guarantee
        } finally {
            release.countDown()
            tab.dispose()
            executor.shutdownNow()
        }
    }

    @Test
    fun `dispose unblocks an idle remote parser and container tabs need no worker`() {
        val controller = TabController(TerminalSettings(), {})
        val remote = controller.createRemoteSession("idle")
        val container = controller.createRemoteSession("container", feedsStream = false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { remote.dispose(); container.dispose() }.get(5, TimeUnit.SECONDS)
            assertTrue(remote.remoteParserJob!!.isCompleted)
            assertTrue(container.remoteParserJob == null)
        } finally {
            remote.dispose()
            container.dispose()
            executor.shutdownNow()
        }
    }

    @Test
    fun `dispose unblocks a remote parser waiting for the rest of an escape sequence`() {
        val controller = TabController(TerminalSettings(), {})
        val tab = controller.createRemoteSession("partial CSI")
        val entered = CountDownLatch(1)
        val beginBatch = tab.dataStream.onChunkStart
        tab.dataStream.onChunkStart = { beginBatch?.invoke(); entered.countDown() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            tab.dataStream.append("\u001b[")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            executor.submit { tab.dispose() }.get(5, TimeUnit.SECONDS)
            assertTrue(tab.remoteParserJob!!.isCompleted)
        } finally {
            tab.dispose()
            executor.shutdownNow()
        }
    }
}
