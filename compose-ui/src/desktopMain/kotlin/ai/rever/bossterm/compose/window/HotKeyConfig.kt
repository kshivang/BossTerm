package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.settings.TerminalSettings
import java.awt.event.KeyEvent

/**
 * Configuration for a global hotkey.
 * Contains the key combination and methods to convert to Win32 API values.
 */
data class HotKeyConfig(
    val enabled: Boolean,
    val ctrl: Boolean,
    val alt: Boolean,
    val shift: Boolean,
    val win: Boolean,
    val key: String
) {
    /**
     * Convert modifier settings to Win32 modifier flags.
     */
    fun toWin32Modifiers(): Int {
        var modifiers = Win32HotKeyApi.MOD_NOREPEAT  // Always prevent key repeat
        if (ctrl) modifiers = modifiers or Win32HotKeyApi.MOD_CONTROL
        if (alt) modifiers = modifiers or Win32HotKeyApi.MOD_ALT
        if (shift) modifiers = modifiers or Win32HotKeyApi.MOD_SHIFT
        if (win) modifiers = modifiers or Win32HotKeyApi.MOD_WIN
        return modifiers
    }

    /**
     * Convert key string to Win32 virtual key code.
     */
    fun toVirtualKeyCode(): Int {
        return when (key.uppercase()) {
            // Special keys
            "GRAVE", "`" -> KeyEvent.VK_BACK_QUOTE  // 0xC0
            "SPACE" -> KeyEvent.VK_SPACE
            "ESCAPE", "ESC" -> KeyEvent.VK_ESCAPE
            "TAB" -> KeyEvent.VK_TAB
            "ENTER", "RETURN" -> KeyEvent.VK_ENTER

            // Letters A-Z
            "A" -> KeyEvent.VK_A
            "B" -> KeyEvent.VK_B
            "C" -> KeyEvent.VK_C
            "D" -> KeyEvent.VK_D
            "E" -> KeyEvent.VK_E
            "F" -> KeyEvent.VK_F
            "G" -> KeyEvent.VK_G
            "H" -> KeyEvent.VK_H
            "I" -> KeyEvent.VK_I
            "J" -> KeyEvent.VK_J
            "K" -> KeyEvent.VK_K
            "L" -> KeyEvent.VK_L
            "M" -> KeyEvent.VK_M
            "N" -> KeyEvent.VK_N
            "O" -> KeyEvent.VK_O
            "P" -> KeyEvent.VK_P
            "Q" -> KeyEvent.VK_Q
            "R" -> KeyEvent.VK_R
            "S" -> KeyEvent.VK_S
            "T" -> KeyEvent.VK_T
            "U" -> KeyEvent.VK_U
            "V" -> KeyEvent.VK_V
            "W" -> KeyEvent.VK_W
            "X" -> KeyEvent.VK_X
            "Y" -> KeyEvent.VK_Y
            "Z" -> KeyEvent.VK_Z

            // Numbers 0-9
            "0" -> KeyEvent.VK_0
            "1" -> KeyEvent.VK_1
            "2" -> KeyEvent.VK_2
            "3" -> KeyEvent.VK_3
            "4" -> KeyEvent.VK_4
            "5" -> KeyEvent.VK_5
            "6" -> KeyEvent.VK_6
            "7" -> KeyEvent.VK_7
            "8" -> KeyEvent.VK_8
            "9" -> KeyEvent.VK_9

            // Function keys F1-F12
            "F1" -> KeyEvent.VK_F1
            "F2" -> KeyEvent.VK_F2
            "F3" -> KeyEvent.VK_F3
            "F4" -> KeyEvent.VK_F4
            "F5" -> KeyEvent.VK_F5
            "F6" -> KeyEvent.VK_F6
            "F7" -> KeyEvent.VK_F7
            "F8" -> KeyEvent.VK_F8
            "F9" -> KeyEvent.VK_F9
            "F10" -> KeyEvent.VK_F10
            "F11" -> KeyEvent.VK_F11
            "F12" -> KeyEvent.VK_F12

            else -> KeyEvent.VK_BACK_QUOTE  // Default to grave/backtick
        }
    }

    /**
     * Convert to human-readable display string.
     * Uses macOS symbols (⌃⌥⇧⌘) on Mac, text (Ctrl+Alt+Shift+Win) on Windows.
     */
    fun toDisplayString(useMacSymbols: Boolean = false): String {
        if (!enabled) return ""

        return if (useMacSymbols) {
            // macOS style: ⌃⌥⇧⌘` (no separators, just symbols)
            buildString {
                if (ctrl) append("⌃")  // Control
                if (alt) append("⌥")   // Option
                if (shift) append("⇧") // Shift
                if (win) append("⌘")   // Command (map Win key to Cmd on Mac)
                append(when (key.uppercase()) {
                    "GRAVE", "`" -> "`"
                    "SPACE" -> "␣"
                    "ESCAPE", "ESC" -> "⎋"
                    "TAB" -> "⇥"
                    "ENTER", "RETURN" -> "↩"
                    else -> key.uppercase()
                })
            }
        } else {
            // Windows style: Ctrl+Alt+Shift+Win+Key
            val parts = mutableListOf<String>()
            if (ctrl) parts.add("Ctrl")
            if (alt) parts.add("Alt")
            if (shift) parts.add("Shift")
            if (win) parts.add("Win")

            val keyDisplay = when (key.uppercase()) {
                "GRAVE", "`" -> "`"
                "SPACE" -> "Space"
                "ESCAPE", "ESC" -> "Esc"
                "TAB" -> "Tab"
                "ENTER", "RETURN" -> "Enter"
                else -> key.uppercase()
            }
            parts.add(keyDisplay)

            parts.joinToString("+")
        }
    }

    /**
     * Check if the configuration is valid (has at least one modifier and a key).
     */
    fun isValid(): Boolean {
        return enabled && (ctrl || alt || shift || win) && key.isNotEmpty()
    }

    /**
     * Get display string for a specific window number.
     * Uses the base modifiers + window number (1-9).
     *
     * @param windowNumber The window number (1-9)
     * @param useMacSymbols Use macOS-style symbols (⌃⌥⇧⌘)
     */
    fun toWindowDisplayString(windowNumber: Int, useMacSymbols: Boolean = false): String {
        if (!enabled || windowNumber < 1 || windowNumber > 9) return ""

        return if (useMacSymbols) {
            // macOS style: ⌃⌥⇧⌘1 (no separators, just symbols)
            buildString {
                if (ctrl) append("⌃")  // Control
                if (alt) append("⌥")   // Option
                if (shift) append("⇧") // Shift
                if (win) append("⌘")   // Command
                append(windowNumber)
            }
        } else {
            // Windows/Linux style: Ctrl+Alt+1
            val parts = mutableListOf<String>()
            if (ctrl) parts.add("Ctrl")
            if (alt) parts.add("Alt")
            if (shift) parts.add("Shift")
            if (win) parts.add("Win")
            parts.add(windowNumber.toString())
            parts.joinToString("+")
        }
    }

    companion object {
        /**
         * Create HotKeyConfig from TerminalSettings.
         */
        fun fromSettings(settings: TerminalSettings): HotKeyConfig {
            return HotKeyConfig(
                enabled = settings.globalHotkeyEnabled,
                ctrl = settings.globalHotkeyCtrl,
                alt = settings.globalHotkeyAlt,
                shift = settings.globalHotkeyShift,
                win = settings.globalHotkeyWin,
                key = settings.globalHotkeyKey
            )
        }

        /**
         * List of supported keys for the settings UI dropdown.
         */
        val SUPPORTED_KEYS = listOf(
            "GRAVE",
            "SPACE",
            "ESCAPE",
            "TAB"
        ) + ('A'..'Z').map { it.toString() } +
          ('0'..'9').map { it.toString() } +
          (1..12).map { "F$it" }

        /**
         * Map key codes to display names for the settings UI.
         */
        fun keyToDisplayName(key: String): String {
            return when (key.uppercase()) {
                "GRAVE" -> "` (Backtick)"
                "SPACE" -> "Space"
                "ESCAPE" -> "Escape"
                "TAB" -> "Tab"
                else -> key.uppercase()
            }
        }

        /**
         * Map display names back to key codes.
         */
        fun displayNameToKey(displayName: String): String {
            return when {
                displayName.contains("Backtick") -> "GRAVE"
                displayName == "Space" -> "SPACE"
                displayName == "Escape" -> "ESCAPE"
                displayName == "Tab" -> "TAB"
                else -> displayName.uppercase()
            }
        }
    }
}
