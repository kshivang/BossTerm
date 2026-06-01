package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.tabs.TerminalTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide registry of live [TabbedTerminalState] instances exposed to the
 * MCP server. Each application Window registers its own state on creation and
 * unregisters on dispose. Tab ids are UUIDs (globally unique across windows),
 * so the MCP tools can resolve a tab to its owning state by id alone.
 *
 * Why a registry instead of a per-Window manager: the MCP server binds a
 * single TCP port. Running one manager per Window means the second Window
 * silently fails to bind (BindException). This registry lets a single
 * app-wide manager expose tabs from every Window through one endpoint.
 *
 * Thread-safety: backed by a [CopyOnWriteArrayList], safe for concurrent
 * reads from MCP tool handlers and writes from Window lifecycle callbacks.
 */
object McpTerminalRegistry {

    private val states = CopyOnWriteArrayList<TabbedTerminalState>()

    /** Register a state. Idempotent — duplicate registrations are ignored. */
    fun register(state: TabbedTerminalState) {
        if (!states.contains(state)) {
            states.add(state)
        }
    }

    /** Unregister a state. No-op if not present. */
    fun unregister(state: TabbedTerminalState) {
        states.remove(state)
    }

    /** All tabs across all registered states, in registration order. */
    fun allTabs(): List<TerminalTab> = states.flatMap { it.tabs }

    /**
     * Find the state that owns [tabId], or null if no registered state knows
     * about that tab id.
     */
    fun findState(tabId: String): TabbedTerminalState? =
        states.firstOrNull { it.getTabById(tabId) != null }

    /**
     * Find a tab by id across all registered states, or null if unknown.
     */
    fun findTab(tabId: String): TerminalTab? =
        states.firstNotNullOfOrNull { it.getTabById(tabId) }

    /**
     * The "primary" state used by [get_active_tab]. In a multi-window app
     * there is no globally-correct answer; returning the first registered
     * state is documented as the v1 behavior and is stable.
     */
    fun primaryState(): TabbedTerminalState? = states.firstOrNull()

    /** Total registered state count. Useful for diagnostics/tests. */
    fun stateCount(): Int = states.size

    /**
     * Snapshot of every currently-registered [TabbedTerminalState], in
     * registration order. Returned as a defensive copy — mutating the result
     * does not affect the registry. Embedders use this from their
     * `additionalTools` blocks to iterate windows when defining custom MCP tools.
     */
    fun allStates(): List<TabbedTerminalState> = states.toList()

    // -----------------------------------------------------------------
    // Server running state — owned by BossTermMcpManager, read by UI.
    // -----------------------------------------------------------------

    private val _runningPort = MutableStateFlow<Int?>(null)

    /**
     * Port the MCP Ktor engine is currently bound to, or `null` when no
     * server is running. Used by the in-app status indicator so the dot
     * only lights up when the manager has successfully bound — never just
     * because the user toggled the setting. Reflects reality, not intent.
     *
     * Updated by [BossTermMcpManager] on successful start / on stop.
     */
    val runningPort: StateFlow<Int?> = _runningPort.asStateFlow()

    /** @suppress Manager-only. Mark the server as running on [port]. */
    internal fun setRunning(port: Int) {
        _runningPort.value = port
    }

    /** @suppress Manager-only. Mark the server as stopped. */
    internal fun setStopped() {
        _runningPort.value = null
    }

    // -----------------------------------------------------------------
    // Attached-CLI tracking — written by McpCliAttacher's callers when an
    // attach succeeds; read by the Settings panel and the right-click menus
    // to surface "✓ attached" status.
    //
    // Persisted across runs via [TerminalSettings.mcpAttachedTo] so the
    // next session's auto-reattach has the right targets. The CLI's own
    // config file remains the canonical record; this flow is the manager's
    // mirror of it. Persisted as the stable [McpAttachTarget.persistenceKey]
    // strings (not the raw enum names) so future enum renames don't drop
    // saved state.
    // -----------------------------------------------------------------

    private val _attachedTargets = MutableStateFlow<Set<McpAttachTarget>>(emptySet())

    /** Set of CLIs this BossTerm endpoint is registered with. Hydrated from
     *  TerminalSettings.mcpAttachedTo on manager start; persisted back on
     *  every mark*. */
    val attachedTargets: StateFlow<Set<McpAttachTarget>> = _attachedTargets.asStateFlow()

    /**
     * @suppress Manager-only. Replace the runtime set with the persisted
     *   names from settings. Called once on app start so the UI shows the
     *   correct ✓ marks immediately and the manager's auto-reattach loop
     *   has the right targets to refresh.
     */
    internal fun hydrate(persistedKeys: Set<String>) {
        val targets = persistedKeys.mapNotNull { McpAttachTarget.fromPersistenceKey(it) }.toSet()
        _attachedTargets.value = targets
    }

    /** @suppress Internal — recorded by attach callers on Success. Persists to settings. */
    internal fun markAttached(target: McpAttachTarget) {
        val next = _attachedTargets.value + target
        if (next != _attachedTargets.value) {
            _attachedTargets.value = next
            persist(next)
        }
    }

    /** @suppress Internal — recorded when an attach fails (auto-reattach) or user detaches. */
    internal fun markDetached(target: McpAttachTarget) {
        val next = _attachedTargets.value - target
        if (next != _attachedTargets.value) {
            _attachedTargets.value = next
            persist(next)
        }
    }

    // -----------------------------------------------------------------
    // run_command pane reuse — cache the "MCP scratch pane" per tab so
    // consecutive run_command calls stack into one visible split instead
    // of spawning a new one each time. Eviction is lazy: the cache holds
    // a hint, and run_command verifies the paneId still resolves via
    // state.findSession before reusing. No listener wiring needed.
    //
    // Per-pane Mutex serializes concurrent run_command calls hitting the
    // same pane — without it, two pipelined calls would interleave their
    // scripts in the shell's stdin buffer. Stale entries accumulate when
    // panes close (unbounded by paneId UUIDs) but the leak is bounded by
    // pane-creation rate, which is human-scale.
    // -----------------------------------------------------------------

    private val mcpScratchPanes = ConcurrentHashMap<String /*tabId*/, String /*paneId*/>()
    private val paneMutexes = ConcurrentHashMap<String /*paneId*/, Mutex>()
    private val tabCacheLocks = ConcurrentHashMap<String /*tabId*/, Mutex>()

    /** Most recent MCP scratch pane recorded for [tabId], or null if none. */
    internal fun getScratchPane(tabId: String): String? = mcpScratchPanes[tabId]

    /** Record [paneId] as the active MCP scratch pane for [tabId]. */
    internal fun setScratchPane(tabId: String, paneId: String) {
        mcpScratchPanes[tabId] = paneId
        // Opportunistic GC: shed paneMutex entries for panes / sessions that
        // no longer exist anywhere in the registry. Without this the mutex
        // map grows unbounded — clearScratchPane only sheds entries on the
        // cache-miss-with-stale-pane path, so a user who closes the cached
        // pane and never re-runs against that tab leaks a Mutex forever.
        // Triggered from setScratchPane because it runs on every successful
        // new-pane creation, naturally batching the sweep behind real work.
        if (paneMutexes.size > MCP_MUTEX_GC_THRESHOLD) {
            gcStalePaneMutexes()
        }
    }

    /**
     * Walk every registered state's tabs + split-tree pane snapshots,
     * collect all live ids (tab id, pane id, session id), and evict any
     * [paneMutexes] / [tabCacheLocks] entries that aren't in that set. Also
     * sweeps stale [mcpScratchPanes] entries pointing at dead panes — they'd
     * be lazy-cleared on next read anyway, but better to drop them now while
     * the sweep is hot.
     */
    private fun gcStalePaneMutexes() {
        val live = HashSet<String>()
        for (state in states) {
            for (tab in state.tabs) {
                live.add(tab.id)
                for (snapshot in state.getPaneSnapshots(tab.id)) {
                    live.add(snapshot.id)
                    live.add(snapshot.sessionId)
                }
            }
        }
        paneMutexes.keys.removeIf { it !in live }
        // tabCacheLocks is keyed by tabId; closed tabs would otherwise leak one
        // Mutex each, forever. Swept here with the related pane state.
        tabCacheLocks.keys.removeIf { it !in live }
        mcpScratchPanes.entries.removeIf { (k, v) -> k !in live || v !in live }
    }

    /**
     * Sweep threshold for the opportunistic [gcStalePaneMutexes]. Set well
     * above the realistic concurrent-pane count (matching the
     * `clientWindowByPort` cap heuristic) so a heavy multi-window session
     * doesn't trigger a full-state sweep on every pane creation, while still
     * bounding map growth for very long-lived processes.
     */
    private const val MCP_MUTEX_GC_THRESHOLD = 256

    /**
     * Drop the recorded scratch pane for [tabId] (called when the pane is gone).
     * Pass [paneId] to also evict the stale per-pane mutex, preventing the
     * mutex map from accumulating entries over long-lived sessions.
     */
    internal fun clearScratchPane(tabId: String, paneId: String? = null) {
        mcpScratchPanes.remove(tabId)
        if (paneId != null) paneMutexes.remove(paneId)
    }

    /** Per-pane mutex; created on first use. */
    internal fun paneMutex(paneId: String): Mutex =
        paneMutexes.computeIfAbsent(paneId) { Mutex() }

    /**
     * Per-tab lock that guards the scratch-pane read-or-create-and-cache
     * critical section. Without this, two concurrent run_command calls with
     * the same tabId can each miss the cache, each create a fresh pane, and
     * orphan one of them. Created on first use; held briefly so contention
     * is low.
     */
    internal fun tabCacheLock(tabId: String): Mutex =
        tabCacheLocks.computeIfAbsent(tabId) { Mutex() }

    // -----------------------------------------------------------------
    // Caller-window resolution — pick the window an MCP client is
    // running INSIDE (via process-tree walk in ProcessAncestry) so tools
    // that default to "the primary window" target the window the calling
    // client lives in, not whichever window happened to register first.
    //
    // Updated by the Ktor interceptor in BossTermMcpManager on each
    // incoming request; read by tool handlers in BossTermMcpServer that
    // previously called primaryState() directly.
    //
    // Race: in a multi-client multi-window setup, concurrent requests
    // from different clients write here last-writer-wins. Acceptable —
    // the single-client case (the common one) is consistent; the rare
    // multi-client race may target one client's window for another's
    // call for a single request, never permanently.
    // -----------------------------------------------------------------

    private val lastResolvedClient = AtomicReference<TabbedTerminalState?>(null)

    /**
     * Most recently resolved "calling-client window", or null if no
     * request has been resolved yet OR the resolved state has been
     * unregistered (window closed). Lazy invalidation: stale entries
     * are detected on read and cleared.
     */
    internal fun lastResolvedClientWindow(): TabbedTerminalState? {
        val cached = lastResolvedClient.get() ?: return null
        if (cached !in states) {
            lastResolvedClient.compareAndSet(cached, null)
            return null
        }
        return cached
    }

    /**
     * Manager-only. Pass a resolved state to record it as the most-recent
     * caller window. Null is a no-op — a non-resolving request (client
     * running outside any BossTerm pane) shouldn't blow away the prior
     * resolution from a request that DID resolve.
     */
    internal fun setLastResolvedClientWindow(state: TabbedTerminalState?) {
        if (state != null) lastResolvedClient.set(state)
    }

    private fun persist(targets: Set<McpAttachTarget>) {
        // Sort by enum-declaration order so settings.json is deterministic
        // across saves (kotlinx.serialization writes whatever the Set
        // iterator yields, which isn't ordering-stable for HashSet).
        val keys = McpAttachTarget.entries
            .filter { it in targets }
            .map { it.persistenceKey }
            .toSet()
        SettingsManager.instance.updateSetting { copy(mcpAttachedTo = keys) }
    }
}
