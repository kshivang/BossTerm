package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.RemoteFileEntry
import ai.rever.bossterm.compose.share.ServerMessage
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Root handle, not string-prefix sandboxing. All remote paths are walked with NOFOLLOW_LINKS. */
internal class RemoteFileStore(rootPath: Path, val writable: Boolean) : AutoCloseable {
    val rootLabel: String = rootPath.toRealPath().toString()
    private val root = openRoot(Path.of(rootLabel))
    private var transfer: Transfer? = null
    private var lastActivity = System.nanoTime()

    private class Transfer(
        val id: String,
        val parent: FileDirectory,
        val name: Path,
        val temporary: Path?,
        val channel: SeekableByteChannel,
        val size: Long,
        val overwrite: Boolean,
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
        var position: Long = 0,
    )

    @Synchronized
    fun execute(request: ClientMessage.FilesRequest): ServerMessage.FilesReply {
        require(request.requestId.length <= 80 && request.path.length <= 4096)
        require(request.data.length <= ((CHUNK_SIZE + 2) / 3) * 4)
        lastActivity = System.nanoTime()
        fun reply() = ServerMessage.FilesReply(request.requestId)
        return when (request.operation) {
            "list" -> {
                require(request.offset in 0..100_000)
                directory(parts(request.path)).use { dir ->
                    // Bound both enumeration work and response size, including unusually long names.
                    val all = dir.names().also { require(it.size <= 100_000) { "Folder is too large to browse" } }.asSequence().mapNotNull { path ->
                        val name = path.fileName
                        if (name.toString().startsWith(TEMP_PREFIX)) return@mapNotNull null
                        val attr = try { attributes(dir, name) } catch (_: NoSuchFileException) { return@mapNotNull null }
                        if (!attr.isDirectory && !attr.isRegularFile) null
                        else RemoteFileEntry(name.toString(), attr.isDirectory, attr.size(), attr.lastModifiedTime().toMillis())
                    }.toList()
                    require(all.size <= 100_000) { "Folder is too large to browse" }
                    val sorted = all.sortedWith(compareByDescending<RemoteFileEntry> { it.directory }.thenBy { it.name.lowercase() }.thenBy { it.name })
                    val start = request.offset.toInt().coerceAtMost(sorted.size)
                    val page = sorted.drop(start).take(100)
                    reply().copy(root = rootLabel, writable = writable, entries = page,
                        next = (start + page.size).takeIf { it < sorted.size })
                }
            }
            "download" -> {
                check(transfer == null) { "Another transfer is active" }
                val path = parts(request.path); require(path.isNotEmpty())
                val dir = directory(path.dropLast(1)); val name = Path.of(path.last())
                try {
                    val attr = attributes(dir, name)
                    require(attr.isRegularFile && attr.size() <= MAX_FILE_SIZE) { "Select a regular file up to 10 GiB" }
                    val channel = dir.open(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                    val length = try {
                        channel.size().also { require(it in 0..MAX_FILE_SIZE) { "Select a regular file up to 10 GiB" } }
                    } catch (e: Exception) { channel.close(); throw e }
                    val t = Transfer(UUID.randomUUID().toString(), dir, name, null, channel, length, false)
                    transfer = t
                    reply().copy(transferId = t.id, size = t.size)
                } catch (e: Exception) { dir.close(); throw e }
            }
            "read" -> {
                val t = active(request); require(t.temporary == null && request.offset == t.position)
                val buffer = ByteBuffer.allocate(minOf(CHUNK_SIZE.toLong(), t.size - t.position).toInt())
                while (buffer.hasRemaining()) {
                    check(t.channel.read(buffer) >= 0) { "File changed during download" }
                }
                val bytes = buffer.array(); t.digest.update(bytes); t.position += bytes.size
                val done = t.position == t.size
                val hash = if (done) hex(t.digest.digest()) else null
                val result = reply().copy(data = Base64.getEncoder().encodeToString(bytes), size = t.position, done = done, sha256 = hash)
                if (done) cancelTransfer()
                result
            }
            "upload" -> {
                check(writable) { "File access is read-only" }
                check(transfer == null) { "Another transfer is active" }
                require(request.size in 0..MAX_FILE_SIZE) { "Files must be at most 10 GiB" }
                val path = parts(request.path); require(path.isNotEmpty())
                val dir = directory(path.dropLast(1)); val name = Path.of(path.last())
                val temporary = Path.of(TEMP_PREFIX + UUID.randomUUID())
                try {
                    val existing = existing(dir, name)
                    check(existing == null || (request.overwrite && existing.isRegularFile)) { "File already exists" }
                    val channel = dir.open(temporary, setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS))
                    val t = Transfer(UUID.randomUUID().toString(), dir, name, temporary, channel, request.size, request.overwrite)
                    transfer = t
                    reply().copy(transferId = t.id, size = t.size)
                } catch (e: Exception) { dir.close(); throw e }
            }
            "write" -> {
                val t = active(request); check(writable && t.temporary != null)
                require(request.offset == t.position) { "Unexpected transfer offset" }
                val bytes = Base64.getDecoder().decode(request.data)
                require(bytes.size <= CHUNK_SIZE && bytes.isNotEmpty() && t.position + bytes.size <= t.size)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) t.channel.write(buffer)
                t.digest.update(bytes); t.position += bytes.size
                reply().copy(size = t.position)
            }
            "finish" -> {
                val t = active(request); check(writable && t.temporary != null)
                check(t.position == t.size && hex(t.digest.digest()) == request.sha256) { "Upload verification failed" }
                t.channel.close()
                if (t.overwrite) {
                    check(existing(t.parent, t.name)?.let { it.isRegularFile } != false) { "Destination is not a regular file" }
                }
                t.parent.move(t.temporary, t.name, t.overwrite)
                cancelTransfer()
                reply().copy(done = true)
            }
            "cancel" -> { if (transfer?.id == request.transferId) cancelTransfer(); reply() }
            else -> error("Unsupported file operation")
        }
    }

    private fun active(r: ClientMessage.FilesRequest): Transfer =
        transfer?.takeIf { it.id == r.transferId } ?: error("Transfer expired; retry it")

    private fun parts(value: String): List<String> {
        require(!value.startsWith('/') && !value.contains('\\') && !value.contains(':') && !value.contains('\u0000')) { "Invalid path" }
        if (value.isEmpty()) return emptyList()
        return value.split('/').also { parts ->
            require(parts.all { it.isNotBlank() && it != "." && it != ".." && !it.startsWith(TEMP_PREFIX) }) { "Invalid path" }
        }
    }

    private fun directory(parts: List<String>): FileDirectory {
        var current = root.directory(Path.of("."))
        try {
            for (part in parts) {
                val next = current.directory(Path.of(part))
                current.close(); current = next
            }
            return current
        } catch (e: Exception) { current.close(); throw e }
    }

    private fun attributes(dir: FileDirectory, name: Path): BasicFileAttributes =
        dir.attributes(name)

    private fun existing(dir: FileDirectory, name: Path): BasicFileAttributes? =
        try { attributes(dir, name) } catch (_: NoSuchFileException) { null }

    @Synchronized
    fun cancelTransfer() {
        val t = transfer ?: return
        transfer = null
        runCatching { t.channel.close() }
        if (t.temporary != null) runCatching { t.parent.delete(t.temporary) }
        runCatching { t.parent.close() }
    }

    @Synchronized
    fun expireIdleTransfer() {
        if (System.nanoTime() - lastActivity > 60_000_000_000L) cancelTransfer()
    }

    @Synchronized
    override fun close() { cancelTransfer(); root.close() }

    companion object {
        const val CHUNK_SIZE = 32 * 1024
        const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024
        private const val TEMP_PREFIX = ".bossterm-upload-"
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun openRoot(path: Path): FileDirectory = FileDirectory.open(path)
        val available: Boolean by lazy { supported() }
        fun supported(): Boolean = runCatching { openRoot(Path.of(System.getProperty("user.home"))).use { true } }.getOrDefault(false)
    }
}
