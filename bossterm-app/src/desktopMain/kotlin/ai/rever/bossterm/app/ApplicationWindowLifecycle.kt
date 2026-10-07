package ai.rever.bossterm.app

import ai.rever.bossterm.compose.window.WindowManager

/** Application-level window actions; closing a macOS window does not quit the app. */
internal class ApplicationWindowLifecycle(
    private val isMacOS: Boolean,
    private val exitApplication: () -> Unit,
) {
    fun openInitialWindow() {
        if (!WindowManager.hasWindows()) WindowManager.createWindow()
    }

    fun closeWindow(id: String) {
        WindowManager.closeWindow(id)
        if (!isMacOS && !WindowManager.hasWindows()) exitApplication()
    }

    /** Dock reopen requests restore an existing window or open a fresh terminal. */
    fun reopen() {
        if (!WindowManager.hasWindows()) {
            WindowManager.createWindow()
            return
        }

        val target = WindowManager.windows.firstOrNull { it.awtWindow?.isFocused == true }
            ?: WindowManager.windows.last()
        target.composeWindowState?.isMinimized = false
        target.awtWindow?.let {
            it.isVisible = true
            it.toFront()
            it.requestFocus()
        }
    }
}
