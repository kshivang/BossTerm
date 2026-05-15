package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.tabs.TerminalTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

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
