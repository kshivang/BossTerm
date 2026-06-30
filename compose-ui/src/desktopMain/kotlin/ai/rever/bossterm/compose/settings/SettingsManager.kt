package ai.rever.bossterm.compose.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * Manager for terminal settings with persistence support.
 * Settings are saved to ~/.bossterm/settings.json by default,
 * or to a custom path if specified.
 *
 * @param customSettingsPath Optional custom path for settings file.
 *        If null, uses default ~/.bossterm/settings.json
 */
class SettingsManager(private val customSettingsPath: String? = null) {
    private val _settings = MutableStateFlow(TerminalSettings.DEFAULT)

    /**
     * Current settings as a StateFlow (reactive)
     */
    val settings: StateFlow<TerminalSettings> = _settings.asStateFlow()

    /**
     * `true` if the settings file did not exist when [loadFromFile] last ran
     * (i.e. this process started against a brand-new install). `false` if a
     * settings file was already on disk (the common upgrade path). Initially
     * `false`; set inside [loadFromFile].
     *
     * Used by [ai.rever.bossterm.compose.mcp.BossTermMcpManager] to decide
     * whether to apply embedder-supplied first-launch defaults: applying them
     * to an existing user's settings on upgrade would silently overwrite
     * their saved choices.
     */
    var wasFreshInstall: Boolean = false
        private set

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true  // Ensure all fields are written, not just non-default ones
    }

    // Guards saveToFile so concurrent writers can't interleave. Declared BEFORE the init{} block:
    // init calls loadFromFile() → saveToFile(), so a later declaration would still be null then (the
    // property initializer hasn't run yet) and synchronized(saveLock) would NPE.
    private val saveLock = Any()

    private val settingsDir: File by lazy {
        if (customSettingsPath != null) {
            File(customSettingsPath).parentFile?.apply {
                if (!exists()) mkdirs()
            } ?: defaultSettingsDir()
        } else {
            defaultSettingsDir()
        }
    }

    /**
     * Default directory for the settings file when no [customSettingsPath] is
     * given. Honors the `bossterm.settings.dir` system property so an embedder
     * (e.g. BossConsole's terminal plugin) can relocate the entire settings
     * store off the shared `~/.bossterm` — letting multiple BossTerm-based apps
     * on one machine keep independent settings, including independent MCP
     * `mcpEnabled`/`mcpPort` state. When the property is unset or blank, falls
     * back to the historical `~/.bossterm` so standalone BossTerm is unchanged.
     */
    private fun defaultSettingsDir(): File =
        ai.rever.bossterm.compose.daemon.BossTermPaths.dir()

    private val settingsFile: File by lazy {
        if (customSettingsPath != null) {
            File(customSettingsPath)
        } else {
            File(settingsDir, "settings.json")
        }
    }

    init {
        loadFromFile()
    }

    /**
     * Update settings and save to file
     */
    fun updateSettings(newSettings: TerminalSettings) {
        synchronized(saveLock) {
            _settings.value = newSettings
            writeToFileLocked(newSettings)
        }
    }

    /**
     * Apply only the fields the user actually changed ([edited] vs [baseline]) onto the CURRENT
     * settings, atomically — used by the Settings window's debounced save so its possibly-stale full
     * snapshot can't revert a concurrent programmatic [updateSetting] to a *different* field (e.g. the
     * daemon flipping sessionSharingEnabled/startDaemonAtLogin while the window is open). Same-field
     * conflicts are last-writer-wins.
     *
     * The merge target is the CURRENT ON-DISK settings (see [currentOnDiskOrMemory]), not just this
     * process's in-memory snapshot, which greatly narrows the window in which the user's changes revert
     * a field another *process* (the daemon vs the GUI) persisted since we loaded — there is no
     * file-watch/reload between them. NOTE this is best-effort, not airtight: [saveLock] only serializes
     * writers within ONE process, so two processes that read-modify-write *different* fields in the same
     * instant can still lose one update (the second ATOMIC_MOVE wins the whole file). Settings writes are
     * rare and the daemon only persists `mcpEnabled` (reconciled out-of-band via the attach McpState
     * channel), so this is acceptable; a file lock around the RMW would be needed for a hard guarantee.
     */
    fun mergeChangedFields(baseline: TerminalSettings, edited: TerminalSettings) {
        synchronized(saveLock) {
            val current = currentOnDiskOrMemory()
            val base = json.encodeToJsonElement(TerminalSettings.serializer(), baseline).jsonObject
            val ed = json.encodeToJsonElement(TerminalSettings.serializer(), edited).jsonObject
            val merged = json.encodeToJsonElement(TerminalSettings.serializer(), current).jsonObject.toMutableMap()
            for ((k, v) in ed) if (base[k] != v) merged[k] = v // field the user changed → apply over current
            val result = json.decodeFromJsonElement(TerminalSettings.serializer(), JsonObject(merged))
            _settings.value = result
            writeToFileLocked(result)
        }
    }

    /**
     * Update a single setting field. Read-modify-write under [saveLock] against the CURRENT ON-DISK
     * settings, so a single-field change from one process (the daemon flipping `mcpEnabled`, the GUI a
     * theme) is far less likely to revert a *different* field another process persisted since we last
     * loaded — the GUI and daemon hold independent in-memory copies with no reload-on-change. This is
     * best-effort (see [mergeChangedFields]): [saveLock] is in-process only, so two processes RMW-ing
     * different fields at the same instant can still lose one update. Same-field writes are
     * last-writer-wins.
     */
    fun updateSetting(updater: TerminalSettings.() -> TerminalSettings) {
        synchronized(saveLock) {
            val result = currentOnDiskOrMemory().updater()
            _settings.value = result
            writeToFileLocked(result)
        }
    }

    /**
     * Reset settings to defaults
     */
    fun resetToDefaults() {
        updateSettings(TerminalSettings.DEFAULT)
    }

    /**
     * Save current settings to file
     */
    fun saveToFile() {
        synchronized(saveLock) { writeToFileLocked(_settings.value) }
    }

    /**
     * Latest persisted settings, or the in-memory snapshot if the file is missing/unreadable. Used as
     * the merge base for [updateSetting]/[mergeChangedFields] so a write reflects what another process
     * last persisted. Caller holds [saveLock] so the read→modify→write is atomic against other writers
     * in THIS process; cross-process atomicity comes from the unique-temp + rename in [writeToFileLocked].
     */
    private fun currentOnDiskOrMemory(): TerminalSettings =
        runCatching { json.decodeFromString<TerminalSettings>(settingsFile.readText()) }.getOrNull() ?: _settings.value

    /** Write [value] atomically (temp + rename). Caller holds [saveLock]. */
    private fun writeToFileLocked(value: TerminalSettings) {
        try {
            val jsonString = json.encodeToString(value)
            // UNIQUE temp name (not a fixed ".settings.json.tmp"): with the daemon enabled there are TWO
            // JVMs writing this same file in the same dir, and a shared temp name lets one process's
            // writeText interleave with the other's before the rename — publishing a torn/garbage file.
            // A unique temp per write keeps each writer's temp private; the atomic rename then publishes
            // a whole file. (Mirrors DaemonControlChannel.writePortFile's createTempFile rationale.)
            val tmp = File.createTempFile(".settings", ".tmp", settingsFile.parentFile)
            try {
                tmp.writeText(jsonString)
                runCatching {
                    java.nio.file.Files.move(
                        tmp.toPath(), settingsFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }.onFailure {
                    // Filesystems without atomic rename: fall back to in-place replace.
                    java.nio.file.Files.move(
                        tmp.toPath(), settingsFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
            println("Settings saved to: ${settingsFile.absolutePath}")
        } catch (e: Exception) {
            System.err.println("Failed to save settings: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Load settings from file.
     * After loading, re-saves to ensure any new fields (added in updates) are persisted
     * with their default values. This provides automatic settings migration.
     */
    fun loadFromFile() {
        try {
            if (settingsFile.exists()) {
                wasFreshInstall = false
                val jsonString = settingsFile.readText()
                val loadedSettings = json.decodeFromString<TerminalSettings>(jsonString)
                _settings.value = loadedSettings
                println("Settings loaded from: ${settingsFile.absolutePath}")

                // Re-save to migrate settings file with any new fields added in updates
                // This ensures new settings (like globalHotkey*) are written with defaults
                saveToFile()
            } else {
                wasFreshInstall = true
                println("No settings file found, using defaults")
                // Save defaults on first run
                saveToFile()
            }
        } catch (e: Exception) {
            System.err.println("Failed to load settings, using defaults: ${e.message}")
            e.printStackTrace()
            _settings.value = TerminalSettings.DEFAULT
        }
    }

    companion object {
        /**
         * Global singleton instance using default settings path
         */
        val instance: SettingsManager by lazy { SettingsManager() }

        /**
         * Create a new SettingsManager with a custom settings file path.
         *
         * @param path Path to the settings JSON file
         * @return New SettingsManager instance using the custom path
         */
        fun withCustomPath(path: String): SettingsManager {
            return SettingsManager(customSettingsPath = path)
        }
    }
}
