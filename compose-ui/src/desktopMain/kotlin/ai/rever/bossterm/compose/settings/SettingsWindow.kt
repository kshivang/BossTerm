package ai.rever.bossterm.compose.settings

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

/** Debounce delay for auto-saving settings changes */
private const val SETTINGS_DEBOUNCE_MS = 100L

/**
 * Settings window (non-modal, allows terminal interaction).
 *
 * Uses debounced auto-save (100ms) for all controls:
 * - Immediate UI feedback on every change
 * - Disk write after 100ms of no changes (prevents excessive I/O during slider drag)
 * - Sliders also call onSettingsSave on release for immediate persistence
 *
 * @param visible Whether the window is visible
 * @param onDismiss Called when the window should be closed
 * @param onRestartApp Called when app should restart (for settings that require restart)
 */
@Composable
fun SettingsWindow(
    visible: Boolean,
    onDismiss: () -> Unit,
    onRestartApp: (() -> Unit)? = null
) {
    if (!visible) return

    val settingsManager = remember { SettingsManager.instance }
    val savedSettings by settingsManager.settings.collectAsState()

    // Pending settings for smooth slider interaction (no I/O during drag)
    var pendingSettings by remember { mutableStateOf(savedSettings) }

    // Track last saved pending state to avoid redundant saves after external updates
    var lastSavedPending by remember { mutableStateOf(savedSettings) }

    // Sync pending settings when saved settings change externally
    LaunchedEffect(savedSettings) {
        pendingSettings = savedSettings
        lastSavedPending = savedSettings
    }

    // Debounced auto-save: wait for 100ms of inactivity, then save final value
    // Delay first ensures only the last value after rapid changes gets saved
    LaunchedEffect(pendingSettings) {
        delay(SETTINGS_DEBOUNCE_MS)
        if (pendingSettings != savedSettings && pendingSettings != lastSavedPending) {
            lastSavedPending = pendingSettings
            settingsManager.updateSettings(pendingSettings)
        }
    }

    Window(
        onCloseRequest = onDismiss,
        title = "BossTerm Settings",
        resizable = false,
        alwaysOnTop = false,
        state = rememberWindowState(
            size = DpSize(750.dp, 580.dp)
        )
    ) {
        SettingsPanel(
            settings = pendingSettings,
            onSettingsChange = { newSettings ->
                // Update UI immediately (no disk I/O)
                pendingSettings = newSettings
            },
            onSettingsSave = {
                // Sliders call this on release - save immediately
                settingsManager.updateSettings(pendingSettings)
            },
            onResetToDefaults = {
                settingsManager.resetToDefaults()
            },
            onRestartApp = onRestartApp
        )
    }
}
