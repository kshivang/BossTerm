package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The daemon's authoritative registry of live terminal sessions — the headless analogue of the
 * GUI's [ai.rever.bossterm.compose.tabs.TabController], holding [TerminalSessionCore]s instead of
 * Compose-bound tabs. Everything the daemon exposes (MCP, session sharing, GUI attach) reads/writes
 * sessions through this single owner, so a session's lifetime is the daemon's, not any GUI window's.
 *
 * Thread-safe; methods are callable from the control channel's request threads.
 */
class SessionHost(
    private val settings: TerminalSettings,
) {
    private val log = LoggerFactory.getLogger(SessionHost::class.java)
    private val sessions = ConcurrentHashMap<String, TerminalSessionCore>()

    /** Lightweight, serializable view of a session for LIST_SESSIONS / status. */
    @Serializable
    data class SessionInfo(
        val id: String,
        val title: String,
        val cwd: String?,
        val alive: Boolean,
    )

    /** Create, start, and register a session. Returns its id. */
    fun openSession(
        cwd: String? = null,
        command: String? = null,
        arguments: List<String> = emptyList(),
        cols: Int = 80,
        rows: Int = 24,
    ): String {
        val core = TerminalSessionCore(
            settings = settings,
            workingDir = cwd,
            command = command,
            arguments = arguments,
            initialCols = cols,
            initialRows = rows,
        )
        sessions[core.id] = core
        // Reap on exit so a dead shell doesn't linger in the registry.
        core.onExit = { sessions.remove(core.id) }
        core.start()
        log.info("opened session {} (cwd={}, cmd={})", core.id, cwd, command ?: "<default shell>")
        return core.id
    }

    fun get(id: String): TerminalSessionCore? = sessions[id]

    fun closeSession(id: String) {
        sessions.remove(id)?.let {
            it.close()
            log.info("closed session {}", id)
        }
    }

    fun list(): List<SessionInfo> = sessions.values.map { core ->
        SessionInfo(
            id = core.id,
            title = core.windowTitle.value.ifBlank { labelFor(core.workingDirectory.value) },
            cwd = core.workingDirectory.value,
            alive = core.isAlive(),
        )
    }

    fun count(): Int = sessions.size

    /** Kill every session — used on daemon SHUTDOWN {killSessions:true}. */
    fun shutdownAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    private fun labelFor(path: String?): String {
        if (path.isNullOrBlank()) return "~"
        val clean = path.trimEnd('/')
        val home = System.getProperty("user.home")?.trimEnd('/')
        if (clean == home) return "~"
        return clean.substringAfterLast('/').ifEmpty { "/" }
    }
}
