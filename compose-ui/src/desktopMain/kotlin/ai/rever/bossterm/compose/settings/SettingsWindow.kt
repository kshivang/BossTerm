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
    onRestartApp: (() -> Unit)? = null,
    /** Optional category to open the panel at. Null falls back to [SettingsCategory.default]. */
    initialCategory: SettingsCategory? = null,
    /** Bumped each time settings is requested; raises the window to the front when it changes. */
    focusTick: Int = 0
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
            val baseline = lastSavedPending
            lastSavedPending = pendingSettings
            // Merge only the fields the user changed (vs baseline) — a full replace here would clobber a
            // concurrent programmatic write (e.g. the daemon flipping sessionSharingEnabled).
            settingsManager.mergeChangedFields(baseline, pendingSettings)
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
        // Raise an already-open settings window to the front when settings is requested again.
        LaunchedEffect(focusTick) {
            window.toFront()
            window.requestFocus()
        }
        SettingsPanel(
            settings = pendingSettings,
            onSettingsChange = { newSettings ->
                // Update UI immediately (no disk I/O)
                pendingSettings = newSettings
            },
            onSettingsSave = {
                // Sliders call this on release - save immediately (merge, not full replace).
                val baseline = lastSavedPending
                lastSavedPending = pendingSettings
                settingsManager.mergeChangedFields(baseline, pendingSettings)
            },
            onResetToDefaults = {
                settingsManager.resetToDefaults()
            },
            onRestartApp = onRestartApp,
            initialCategory = initialCategory
        )
    }
}
