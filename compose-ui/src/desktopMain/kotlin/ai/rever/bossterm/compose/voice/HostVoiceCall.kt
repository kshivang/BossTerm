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
 * Process-wide handle for the in-app voice call, so the pill in any window can show and drive the
 * one call this app has — mirroring how [ai.rever.bossterm.compose.share.SessionShareManager] owns
 * sharing state for the whole app.
 *
 * Deliberately unnamed here: what the pill says is the embedder's `callLabel`, so writing "Call
 * BossTerm" into this KDoc would go stale the first time a host set it to anything else.
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

    /**
     * Start a call scoped to this host's own tabs, defaulting to whichever tab is focused.
     *
     * The tool scope is every registered tab rather than one share's subset: the caller here is the
     * machine's owner, talking about their own terminal, and this is the same surface their MCP
     * endpoint already exposes.
     *
     * [toolSource] is the embedder's own tool surface, passed down from
     * [ai.rever.bossterm.compose.TabbedTerminal]'s `voiceToolSource` parameter — taken per call
     * rather than registered once, so a host that swaps it (a plugin reload replacing the registry
     * behind it) is picked up by the next call with nothing to unregister. Null leaves the surface
     * exactly as the standalone app has it.
     */
    fun start(toolSource: VoiceToolSource? = null) {
        val existing = controller
        if (existing != null && existing.state.value.active) return
        // `created` so the terminal callback can name the controller it belongs to: the release must
        // be a no-op for a call this object has already moved on from.
        // A FRESH controller per call is required, not merely tidy: JavaSoundVoiceAudioIo is
        // single-use by design (its `disposed` latch is never cleared, because clearing it is what
        // let an end-during-agent-speech resurrect the capture loop and leak a line and a thread per
        // call), so reusing a controller would start a call whose audio can never open.
        var created: HostVoiceCallController? = null
        val ownTools = GuiVoiceToolExecutor(
            inScopeTabIds = { McpTerminalRegistry.allTabs().map { it.id }.toSet() },
            anchorTabId = { activeTabId() },
            // The caller IS the person at the keyboard, so their commands belong in the pane
            // they are looking at. A share's executor deliberately leaves this false.
            mayUseFocusedPane = true,
        )
        // One gate per call, shared by the executor that mints tokens and the controller that
        // reports the user speaking. Built even without a source so the wiring has no second shape.
        val confirmations = VoiceConfirmationGate()
        val c = HostVoiceCallController(
            scope = scope,
            executor = if (toolSource == null) ownTools else CompositeVoiceToolExecutor(
                base = ownTools,
                source = toolSource,
                confirmations = confirmations,
            ),
            confirmations = confirmations,
            onTerminal = { created?.let { releaseIfCurrent(it) } },
        )
        created = c
        controller = c
        killSwitchJob?.cancel()
        killSwitchJob = null
        // One mirror per call: the previous controller's collector must go, or every start would
        // add another live collector writing into the same flow.
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            launch { c.level.collect { _level.value = it } }
            launch { c.state.collect { _state.value = it } }
        }
        c.start()
        // AFTER start(), deliberately. The master switch is a kill switch on this surface too — the
        // share path ends its call when voiceStatus goes unavailable, and the surface that owns the
        // microphone must not be the one still listening after the user turns the feature off. But
        // armed BEFORE start(), a flip landing in the gap ran end() against a controller that had not
        // started: it cleared `controller` and cancelled the mirror, and then start() proceeded
        // anyway, opening the mic and the billed socket with nothing left holding a reference to stop
        // it. The controller re-checks the switch itself at the Connecting → Live transition, which
        // is where the mic is known to be open, so the narrowed window is closed on both sides.
        killSwitchJob = scope.launch {
            SettingsManager.instance.settings
                .map { it.voiceCallEnabled }
                .distinctUntilChanged()
                .collect { enabled -> if (!enabled) end() }
        }
    }

    fun toggleMute() = controller?.toggleMute() ?: Unit

    fun end() {
        // The UI-visible flip happens here; the blocking parts of teardown (joining the socket
        // writer for up to 500ms, closing audio lines, taking toolsLock in dispose) go to IO. This
        // runs from a Compose click handler on the thread that owns terminal rendering.
        _state.value = HostCallState()
        _level.value = 0f
        val ending = controller
        controller = null
        mirrorJob?.cancel()
        mirrorJob = null
        killSwitchJob?.cancel()
        killSwitchJob = null
        if (ending != null) scope.launch(Dispatchers.IO) { ending.end() }
    }

    /**
     * Drop [ended] if it is still the call we own — a call that finished on its own (a ceiling, or a
     * failure) must release the controller and the kill-switch collector, or this object keeps them
     * until the next start()/end() despite documenting that it owns exactly one call.
     *
     * Internal for tests: the object itself builds a real MCP-backed executor, so this rule is only
     * reachable in a test through the seam it is called from.
     */
    internal fun releaseIfCurrent(ended: HostVoiceCallController) {
        if (controller !== ended) return
        // mirrorJob deliberately keeps running. A call that failed sits in Error, and that state has
        // to keep reaching the UI so the bar can offer its Dismiss — cancelling the collector here
        // would freeze the pill on whatever it last showed. It is cheap, and the next
        // start()/end()/dismissError() replaces or cancels it. (A collector cannot cancel itself
        // from inside its own collect anyway.)
        controller = null
        killSwitchJob?.cancel()
        killSwitchJob = null
    }

    /** Clear a failed call's error so the pill goes back to idle. */
    fun dismissError() {
        if (_state.value.phase == HostCallPhase.Error) {
            controller = null
            mirrorJob?.cancel()
            mirrorJob = null
            killSwitchJob?.cancel()
            killSwitchJob = null
            _state.value = HostCallState()
            _level.value = 0f
        }
    }

    /** The focused tab of the primary window, which is what "this tab" means to a host caller. */
    private fun activeTabId(): String? =
        McpTerminalRegistry.primaryState()?.activeTabId
            ?: McpTerminalRegistry.allTabs().firstOrNull()?.id
}
