package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay
import java.awt.Window as AwtWindow

/** DWMWA_USE_IMMERSIVE_DARK_MODE, Windows 10 20H1 (build 19041) and later. */
internal const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

/** The same attribute's undocumented id on Windows 10 1809 to 1909. */
internal const val DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY = 19

/** DWMWA_CAPTION_COLOR, Windows 11 (build 22000) and later. */
internal const val DWMWA_CAPTION_COLOR = 35

/** DWMWA_TEXT_COLOR, Windows 11 (build 22000) and later. */
internal const val DWMWA_TEXT_COLOR = 36

/**
 * A Compose colour as a Win32 COLORREF (`0x00BBGGRR`). Alpha is dropped: DWM paints the caption
 * opaque, and a set high byte would be read as one of the special DWMWA_COLOR_* values.
 */
internal fun colorRef(color: Color): Int {
    val r = (color.red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (color.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (b shl 16) or (g shl 8) or r
}

/**
 * Paints the system-drawn Windows title bar in the app's colours instead of the system theme's.
 *
 * Without this a native title bar follows the Windows app mode, which is light on most machines,
 * so the dark default theme gets a white strip above it. Two layers, each failing closed:
 *  - dark mode picks the dark caption, buttons and frame (Windows 10 and 11);
 *  - caption and text colours then match the terminal exactly (Windows 11 only; earlier builds
 *    reject the attribute and keep the dark-mode caption).
 */
internal class WindowsTitleBarColors(
    private val api: DwmGlassApi,
    private val hwnd: Pointer,
    private val refreshFrame: () -> Unit = {},
) {
    /** @return true when the exact caption colour was applied, false when only dark mode (or nothing) was. */
    fun apply(background: Color, foreground: Color): Boolean {
        val dark = if (background.luminance() < 0.5f) 1 else 0
        if (set(DWMWA_USE_IMMERSIVE_DARK_MODE, dark) < 0) set(DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY, dark)
        val exact = set(DWMWA_CAPTION_COLOR, colorRef(background)) >= 0 &&
            set(DWMWA_TEXT_COLOR, colorRef(foreground)) >= 0
        // Windows 10 only repaints the non-client area for dark mode on the next frame change.
        refreshFrame()
        return exact
    }

    private fun set(attribute: Int, value: Int) = api.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4)
}

/**
 * Keeps a decorated window's Windows title bar in [background] / [foreground], following theme
 * changes. No-op off Windows and when [enabled] is false (for example while acrylic owns the frame).
 */
@Composable
fun WindowsTitleBarColorEffect(window: AwtWindow, background: Color, foreground: Color, enabled: Boolean = true) {
    val windows = remember { ShellCustomizationUtils.isWindows() }
    if (!windows || !enabled) return
    LaunchedEffect(window, background, foreground) {
        while (!window.isDisplayable) delay(25)
        try {
            val hwnd = Native.getWindowPointer(window) ?: return@LaunchedEffect
            val api = Native.load("dwmapi", DwmGlassApi::class.java)
            WindowsTitleBarColors(api, hwnd) { refreshFrame(hwnd) }.apply(background, foreground)
        } catch (error: Exception) {
            System.err.println("WindowsTitleBarColors: could not colour the title bar: $error")
        } catch (error: LinkageError) {
            System.err.println("WindowsTitleBarColors: could not colour the title bar: $error")
        }
    }
}

private fun refreshFrame(hwnd: Pointer) {
    // SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
    User32.INSTANCE.SetWindowPos(WinDef.HWND(hwnd), null, 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020)
}
