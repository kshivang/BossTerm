package ai.rever.bossterm.compose.util

import ai.rever.bossterm.core.input.InputEvent
import ai.rever.bossterm.terminal.TerminalKeyEncoder
import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeyMappingUtilsTest {
    @Test
    fun slashReachesTerminalControlKeyEncoder() {
        val keyCode = assertNotNull(KeyMappingUtils.mapComposeKeyToVK(Key.Slash))
        val encoder = TerminalKeyEncoder()
        assertEquals("\u001f", encoder.getCode(keyCode, InputEvent.CTRL_MASK)?.decodeToString())
        assertNull(encoder.getCode(keyCode, 0))
        assertNull(encoder.getCode(keyCode, InputEvent.SHIFT_MASK))
    }
}
