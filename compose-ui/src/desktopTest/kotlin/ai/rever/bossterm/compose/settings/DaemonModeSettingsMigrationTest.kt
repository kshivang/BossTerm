package ai.rever.bossterm.compose.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonModeSettingsMigrationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `legacy saved false is enabled once and unrelated preferences survive`() = withSettingsFile { file ->
        writeLegacy(file, TerminalSettings.DEFAULT.copy(
            daemonEnabled = false, startDaemonAtLogin = false, fontSize = 21f, mcpPort = 18123,
        ))
        val manager = SettingsManager(file.path)
        assertTrue(manager.settings.value.daemonEnabled)
        assertFalse(manager.wasFreshInstall)
        assertEquals(21f, manager.settings.value.fontSize)
        assertEquals(18123, manager.settings.value.mcpPort)
        assertFalse(manager.settings.value.startDaemonAtLogin)
        assertMigrationPersisted(file)
        assertTrue(SettingsManager(file.path).settings.value.daemonEnabled)
    }

    @Test
    fun `legacy config without daemon key also persists migration marker`() = withSettingsFile { file ->
        file.writeText("""{"fontSize": 19.0}""")
        val manager = SettingsManager(file.path)
        assertTrue(manager.settings.value.daemonEnabled)
        assertEquals(19f, manager.settings.value.fontSize)
        assertMigrationPersisted(file)
    }

    @Test
    fun `later opt-out survives restarts and another process updating a different setting`() = withSettingsFile { file ->
        writeLegacy(file, TerminalSettings.DEFAULT.copy(daemonEnabled = false))
        val gui = SettingsManager(file.path)
        val daemon = SettingsManager(file.path)
        val baseline = daemon.settings.value
        assertTrue(gui.settings.value.daemonEnabled)
        gui.updateSetting { copy(daemonEnabled = false) }
        daemon.mergeChangedFields(baseline, baseline.copy(fontSize = 23f))
        assertFalse(daemon.settings.value.daemonEnabled, "a stale snapshot must preserve the post-migration opt-out")
        assertFalse(SettingsManager(file.path).settings.value.daemonEnabled)
        assertEquals(23f, SettingsManager(file.path).settings.value.fontSize)
        assertMigrationPersisted(file)
    }

    @Test
    fun `already migrated opt-out is honored on first load`() = withSettingsFile { file ->
        file.writeText(json.encodeToString(TerminalSettings.serializer(), TerminalSettings.DEFAULT.copy(daemonEnabled = false)))
        assertFalse(SettingsManager(file.path).settings.value.daemonEnabled)
        assertFalse(SettingsManager(file.path).settings.value.daemonEnabled)
        assertMigrationPersisted(file)
    }

    @Test
    fun `fresh install records migration so its later opt-out also persists`() = withSettingsFile { file ->
        val manager = SettingsManager(file.path)
        assertTrue(manager.wasFreshInstall)
        assertTrue(manager.settings.value.daemonEnabled)
        assertMigrationPersisted(file)
        manager.updateSetting { copy(daemonEnabled = false) }
        assertFalse(SettingsManager(file.path).settings.value.daemonEnabled)
    }

    private fun writeLegacy(file: File, settings: TerminalSettings) {
        val encoded = json.encodeToJsonElement(TerminalSettings.serializer(), settings).jsonObject
        file.writeText(JsonObject(encoded - "daemonModeDefaultsVersion").toString())
    }

    private fun assertMigrationPersisted(file: File) {
        val persisted = json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, persisted.getValue("daemonModeDefaultsVersion").jsonPrimitive.int)
    }

    private fun withSettingsFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("daemon-mode-migration").toFile()
        try { test(File(directory, "settings.json")) } finally { directory.deleteRecursively() }
    }
}
