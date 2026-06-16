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
            file.parentFile?.mkdirs()
            val tmp = File.createTempFile("auth", ".json.tmp", file.parentFile)
            tmp.writeText(json.encodeToString(StoredAuth.serializer(), auth))
            restrictToOwner(tmp)
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure { log.warn("Could not persist auth state: {}", it.message) }
    }

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
