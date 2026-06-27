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

    /** Listeners notified when the session set changes (open/close/exit) — drives attach Layout pushes. */
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addChangeListener(l: () -> Unit) { changeListeners.add(l) }
    fun removeChangeListener(l: () -> Unit) { changeListeners.remove(l) }

    // Listeners snapshot-encode (DaemonAttachServer.beginLocked), which can be heavy; run them on a
    // dedicated single thread so a slow/back-pressured attach client never blocks the PTY-exit or
    // control-request thread that fired the change. Single thread also preserves notification order.
    private val notifier = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "bossterm-session-notify").apply { isDaemon = true }
    }
    private fun notifyChanged() {
        runCatching { notifier.execute { changeListeners.forEach { l -> runCatching { l() } } } }
    }

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
        core.onExit = { sessions.remove(core.id); notifyChanged() }
        core.start()
        log.info("opened session {} (cwd={}, cmd={})", core.id, cwd, command ?: "<default shell>")
        notifyChanged()
        return core.id
    }

    fun get(id: String): TerminalSessionCore? = sessions[id]

    fun closeSession(id: String) {
        sessions.remove(id)?.let {
            it.close()
            log.info("closed session {}", id)
            notifyChanged()
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
        // close() kills each PTY on a detached thread; join them (bounded) so the JVM doesn't exit
        // before the shells are actually destroyed (which would orphan them).
        val killThreads = sessions.values.mapNotNull { runCatching { it.close() }.getOrNull() }
        sessions.clear()
        val deadline = System.currentTimeMillis() + 3000
        killThreads.forEach { t ->
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) runCatching { t.join(remaining) }
        }
    }

    private fun labelFor(path: String?): String {
        if (path.isNullOrBlank()) return "~"
        val clean = path.trimEnd('/')
        val home = System.getProperty("user.home")?.trimEnd('/')
        if (clean == home) return "~"
        return clean.substringAfterLast('/').ifEmpty { "/" }
    }
}
