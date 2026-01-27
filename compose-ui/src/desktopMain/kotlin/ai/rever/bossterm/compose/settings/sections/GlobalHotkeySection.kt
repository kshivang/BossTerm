package ai.rever.bossterm.compose.settings.sections

import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.SettingsSection
import ai.rever.bossterm.compose.settings.components.SettingsToggle
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.window.GlobalHotKeyManager
import ai.rever.bossterm.compose.window.HotKeyConfig
import ai.rever.bossterm.compose.window.HotKeyRegistrationStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Global hotkey settings section.
 * Allows configuring system-wide hotkeys to summon specific BossTerm windows.
 * Each window gets a unique hotkey: Modifiers+1, Modifiers+2, etc.
 */
@Composable
fun GlobalHotkeySection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val registrationStatus by GlobalHotKeyManager.registrationStatus.collectAsState()
    val currentConfig = remember(settings) { HotKeyConfig.fromSettings(settings) }
    val isMacOS = remember { ShellCustomizationUtils.isMacOS() }

    Column(modifier = modifier) {
        SettingsSection(title = "Global Hotkey") {
            SettingsToggle(
                label = "Enable Global Hotkeys",
                checked = settings.globalHotkeyEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(globalHotkeyEnabled = it)) },
                description = "Press modifiers+number to summon specific windows"
            )

            // Show current hotkey display
            if (settings.globalHotkeyEnabled) {
                CurrentHotkeyDisplay(
                    config = currentConfig,
                    status = registrationStatus,
                    isMacOS = isMacOS
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(title = "Modifier Keys") {
            SettingsToggle(
                label = if (isMacOS) "Control (⌃)" else "Ctrl",
                checked = settings.globalHotkeyCtrl,
                onCheckedChange = { onSettingsChange(settings.copy(globalHotkeyCtrl = it)) },
                description = "Include Control modifier",
                enabled = settings.globalHotkeyEnabled
            )

            SettingsToggle(
                label = if (isMacOS) "Option (⌥)" else "Alt",
                checked = settings.globalHotkeyAlt,
                onCheckedChange = { onSettingsChange(settings.copy(globalHotkeyAlt = it)) },
                description = if (isMacOS) "Include Option modifier" else "Include Alt modifier",
                enabled = settings.globalHotkeyEnabled
            )

            SettingsToggle(
                label = if (isMacOS) "Shift (⇧)" else "Shift",
                checked = settings.globalHotkeyShift,
                onCheckedChange = { onSettingsChange(settings.copy(globalHotkeyShift = it)) },
                description = "Include Shift modifier",
                enabled = settings.globalHotkeyEnabled
            )

            SettingsToggle(
                label = if (isMacOS) "Command (⌘)" else "Win",
                checked = settings.globalHotkeyWin,
                onCheckedChange = { onSettingsChange(settings.copy(globalHotkeyWin = it)) },
                description = if (isMacOS) "Include Command modifier" else "Include Windows key modifier",
                enabled = settings.globalHotkeyEnabled
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // How it works section
        HowItWorksSection(config = currentConfig, isMacOS = isMacOS)

        Spacer(modifier = Modifier.height(24.dp))

        // Restart notice
        RestartNotice()
    }
}

/**
 * Display current hotkey configuration and registration status.
 */
@Composable
private fun CurrentHotkeyDisplay(
    config: HotKeyConfig,
    status: HotKeyRegistrationStatus,
    isMacOS: Boolean
) {
    val hasModifiers = config.ctrl || config.alt || config.shift || config.win

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Window Hotkeys",
                color = TextPrimary,
                fontSize = 13.sp
            )
            Text(
                text = if (hasModifiers) {
                    // Show example: "⌃⌥1, ⌃⌥2, ⌃⌥3..." or "Ctrl+Alt+1, Ctrl+Alt+2..."
                    val example1 = config.toWindowDisplayString(1, isMacOS)
                    val example2 = config.toWindowDisplayString(2, isMacOS)
                    val example3 = config.toWindowDisplayString(3, isMacOS)
                    "$example1, $example2, $example3..."
                } else {
                    "Select at least one modifier"
                },
                color = if (hasModifiers) AccentColor else Color(0xFFE04040),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Status indicator
        StatusIndicator(status = status)
    }
}

/**
 * Explains how window-specific hotkeys work.
 */
@Composable
private fun HowItWorksSection(config: HotKeyConfig, isMacOS: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceColor)
            .padding(12.dp)
    ) {
        Text(
            text = "How It Works",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = buildString {
                append("Each BossTerm window gets a unique number (1-9).\n")
                append("Press your chosen modifiers + the window number to summon that window.\n\n")
                append("Examples:\n")
                val ex1 = config.toWindowDisplayString(1, isMacOS).ifEmpty { "Modifiers+1" }
                val ex2 = config.toWindowDisplayString(2, isMacOS).ifEmpty { "Modifiers+2" }
                append("  $ex1 - Focus/show window 1\n")
                append("  $ex2 - Focus/show window 2")
            },
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

/**
 * Status indicator showing registration status.
 */
@Composable
private fun StatusIndicator(status: HotKeyRegistrationStatus) {
    val (color, text) = when (status) {
        HotKeyRegistrationStatus.INACTIVE -> Color.Gray to "Inactive"
        HotKeyRegistrationStatus.REGISTERED -> Color(0xFF28C941) to "Active"
        HotKeyRegistrationStatus.FAILED -> Color(0xFFE04040) to "Conflict"
        HotKeyRegistrationStatus.UNAVAILABLE -> Color.Gray to "N/A"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = text,
            color = TextMuted,
            fontSize = 12.sp
        )
    }
}

/**
 * Notice that changes require restart.
 */
@Composable
private fun RestartNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF3A3A3A))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Note: Changes to global hotkey settings take effect after restarting BossTerm.",
            color = TextMuted,
            fontSize = 12.sp
        )
    }
}
