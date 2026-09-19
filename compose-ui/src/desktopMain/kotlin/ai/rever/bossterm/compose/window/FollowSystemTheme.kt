package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Install once per application so appearance changes also apply with Settings closed. */
@Composable
fun FollowSystemTheme() {
    val settings by SettingsManager.instance.settings.collectAsState()
    val appearance = rememberMacChromePreferences()
    LaunchedEffect(appearance.dark, settings.followSystemTheme) {
        ThemeManager.instance.refreshSystemAppearance(appearance.dark)
    }
}
