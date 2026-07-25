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

    // Starts false and is corrected off-thread: reading + parsing the file in the initializer put a
    // disk hit on whatever thread first touched this object — in practice the Compose thread, at
    // first composition of the status strip.
    private val _keyPresentFlow = MutableStateFlow(false)

    init {
        Thread({ _keyPresentFlow.value = keyPresent() }, "boss-voice-key-probe")
            .apply { isDaemon = true }
            .start()
    }

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

    /** Whether a non-blank key exists on disk right now (read + parse; daemon-safe). */
    fun keyPresent(file: File = defaultFile()): Boolean = load(file)?.openaiApiKey?.isNotBlank() == true

    /**
     * Cheap change token for the key file: mtime + size, 0 when absent. Pollers (the daemon has no
     * cross-process flow) compare this and only re-read on a change, instead of decoding the JSON
     * on every tick.
     */
    fun keyStamp(file: File = defaultFile()): Long =
        if (file.isFile) file.lastModified() * 31 + file.length() else 0L

    /**
     * How many consecutive unchanged stamps a poller should trust before re-reading anyway.
     * mtime granularity is 1 s on some filesystems, so swapping a key for one of the same length
     * within a second is invisible to [keyStamp] alone.
     */
    const val STAMP_TRUST_TICKS = 12

    /**
     * Persist [config], returning whether it actually reached disk.
     *
     * The flow is only updated on success: a read-only home or a full disk used to log a warning
     * and then flip the UI to "a key is set", which also made every share advertise
     * `available: true` while `load()` still returned nothing — viewers got a Call button that
     * could only fail. Callers should surface `false` rather than assume it worked.
     */
    fun save(config: StoredVoiceConfig, file: File = defaultFile()): Boolean {
        // Stage the temp in the target's OWN directory so the ATOMIC_MOVE stays on one
        // filesystem (see AuthStorage.save for the bare-filename caveat).
        val dir = file.absoluteFile.parentFile
        var tmp: File? = null
        val ok = runCatching {
            dir?.mkdirs()
            val staged = newOwnerOnlyTempFile(dir)
            tmp = staged
            staged.writeText(json.encodeToString(StoredVoiceConfig.serializer(), config))
            Files.move(
                staged.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure {
            log.warn("Could not persist voice config: {}", it.message)
            // Don't leave the key sitting in an orphaned temp file next to the real one.
            runCatching { tmp?.delete() }
        }.isSuccess
        if (ok) _keyPresentFlow.value = config.openaiApiKey.isNotBlank()
        return ok
    }

    /** Delete the stored key, returning whether the file is now gone. */
    fun clear(file: File = defaultFile()): Boolean {
        val ok = runCatching { Files.deleteIfExists(file.toPath()); !file.exists() }
            .onFailure { log.warn("Could not remove voice config: {}", it.message) }
            .getOrDefault(false)
        if (ok) _keyPresentFlow.value = false
        return ok
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
