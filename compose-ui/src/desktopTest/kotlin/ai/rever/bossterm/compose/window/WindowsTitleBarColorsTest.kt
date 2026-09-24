package ai.rever.bossterm.compose.window

import androidx.compose.ui.graphics.Color
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsTitleBarColorsTest {
    /** Records every attribute write; ids in [rejected] fail like an older Windows build. */
    private class Dwm(val rejected: Set<Int> = emptySet()) : DwmGlassApi {
        val attributes = mutableListOf<Pair<Int, Int>>()
        override fun DwmIsCompositionEnabled(enabled: IntByReference) = 0
        override fun DwmGetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int) = 0
        override fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int {
            assertEquals(4, size)
            attributes += attribute to value.value
            return if (attribute in rejected) -1 else 0
        }
        override fun DwmExtendFrameIntoClientArea(window: Pointer, margins: GlassMargins) = 0
    }

    private val blueprintInk = Color(0xFF05070B)
    private val blueprintText = Color(0xFFD5DBE5)

    @Test fun colorRefIsBgrWithNoAlpha() {
        assertEquals(0x000B0705, colorRef(blueprintInk))
        assertEquals(0x00332211, colorRef(Color(0x80112233)))
        assertEquals(0x00FFFFFF, colorRef(Color.White))
    }

    @Test fun windows11GetsDarkModeAndTheExactCaptionColours() {
        val api = Dwm()
        var refreshed = 0
        assertTrue(WindowsTitleBarColors(api, Pointer(1)) { refreshed++ }.apply(blueprintInk, blueprintText))
        assertEquals(
            listOf(
                DWMWA_USE_IMMERSIVE_DARK_MODE to 1,
                DWMWA_CAPTION_COLOR to colorRef(blueprintInk),
                DWMWA_TEXT_COLOR to colorRef(blueprintText),
            ),
            api.attributes,
        )
        assertEquals(1, refreshed)
    }

    @Test fun lightBackgroundTurnsDarkModeOff() {
        val api = Dwm()
        WindowsTitleBarColors(api, Pointer(1)).apply(Color.White, Color.Black)
        assertEquals(DWMWA_USE_IMMERSIVE_DARK_MODE to 0, api.attributes.first())
    }

    @Test fun windows10KeepsDarkModeWhenCaptionColourIsRejected() {
        val api = Dwm(rejected = setOf(DWMWA_CAPTION_COLOR, DWMWA_TEXT_COLOR))
        var refreshed = 0
        assertFalse(WindowsTitleBarColors(api, Pointer(1)) { refreshed++ }.apply(blueprintInk, blueprintText))
        assertEquals(DWMWA_USE_IMMERSIVE_DARK_MODE to 1, api.attributes.first())
        // Dark mode set after the window is shown needs a frame change to repaint on Windows 10.
        assertEquals(1, refreshed)
    }

    @Test fun oldWindows10FallsBackToTheLegacyDarkModeId() {
        val api = Dwm(rejected = setOf(DWMWA_USE_IMMERSIVE_DARK_MODE, DWMWA_CAPTION_COLOR))
        WindowsTitleBarColors(api, Pointer(1)).apply(blueprintInk, blueprintText)
        assertEquals(
            listOf(DWMWA_USE_IMMERSIVE_DARK_MODE to 1, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY to 1, DWMWA_CAPTION_COLOR to colorRef(blueprintInk)),
            api.attributes,
        )
    }
}
