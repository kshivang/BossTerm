package ai.rever.bossterm.compose.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class CursorRenderingSettingsMigrationTest {

    @Test
    fun upgradesLegacyFocusedOpacityToBrowserParity() {
        val migrated = migrateCursorRenderingDefaults(
            settings = TerminalSettings(cursorFocusedAlpha = 0.7f),
            hasCursorRenderingVersion = false,
        )

        assertEquals(1f, migrated.cursorFocusedAlpha)
        assertEquals(2, migrated.cursorRenderingVersion)
    }

    @Test
    fun preservesCustomizedLegacyOpacity() {
        val migrated = migrateCursorRenderingDefaults(
            settings = TerminalSettings(cursorFocusedAlpha = 0.5f),
            hasCursorRenderingVersion = false,
        )

        assertEquals(0.5f, migrated.cursorFocusedAlpha)
        assertEquals(2, migrated.cursorRenderingVersion)
    }

    @Test
    fun versionedSettingsNeverReapplyMigration() {
        val migrated = migrateCursorRenderingDefaults(
            settings = TerminalSettings(cursorFocusedAlpha = 0.7f, cursorRenderingVersion = 2),
            hasCursorRenderingVersion = true,
        )

        assertEquals(0.7f, migrated.cursorFocusedAlpha)
    }
}
