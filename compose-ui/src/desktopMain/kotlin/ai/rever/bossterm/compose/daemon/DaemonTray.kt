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

            val icon = TrayIcon(loadIcon(), "BossTerm daemon", popup).apply {
                isImageAutoSize = true
                // Keep the session count fresh whenever the user opens the menu.
                addActionListener { sessionsItem?.label = "Sessions: ${sessionCount()}" }
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

    /** The BossTerm logo scaled to menu-bar size; falls back to a drawn ">_" glyph if unavailable. */
    private fun loadIcon(): BufferedImage {
        val n = 22
        val stream = DaemonTray::class.java.getResourceAsStream("/icons/bossterm-tray.png")
        if (stream != null) {
            val scaled = runCatching {
                stream.use { javax.imageio.ImageIO.read(it) }?.let { src ->
                    BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB).also { out ->
                        val g = out.createGraphics()
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g.drawImage(src, 0, 0, n, n, null)
                        g.dispose()
                    }
                }
            }.getOrNull()
            if (scaled != null) return scaled
            log.warn("Could not decode bundled BossTerm tray icon; using drawn glyph")
        }
        return drawGlyphFallback()
    }

    /** A small white ">_" terminal glyph — readable on the typical dark menu bar. */
    private fun drawGlyphFallback(): BufferedImage {
        val n = 22
        val img = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = Color.WHITE
            g.font = Font(Font.MONOSPACED, Font.BOLD, 13)
            // ">" chevron
            g.stroke = java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            g.drawPolyline(intArrayOf(5, 10, 5), intArrayOf(7, 11, 15), 3)
            // "_" cursor
            g.fillRect(11, 14, 6, 2)
        } finally {
            g.dispose()
        }
        return img
    }
}
