package ai.rever.bossterm.compose.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** The Boss Calling secrets persisted to `~/.bossterm/voice.json`. */
@Serializable
data class StoredVoiceConfig(
    val openaiApiKey: String,
)

/**
 * Persists the Boss Calling (voice agent) OpenAI API key to `~/.bossterm/voice.json` —
 * deliberately a separate file from settings.json (which SettingsManager rewrites on every
 * settings change and users paste into bug reports), mirroring [ai.rever.bossterm.compose.auth.AuthStorage].
 * Writes are atomic (temp file + ATOMIC_MOVE) and the file is chmod 600 where the filesystem
 * supports POSIX permissions (on Windows the user-profile ACL is the protection). The key is
 * never logged and never leaves the app except as `Authorization` on the OpenAI mint call.
 */
internal object VoiceAgentStorage {

    private val log = LoggerFactory.getLogger(VoiceAgentStorage::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _keyPresentFlow = MutableStateFlow(load()?.openaiApiKey?.isNotBlank() == true)

    /**
     * Whether a non-blank key is currently stored. Updated by [save]/[clear] in THIS process;
     * the daemon process (no live flow across processes) re-stats the file instead.
     */
    val keyPresentFlow: StateFlow<Boolean> = _keyPresentFlow.asStateFlow()

    fun defaultFile(): File = File(System.getProperty("user.home"), ".bossterm/voice.json")

    fun load(file: File = defaultFile()): StoredVoiceConfig? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<StoredVoiceConfig>(file.readText()) }
            .onFailure { log.warn("Unreadable voice config ({}); ignoring", it.javaClass.simpleName) }
            .getOrNull()
    }

    /** Whether a non-blank key exists on disk right now (cheap stat + parse; daemon-safe). */
    fun keyPresent(file: File = defaultFile()): Boolean = load(file)?.openaiApiKey?.isNotBlank() == true

    fun save(config: StoredVoiceConfig, file: File = defaultFile()) {
        runCatching {
            // Stage the temp in the target's OWN directory so the ATOMIC_MOVE stays on one
            // filesystem (see AuthStorage.save for the bare-filename caveat).
            val dir = file.absoluteFile.parentFile
            dir?.mkdirs()
            val tmp = newOwnerOnlyTempFile(dir)
            tmp.writeText(json.encodeToString(StoredVoiceConfig.serializer(), config))
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure { log.warn("Could not persist voice config: {}", it.message) }
        _keyPresentFlow.value = config.openaiApiKey.isNotBlank()
    }

    fun clear(file: File = defaultFile()) {
        runCatching { Files.deleteIfExists(file.toPath()) }
        _keyPresentFlow.value = false
    }

    /**
     * Staging temp file for [save], chmod-600 (on POSIX) while still EMPTY — `File.createTempFile`
     * honors the umask, so restricting before the key is written closes the window where the key
     * bytes would otherwise be group/world-readable on disk.
     */
    internal fun newOwnerOnlyTempFile(dir: File?): File =
        File.createTempFile("voice", ".json.tmp", dir).also { restrictToOwner(it) }

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
