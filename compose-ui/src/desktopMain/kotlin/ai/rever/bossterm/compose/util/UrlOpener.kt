package ai.rever.bossterm.compose.util

import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Cross-platform utility for opening URLs in the default browser.
 * Consolidates URL opening logic with proper fallbacks for all platforms.
 */
object UrlOpener {

    /**
     * Open a URL in the default browser.
     *
     * @param url The URL to open
     * @return true if the URL was successfully opened, false otherwise
     */
    fun open(url: String): Boolean {
        val os = System.getProperty("os.name").lowercase()

        return try {
            when {
                os.contains("linux") -> openOnLinux(url)
                os.contains("mac") -> {
                    ProcessBuilder("open", url).start()
                    true
                }
                os.contains("win") -> {
                    ProcessBuilder("cmd", "/c", "start", "", url).start()
                    true
                }
                else -> openWithDesktop(url)
            }
        } catch (e: Exception) {
            println("Failed to open URL: $url - ${e.message}")
            false
        }
    }

    /**
     * Open URL on Linux using multiple fallback strategies.
     * Tries xdg-open first (most standard), then various browser alternatives.
     */
    private fun openOnLinux(url: String): Boolean {
        val browserCommands = listOf(
            listOf("xdg-open", url),          // Standard Linux
            listOf("sensible-browser", url),  // Debian/Ubuntu
            listOf("x-www-browser", url),     // Debian alternatives
            listOf("gnome-open", url),        // GNOME
            listOf("kde-open", url),          // KDE
            listOf("firefox", url),           // Direct browser fallbacks
            listOf("google-chrome", url),
            listOf("chromium", url),
            listOf("chromium-browser", url)
        )

        for (command in browserCommands) {
            try {
                val whichProcess = ProcessBuilder("which", command[0])
                    .redirectErrorStream(true)
                    .start()
                val completed = whichProcess.waitFor(2, TimeUnit.SECONDS)
                val exists = completed && whichProcess.exitValue() == 0

                if (exists) {
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                    return true
                }
            } catch (e: Exception) {
                continue
            }
        }

        // Final fallback: try Java Desktop API
        return openWithDesktop(url)
    }

    /**
     * Try to open URL using Java's Desktop API.
     */
    private fun openWithDesktop(url: String): Boolean {
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
