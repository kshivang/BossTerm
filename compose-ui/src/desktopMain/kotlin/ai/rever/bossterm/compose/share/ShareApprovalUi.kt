package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ApproveColor = Color(0xFF4CAF50)
private val DenyColor = Color(0xFFE57373)

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
        color = Color(0xFF2B2B2B),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderColor),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Session sharing", color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "${request.deviceName} wants to ${verb(request.wantsControl)} this session",
                color = Color.White, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDeny, colors = ButtonDefaults.textButtonColors(contentColor = DenyColor)) {
                    Text("Deny")
                }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = ApproveColor, contentColor = Color.White)
                ) { Text("Approve") }
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
                    Text("wants to ${verb(req.wantsControl)}", color = TextMuted, fontSize = 11.sp)
                }
                TextButton(onClick = { onDeny(req.id) }, colors = ButtonDefaults.textButtonColors(contentColor = DenyColor)) {
                    Text("Deny")
                }
                Button(
                    onClick = { onApprove(req.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = ApproveColor, contentColor = Color.White)
                ) { Text("Approve") }
            }
        }
    }
}
