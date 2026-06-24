package ai.rever.bossterm.compose.daemon

import java.io.File

/**
 * Single source of truth for BossTerm's on-disk locations.
 *
 * Resolution honors the `bossterm.settings.dir` system property so an embedder
 * (e.g. BossConsole's terminal plugin) can relocate the whole store off the shared
 * `~/.bossterm` — letting multiple BossTerm-based apps on one machine keep
 * independent settings, MCP port, session store, AND daemon (one daemon per
 * settings dir, see [DaemonControlChannel]). When the property is unset or blank,
 * falls back to the historical `~/.bossterm` so standalone BossTerm is unchanged.
 *
 * This consolidates logic that previously lived (duplicated) in
 * [ai.rever.bossterm.compose.settings.SettingsManager.defaultSettingsDir] and
 * [ai.rever.bossterm.compose.session.SessionStore.file]; both now delegate here.
 *
 * The GUI and the daemon process MUST resolve identically — the daemon is launched
 * with the GUI's `-Dbossterm.settings.dir` propagated (see DaemonLauncher), so both
 * read the same property and land on the same files.
 */
object BossTermPaths {

    /** System property an embedder sets to relocate the entire store. */
    const val SETTINGS_DIR_PROPERTY: String = "bossterm.settings.dir"

    /**
     * The BossTerm base directory, created if missing. Honors
     * [SETTINGS_DIR_PROPERTY], else `~/.bossterm`.
     */
    fun dir(): File {
        val override = System.getProperty(SETTINGS_DIR_PROPERTY)?.takeIf { it.isNotBlank() }
        val dir = if (override != null) File(override) else File(System.getProperty("user.home"), ".bossterm")
        return dir.apply { if (!exists()) mkdirs() }
    }

    /** A file inside the base directory (directory ensured to exist by [dir]). */
    fun file(name: String): File = File(dir(), name)

    // ---- well-known files ----

    /** User settings (`settings.json`). */
    fun settingsFile(): File = file("settings.json")

    /** Window-structure snapshot for session restore (`session.json`). */
    fun sessionFile(): File = file("session.json")

    /** MCP server port marker, consumed by the CLI / Claude Code hook (`mcp.port`). */
    fun mcpPortFile(): File = file("mcp.port")

    /** Daemon discovery + auth handshake file: `port`, `secret`, `version` (`daemon.port`). */
    fun daemonPortFile(): File = file("daemon.port")

    /** Advisory lock guarding single daemon spawn per settings dir (`daemon.lock`). */
    fun daemonLockFile(): File = file("daemon.lock")

    /** Running daemon's PID, for diagnostics (`daemon.pid`). */
    fun daemonPidFile(): File = file("daemon.pid")

    /** Daemon stdout/stderr log (`daemon.log`). */
    fun daemonLogFile(): File = file("daemon.log")

    /**
     * A short, stable hash of the resolved base dir — used to de-collide per-profile
     * artifacts that live OUTSIDE the base dir (e.g. a launchd Label or Windows Run-key
     * value, which can't be namespaced by directory). Standalone (`~/.bossterm`) and each
     * `-Dbossterm.settings.dir` profile get distinct values.
     */
    fun profileTag(): String {
        val path = dir().absolutePath
        // Small, filename-safe, deterministic. Not security-sensitive.
        return Integer.toHexString(path.hashCode())
    }
}
