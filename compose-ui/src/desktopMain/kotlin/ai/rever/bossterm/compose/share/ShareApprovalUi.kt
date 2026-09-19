package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.Danger
import ai.rever.bossterm.compose.settings.SettingsTheme.Success
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ApproveColor get() = Success
private val DenyColor get() = Danger

private fun verb(wantsControl: Boolean) = if (wantsControl) "control" else "view"

/**
 * Floating banner (issue #276) prompting the host to approve/deny one device's
 * request to connect to a share. Shown in the top-right overlay alongside the
 * status strip; the full queue also appears in the share dialog.
 */
@Composable
fun ShareRequestToast(
    request: SessionShareManager.PendingShareRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        // This overlays terminal content without a native blur layer. Keep it opaque.
        color = SurfaceColor.copy(alpha = 1f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderColor),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.widthIn(max = 340.dp).padding(16.dp)) {
            Text("Session sharing", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "${request.deviceName} wants to ${verb(request.wantsControl)} this session",
                color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp
            )
            if (request.wantsControl) {
                Spacer(Modifier.height(6.dp))
                Text("Control includes file browsing and uploads in your home folder.", color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDeny, colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)) {
                    Text("Deny", fontSize = 13.sp)
                }
                Button(
                    onClick = onApprove,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ApproveColor, contentColor = BossUiTheme.current.ink)
                ) { Text("Approve", fontSize = 13.sp) }
            }
        }
    }
}

/**
 * The pending-request queue rendered inside the share dialog. Each row names the
 * device and what it asked for, with Approve / Deny. Renders nothing when empty.
 */
@Composable
fun PendingRequestsList(
    requests: List<SessionShareManager.PendingShareRequest>,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    requests.forEach { req ->
        Surface(color = SurfaceColor, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(req.deviceName, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (req.wantsControl) "wants control, including file browsing and uploads" else "wants to view", color = TextMuted, fontSize = 11.sp)
                }
                TextButton(onClick = { onDeny(req.id) }, colors = ButtonDefaults.textButtonColors(contentColor = DenyColor)) {
                    Text("Deny")
                }
                Button(
                    onClick = { onApprove(req.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = ApproveColor, contentColor = BossUiTheme.current.ink)
                ) { Text("Approve") }
            }
        }
    }
}
