package ai.rever.bossterm.compose.input

import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HyperlinkClickTest {
    // Compose's desktop constructor is internal; constructing real pointer events here
    // lets these tests cover native modifier extraction as well as button dispatch.
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private fun event(
        modifiers: Int = 0,
        button: PointerButton? = PointerButton.Primary,
        type: PointerEventType = PointerEventType.Press,
    ): PointerEvent = PointerEvent(
        changes = emptyList(),
        buttons = PointerButtons(),
        keyboardModifiers = PointerKeyboardModifiers(),
        type = type,
        nativeEvent = MouseEvent(
            Canvas(), MouseEvent.MOUSE_PRESSED, 0L, modifiers,
            10, 10, 1, false,
            when (button) {
                PointerButton.Primary -> MouseEvent.BUTTON1
                PointerButton.Secondary -> MouseEvent.BUTTON3
                PointerButton.Tertiary -> MouseEvent.BUTTON2
                else -> MouseEvent.NOBUTTON
            },
        ),
        button = button,
    )

    @Test
    fun modifierReleaseOutsideCanvasDoesNotAuthorizeNextPlainClick() {
        assertTrue(event(InputEvent.META_DOWN_MASK).isHyperlinkClick())
        // No KeyUp or intervening pointer movement: the press is authoritative.
        assertFalse(event().isHyperlinkClick())
        assertTrue(event(InputEvent.CTRL_DOWN_MASK).isHyperlinkClick())
        assertFalse(event().isHyperlinkClick())
    }

    @Test
    fun currentModifierPressWorksWithoutCanvasKeyDown() {
        assertTrue(event(InputEvent.META_DOWN_MASK).isHyperlinkClick())
        assertTrue(event(InputEvent.CTRL_DOWN_MASK).isHyperlinkClick())
    }

    @Test
    fun ordinarySelectionAndShiftOverrideDoNotOpenLinks() {
        for (modifiers in listOf(0, InputEvent.SHIFT_DOWN_MASK, InputEvent.ALT_DOWN_MASK)) {
            assertFalse(event(modifiers).isHyperlinkClick())
        }
    }

    @Test
    fun middleAndSecondaryButtonsNeverOpenLinks() {
        for (button in listOf(PointerButton.Secondary, PointerButton.Tertiary, null)) {
            for (modifiers in listOf(0, InputEvent.META_DOWN_MASK, InputEvent.CTRL_DOWN_MASK)) {
                assertFalse(event(modifiers, button).isHyperlinkClick())
            }
        }
    }

    @Test
    fun dragMotionAndReleaseNeverActivateLinks() {
        for (type in listOf(PointerEventType.Move, PointerEventType.Release)) {
            assertFalse(event(InputEvent.META_DOWN_MASK, type = type).isHyperlinkClick())
        }
    }
}
