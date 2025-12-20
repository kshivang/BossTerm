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
    val isWindowFocused: MutableState<Boolean> = mutableStateOf(true)
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

    fun createWindow(): TerminalWindow {
        val window = TerminalWindow()
        _windows.add(window)
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
        _windows.removeAll { it.id == id }
    }

    fun hasWindows(): Boolean = _windows.isNotEmpty()
}
