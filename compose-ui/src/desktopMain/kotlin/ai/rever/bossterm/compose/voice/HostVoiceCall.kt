package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /** Mic loudness, kept off [state] so a 25 Hz meter can't recompose every state reader. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var controller: HostVoiceCallController? = null
    @Volatile private var mirrorJob: Job? = null
    @Volatile private var killSwitchJob: Job? = null

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
        // The master switch is a kill switch on THIS surface too: the share path ends its call when
        // voiceStatus goes unavailable, and the surface that owns the microphone must not be the one
        // that keeps listening after the user turns the feature off.
        killSwitchJob?.cancel()
        killSwitchJob = scope.launch {
            SettingsManager.instance.settings
                .map { it.voiceCallEnabled }
                .distinctUntilChanged()
                .collect { enabled -> if (!enabled) end() }
        }
        // One mirror per call: the previous controller's collector must go, or every start would
        // add another live collector writing into the same flow.
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            launch { c.state.collect { _state.value = it } }
            launch { c.level.collect { _level.value = it } }
        }
        c.start()
    }

    fun toggleMute() = controller?.toggleMute() ?: Unit

    fun end() {
        controller?.end()
        controller = null
        mirrorJob?.cancel()
        mirrorJob = null
        killSwitchJob?.cancel()
        killSwitchJob = null
        _state.value = HostCallState()
        _level.value = 0f
    }

    /** Clear a failed call's error so the pill goes back to idle. */
    fun dismissError() {
        if (_state.value.phase == HostCallPhase.Error) {
            controller = null
            mirrorJob?.cancel()
            mirrorJob = null
            _state.value = HostCallState()
            _level.value = 0f
        }
    }

    /** The focused tab of the primary window, which is what "this tab" means to a host caller. */
    private fun activeTabId(): String? =
        McpTerminalRegistry.primaryState()?.activeTabId
            ?: McpTerminalRegistry.allTabs().firstOrNull()?.id
}
