package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.remote.RemoteStatus
import ai.rever.bossterm.compose.tabs.TerminalTab
import kotlinx.serialization.Serializable

/**
 * Which machine a tab runs on, as an MCP agent needs to see it.
 *
 * A window that has joined other BossTerm shares holds MIRROR tabs: a tab whose shell runs on the
 * sharing machine and whose screen is streamed here. To an agent they used to look exactly like
 * local tabs - same fields, same tools - so "run the tests on machine A" was a guess from titles,
 * and two tools did something worse than fail on them: `run_command` and `run_in_panel` created
 * their scratch split as a LOCAL shell inside the remote tab, so the command ran on this machine
 * while the agent believed it ran on the other one.
 *
 * Everything here is a pure function of what [ai.rever.bossterm.compose.remote.RemoteSession]
 * already knows, so the decisions are testable without a window. [of] is the one live read.
 */
@Serializable
data class MachineRef(
    /** Stable key: [McpMachines.LOCAL_ID] for this machine, else the share's origin hash. */
    val id: String,
    /** What to call it: the sharer's session name (their username by default), or "local". */
    val name: String,
    val remote: Boolean,
    /** For a chained share (A shared into B, B shared into us): the machine we reach it through. */
    val via: String? = null,
    /** False when we are view-only on it (or on any hop of a chain): writes are refused. */
    val canControl: Boolean = true,
    /** False while the connection to it (or any hop) is down: writes are refused. */
    val connected: Boolean = true,
)

/** One machine and the tabs it owns, for `list_machines`. */
@Serializable
data class MachineInfo(
    val id: String,
    val name: String,
    val remote: Boolean,
    val via: String? = null,
    val canControl: Boolean,
    val connected: Boolean,
    val tabIds: List<String>,
)

object McpMachines {
    const val LOCAL_ID = "local"

    val LOCAL = MachineRef(id = LOCAL_ID, name = "local", remote = false)

    /**
     * A remote tab's machine. [deeperKey] is set when the tab is itself a mirror the direct host
     * re-shares: then the machine is that deeper origin, reached [MachineRef.via] the direct host,
     * and it is only controllable / connected if every hop is.
     */
    fun remote(
        directKey: String,
        directName: String,
        directCanControl: Boolean,
        directConnected: Boolean,
        deeperKey: String? = null,
        deeperName: String? = null,
        deeperReadOnly: Boolean = false,
        deeperOffline: Boolean = false,
    ): MachineRef =
        if (deeperKey != null) {
            MachineRef(
                id = deeperKey,
                name = deeperName?.takeIf { it.isNotBlank() } ?: "remote",
                remote = true,
                via = directName,
                canControl = directCanControl && !deeperReadOnly,
                connected = directConnected && !deeperOffline,
            )
        } else {
            MachineRef(
                id = directKey,
                name = directName,
                remote = true,
                canControl = directCanControl,
                connected = directConnected,
            )
        }

    /**
     * Why [tool] must not write to a tab on [machine], or null if it may. A view-only write used
     * to report success while the keystrokes went nowhere (and popped a "request control" prompt
     * on this machine's screen instead), so the agent concluded its command had run.
     */
    fun writeRefusal(machine: MachineRef, tool: String): String? = when {
        !machine.remote -> null
        !machine.connected ->
            "$tool: this tab is on remote machine '${machine.label()}', which is disconnected. " +
                "Nothing was sent; check list_machines and retry once it is connected again."
        !machine.canControl ->
            "$tool: this tab is on remote machine '${machine.label()}', where this session is view-only. " +
                "Nothing was sent. Reading (read_scrollback, search_output) works; writing needs the " +
                "machine's owner to grant control."
        else -> null
    }

    /**
     * Why [tool] cannot create or draw in a pane of a tab on [machine], or null if it can. Splits
     * and images are made by THIS machine: in a remote tab they become a local shell or a local
     * picture, so the agent would act on the wrong computer while the result looked normal.
     */
    fun localPaneRefusal(machine: MachineRef, tool: String, what: String): String? =
        if (!machine.remote) {
            null
        } else {
            "$tool: this tab is on remote machine '${machine.label()}', and $what would be created on " +
                "this machine instead, not on '${machine.name}'. Use an existing pane of the remote tab " +
                "(list_panes gives pane ids) with send_input, or run_command with pane_id."
        }

    /** Tabs grouped by machine, this machine first, then remote machines in first-seen order. */
    fun group(tabs: List<Pair<String, MachineRef>>): List<MachineInfo> {
        val byId = LinkedHashMap<String, Pair<MachineRef, MutableList<String>>>()
        byId[LOCAL_ID] = LOCAL to mutableListOf()
        for ((tabId, machine) in tabs) {
            byId.getOrPut(machine.id) { machine to mutableListOf() }.second.add(tabId)
        }
        return byId.values.map { (m, ids) ->
            MachineInfo(m.id, m.name, m.remote, m.via, m.canControl, m.connected, ids)
        }
    }

    /**
     * The machine [tab] of [state] runs on. Only remote tabs touch [TabbedTerminalState.remoteSessions]
     * (it is created lazily), and an unattributable mirror is reported as an unreachable remote
     * rather than as local, so a write is refused instead of guessed.
     */
    fun of(state: TabbedTerminalState, tab: TerminalTab): MachineRef {
        if (!tab.isRemote) return LOCAL
        val session = state.remoteSessions.sessionForTab(tab)
            ?: return MachineRef(id = "remote:${tab.id}", name = "remote", remote = true, canControl = false, connected = false)
        val directName = session.customName.value ?: session.hostName.value
            ?: runCatching { java.net.URI(session.link).host }.getOrNull() ?: "remote"
        val deeper = session.upstreamFor(tab.id)
        return remote(
            directKey = session.originHash ?: session.link,
            directName = directName,
            directCanControl = session.canControlState.value,
            directConnected = session.statusState.value is RemoteStatus.Connected,
            deeperKey = deeper?.key,
            deeperName = deeper?.name,
            deeperReadOnly = deeper?.readOnly ?: false,
            deeperOffline = deeper?.offline ?: false,
        )
    }

    private fun MachineRef.label(): String = if (via != null) "$name (via $via)" else name
}
