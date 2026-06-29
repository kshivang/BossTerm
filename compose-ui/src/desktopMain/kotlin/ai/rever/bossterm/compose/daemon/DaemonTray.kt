package ai.rever.bossterm.compose.daemon

import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Font
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * Menu-bar (macOS) / system-tray (Windows/Linux) presence for the running daemon, so a long-lived
 * background process isn't invisible. Standard background-agent pattern (Docker, Dropbox, …).
 *
 * Click behavior is handled explicitly by mouse button so it's deterministic across platforms:
 * **left-click opens BossTerm**, **right-click shows the menu** (session count + Open + Quit). We
 * use the standard AWT [PopupMenu] (not Swing), shown manually via a tiny anchor [Frame] because a
 * tray icon isn't a Component and `setPopupMenu` would bind the menu to the platform's own trigger
 * (left-click on macOS) — the opposite of what we want.
 *
 * Requires a non-headless JVM with tray support; on a headless/no-display host it no-ops.
 */
object DaemonTray {
    private val log = LoggerFactory.getLogger(DaemonTray::class.java)

    @Volatile private var trayIcon: TrayIcon? = null
    @Volatile private var sessionsItem: MenuItem? = null
    @Volatile private var anchor: Frame? = null

    fun install(version: String, sessionCount: () -> Int, onOpenApp: (() -> Unit)?, onQuit: () -> Unit): Boolean {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("System tray unavailable (headless/unsupported); daemon runs without a menu-bar icon")
            return false
        }
        // AWT components + SystemTray.add() must be built/registered on the EDT.
        if (!SwingUtilities.isEventDispatchThread()) {
            var result = false
            runCatching { SwingUtilities.invokeAndWait { result = install(version, sessionCount, onOpenApp, onQuit) } }
            return result
        }
        return try {
            val popup = PopupMenu()
            popup.add(MenuItem("BossTerm v$version").apply { isEnabled = false })
            val sessions = MenuItem("Sessions: ${sessionCount()}").apply { isEnabled = false }
            sessionsItem = sessions
            popup.add(sessions)
            popup.addSeparator()
            if (onOpenApp != null) {
                popup.add(MenuItem("Open BossTerm").apply { addActionListener { runCatching { onOpenApp() } } })
            }
            popup.add(MenuItem("Quit BossTerm").apply { addActionListener { runCatching { onQuit() } } })

            // A PopupMenu must hang off a Component; the tray icon isn't one. Park a 1px undecorated
            // frame to anchor it at the click location. Kept tiny + reused so it's imperceptible.
            val frame = Frame().apply { isUndecorated = true; isResizable = false; setSize(1, 1) }
            frame.add(popup)
            anchor = frame

            val icon = TrayIcon(renderWordmark(), "BossTerm").apply {
                isImageAutoSize = false
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        // Right-click → show the menu at the cursor (TrayIcon events are screen coords).
                        if (SwingUtilities.isRightMouseButton(e)) {
                            sessions.label = "Sessions: ${sessionCount()}"
                            frame.setLocation(e.x, e.y)
                            if (!frame.isVisible) frame.isVisible = true
                            popup.show(frame, 0, 0)
                        }
                    }
                    override fun mouseClicked(e: MouseEvent) {
                        // Left-click → open BossTerm.
                        if (SwingUtilities.isLeftMouseButton(e)) runCatching { onOpenApp?.invoke() }
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
        anchor?.let { runCatching { it.dispose() } }
        anchor = null
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
