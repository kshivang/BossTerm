package ai.rever.bossterm.compose.mcp

import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class McpAttachmentLifecycleTest {
    @Test fun replacementWaitsForCancelledAttachmentCleanup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<Int>()
        val lifecycle = McpAttachmentLifecycle(scope) { port, _ ->
            if (port == 1) {
                started.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        finishCleanup.await()
                    }
                }
            } else replacement.complete(port)
        }
        try {
            withTimeout(5000) {
                lifecycle.replace(1) { true }
                started.await()
                lifecycle.replace(2) { true }
                cleaning.await()
                lifecycle.replace(3) { true }
                assertFalse(replacement.isCompleted)
                finishCleanup.complete(Unit)
                assertEquals(3, replacement.await())
            }
        } finally { finishCleanup.complete(Unit); scope.cancel() }
    }

    @Test fun stopInterruptsBlockingAttachment() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val interrupted = CompletableDeferred<Unit>()
        val lifecycle = McpAttachmentLifecycle(scope) { _, _ ->
            runInterruptible {
                started.complete(Unit)
                try { java.util.concurrent.CountDownLatch(1).await() }
                catch (e: InterruptedException) { interrupted.complete(Unit); throw e }
            }
        }
        try {
            withTimeout(5000) {
                lifecycle.replace(1) { true }
                started.await()
                lifecycle.stop()
                interrupted.await()
            }
        } finally { scope.cancel() }
    }
}
