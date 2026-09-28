package ai.rever.bossterm.compose.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One current notice attached to its connection, never a queued application-wide prompt. */
data class RemoteResizeNotice(
    val paneName: String,
    val hostColumns: Int,
    val hostRows: Int,
    val localColumns: Int,
    val localRows: Int,
    val canResizeHost: Boolean,
    val onFitMyWindow: () -> Unit,
    val onFitHost: () -> Unit,
    val onDismiss: () -> Unit,
)

internal fun remoteGridMismatch(hostCols: Int, hostRows: Int, localCols: Int, localRows: Int): Boolean =
    minOf(hostCols, hostRows, localCols, localRows) >= 2 &&
        (kotlin.math.abs(hostCols - localCols) > 2 || kotlin.math.abs(hostRows - localRows) > 2)

internal fun remoteConnectionName(customName: String?, hostName: String?, link: String): String =
    customName?.takeIf { it.isNotBlank() } ?: hostName?.takeIf { it.isNotBlank() }
    ?: runCatching { java.net.URI(link).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Remote session"

internal fun remoteConnectionStatus(status: RemoteStatus, hasMirroredTabs: Boolean): String = when (status) {
    RemoteStatus.Connecting -> if (hasMirroredTabs) "Reconnecting…" else "Connecting…"
    RemoteStatus.Pending -> "Awaiting approval…"
    is RemoteStatus.Connected -> if (status.canControl) "Connected · control" else "Connected · view only"
    is RemoteStatus.Denied -> "Access denied"
    is RemoteStatus.Failed -> "Disconnected"
    RemoteStatus.Closed -> "Closed"
}

internal fun canReconnectRemote(status: RemoteStatus): Boolean = status is RemoteStatus.Failed

/** Shared by the sidebar connection box and the explicitly opened Remote Sessions manager. */
@Composable
internal fun RemoteConnectionDetails(
    message: String?,
    onReconnect: (() -> Unit)?,
    resize: RemoteResizeNotice?,
    textColor: Color,
    actionColor: Color,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        message?.takeIf { it.isNotBlank() }?.let { Text(it, color = textColor, fontSize = 10.sp, maxLines = 2) }
        if (onReconnect != null) {
            Text("Reconnect", color = actionColor, fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onReconnect).padding(vertical = 4.dp))
        }
        resize?.let { notice ->
            Text("Resize · ${notice.paneName}", color = textColor, fontSize = 11.sp)
            Text("Host ${notice.hostColumns}×${notice.hostRows} · here ${notice.localColumns}×${notice.localRows}",
                color = textColor, fontSize = 10.sp)
            Text("Fit my window to host", color = actionColor, fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = notice.onFitMyWindow).padding(vertical = 4.dp))
            Text(if (notice.canResizeHost) "Fit host to my window" else "Request control to resize host",
                color = actionColor, fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = notice.onFitHost).padding(vertical = 4.dp))
            Text("Keep current size", color = textColor, fontSize = 10.sp,
                modifier = Modifier.clickable(onClick = notice.onDismiss).padding(vertical = 4.dp))
        }
    }
}
