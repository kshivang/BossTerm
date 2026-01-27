package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.menu.MenuActions
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TerminalTab
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.util.UUID

/**
 * Represents a single terminal window with its own state.
 */
data class TerminalWindow(
    val id: String = UUID.randomUUID().toString(),
    val title: MutableState<String> = mutableStateOf("BossTerm"),
    val menuActions: MenuActions = MenuActions(),
    val isWindowFocused: MutableState<Boolean> = mutableStateOf(true),
    /** AWT window reference for global hotkey toggle (set after Window composable renders) */
    var awtWindow: java.awt.Window? = null,
    /** Window number for global hotkey (1-9, or 0 if no hotkey assigned) */
    val windowNumber: Int = 0
)

/**
 * Global window manager for multi-window support.
 *
 * This object manages the lifecycle of terminal windows and handles
 * tab transfers between windows.
 */
object WindowManager {
    private val _windows = mutableStateListOf<TerminalWindow>()
    val windows: List<TerminalWindow> get() = _windows

    // Pending tab to transfer to newly created window
    var pendingTabForNewWindow: TerminalTab? = null
    // Pending split state to transfer along with the tab
    var pendingSplitStateForNewWindow: SplitViewState? = null

    // Callback for when a new window is created (for hotkey registration)
    var onWindowCreated: ((TerminalWindow) -> Unit)? = null
    // Callback for when a window is closed (for hotkey unregistration)
    var onWindowClosed: ((TerminalWindow) -> Unit)? = null

    /**
     * Get the next available window number (1-9).
     * Returns 0 if all numbers are taken.
     */
    private fun getNextWindowNumber(): Int {
        val usedNumbers = _windows.map { it.windowNumber }.toSet()
        for (i in 1..9) {
            if (i !in usedNumbers) return i
        }
        return 0  // All numbers taken
    }

    /**
     * Get a window by its number.
     */
    fun getWindowByNumber(number: Int): TerminalWindow? {
        return _windows.find { it.windowNumber == number }
    }

    fun createWindow(): TerminalWindow {
        val windowNumber = getNextWindowNumber()
        val window = TerminalWindow(windowNumber = windowNumber)
        _windows.add(window)
        onWindowCreated?.invoke(window)
        return window
    }

    /**
     * Create a new window and transfer an existing tab to it.
     * The tab will be added to the new window's TabController on init.
     *
     * @param tab The terminal tab to transfer
     * @param splitState Optional split state if the tab has split panes
     */
    fun createWindowWithTab(tab: TerminalTab, splitState: SplitViewState? = null): TerminalWindow {
        pendingTabForNewWindow = tab
        pendingSplitStateForNewWindow = splitState
        return createWindow()
    }

    fun closeWindow(id: String) {
        val window = _windows.find { it.id == id }
        if (window != null) {
            onWindowClosed?.invoke(window)
            _windows.removeAll { it.id == id }
        }
    }

    fun hasWindows(): Boolean = _windows.isNotEmpty()
}
