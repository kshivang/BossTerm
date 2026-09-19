package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import com.sun.jna.*
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** Operations relative to an open directory; no reconstruction of untrusted absolute paths. */
internal interface FileDirectory : AutoCloseable {
    fun names(): List<Path>
    fun directory(name: Path): FileDirectory
    fun attributes(name: Path): BasicFileAttributes
    fun open(name: Path, options: Set<OpenOption>): SeekableByteChannel
    fun delete(name: Path)
    fun move(source: Path, target: Path, overwrite: Boolean)

    companion object {
        fun open(path: Path): FileDirectory {
            if (ShellCustomizationUtils.isMacOS()) return MacFileDirectory.open(path)
            if (ShellCustomizationUtils.isLinux()) return NioFileDirectory.open(path)
            error("Secure remote file access is unavailable on this filesystem")
        }
    }
}

/**
 * The native descriptor and Java stream refer to the same pinned directory.
 * /proc/self/fd is used only to open that descriptor as a SecureDirectoryStream;
 * untrusted paths are still resolved using descriptor-relative operations.
 */
private class NioFileDirectory(
    private val stream: SecureDirectoryStream<Path>,
    private val fd: Int,
) : FileDirectory {
    private var closed = false
    private fun descriptor(): Int { check(!closed) { "Directory closed" }; return fd }
    override fun names() = stream.asSequence().take(100_001).map { it.fileName }.toList()
    override fun directory(name: Path): FileDirectory =
        fromDescriptor(checked(libc.openat(descriptor(), name.toString(), DIRECTORY_FLAGS)))
    override fun attributes(name: Path): BasicFileAttributes = stream.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
    override fun open(name: Path, options: Set<OpenOption>): SeekableByteChannel = stream.newByteChannel(name, options)
    override fun delete(name: Path) = stream.deleteFile(name)
    override fun move(source: Path, target: Path, overwrite: Boolean) {
        if (overwrite) {
            stream.move(source, stream, target)
        } else {
            // linkat publishes the complete staging inode atomically and fails with EEXIST
            // if anyone created the destination. Never reserve, replace, or delete target.
            // https://man7.org/linux/man-pages/man2/link.2.html
            checked(libc.linkat(descriptor(), source.toString(), descriptor(), target.toString(), 0))
            // Publication succeeded. Cleanup failure must not report a failed upload or
            // remove the published file; RemoteFileStore also retries staging cleanup.
            runCatching { stream.deleteFile(source) }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        try { stream.close() } finally { libc.close(fd) }
    }

    companion object {
        // Linux O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC (x86_64 and aarch64).
        private const val DIRECTORY_FLAGS = 0x10000 or 0x20000 or 0x80000
        private val libc: LinuxFiles by lazy { Native.load(Platform.C_LIBRARY_NAME, LinuxFiles::class.java) }
        fun open(path: Path): FileDirectory = fromDescriptor(checked(libc.open(path.toString(), DIRECTORY_FLAGS)))
        private fun fromDescriptor(fd: Int): FileDirectory {
            try {
                val stream = Files.newDirectoryStream(Path.of("/proc/self/fd/$fd"))
                if (stream is SecureDirectoryStream<Path>) return NioFileDirectory(stream, fd)
                stream.close()
                error("Secure remote file access is unavailable on this filesystem")
            } catch (e: Throwable) { libc.close(fd); throw e }
        }
        private fun checked(result: Int): Int {
            if (result >= 0) return result
            throw when (Native.getLastError()) {
                2 -> NoSuchFileException("Remote file")
                13, 1 -> AccessDeniedException("Remote file")
                17 -> FileAlreadyExistsException("Remote file")
                else -> IOException("Remote filesystem operation failed")
            }
        }
    }
}

internal interface LinuxFiles : Library {
    fun open(path: String, flags: Int): Int
    fun openat(fd: Int, path: String, flags: Int): Int
    fun close(fd: Int): Int
    fun linkat(from: Int, source: String, to: Int, target: String, flags: Int): Int
}
