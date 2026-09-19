package ai.rever.bossterm.compose.remote.files

import com.sun.jna.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * Darwin openat/fstatat/renameat backend (JDK macOS does not implement SecureDirectoryStream).
 * Layouts/flags follow the public macOS SDK sys/stat.h, sys/dirent.h and sys/fcntl.h.
 * Each component is opened with O_NOFOLLOW; descriptors are close-on-exec and always owned.
 */
internal class MacFileDirectory private constructor(private var fd: Int) : FileDirectory {
    private fun descriptor(): Int = fd.also { if (it < 0) throw ClosedChannelException() }
    override fun directory(name: Path): FileDirectory = MacFileDirectory(openAt(descriptor(), name.toString(), DIRECTORY_FLAGS))

    override fun names(): List<Path> {
        val duplicate = openAt(descriptor(), ".", DIRECTORY_FLAGS)
        val directory = libc.fdopendir(duplicate)
        if (directory == null) { libc.close(duplicate); fail() }
        try {
            val result = ArrayList<Path>()
            while (result.size <= 100_000) {
                Native.setLastError(0)
                val entry = libc.readdir(directory)
                if (entry == null) { if (Native.getLastError() != 0) fail(); break }
                // dirent with 64-bit inode: namlen at 18, name at 21, per sys/dirent.h.
                val length = entry.getShort(18).toInt() and 0xffff
                check(length in 1..1023) { "Invalid directory entry" }
                val name = String(entry.getByteArray(21, length), Charsets.UTF_8)
                if (name != "." && name != "..") result.add(Path.of(name))
            }
            return result
        } finally { libc.closedir(directory) }
    }

    override fun attributes(name: Path): BasicFileAttributes {
        val stat = DarwinStat()
        checked(libc.fstatat(descriptor(), name.toString(), stat, 0x20 /* AT_SYMLINK_NOFOLLOW */))
        return stat.attributes()
    }

    override fun open(name: Path, options: Set<OpenOption>): SeekableByteChannel {
        var flags = O_NOFOLLOW or O_CLOEXEC or 4 /* O_NONBLOCK: never hang opening a replaced FIFO */
        if (StandardOpenOption.WRITE in options) flags = flags or 1
        if (StandardOpenOption.CREATE_NEW in options) flags = flags or 0x200 or 0x800
        val file = openAt(descriptor(), name.toString(), flags)
        try {
            val stat = DarwinStat(); checked(libc.fstat(file, stat))
            check(stat.attributes().isRegularFile) { "Select a regular file" }
            return MacChannel(file)
        } catch (e: Exception) { libc.close(file); throw e }
    }

    override fun delete(name: Path) { checked(libc.unlinkat(descriptor(), name.toString(), 0)) }
    override fun move(source: Path, target: Path, overwrite: Boolean) {
        // RENAME_EXCL makes publication atomic without replacing a concurrently created file.
        checked(libc.renameatx_np(descriptor(), source.toString(), descriptor(), target.toString(), if (overwrite) 0 else 4))
    }
    override fun close() { if (fd >= 0) { val old = fd; fd = -1; libc.close(old) } }

    private class MacChannel(private var fd: Int) : SeekableByteChannel {
        private fun descriptor() = fd.also { if (it < 0) throw ClosedChannelException() }
        override fun isOpen() = fd >= 0
        override fun close() { if (fd >= 0) { val old = fd; fd = -1; libc.close(old) } }
        override fun read(dst: ByteBuffer): Int {
            if (!dst.hasRemaining()) return 0
            val bytes = ByteArray(minOf(dst.remaining(), RemoteFileStore.CHUNK_SIZE))
            val count = libc.read(descriptor(), bytes, bytes.size.toLong())
            if (count < 0) fail()
            if (count == 0L) return -1
            dst.put(bytes, 0, count.toInt()); return count.toInt()
        }
        override fun write(src: ByteBuffer): Int {
            val bytes = ByteArray(minOf(src.remaining(), RemoteFileStore.CHUNK_SIZE))
            src.duplicate().get(bytes)
            val count = libc.write(descriptor(), bytes, bytes.size.toLong())
            if (count < 0) fail()
            src.position(src.position() + count.toInt()); return count.toInt()
        }
        override fun position(): Long = libc.lseek(descriptor(), 0, 1).also { if (it < 0) fail() }
        override fun position(newPosition: Long): SeekableByteChannel {
            require(newPosition >= 0)
            if (libc.lseek(descriptor(), newPosition, 0) < 0) fail()
            return this
        }
        override fun size(): Long { val stat = DarwinStat(); checked(libc.fstat(descriptor(), stat)); return stat.length }
        override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
    }

    companion object {
        private const val O_NOFOLLOW = 0x100
        private const val O_CLOEXEC = 0x01000000
        private const val DIRECTORY_FLAGS = O_NOFOLLOW or O_CLOEXEC or 0x00100000
        // Darwin's x86 ABI exposes the INODE64 symbols; arm64 has only the 64-bit inode ABI.
        private val libc: DarwinFiles by lazy {
            Native.load("/usr/lib/libSystem.B.dylib", DarwinFiles::class.java, mapOf(
                Library.OPTION_FUNCTION_MAPPER to FunctionMapper { _, method ->
                    if (Platform.isIntel() && method.name in setOf("fstat", "fstatat", "readdir", "fdopendir")) method.name + "\$INODE64" else method.name
                }
            ))
        }
        fun open(path: Path): MacFileDirectory {
            val fd = libc.open(path.toString(), DIRECTORY_FLAGS, 0)
            if (fd < 0) fail()
            return MacFileDirectory(fd)
        }
        private fun openAt(fd: Int, name: String, flags: Int): Int = libc.openat(fd, name, flags, 0x180 /* 0600 */).also { if (it < 0) fail() }
        private fun checked(result: Int) { if (result < 0) fail() }
        private fun fail(): Nothing = when (Native.getLastError()) {
            2 -> throw NoSuchFileException("Remote file")
            13, 1 -> throw AccessDeniedException("Remote file")
            17 -> throw FileAlreadyExistsException("Remote file")
            else -> throw IOException("Remote filesystem operation failed")
        }
    }
}

internal interface DarwinFiles : Library {
    fun open(path: String, flags: Int, vararg mode: Any): Int
    fun openat(fd: Int, path: String, flags: Int, vararg mode: Any): Int
    fun close(fd: Int): Int
    fun fdopendir(fd: Int): Pointer?
    fun readdir(dir: Pointer): Pointer?
    fun closedir(dir: Pointer): Int
    fun fstat(fd: Int, stat: DarwinStat): Int
    fun fstatat(fd: Int, path: String, stat: DarwinStat, flags: Int): Int
    fun renameatx_np(from: Int, name: String, to: Int, target: String, flags: Int): Int
    fun unlinkat(fd: Int, name: String, flags: Int): Int
    fun read(fd: Int, bytes: ByteArray, count: Long): Long
    fun write(fd: Int, bytes: ByteArray, count: Long): Long
    fun lseek(fd: Int, offset: Long, whence: Int): Long
}

@Structure.FieldOrder("seconds", "nanos")
internal class DarwinTimespec : Structure() {
    @JvmField var seconds: Long = 0
    @JvmField var nanos: Long = 0
    fun time(): FileTime = FileTime.fromMillis(seconds * 1000 + nanos / 1_000_000)
}

@Structure.FieldOrder("dev", "mode", "links", "ino", "uid", "gid", "rdev", "atime", "mtime", "ctime", "birthtime", "length", "blocks", "blockSize", "flags", "gen", "spare", "qspare")
internal class DarwinStat : Structure() {
    @JvmField var dev: Int = 0
    @JvmField var mode: Short = 0
    @JvmField var links: Short = 0
    @JvmField var ino: Long = 0
    @JvmField var uid: Int = 0
    @JvmField var gid: Int = 0
    @JvmField var rdev: Int = 0
    @JvmField var atime = DarwinTimespec()
    @JvmField var mtime = DarwinTimespec()
    @JvmField var ctime = DarwinTimespec()
    @JvmField var birthtime = DarwinTimespec()
    @JvmField var length: Long = 0
    @JvmField var blocks: Long = 0
    @JvmField var blockSize: Int = 0
    @JvmField var flags: Int = 0
    @JvmField var gen: Int = 0
    @JvmField var spare: Int = 0
    @JvmField var qspare = LongArray(2)
    fun attributes(): BasicFileAttributes = object : BasicFileAttributes {
        override fun size() = length
        override fun isDirectory() = mode.toInt() and 0xf000 == 0x4000
        override fun isRegularFile() = mode.toInt() and 0xf000 == 0x8000
        override fun isSymbolicLink() = mode.toInt() and 0xf000 == 0xa000
        override fun isOther() = !isDirectory && !isRegularFile && !isSymbolicLink
        override fun lastModifiedTime() = mtime.time()
        override fun lastAccessTime() = atime.time()
        override fun creationTime() = birthtime.time()
        override fun fileKey(): Any = "$dev:$ino"
    }
}
