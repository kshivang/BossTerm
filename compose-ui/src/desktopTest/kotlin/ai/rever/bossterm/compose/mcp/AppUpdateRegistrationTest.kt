package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.settings.SettingsManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateRegistrationTest {
    @Test
    fun `embedded servers never expose standalone updater by default`() {
        val settings = SettingsManager(Files.createTempDirectory("mcp-update-config").resolve("settings.json").toString())
        val server = BossTermMcpServer(config = BossTermMcpConfig(), settingsManager = settings)
        assertFalse(server.availableToolNames().any { it.startsWith("app_update_") })
    }

    @Test
    fun `standalone opt in respects write tool gate and prefix`() {
        val settings = SettingsManager(Files.createTempDirectory("mcp-update-config").resolve("settings.json").toString())
        val config = BossTermMcpConfig(allowWriteTools = false, toolNamePrefix = "test_").apply {
            appUpdateToolsEnabled = true
        }
        val readOnly = BossTermMcpServer(config = config, settingsManager = settings)
        assertEquals(listOf("app_update_status"), readOnly.availableToolNames().filter { it.startsWith("app_update_") })
        readOnly.createServer()
        assertTrue("test_app_update_status" in readOnly.toolNames())
        assertFalse("test_app_update_install" in readOnly.toolNames())
        val writable = BossTermMcpServer(config = BossTermMcpConfig().apply { appUpdateToolsEnabled = true }, settingsManager = settings)
        writable.createServer()
        val updates = writable.registeredToolInfo().filter { it.name.startsWith("app_update_") }
        assertEquals(4, updates.size)
        assertEquals(listOf("app_update_status"), updates.filterNot { it.write }.map { it.name })
    }
}
