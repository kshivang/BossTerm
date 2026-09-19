package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
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
            val stream = Files.newDirectoryStream(path)
            if (stream is SecureDirectoryStream<Path>) return NioFileDirectory(stream)
            stream.close()
            error("Secure remote file access is unavailable on this filesystem")
        }
    }
}

private class NioFileDirectory(private val stream: SecureDirectoryStream<Path>) : FileDirectory {
    override fun names() = stream.asSequence().take(100_001).map { it.fileName }.toList()
    override fun directory(name: Path) = NioFileDirectory(stream.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS))
    override fun attributes(name: Path): BasicFileAttributes = stream.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
    override fun open(name: Path, options: Set<OpenOption>): SeekableByteChannel = stream.newByteChannel(name, options)
    override fun delete(name: Path) = stream.deleteFile(name)
    override fun move(source: Path, target: Path, overwrite: Boolean) {
        var reserved = false
        try {
            if (!overwrite) {
                stream.newByteChannel(target, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).close()
                reserved = true
            }
            stream.move(source, stream, target)
        } catch (e: Exception) {
            if (reserved) runCatching { stream.deleteFile(target) }
            throw e
        }
    }
    override fun close() = stream.close()
}
