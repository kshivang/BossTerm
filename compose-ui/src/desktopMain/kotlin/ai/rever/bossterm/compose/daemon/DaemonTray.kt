package ai.rever.bossterm.compose.daemon

import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Menu-bar (macOS) / system-tray (Windows/Linux) presence for the running daemon, so a long-lived
 * background process isn't invisible. Standard background-agent pattern (Docker, Dropbox, …).
 *
 * Click behavior (macOS-native): **left-click opens BossTerm**, **right-click shows a menu** with
 * the session count + Quit. AWT's built-in `TrayIcon` popup would show on left-click, so we handle
 * clicks ourselves (no AWT popup) and show a Swing [JPopupMenu] on the popup trigger.
 *
 * Requires a non-headless JVM with tray support; on a headless/no-display host it no-ops and the
 * daemon runs without an icon.
 */
object DaemonTray {
    private val log = LoggerFactory.getLogger(DaemonTray::class.java)

    @Volatile private var trayIcon: TrayIcon? = null

    /**
     * Install the tray icon. [onOpenApp] runs on left-click (and the menu's "Open BossTerm");
     * [onQuit] runs from the right-click menu. The session count shown is read fresh on each menu open.
     */
    fun install(version: String, sessionCount: () -> Int, onOpenApp: (() -> Unit)?, onQuit: () -> Unit): Boolean {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("System tray unavailable (headless/unsupported); daemon runs without a menu-bar icon")
            return false
        }
        return try {
            val icon = TrayIcon(renderWordmark(), "BossTerm daemon").apply {
                isImageAutoSize = false
                addMouseListener(object : MouseAdapter() {
                    // Popup trigger fires on press on macOS, on release elsewhere — handle both.
                    override fun mousePressed(e: MouseEvent) = onMouse(e)
                    override fun mouseReleased(e: MouseEvent) = onMouse(e)
                    override fun mouseClicked(e: MouseEvent) {
                        if (!e.isPopupTrigger && SwingUtilities.isLeftMouseButton(e)) {
                            runCatching { onOpenApp?.invoke() }
                        }
                    }
                    private fun onMouse(e: MouseEvent) {
                        if (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)) {
                            showMenu(e.x, e.y, version, sessionCount, onOpenApp, onQuit)
                        }
                    }
                })
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            log.info("Daemon menu-bar icon installed")
            true
        } catch (e: Throwable) {
            log.warn("Failed to install daemon tray icon: {}", e.message)
            false
        }
    }

    fun remove() {
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        trayIcon = null
    }

    @Volatile private var menuShowing = false

    /** Show a fresh right-click menu at screen [x],[y] via the standard invisible-invoker-window trick. */
    private fun showMenu(
        x: Int, y: Int, version: String, sessionCount: () -> Int,
        onOpenApp: (() -> Unit)?, onQuit: () -> Unit,
    ) {
        if (menuShowing) return
        menuShowing = true
        val popup = JPopupMenu()
        popup.add(JMenuItem("BossTerm daemon v$version").apply { isEnabled = false })
        popup.add(JMenuItem("Sessions: ${sessionCount()}").apply { isEnabled = false })
        popup.addSeparator()
        if (onOpenApp != null) {
            popup.add(JMenuItem("Open BossTerm").apply { addActionListener { runCatching { onOpenApp() } } })
        }
        popup.add(JMenuItem("Quit BossTerm Daemon").apply { addActionListener { runCatching { onQuit() } } })

        // A JPopupMenu needs a visible invoker; the tray icon isn't a Component, so park a 1px
        // borderless window at the click point and anchor the menu to it, disposing it on close.
        val invoker = JWindow().apply { setLocation(x, y); setSize(1, 1); isVisible = true; toFront() }
        popup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                menuShowing = false
                SwingUtilities.invokeLater { invoker.dispose() }
            }
            override fun popupMenuCanceled(e: PopupMenuEvent) { menuShowing = false }
        })
        popup.show(invoker, 0, 0)
    }

    /**
     * A flat monochrome "BOSS" wordmark sized for the menu bar — reads cleanly next to the system's
     * template icons. White so it shows on the typical dark menu bar.
     */
    private fun renderWordmark(text: String = "BOSS"): BufferedImage {
        val h = 18
        val font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val pg = probe.createGraphics().apply { this.font = font }
        val fm = pg.fontMetrics
        val w = (fm.stringWidth(text) + 4).coerceAtLeast(8)
        pg.dispose()

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.font = font
            val gm = g.fontMetrics
            val x = (w - gm.stringWidth(text)) / 2
            val y = (h - gm.height) / 2 + gm.ascent
            g.drawString(text, x, y)
        } finally {
            g.dispose()
        }
        return img
    }
}
