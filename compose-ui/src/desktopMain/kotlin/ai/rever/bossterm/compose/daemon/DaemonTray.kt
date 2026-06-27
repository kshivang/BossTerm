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
 * background process isn't invisible — you can see it's alive and quit it without opening the GUI.
 * Standard pattern for background agents (Docker, Dropbox, …).
 *
 * Requires a non-headless JVM with tray support; on a headless/no-display host (CI, SSH) it no-ops
 * and the daemon runs without an icon. On macOS the daemon is launched as a UIElement agent (no
 * Dock icon) — see [DaemonLauncher].
 */
object DaemonTray {
    private val log = LoggerFactory.getLogger(DaemonTray::class.java)

    @Volatile private var trayIcon: TrayIcon? = null
    @Volatile private var sessionsItem: MenuItem? = null

    /**
     * Install the tray icon + menu. Returns true if installed. [onQuit] is invoked from the menu's
     * "Quit" item. The session count is refreshed on each menu open via [sessionCount].
     */
    fun install(version: String, sessionCount: () -> Int, onOpenApp: (() -> Unit)?, onQuit: () -> Unit): Boolean {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("System tray unavailable (headless/unsupported); daemon runs without a menu-bar icon")
            return false
        }
        return try {
            val popup = PopupMenu()
            val title = MenuItem("BossTerm daemon v$version").apply { isEnabled = false }
            val sessions = MenuItem("Sessions: ${sessionCount()}").apply { isEnabled = false }
            sessionsItem = sessions
            popup.add(title)
            popup.add(sessions)
            popup.addSeparator()
            if (onOpenApp != null) {
                popup.add(MenuItem("Open BossTerm").apply { addActionListener { runCatching { onOpenApp() } } })
            }
            popup.add(MenuItem("Quit BossTerm Daemon").apply { addActionListener { runCatching { onQuit() } } })

            val icon = TrayIcon(renderWordmark(), "BossTerm daemon", popup).apply {
                // Keep the natural (wide) aspect of the wordmark — autoSize would squish it square.
                isImageAutoSize = false
                // Double-click opens BossTerm (single-click shows the menu, which also has "Open
                // BossTerm"). The session-count label is kept fresh by the periodic refresh below.
                addActionListener { runCatching { onOpenApp?.invoke() } }
            }
            // Also refresh the label just before the popup shows (action listener doesn't always fire
            // on the menu open across platforms); a lightweight periodic refresh covers it.
            startRefresh(sessionCount)

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

    private fun startRefresh(sessionCount: () -> Int) {
        // Daemon, low-frequency; java.util.Timer is fine and AWT-thread-safe enough for label set.
        java.util.Timer("bossterm-tray-refresh", true).scheduleAtFixedRate(
            object : java.util.TimerTask() {
                override fun run() { runCatching { sessionsItem?.label = "Sessions: ${sessionCount()}" } }
            }, 2000L, 3000L,
        )
    }

    /**
     * A flat monochrome "BOSS" wordmark sized for the menu bar — reads cleanly next to the system's
     * template icons (the full color logo looked cramped scaled to ~22px). White so it shows on the
     * typical dark menu bar.
     */
    private fun renderWordmark(text: String = "BOSS"): BufferedImage {
        val h = 18
        val font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        // Measure the string to size the (wide) image, so letters aren't clipped or squished.
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
