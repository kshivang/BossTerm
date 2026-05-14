package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.tabs.TerminalTab
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
}
