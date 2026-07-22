package ai.rever.bossterm.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalGraphicsEnvironmentTest {

    @Test
    fun advertisesStableNonEmptyKittyWindowId() {
        val environment = mutableMapOf<String, String>()

        environment.putBossTermGraphicsEnvironment("session-42")

        assertEquals(
            Integer.toUnsignedString("session-42".hashCode()),
            environment["KITTY_WINDOW_ID"]
        )
    }
}
