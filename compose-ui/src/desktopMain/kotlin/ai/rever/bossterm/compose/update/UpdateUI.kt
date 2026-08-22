package ai.rever.bossterm.compose.update

import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Update notification banner that appears at the top of the application.
 */
@Composable
fun UpdateBanner(
    updateState: UpdateState,
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: (UpdateInfo) -> Unit = {},
    onInstallUpdate: (String) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    when (updateState) {
        is UpdateState.UpdateAvailable -> {
            UpdateAvailableBanner(
                updateInfo = updateState.updateInfo,
                onDownload = { onDownloadUpdate(updateState.updateInfo) },
                onDismiss = onDismiss
            )
        }
        is UpdateState.Downloading -> {
            DownloadProgressBanner(progress = updateState.progress)
        }
        is UpdateState.ReadyToInstall -> {
            ReadyToInstallBanner(
                onInstall = { onInstallUpdate(updateState.downloadPath) }
            )
        }
        is UpdateState.RestartRequired -> {
            RestartRequiredBanner()
        }
        is UpdateState.Error -> {
            ErrorBanner(
                message = updateState.message,
                onRetry = onCheckForUpdates,
                onDismiss = onDismiss
            )
        }
        else -> { /* No banner for other states */ }
    }
}

// Colors matching TabBar
// Getters, not vals: a file-scope val is initialised at class-init and cannot
// follow a theme switch. AccentBlue is used for text and icons as well as fills, so
// it takes `signalText` - the accent held to the 4.5:1 text floor - rather than
// `signal`, which is only held to the 3:1 component floor.
private val BannerBackground: Color get() = BossUiTheme.current.panel
private val AccentBlue: Color get() = BossUiTheme.current.signalText
private val AccentGreen: Color get() = BossUiTheme.current.ok
private val AccentOrange: Color get() = BossUiTheme.current.warn
private val AccentRed: Color get() = BossUiTheme.current.alert

@Composable
private fun UpdateAvailableBanner(
    updateInfo: UpdateInfo,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BannerBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Update Available",
                    tint = AccentBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Update v${updateInfo.latestVersion} available",
                    color = BossUiTheme.current.chalk,
                    fontSize = 12.sp
                )
                Text(
                    " (current: v${updateInfo.currentVersion})",
                    color = BossUiTheme.current.mist,
                    fontSize = 12.sp
                )
            }

            Row {
                TextButton(
                    onClick = onDownload,
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentBlue),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Download", fontSize = 11.sp)
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = BossUiTheme.current.mist),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Dismiss", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressBanner(progress: Float) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BannerBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Downloading",
                tint = AccentGreen,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "Downloading... ${(progress * 100).toInt()}%",
                color = BossUiTheme.current.chalk,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.weight(1f).height(4.dp),
                color = AccentGreen,
                backgroundColor = BossUiTheme.current.line
            )
        }
    }
}

@Composable
private fun ReadyToInstallBanner(onInstall: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BannerBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Ready to Install",
                    tint = AccentOrange,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Update ready to install - password prompt will appear",
                    color = BossUiTheme.current.chalk,
                    fontSize = 12.sp
                )
            }

            TextButton(
                onClick = onInstall,
                // The banner's primary action, so it takes the accent held to the text
                // floor - `warn` is a fill token and 3.3:1 on daylight. The Info icon
                // above keeps AccentOrange: an icon is held to the 3:1 component floor.
                colors = ButtonDefaults.textButtonColors(contentColor = AccentBlue),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("Install Update", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun RestartRequiredBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BannerBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Restart Required",
                tint = AccentBlue,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "Installing update... App will relaunch automatically. Check /tmp/bossterm-updater/ for logs if needed.",
                color = BossUiTheme.current.chalk,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BannerBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = AccentRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Update error: $message",
                        color = BossUiTheme.current.chalk,
                        fontSize = 12.sp,
                        maxLines = 2
                    )
                }

                Row {
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(contentColor = AccentBlue),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Retry", fontSize = 11.sp)
                    }
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = BossUiTheme.current.mist),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Dismiss", fontSize = 11.sp)
                    }
                }
            }
            Text(
                "Check logs: /tmp/bossterm-updater/update-*.log or /tmp/bossterm-update-debug-*.log",
                color = BossUiTheme.current.mist,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, start = 22.dp)
            )
        }
    }
}

/**
 * Format timestamp as human-readable time ago string.
 */
fun formatTimeAgo(timestampMs: Long?): String {
    if (timestampMs == null) return "Never"

    val now = System.currentTimeMillis()
    val diffMs = now - timestampMs
    val diffMinutes = diffMs / (1000 * 60)
    val diffHours = diffMinutes / 60
    val diffDays = diffHours / 24

    return when {
        diffMinutes < 5 -> "Just now"
        diffMinutes < 60 -> "$diffMinutes minutes ago"
        diffHours == 1L -> "1 hour ago"
        diffHours < 24 -> "$diffHours hours ago"
        diffDays == 1L -> "Yesterday"
        diffDays < 7 -> "$diffDays days ago"
        else -> "${diffDays / 7} weeks ago"
    }
}
