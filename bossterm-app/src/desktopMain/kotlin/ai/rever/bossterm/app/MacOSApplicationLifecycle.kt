package ai.rever.bossterm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlinx.coroutines.awaitCancellation

/** Called only by the standalone macOS GUI, outside all window compositions. */
@Composable
internal fun MacOSApplicationLifecycle(
    onReopen: () -> Unit,
    onNewWindow: () -> Unit,
    onQuit: () -> Unit,
) {
    val currentReopen by rememberUpdatedState(onReopen)
    val currentNewWindow by rememberUpdatedState(onNewWindow)
    val currentQuit by rememberUpdatedState(onQuit)

    // Compose ends application{} when no windows or active effects remain. Keep
    // this effect suspended until explicit Quit disposes the application composition.
    LaunchedEffect(Unit) { awaitCancellation() }

    DisposableEffect(Unit) {
        val desktop = Desktop.getDesktop()
        var disposed = false
        val reopenListener = AppReopenedListener {
            SwingUtilities.invokeLater {
                if (!disposed) currentReopen()
            }
        }
        desktop.addAppEventListener(reopenListener)
        desktop.setQuitHandler { _, response ->
            // Let Compose dispose every window and its sessions before process
            // shutdown; performQuit() would use AWT's immediate System.exit strategy.
            response.cancelQuit()
            SwingUtilities.invokeLater {
                if (!disposed) currentQuit()
            }
        }

        // Window MenuBar compositions disappear with their windows. Supply New
        // Window (Cmd+N) while there are no frames; AppKit retains the app/Quit menu.
        val supportsDefaultMenu = desktop.isSupported(Desktop.Action.APP_MENU_BAR)
        if (supportsDefaultMenu) {
            val newWindow = JMenuItem("New Window").apply {
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.META_DOWN_MASK)
                addActionListener { if (!disposed) currentNewWindow() }
            }
            desktop.setDefaultMenuBar(JMenuBar().apply {
                add(JMenu("File").apply { add(newWindow) })
            })
        }

        onDispose {
            disposed = true
            desktop.removeAppEventListener(reopenListener)
            desktop.setQuitHandler(null)
            if (supportsDefaultMenu) desktop.setDefaultMenuBar(null)
        }
    }
}
