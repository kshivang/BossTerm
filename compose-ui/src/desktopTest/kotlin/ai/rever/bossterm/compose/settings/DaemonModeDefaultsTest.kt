package ai.rever.bossterm.compose.settings

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonModeDefaultsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `fresh install keeps daemon and login service off across restarts`() = withSettingsFile { file ->
        val manager = SettingsManager(file.path)
        assertTrue(manager.wasFreshInstall)
        assertFalse(manager.settings.value.daemonEnabled)
        assertFalse(manager.settings.value.startDaemonAtLogin)
        val reloaded = SettingsManager(file.path)
        assertFalse(reloaded.settings.value.daemonEnabled)
        assertFalse(reloaded.settings.value.startDaemonAtLogin)
    }

    @Test
    fun `legacy saved false stays off and unrelated preferences survive`() = withSettingsFile { file ->
        writeSettings(file, TerminalSettings.DEFAULT.copy(fontSize = 21f, mcpPort = 18123))
        val manager = SettingsManager(file.path)
        assertFalse(manager.wasFreshInstall)
        assertFalse(manager.settings.value.daemonEnabled)
        assertFalse(manager.settings.value.startDaemonAtLogin)
        assertEquals(21f, manager.settings.value.fontSize)
        assertEquals(18123, manager.settings.value.mcpPort)
        assertFalse(SettingsManager(file.path).settings.value.daemonEnabled)
    }

    @Test
    fun `legacy config without daemon keys defaults to local sessions`() = withSettingsFile { file ->
        file.writeText("""{"fontSize": 19.0}""")
        val manager = SettingsManager(file.path)
        assertFalse(manager.settings.value.daemonEnabled)
        assertFalse(manager.settings.value.startDaemonAtLogin)
        assertEquals(19f, manager.settings.value.fontSize)
        assertFalse(SettingsManager(file.path).settings.value.daemonEnabled)
    }

    @Test
    fun `old migration markers never enable daemon mode`() = withSettingsFile { file ->
        for (marker in listOf(0, 1)) {
            file.writeText("""{"daemonEnabled":false,"startDaemonAtLogin":false,"daemonModeDefaultsVersion":$marker}""")
            val manager = SettingsManager(file.path)
            assertFalse(manager.settings.value.daemonEnabled)
            assertFalse(manager.settings.value.startDaemonAtLogin)
            assertFalse(SettingsManager(file.path).settings.value.daemonEnabled)
        }
    }

    @Test
    fun `saved opt-in remains enabled on upgrade`() = withSettingsFile { file ->
        writeSettings(file, TerminalSettings.DEFAULT.copy(daemonEnabled = true, startDaemonAtLogin = true))
        val manager = SettingsManager(file.path)
        assertTrue(manager.settings.value.daemonEnabled)
        assertTrue(manager.settings.value.startDaemonAtLogin)
    }

    @Test
    fun `opt-out survives another process updating an unrelated setting`() = withSettingsFile { file ->
        writeSettings(file, TerminalSettings.DEFAULT.copy(daemonEnabled = true, startDaemonAtLogin = true))
        val gui = SettingsManager(file.path)
        val daemon = SettingsManager(file.path)
        val baseline = daemon.settings.value
        gui.updateSetting { copy(daemonEnabled = false, startDaemonAtLogin = false) }
        daemon.mergeChangedFields(baseline, baseline.copy(fontSize = 23f))

        val reloaded = SettingsManager(file.path)
        assertFalse(reloaded.settings.value.daemonEnabled)
        assertFalse(reloaded.settings.value.startDaemonAtLogin)
        assertEquals(23f, reloaded.settings.value.fontSize)
    }

    private fun writeSettings(file: File, settings: TerminalSettings) {
        file.writeText(json.encodeToString(TerminalSettings.serializer(), settings))
    }

    private fun withSettingsFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("daemon-mode-defaults").toFile()
        try { test(File(directory, "settings.json")) } finally { directory.deleteRecursively() }
    }
}
