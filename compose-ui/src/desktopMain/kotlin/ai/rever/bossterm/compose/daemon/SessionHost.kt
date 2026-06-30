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

    // groupId -> split tree (session ids as leaves). A session id that never appears as a Pane
    // leaf in any group is a flat/ungrouped session (today's only kind: MCP/CLI-created). Every
    // GUI-attach-created session is grouped, even a lone single-pane "tab" (a 1-pane group).
    private val groups = ConcurrentHashMap<String, GroupNode>()
    // Reverse index, sessionId -> groupId, maintained alongside `groups` under `groupLock`. Lets
    // callers that only know a session id (closeSession, the PTY-exit reaper) cheaply find its
    // group without scanning every tree.
    private val sessionToGroup = ConcurrentHashMap<String, String>()
    // Single coarse lock serializing all group-tree mutations (open-in-group, split, close-in-group,
    // ratio update). Distinct from `sessions` (a ConcurrentHashMap, already mutation-safe on its
    // own) because group mutations are read-modify-write over an immutable tree and must not
    // interleave. Never held across PTY spawn/kill — see [splitPane].
    private val groupLock = Any()

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

    /** A daemon group's wire-ready tree, for GUI-attach broadcast. */
    @Serializable
    data class GroupInfo(val groupId: String, val tree: GroupTreeDto)

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
        // Reap on exit so a dead shell doesn't linger in the registry. Also collapses the session
        // out of its group (if any) — covers a SPONTANEOUS exit (shell `exit`, crash), not just an
        // explicit close, so a split's tree never keeps a dangling leaf for a session that died on
        // its own.
        core.onExit = { collapseFromGroup(core.id); sessions.remove(core.id); notifyChanged() }
        core.start()
        log.info("opened session {} (cwd={}, cmd={})", core.id, cwd, command ?: "<default shell>")
        notifyChanged()
        return core.id
    }

    /** Open a brand-new session AND register it as a fresh single-pane group — the GUI attach
     *  path's "new window" (Client.Open). A single-pane group renders identically to a flat tab,
     *  so every attach-created session being grouped (even alone) lets callers treat "tab" and
     *  "group" uniformly. Returns (sessionId, groupId). */
    fun openWindow(
        cwd: String? = null,
        command: String? = null,
        arguments: List<String> = emptyList(),
        cols: Int = 80,
        rows: Int = 24,
    ): Pair<String, String> {
        val sessionId = openSession(cwd, command, arguments, cols, rows)
        val groupId = java.util.UUID.randomUUID().toString()
        synchronized(groupLock) {
            groups[groupId] = GroupNode.Pane(sessionId = sessionId)
            sessionToGroup[sessionId] = groupId
        }
        notifyChanged()
        return sessionId to groupId
    }

    /**
     * Split the pane holding [sessionId] (must already be a member of some group). Creates a new
     * session and grafts it into that group's tree next to the target pane. Returns the new
     * session id, or null if [sessionId] isn't currently part of any live group (already closed,
     * raced, or ungrouped).
     */
    fun splitPane(
        sessionId: String,
        orientation: SplitOrientation,
        cwd: String? = null,
        ratio: Float = 0.5f,
    ): String? {
        val groupId = synchronized(groupLock) { sessionToGroup[sessionId] } ?: return null
        if (sessions[sessionId] == null) {
            // Target vanished between the lookup above and here (race with PTY exit's
            // collapseFromGroup, which should have already cleaned this up — defensive only).
            synchronized(groupLock) { sessionToGroup.remove(sessionId) }
            return null
        }
        // null is a legitimate "no specific cwd" value (e.g. cwd not yet captured via OSC 7) —
        // openSession() already treats a null cwd as "use the default," so don't bail here.
        val inheritedCwd = cwd ?: sessions[sessionId]?.workingDirectory?.value

        // Spawn the new PTY OUTSIDE groupLock — openSession() does real process work and must not
        // run while holding the tree lock (would block every other group mutation/resync for the
        // duration of the spawn).
        val newSessionId = openSession(cwd = inheritedCwd)

        // Graft into the tree under groupLock, re-validating the target pane is still there — it
        // could have been closed by a concurrent splitPane/closePane/exit while we were spawning.
        val grafted = synchronized(groupLock) {
            val tree = groups[groupId]
            val targetPane = tree?.findPaneBySessionId(sessionId)
            if (tree == null || targetPane == null) {
                false
            } else {
                val newPane = GroupNode.Pane(sessionId = newSessionId)
                val newTree = tree.replaceNode(targetPane.id) { pane ->
                    when (orientation) {
                        SplitOrientation.HORIZONTAL -> GroupNode.HorizontalSplit(top = pane, bottom = newPane, ratio = ratio)
                        SplitOrientation.VERTICAL -> GroupNode.VerticalSplit(left = pane, right = newPane, ratio = ratio)
                    }
                }
                groups[groupId] = newTree
                sessionToGroup[newSessionId] = groupId
                true
            }
        }
        if (!grafted) {
            // Lost the race: the target pane is gone. Don't leak the new session as an orphan.
            closeSession(newSessionId)
            return null
        }
        notifyChanged()
        return newSessionId
    }

    /** Remove [sessionId] from whatever group it's in (idempotent; no-op if ungrouped), collapsing
     *  the parent split so the sibling takes its slot, or dropping the group entirely if it was
     *  the last pane. Pure tree edit — no session side effects. Shared by [closeGroupedSession]
     *  (user-initiated close) and the [openSession] exit reaper (spontaneous exit), so there is
     *  exactly one place that knows how to remove a session from a group. */
    private fun collapseFromGroup(sessionId: String) {
        synchronized(groupLock) {
            val groupId = sessionToGroup.remove(sessionId) ?: return
            val tree = groups[groupId] ?: return
            val pane = tree.findPaneBySessionId(sessionId) ?: return
            val newTree = tree.removePane(pane.id)
            if (newTree == null) groups.remove(groupId) else groups[groupId] = newTree
        }
    }

    /** Close one pane's session. If it's the last pane of its group, this is exactly
     *  [closeSession] (the group disappears too). If it has siblings, the session is killed AND
     *  the tree collapses around it. Ungrouped sessions just close normally. */
    fun closeGroupedSession(sessionId: String) {
        collapseFromGroup(sessionId)
        closeSession(sessionId)
    }

    /** Update one split's ratio within its group's tree (divider drag commit). Pure tree edit. */
    fun updateSplitRatio(groupId: String, splitId: String, ratio: Float) {
        synchronized(groupLock) {
            groups[groupId]?.let { groups[groupId] = it.updateRatio(splitId, ratio) }
        }
        notifyChanged()
    }

    /** All current groups, as wire DTOs, for GroupList broadcast. */
    fun listGroups(): List<GroupInfo> = synchronized(groupLock) {
        groups.map { (groupId, tree) -> GroupInfo(groupId, tree.toDto()) }
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
        synchronized(groupLock) { groups.clear(); sessionToGroup.clear() }
        // Tell attached GUIs the sessions are gone — otherwise a mass close the daemon survives leaves
        // them showing stale tabs. (The notifier is a daemon-thread executor, so it needn't be shut
        // down explicitly; it dies with the JVM on actual daemon exit.)
        notifyChanged()
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
