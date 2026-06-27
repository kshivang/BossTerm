package ai.rever.bossterm.compose.daemon

import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Menu-bar (macOS) / system-tray (Windows/Linux) presence for the running daemon, so a long-lived
 * background process isn't invisible. Standard background-agent pattern (Docker, Dropbox, …).
 *
 * Uses the standard AWT [PopupMenu] on the [TrayIcon] (clicking shows it). The menu offers
 * "Open BossTerm" + "Quit BossTerm" and a live session count; double-clicking the icon also opens
 * BossTerm.
 *
 * Requires a non-headless JVM with tray support; on a headless/no-display host it no-ops and the
 * daemon runs without an icon.
 */
object DaemonTray {
    private val log = LoggerFactory.getLogger(DaemonTray::class.java)

    @Volatile private var trayIcon: TrayIcon? = null
    @Volatile private var sessionsItem: MenuItem? = null

    /**
     * Install the tray icon + popup menu. [onOpenApp] runs from "Open BossTerm" (and on double-click);
     * [onQuit] from "Quit BossTerm". The session count refreshes periodically.
     */
    fun install(version: String, sessionCount: () -> Int, onOpenApp: (() -> Unit)?, onQuit: () -> Unit): Boolean {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("System tray unavailable (headless/unsupported); daemon runs without a menu-bar icon")
            return false
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

            val icon = TrayIcon(renderWordmark(), "BossTerm", popup).apply {
                isImageAutoSize = false
                // Double-click opens BossTerm (single click shows the menu, which also has it).
                addActionListener { runCatching { onOpenApp?.invoke() } }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            startRefresh(sessionCount)
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

    private fun startRefresh(sessionCount: () -> Int) {
        java.util.Timer("bossterm-tray-refresh", true).scheduleAtFixedRate(
            object : java.util.TimerTask() {
                override fun run() { runCatching { sessionsItem?.label = "Sessions: ${sessionCount()}" } }
            }, 2000L, 3000L,
        )
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
