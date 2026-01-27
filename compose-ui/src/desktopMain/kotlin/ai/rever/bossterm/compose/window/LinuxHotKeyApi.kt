package ai.rever.bossterm.compose.window

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * JNA interface for Linux X11 global hotkey APIs.
 * Uses X11's XGrabKey for system-wide hotkeys.
 */
interface LinuxHotKeyApi : Library {
    companion object {
        val INSTANCE: LinuxHotKeyApi? = try {
            Native.load("X11", LinuxHotKeyApi::class.java)
        } catch (e: Throwable) {
            null
        }

        // Modifier masks
        const val ShiftMask = 1 shl 0
        const val LockMask = 1 shl 1      // Caps Lock
        const val ControlMask = 1 shl 2
        const val Mod1Mask = 1 shl 3      // Alt
        const val Mod2Mask = 1 shl 4      // Num Lock
        const val Mod3Mask = 1 shl 5
        const val Mod4Mask = 1 shl 6      // Super/Win
        const val Mod5Mask = 1 shl 7

        // Grab modes
        const val GrabModeAsync = 1

        // Event masks
        const val KeyPressMask = 1L shl 0
        const val KeyReleaseMask = 1L shl 1

        // Event types
        const val KeyPress = 2
        const val KeyRelease = 3

        // X keysyms (subset - common keys)
        const val XK_grave = 0x0060
        const val XK_space = 0x0020
        const val XK_Escape = 0xFF1B
        const val XK_Tab = 0xFF09
        const val XK_Return = 0xFF0D
        const val XK_a = 0x0061
        const val XK_b = 0x0062
        const val XK_c = 0x0063
        const val XK_d = 0x0064
        const val XK_e = 0x0065
        const val XK_f = 0x0066
        const val XK_g = 0x0067
        const val XK_h = 0x0068
        const val XK_i = 0x0069
        const val XK_j = 0x006A
        const val XK_k = 0x006B
        const val XK_l = 0x006C
        const val XK_m = 0x006D
        const val XK_n = 0x006E
        const val XK_o = 0x006F
        const val XK_p = 0x0070
        const val XK_q = 0x0071
        const val XK_r = 0x0072
        const val XK_s = 0x0073
        const val XK_t = 0x0074
        const val XK_u = 0x0075
        const val XK_v = 0x0076
        const val XK_w = 0x0077
        const val XK_x = 0x0078
        const val XK_y = 0x0079
        const val XK_z = 0x007A
        const val XK_0 = 0x0030
        const val XK_1 = 0x0031
        const val XK_2 = 0x0032
        const val XK_3 = 0x0033
        const val XK_4 = 0x0034
        const val XK_5 = 0x0035
        const val XK_6 = 0x0036
        const val XK_7 = 0x0037
        const val XK_8 = 0x0038
        const val XK_9 = 0x0039
        const val XK_F1 = 0xFFBE
        const val XK_F2 = 0xFFBF
        const val XK_F3 = 0xFFC0
        const val XK_F4 = 0xFFC1
        const val XK_F5 = 0xFFC2
        const val XK_F6 = 0xFFC3
        const val XK_F7 = 0xFFC4
        const val XK_F8 = 0xFFC5
        const val XK_F9 = 0xFFC6
        const val XK_F10 = 0xFFC7
        const val XK_F11 = 0xFFC8
        const val XK_F12 = 0xFFC9

        /**
         * Convert HotKeyConfig key to X11 keysym.
         */
        fun keyToKeysym(key: String): Int {
            return when (key.uppercase()) {
                "GRAVE", "`" -> XK_grave
                "SPACE" -> XK_space
                "ESCAPE", "ESC" -> XK_Escape
                "TAB" -> XK_Tab
                "ENTER", "RETURN" -> XK_Return
                "A" -> XK_a
                "B" -> XK_b
                "C" -> XK_c
                "D" -> XK_d
                "E" -> XK_e
                "F" -> XK_f
                "G" -> XK_g
                "H" -> XK_h
                "I" -> XK_i
                "J" -> XK_j
                "K" -> XK_k
                "L" -> XK_l
                "M" -> XK_m
                "N" -> XK_n
                "O" -> XK_o
                "P" -> XK_p
                "Q" -> XK_q
                "R" -> XK_r
                "S" -> XK_s
                "T" -> XK_t
                "U" -> XK_u
                "V" -> XK_v
                "W" -> XK_w
                "X" -> XK_x
                "Y" -> XK_y
                "Z" -> XK_z
                "0" -> XK_0
                "1" -> XK_1
                "2" -> XK_2
                "3" -> XK_3
                "4" -> XK_4
                "5" -> XK_5
                "6" -> XK_6
                "7" -> XK_7
                "8" -> XK_8
                "9" -> XK_9
                "F1" -> XK_F1
                "F2" -> XK_F2
                "F3" -> XK_F3
                "F4" -> XK_F4
                "F5" -> XK_F5
                "F6" -> XK_F6
                "F7" -> XK_F7
                "F8" -> XK_F8
                "F9" -> XK_F9
                "F10" -> XK_F10
                "F11" -> XK_F11
                "F12" -> XK_F12
                else -> XK_grave
            }
        }

        /**
         * Convert HotKeyConfig modifiers to X11 modifier mask.
         */
        fun configToModifiers(config: HotKeyConfig): Int {
            var mods = 0
            if (config.ctrl) mods = mods or ControlMask
            if (config.alt) mods = mods or Mod1Mask
            if (config.shift) mods = mods or ShiftMask
            if (config.win) mods = mods or Mod4Mask
            return mods
        }
    }

    /**
     * Open a connection to the X server.
     */
    fun XOpenDisplay(displayName: String?): Pointer?

    /**
     * Close the connection to the X server.
     */
    fun XCloseDisplay(display: Pointer?): Int

    /**
     * Get the default root window.
     */
    fun XDefaultRootWindow(display: Pointer?): NativeLong

    /**
     * Convert keysym to keycode.
     */
    fun XKeysymToKeycode(display: Pointer?, keysym: NativeLong): Int

    /**
     * Grab a key globally.
     */
    fun XGrabKey(
        display: Pointer?,
        keycode: Int,
        modifiers: Int,
        grabWindow: NativeLong,
        ownerEvents: Int,
        pointerMode: Int,
        keyboardMode: Int
    ): Int

    /**
     * Ungrab a key.
     */
    fun XUngrabKey(
        display: Pointer?,
        keycode: Int,
        modifiers: Int,
        grabWindow: NativeLong
    ): Int

    /**
     * Select input events.
     */
    fun XSelectInput(display: Pointer?, window: NativeLong, eventMask: NativeLong): Int

    /**
     * Get the next event (blocks).
     */
    fun XNextEvent(display: Pointer?, event: XEvent?): Int

    /**
     * Check for pending events.
     */
    fun XPending(display: Pointer?): Int

    /**
     * Flush the output buffer.
     */
    fun XFlush(display: Pointer?): Int

    /**
     * Sync and discard events.
     */
    fun XSync(display: Pointer?, discard: Int): Int
}

/**
 * X11 XEvent union structure (simplified for key events).
 */
@Structure.FieldOrder("type", "pad")
open class XEvent : Structure() {
    @JvmField var type: Int = 0
    @JvmField var pad: ByteArray = ByteArray(188)  // Pad to full XEvent size

    class ByReference : XEvent(), Structure.ByReference

    /**
     * Get as XKeyEvent if this is a key event.
     */
    fun asKeyEvent(): XKeyEvent? {
        if (type != LinuxHotKeyApi.KeyPress && type != LinuxHotKeyApi.KeyRelease) {
            return null
        }
        val keyEvent = XKeyEvent()
        keyEvent.type = type
        // Copy relevant bytes from pad to keyEvent fields
        // The structure layout depends on the platform
        return keyEvent
    }
}

/**
 * X11 XKeyEvent structure (simplified).
 */
@Structure.FieldOrder("type", "serial", "sendEvent", "display", "window", "root", "subwindow",
    "time", "x", "y", "xRoot", "yRoot", "state", "keycode", "sameScreen")
open class XKeyEvent : Structure() {
    @JvmField var type: Int = 0
    @JvmField var serial: NativeLong = NativeLong(0)
    @JvmField var sendEvent: Int = 0
    @JvmField var display: Pointer? = null
    @JvmField var window: NativeLong = NativeLong(0)
    @JvmField var root: NativeLong = NativeLong(0)
    @JvmField var subwindow: NativeLong = NativeLong(0)
    @JvmField var time: NativeLong = NativeLong(0)
    @JvmField var x: Int = 0
    @JvmField var y: Int = 0
    @JvmField var xRoot: Int = 0
    @JvmField var yRoot: Int = 0
    @JvmField var state: Int = 0
    @JvmField var keycode: Int = 0
    @JvmField var sameScreen: Int = 0

    class ByReference : XKeyEvent(), Structure.ByReference
}
