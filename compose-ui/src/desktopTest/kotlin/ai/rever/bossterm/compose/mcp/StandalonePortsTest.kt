package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class StandalonePortsTest {
    @Test fun legacyRangesMoveTogetherAndMigrationIsIdempotent() {
        for (offset in 0..9) {
            val migrated = StandalonePorts.migrate(TerminalSettings(mcpPort = 7676 + offset, sessionSharingPort = 7677 + offset))
            assertEquals(17760, migrated.mcpPort)
            assertEquals(17770, migrated.sessionSharingPort)
            assertEquals(migrated, StandalonePorts.migrate(migrated))
        }
    }

    @Test fun customPortsAndOtherSettingsSurvive() {
        val custom = TerminalSettings(mcpPort = 24000, sessionSharingPort = 25000, activeThemeId = "boss-blueprint")
        assertEquals(custom, StandalonePorts.migrate(custom))
    }

    @Test fun standaloneDefaultMovesButEmbedderOverrideRemainsAuthoritative() {
        assertEquals(17760, BossTermMcpConfig().defaultPort)
        assertEquals(7677, BossTermMcpConfig(serverName = "boss", defaultPort = 7677).defaultPort)
    }
}
