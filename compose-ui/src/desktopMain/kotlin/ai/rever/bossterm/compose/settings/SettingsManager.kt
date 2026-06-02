package ai.rever.bossterm.compose.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
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
    private fun defaultSettingsDir(): File {
        val override = System.getProperty("bossterm.settings.dir")?.takeIf { it.isNotBlank() }
        val dir = if (override != null) File(override) else File(System.getProperty("user.home"), ".bossterm")
        return dir.apply { if (!exists()) mkdirs() }
    }

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
        _settings.value = newSettings
        saveToFile()
    }

    /**
     * Update a single setting field
     */
    fun updateSetting(updater: TerminalSettings.() -> TerminalSettings) {
        updateSettings(updater(_settings.value))
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
        try {
            val jsonString = json.encodeToString(_settings.value)
            settingsFile.writeText(jsonString)
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
