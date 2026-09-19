package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stop-and-wait RPC keeps transfer memory constant and leaves room for terminal traffic. */
internal class RemoteFilesClient(private val send: suspend (ClientMessage.FilesRequest) -> Unit) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ServerMessage.FilesReply>>()
    private val gate = Mutex()
    @Volatile private var generation = 0L

    fun receive(reply: ServerMessage.FilesReply) { pending.remove(reply.requestId)?.complete(reply) }
    fun disconnected() {
        generation++
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Connection interrupted; retry the operation")) }
        pending.clear()
    }

    suspend fun request(operation: String, path: String = "", offset: Long = 0): ServerMessage.FilesReply =
        rpc(ClientMessage.FilesRequest(UUID.randomUUID().toString(), operation, path, offset = offset))

    private suspend fun rpc(request: ClientMessage.FilesRequest): ServerMessage.FilesReply = gate.withLock {
        val response = CompletableDeferred<ServerMessage.FilesReply>()
        pending[request.requestId] = response
        try {
            withTimeout(if (request.operation == "access") 130_000 else 30_000) {
                send(request)
                response.await().also { check(it.error == null) { it.error.orEmpty() } }
            }
        } finally { pending.remove(request.requestId) }
    }

    suspend fun upload(local: Path, remote: String, overwrite: Boolean, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val epoch = generation
        var transfer: String? = null
        try {
            Files.newInputStream(local).use { input ->
                val size = Files.size(local)
                val begin = rpc(ClientMessage.FilesRequest(UUID.randomUUID().toString(), "upload", remote, size = size, overwrite = overwrite))
                transfer = checkNotNull(begin.transferId)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(RemoteFileStore.CHUNK_SIZE)
                var offset = 0L
                progress(0, size)
                while (true) {
                    ensureActive(); check(epoch == generation) { "Connection interrupted; retry upload" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    val reply = rpc(ClientMessage.FilesRequest(UUID.randomUUID().toString(), "write", transferId = transfer,
                        offset = offset, data = Base64.getEncoder().encodeToString(buffer.copyOf(count))))
                    offset += count
                    check(reply.size == offset) { "Unexpected upload acknowledgement" }
                    progress(offset, size)
                }
                check(offset == size) { "Local file changed during upload" }
                rpc(ClientMessage.FilesRequest(UUID.randomUUID().toString(), "finish", transferId = transfer, sha256 = RemoteFileStore.hex(digest.digest())))
                transfer = null
            }
        } finally { cancel(transfer, epoch) }
    }

    suspend fun download(remote: String, local: Path, overwrite: Boolean, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val epoch = generation
        var transfer: String? = null
        val temporary = Files.createTempFile(local.toAbsolutePath().parent, ".bossterm-download-", ".part")
        try {
            val begin = request("download", remote)
            transfer = checkNotNull(begin.transferId)
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            progress(0, begin.size)
            Files.newOutputStream(temporary).use { output ->
                do {
                    ensureActive(); check(epoch == generation) { "Connection interrupted; retry download" }
                    val chunk = rpc(ClientMessage.FilesRequest(UUID.randomUUID().toString(), "read", transferId = transfer, offset = offset))
                    require(chunk.data.length <= 44_000)
                    val bytes = Base64.getDecoder().decode(chunk.data)
                    require(bytes.size <= RemoteFileStore.CHUNK_SIZE && offset + bytes.size <= begin.size)
                    check(bytes.isNotEmpty() || chunk.done) { "Empty download chunk" }
                    output.write(bytes); digest.update(bytes); offset += bytes.size
                    progress(offset, begin.size)
                    if (chunk.done) {
                        check(offset == begin.size && RemoteFileStore.hex(digest.digest()) == chunk.sha256) { "Download verification failed" }
                        break
                    }
                } while (true)
            }
            if (overwrite) Files.move(temporary, local, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(temporary, local)
            transfer = null
        } finally { try { Files.deleteIfExists(temporary) } finally { cancel(transfer, epoch) } }
    }

    private suspend fun cancel(id: String?, epoch: Long) {
        if (id == null || epoch != generation) return
        withContext(NonCancellable) {
            withTimeoutOrNull(2_000) {
                runCatching { rpc(ClientMessage.FilesRequest(UUID.randomUUID().toString(), "cancel", transferId = id)) }
            }
        }
    }
}
