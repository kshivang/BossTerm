package ai.rever.bossterm.compose.window

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemThemeAvailabilityTest {
    @Test fun systemThemeIsAvailableOnlyWithSupportedAppearanceReader() {
        assertTrue(supportsSystemTheme("Mac OS X"))
        assertFalse(supportsSystemTheme("Windows 11"))
        assertFalse(supportsSystemTheme("Linux"))
    }
}
