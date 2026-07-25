package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Process-wide handle for the in-app ("Call BossTerm") voice call, so the pill in any window can
 * show and drive the one call this app has — mirroring how [ai.rever.bossterm.compose.share.SessionShareManager]
 * owns sharing state for the whole app.
 *
 * There is deliberately ONE call at a time: it owns the microphone and the speakers.
 */
internal object HostVoiceCall {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("boss-voice"))

    private val _state = MutableStateFlow(HostCallState())

    /** Live call state for the UI (pill + call bar). */
    val state: StateFlow<HostCallState> = _state.asStateFlow()

    @Volatile private var controller: HostVoiceCallController? = null

    /** Whether a call is possible at all: the feature on, and a key stored. */
    fun available(): Boolean =
        SettingsManager.instance.settings.value.voiceCallEnabled &&
            VoiceAgentStorage.keyPresentFlow.value

    /**
     * Start a call scoped to this host's own tabs, defaulting to whichever tab is focused.
     *
     * The tool scope is every registered tab rather than one share's subset: the caller here is the
     * machine's owner, talking about their own terminal, and this is the same surface their MCP
     * endpoint already exposes.
     */
    fun start() {
        val existing = controller
        if (existing != null && existing.state.value.active) return
        val c = HostVoiceCallController(
            scope = scope,
            executor = GuiVoiceToolExecutor(
                inScopeTabIds = { McpTerminalRegistry.allTabs().map { it.id }.toSet() },
                anchorTabId = { activeTabId() },
            ),
        )
        controller = c
        scope.launch { c.state.collect { _state.value = it } }
        c.start()
    }

    fun toggleMute() = controller?.toggleMute() ?: Unit

    fun end() {
        controller?.end()
        controller = null
        _state.value = HostCallState()
    }

    /** Clear a failed call's error so the pill goes back to idle. */
    fun dismissError() {
        if (_state.value.phase == HostCallPhase.Error) {
            controller = null
            _state.value = HostCallState()
        }
    }

    /** The focused tab of the primary window, which is what "this tab" means to a host caller. */
    private fun activeTabId(): String? =
        McpTerminalRegistry.primaryState()?.activeTabId
            ?: McpTerminalRegistry.allTabs().firstOrNull()?.id
}
