package ai.rever.bossterm.compose.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

/** Cancel old CLI work, including cleanup, before a new server can register its endpoint. */
class McpAttachmentLifecycle(
    private val scope: CoroutineScope,
    private val attach: suspend (Int, () -> Boolean) -> Unit = McpAutoAttachment::attachInstalledToDaemon
) {
    private var job: Job? = null
    private val attachmentLock = Mutex()

    @Synchronized
    fun replace(port: Int, stillRunning: () -> Boolean) {
        job?.cancel()
        job = scope.launch {
            attachmentLock.withLock {
                ensureActive()
                if (stillRunning()) attach(port, stillRunning)
            }
        }
    }

    @Synchronized
    fun stop() {
        // The mutex stays held until cancelled attachment work finishes its cleanup.
        job?.cancel()
    }
}
