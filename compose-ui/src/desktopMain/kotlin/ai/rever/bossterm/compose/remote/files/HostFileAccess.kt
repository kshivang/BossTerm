package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** One bounded RPC in flight per viewer. File IO never blocks terminal input or Compose. */
internal class HostFileAccess(
    private val name: String,
    private val reply: (ServerMessage.FilesReply) -> Boolean,
    private val canControl: () -> Boolean = { false },
    private val defaultRoot: () -> java.nio.file.Path = { java.nio.file.Path.of(System.getProperty("user.home")) },
    private val approve: suspend (String) -> FileAccessApproval.Grant? = FileAccessApproval::request,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    @Volatile private var store: RemoteFileStore? = null
    @Volatile private var closed = false
    private var nextApproval = 0L
    private var approvedReadRoot: java.nio.file.Path? = null

    init { scope.launch { while (isActive) { delay(15_000); store?.expireIdleTransfer() } } }

    fun handle(request: ClientMessage.FilesRequest) {
        if (closed) return
        if (request.requestId.length > 80 || request.path.length > 4096 || request.data.length > 44_000) { close(); return }
        if (!gate.tryLock()) { respond(ServerMessage.FilesReply(request.requestId, error = "Another file request is active")); return }
        scope.launch {
            val response = try {
                synchronizeControl()
                if (request.operation == "access") {
                    if (store == null) {
                        check(System.nanoTime() >= nextApproval) { "Please wait before requesting access again" }
                        nextApproval = System.nanoTime() + 30_000_000_000L
                        val grant = approve(name) ?: error("File access was not approved")
                        ensureActive()
                        approvedReadRoot = grant.root
                        val opened = RemoteFileStore(grant.root, false)
                        synchronized(this@HostFileAccess) {
                            if (closed) { opened.close(); error("Connection closed") }
                            store = opened
                        }
                    }
                    val files = checkNotNull(store)
                    ServerMessage.FilesReply(request.requestId, root = files.rootLabel, writable = files.writable)
                } else {
                    val files = store ?: error("Request file access first")
                    files.execute(request)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                store?.cancelTransfer()
                // Never return OS exception messages containing host paths or credentials.
                val message = when (e) {
                    is java.nio.file.FileAlreadyExistsException -> "File already exists"
                    is java.nio.file.NoSuchFileException -> "File or folder no longer exists"
                    is java.nio.file.AccessDeniedException -> "Permission denied"
                    is IllegalStateException, is IllegalArgumentException -> e.message?.takeIf { it.length < 150 } ?: "Invalid file request"
                    else -> "File operation failed; check permissions and available disk space"
                }
                ServerMessage.FilesReply(request.requestId, error = message)
            } finally { gate.unlock() }
            respond(response)
        }
    }

    /** Recheck the live role for every operation, including each upload chunk and finalization. */
    @Synchronized
    private fun synchronizeControl() {
        check(!closed) { "Connection closed" }
        val control = canControl()
        val current = store
        if (control && current?.writable != true) {
            current?.close()
            store = null
            store = RemoteFileStore(approvedReadRoot ?: defaultRoot(), true)
        } else if (!control && current?.writable == true) {
            current.close()
            store = null
            store = approvedReadRoot?.let { RemoteFileStore(it, false) }
        }
    }

    private fun respond(value: ServerMessage.FilesReply) { if (!closed && !reply(value)) close() }

    @Synchronized
    override fun close() {
        closed = true
        scope.cancel()
        store?.close(); store = null
    }
}
