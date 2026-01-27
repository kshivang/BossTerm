package ai.rever.bossterm.compose.window

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.MSG

/**
 * JNA interface for Win32 global hotkey APIs.
 * Used to register system-wide hotkeys that work regardless of which app is focused.
 */
interface Win32HotKeyApi : Library {
    companion object {
        /**
         * Singleton instance of the Win32 user32 library.
         * Returns null if loading fails (e.g., on non-Windows platforms).
         */
        val INSTANCE: Win32HotKeyApi? = try {
            Native.load("user32", Win32HotKeyApi::class.java)
        } catch (e: Throwable) {
            null
        }

        // Modifier key flags for RegisterHotKey
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_SHIFT = 0x0004
        const val MOD_WIN = 0x0008
        const val MOD_NOREPEAT = 0x4000  // Prevents repeated WM_HOTKEY when key held

        // Window message for hotkey activation
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
    }

    /**
     * Registers a system-wide hotkey.
     *
     * @param hWnd Handle to the window that will receive WM_HOTKEY messages.
     *             Pass null to associate with the calling thread.
     * @param id Unique identifier for this hotkey (used to unregister later).
     * @param fsModifiers Modifier key flags (MOD_ALT, MOD_CONTROL, etc.)
     * @param vk Virtual key code.
     * @return true if registration succeeded, false otherwise.
     */
    fun RegisterHotKey(hWnd: HWND?, id: Int, fsModifiers: Int, vk: Int): Boolean

    /**
     * Unregisters a previously registered hotkey.
     *
     * @param hWnd Handle to the window that registered the hotkey (or null).
     * @param id The identifier used when registering.
     * @return true if unregistration succeeded.
     */
    fun UnregisterHotKey(hWnd: HWND?, id: Int): Boolean

    /**
     * Retrieves a message from the calling thread's message queue.
     * Blocks until a message is available.
     *
     * @param lpMsg Pointer to MSG structure that receives message data.
     * @param hWnd Filter messages to this window (null for all).
     * @param wMsgFilterMin Minimum message value to retrieve.
     * @param wMsgFilterMax Maximum message value to retrieve.
     * @return Non-zero for messages, 0 for WM_QUIT, -1 for error.
     */
    fun GetMessage(lpMsg: MSG, hWnd: HWND?, wMsgFilterMin: Int, wMsgFilterMax: Int): Int

    /**
     * Posts a message to the message queue of a specific thread.
     * Used to signal the message pump thread to exit.
     *
     * @param idThread Thread identifier to post to.
     * @param msg Message type.
     * @param wParam Additional message data.
     * @param lParam Additional message data.
     * @return true if posted successfully.
     */
    fun PostThreadMessage(idThread: Int, msg: Int, wParam: WPARAM, lParam: LPARAM): Boolean

    /**
     * Gets the thread identifier of the calling thread.
     */
    fun GetCurrentThreadId(): Int
}
