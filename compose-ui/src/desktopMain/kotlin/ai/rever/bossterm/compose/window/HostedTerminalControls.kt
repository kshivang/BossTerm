package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.mcp.*
import ai.rever.bossterm.compose.settings.*
import ai.rever.bossterm.compose.share.*
import ai.rever.bossterm.compose.voice.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Window-owned controls for embedders, independent of any terminal's composition lifetime.
 * [activeTabId] resolves the window's last active terminal at click time; null keeps sharing
 * settings available without creating a terminal or sharing another window implicitly.
 * A call without a configured key is exported as call_setup for the host's More menu.
 * A disabled service is enabled only by an explicit action;
 * terminal floating-indicator preferences do not hide host-owned navigation.
 */
@Composable
fun HostedTerminalControls(
    activeTabId: () -> String?,
    callLabel: String,
    voiceToolSource: VoiceToolSource?,
    onActions: (List<HostedStatusAction>) -> Unit,
) {
    val settings by SettingsManager.instance.settings.collectAsState()
    val port by McpTerminalRegistry.runningPort.collectAsState()
    val shared by SessionShareManager.sharedTabIds.collectAsState()
    val pending by SessionShareManager.pendingRequests.collectAsState()
    val remoteUrl by SessionShareManager.remoteUrlFlow.collectAsState()
    val remoteCalls by RemoteVoiceCalls.active.collectAsState()
    val call by HostVoiceCall.state.collectAsState()
    val storedKeyPresent by VoiceAgentStorage.keyPresentFlow.collectAsState()
    val keyPresent by produceState(false, storedKeyPresent) {
        while (true) {
            value = withContext(Dispatchers.IO) { !VoiceKeySource.resolve().isNullOrBlank() }
            delay(1_000)
        }
    }
    var hostedActions by remember { mutableStateOf(emptyList<HostedStatusAction>()) }
    val publishActions by rememberUpdatedState(onActions)
    LaunchedEffect(hostedActions, keyPresent) {
        publishActions(hostedActions.map { action ->
            if (action.id == "call" && !keyPresent) action.copy(id = "call_setup") else action
        })
    }
    DisposableEffect(Unit) { onDispose { publishActions(emptyList()) } }
    val segment = call.segmentState(featureEnabled = true, indicatorEnabled = true, keyPresent = keyPresent)
    val scope = rememberCoroutineScope()
    val menu = remember { ContextMenuController() }
    val config = LocalBossTermMcpConfig.current
    var settingsCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var settingsTick by remember { mutableIntStateOf(0) }
    var keyPrompt by remember { mutableStateOf(false) }
    var shareInfo by remember { mutableStateOf<SessionShareManager.ShareInfo?>(null) }
    var shareTick by remember { mutableIntStateOf(0) }
    var shareUnavailable by remember { mutableStateOf(false) }
    val sharing = remember { HostedShareController() }
    var attaching by remember { mutableStateOf(false) }
    var attachStatus by remember { mutableStateOf<AttachStatus?>(null) }
    DisposableEffect(menu) { onDispose { menu.hideMenu() } }
    LaunchedEffect(remoteUrl) {
        shareInfo?.let { shareInfo = sharing.existing(it.tabId) }
    }
    fun showSettings(category: SettingsCategory) { settingsCategory = category; settingsTick++ }
    fun openShare(id: String, shareScope: ShareScope) {
        SettingsManager.instance.updateSetting { copy(sessionSharingEnabled = true) }
        scope.launch {
            shareInfo = sharing.open(id, shareScope)
            shareUnavailable = shareInfo == null
            shareTick++
        }
    }
    Box(Modifier.size(0.dp)) {
        HostedStatusActions({ hostedActions = it }) {
            StatusStrip(
                showMcp = true, mcpOn = port != null,
                onMcpClick = {
                    menu.showMenu(0f, 0f, buildIndicatorMenuItems(
                        attached = McpTerminalRegistry.attachedTargets.value,
                        isRunning = port != null, isUserEnabled = settings.mcpEnabled,
                        serverLabel = config?.serverName ?: "bossterm",
                        onAttachRequest = { target ->
                            val currentPort = McpTerminalRegistry.runningPort.value
                            if (!attaching && currentPort != null) {
                                attaching = true
                                scope.launch {
                                    try {
                                        val result = McpCliAttacher.attach(target, config?.serverName ?: "bossterm", currentPort)
                                        if (result is McpAttachResult.Success) McpTerminalRegistry.markAttached(target)
                                        attachStatus = AttachStatus.Done(result)
                                    } finally { attaching = false }
                                }
                            }
                        },
                        onShowSettings = { showSettings(SettingsCategory.MCP) },
                        onTurnOffRequest = { SettingsManager.instance.updateSetting { copy(mcpEnabled = false) } },
                        onTurnOnRequest = { SettingsManager.instance.updateSetting { copy(mcpEnabled = true) } },
                    ))
                },
                showSharing = true, sharingCount = shared.size, remoteCalls = remoteCalls,
                onSharingClick = {
                    val id = activeTabId()
                    val existing = sharing.existing(id)
                    if (existing != null) {
                        shareInfo = existing
                        shareTick++
                    } else {
                        menu.showMenu(0f, 0f, listOf(
                            ContextMenuController.MenuItem("share_tab", "Share This Tab", enabled = id != null,
                                action = { id?.let { openShare(it, ShareScope.TAB) } }),
                            ContextMenuController.MenuItem("share_window", "Share Whole Window", enabled = id != null,
                                action = { id?.let { openShare(it, ShareScope.WINDOW) } }),
                            ContextMenuController.MenuItem("share_all", "Share All Windows", enabled = id != null,
                                action = { id?.let { openShare(it, ShareScope.ALL) } }),
                            ContextMenuController.MenuItem("share_settings", "Sharing Settings…", enabled = true,
                                action = { showSettings(SettingsCategory.SESSION_SHARING) }),
                        ))
                    }
                },
                call = segment, callLabel = callLabel,
                onCallClick = {
                    when (segment) {
                        CallSegmentState.NeedsKey -> keyPrompt = true
                        CallSegmentState.Failed -> HostVoiceCall.dismissError()
                        CallSegmentState.Connecting, CallSegmentState.Live,
                        CallSegmentState.Speaking, CallSegmentState.Working -> HostVoiceCall.end()
                        else -> {
                            SettingsManager.instance.updateSetting { copy(voiceCallEnabled = true) }
                            HostVoiceCall.start(voiceToolSource)
                        }
                    }
                },
            )
        }
    }
    if (shareUnavailable) GlassAlertDialog3(
        onDismissRequest = { shareUnavailable = false },
        title = { androidx.compose.material3.Text("Unable to share this terminal") },
        text = { androidx.compose.material3.Text(
            "A sharing link could not be created. If this terminal is already shared through your account, " +
                "manage that share in Sharing Settings. Otherwise, check your sharing configuration and try again."
        ) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                shareUnavailable = false
                showSettings(SettingsCategory.SESSION_SHARING)
            }) { androidx.compose.material3.Text("Sharing Settings") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { shareUnavailable = false }) {
                androidx.compose.material3.Text("Close")
            }
        },
    )
    if (keyPrompt) VoiceKeyDialog(
        onDismiss = { keyPrompt = false },
        onSaved = {
            keyPrompt = false
            SettingsManager.instance.updateSetting { copy(voiceCallEnabled = true) }
            HostVoiceCall.start(voiceToolSource)
        }, callLabel = callLabel,
    )
    SettingsWindow(visible = settingsCategory != null, onDismiss = { settingsCategory = null },
        initialCategory = settingsCategory, focusTick = settingsTick)
    shareInfo?.let { info ->
        ShareWindow(info, onDismiss = { shareInfo = null },
            onStop = { SessionShareManager.unshare(info.tabId); shareInfo = null },
            onScopeChange = { SessionShareManager.reshare(info.tabId, it)?.let { updated -> shareInfo = updated } },
            focusTick = shareTick, pendingRequests = pending,
            onApproveRequest = { SessionShareManager.approveRequest(it) },
            onDenyRequest = { SessionShareManager.denyRequest(it) },
            tailscaleMode = settings.shareTailscaleMode,
            onTailscaleModeChange = { SettingsManager.instance.updateSetting { copy(shareTailscaleMode = it) } },
            onRefreshLink = { SessionShareManager.refreshRemoteLink() },
            sessionName = SessionShareManager.sessionNameFor(info.tabId).orEmpty(),
            onSessionNameChange = { SessionShareManager.setSessionName(info.tabId, it) }, callLabel = callLabel)
    }
    attachStatus?.let { status ->
        androidx.compose.ui.window.DialogWindow(onCloseRequest = { attachStatus = null }, title = "MCP attach") {
            AttachToast(status)
        }
    }
}
