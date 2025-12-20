package ai.rever.bossterm.tabbed

import ai.rever.bossterm.compose.TabbedTerminal
import ai.rever.bossterm.compose.menu.MenuActions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * Example application demonstrating BossTerm's TabbedTerminal component.
 *
 * This shows how to embed a full-featured tabbed terminal in your application with:
 * - Multiple tabs with tab bar
 * - Split panes (Cmd/Ctrl+D for vertical, Cmd/Ctrl+Shift+D for horizontal)
 * - Window title updates from active tab
 * - Multiple windows support
 * - Window focus tracking for command notifications
 * - Menu bar integration
 *
 * Run with: ./gradlew :tabbed-example:run
 */
fun main() = application {
    // Track all open windows
    val windows = remember { mutableStateListOf(WindowState()) }

    // Create new window
    fun createWindow() {
        windows.add(WindowState())
    }

    // Close window
    fun closeWindow(index: Int) {
        if (windows.size > 1) {
            windows.removeAt(index)
        } else {
            exitApplication()
        }
    }

    // Render all windows
    windows.forEachIndexed { index, windowState ->
        TabbedTerminalWindow(
            windowState = windowState,
            windowIndex = index,
            totalWindows = windows.size,
            onCloseRequest = { closeWindow(index) },
            onNewWindow = { createWindow() }
        )
    }
}

@Composable
private fun ApplicationScope.TabbedTerminalWindow(
    windowState: WindowState,
    windowIndex: Int,
    totalWindows: Int,
    onCloseRequest: () -> Unit,
    onNewWindow: () -> Unit
) {
    // Track window title from terminal
    var windowTitle by remember { mutableStateOf("BossTerm Tabbed Example") }

    // Track window focus state for notifications
    var isWindowFocused by remember { mutableStateOf(true) }

    // Track settings panel visibility
    var showSettings by remember { mutableStateOf(false) }

    // Menu actions for wiring up menu bar
    val menuActions = remember { MenuActions() }

    Window(
        onCloseRequest = onCloseRequest,
        title = if (totalWindows > 1) "$windowTitle (Window ${windowIndex + 1})" else windowTitle,
        state = rememberWindowState(
            size = DpSize(1000.dp, 700.dp)
        )
    ) {
        // Track window focus via AWT listener
        LaunchedEffect(Unit) {
            val awtWindow = window
            val focusListener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    isWindowFocused = true
                }
                override fun windowLostFocus(e: WindowEvent?) {
                    isWindowFocused = false
                }
            }
            awtWindow.addWindowFocusListener(focusListener)
        }

        // Menu bar
        MenuBar {
            Menu("File") {
                Item("New Tab", onClick = { menuActions.onNewTab?.invoke() })
                Item("New Window", onClick = onNewWindow)
                Separator()
                Item("Close Tab", onClick = { menuActions.onCloseTab?.invoke() })
            }
            Menu("Edit") {
                Item("Copy", onClick = { /* Handled by terminal */ })
                Item("Paste", onClick = { /* Handled by terminal */ })
            }
            Menu("View") {
                Item("Split Vertically", onClick = { menuActions.onSplitVertical?.invoke() })
                Item("Split Horizontally", onClick = { menuActions.onSplitHorizontal?.invoke() })
                Separator()
                Item("Settings", onClick = { showSettings = true })
            }
            Menu("Window") {
                Item("Next Tab", onClick = { menuActions.onNextTab?.invoke() })
                Item("Previous Tab", onClick = { menuActions.onPreviousTab?.invoke() })
            }
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Main terminal
                TabbedTerminal(
                    onExit = onCloseRequest,
                    onWindowTitleChange = { title -> windowTitle = title },
                    onNewWindow = onNewWindow,
                    onShowSettings = { showSettings = true },
                    menuActions = menuActions,
                    isWindowFocused = { isWindowFocused },
                    modifier = Modifier.fillMaxSize()
                )

                // Settings panel overlay (simplified)
                if (showSettings) {
                    SettingsPanel(
                        onDismiss = { showSettings = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(400.dp)
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "This is a placeholder settings panel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "In a real application, you would integrate with SettingsManager to display and modify terminal settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

/**
 * Simple window state holder for tracking multiple windows.
 */
private class WindowState {
    val id = System.currentTimeMillis()
}
