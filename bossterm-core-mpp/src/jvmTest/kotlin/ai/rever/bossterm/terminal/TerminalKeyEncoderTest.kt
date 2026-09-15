package ai.rever.bossterm.terminal

import ai.rever.bossterm.core.Platform
import ai.rever.bossterm.core.input.InputEvent
import java.awt.event.KeyEvent.*
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalKeyEncoderTest {
    @Test
    fun altArrowsCarryModifiersInBothCursorModes() {
        for (platform in Platform.entries) {
            for (application in listOf(false, true)) {
                val encoder = TerminalKeyEncoder(platform)
                if (application) encoder.arrowKeysApplicationSequences()
                assertEquals("\u001b[1;3A", encoder.getCode(VK_UP, InputEvent.ALT_MASK)?.decodeToString())
                assertEquals("\u001b[1;3B", encoder.getCode(VK_DOWN, InputEvent.ALT_MASK)?.decodeToString())
                assertEquals("\u001b[1;7A", encoder.getCode(VK_UP, InputEvent.ALT_MASK or InputEvent.CTRL_MASK)?.decodeToString())
                assertEquals(if (application) "\u001bOA" else "\u001b[A", encoder.getCode(VK_UP, 0)?.decodeToString())
            }
        }
    }

    @Test
    fun modifiedFunctionKeyDoesNotChangeUnmodifiedApplicationSequence() {
        val encoder = TerminalKeyEncoder(Platform.macOS)
        assertEquals("\u001b[1;3P", encoder.getCode(VK_F1, InputEvent.ALT_MASK)?.decodeToString())
        assertEquals("\u001bOP", encoder.getCode(VK_F1, 0)?.decodeToString())
        assertEquals("\u001bb", encoder.getCode(VK_LEFT, InputEvent.ALT_MASK)?.decodeToString())
        assertEquals("\u001bf", encoder.getCode(VK_RIGHT, InputEvent.ALT_MASK)?.decodeToString())
        assertEquals("\u001b\u007f", encoder.getCode(VK_BACK_SPACE, InputEvent.ALT_MASK)?.decodeToString())
    }
}
