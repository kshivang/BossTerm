package ai.rever.bossterm.compose.auth

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Persists the signed-in session to `~/.bossterm/auth.json` — deliberately a separate
 * file from settings.json (which SettingsManager rewrites on every settings change and
 * users paste into bug reports). Writes are atomic (temp file + ATOMIC_MOVE) and the
 * file is chmod 600 where the filesystem supports POSIX permissions (on Windows the
 * user-profile ACL is the protection). Tokens are never logged.
 */
internal object AuthStorage {

    private val log = LoggerFactory.getLogger(AuthStorage::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun defaultFile(): File = File(System.getProperty("user.home"), ".bossterm/auth.json")

    fun load(file: File = defaultFile()): StoredAuth? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<StoredAuth>(file.readText()) }
            .onFailure { log.warn("Unreadable auth state ({}); signing out", it.javaClass.simpleName) }
            .getOrNull()
    }

    fun save(auth: StoredAuth, file: File = defaultFile()) {
        runCatching {
            // Stage the temp in the target's OWN directory so the ATOMIC_MOVE stays on one
            // filesystem. Going through absoluteFile guarantees a non-null parent even for a
            // bare-filename File — otherwise createTempFile would fall back to the system temp
            // dir and the move would become cross-device and fail.
            val dir = file.absoluteFile.parentFile
            dir?.mkdirs()
            val tmp = newOwnerOnlyTempFile(dir)
            tmp.writeText(json.encodeToString(StoredAuth.serializer(), auth))
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure { log.warn("Could not persist auth state: {}", it.message) }
    }

    /**
     * Staging temp file for [save], chmod-600 (on POSIX) while still EMPTY. `File.createTempFile`
     * honors the umask (commonly 0644), so restricting *before* the token is written closes the
     * window where the token bytes would otherwise be group/world-readable on disk. On non-POSIX
     * filesystems the user-profile ACL is the protection.
     */
    internal fun newOwnerOnlyTempFile(dir: File?): File =
        File.createTempFile("auth", ".json.tmp", dir).also { restrictToOwner(it) }

    fun clear(file: File = defaultFile()) {
        runCatching { Files.deleteIfExists(file.toPath()) }
    }

    private fun restrictToOwner(file: File) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
    }
}
