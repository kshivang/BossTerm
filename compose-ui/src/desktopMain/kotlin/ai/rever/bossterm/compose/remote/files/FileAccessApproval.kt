package ai.rever.bossterm.compose.remote.files

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.settings.SettingsTheme
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.rever.bossterm.compose.window.GlassAlertDialog3
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Path

/** File permissions belong to a live connection, never to its saved terminal-control grant. */
internal object FileAccessApproval {
    data class Grant(val root: Path)
    class Request(val viewer: String) { val decision = CompletableDeferred<Grant?>() }
    val pending = MutableStateFlow<Request?>(null)
    private val gate = Mutex()
    suspend fun request(viewer: String): Grant? {
        if (!gate.tryLock()) return null
        val request = Request(viewer)
        try {
            pending.value = request
            return withTimeoutOrNull(120_000) { request.decision.await() }
        } finally { pending.value = null; gate.unlock() }
    }
}

@Composable
internal fun FileAccessApprovalDialog() = RemoteFilesTheme { FileAccessApprovalContent() }

@Composable
private fun FileAccessApprovalContent() {
    val request by FileAccessApproval.pending.collectAsState()
    val current = request ?: return
    var folder by remember(current) { mutableStateOf(System.getProperty("user.home")) }
    var error by remember(current) { mutableStateOf<String?>(null) }
    var checking by remember(current) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    GlassAlertDialog3(
        onDismissRequest = { current.decision.complete(null) },
        containerColor = SettingsTheme.SurfaceColor,
        title = { Text("Allow remote file access?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.widthIn(max = 420.dp).heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${current.viewer.take(80)} wants read-only access to browse and download files on this computer. Choose the folder they may access.")
                OutlinedTextField(folder, { folder = it }, label = { Text("Allowed folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val picker = javax.swing.JFileChooser().apply {
                        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Choose allowed remote folder"
                    }
                    if (picker.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) folder = picker.selectedFile.absolutePath
                }) { Text("Choose Folder…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Access ends when this connection closes. Stop sharing to revoke it immediately. Symbolic links are not accessible.", color = SettingsTheme.TextSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(enabled = folder.isNotBlank() && !checking, onClick = {
                checking = true
                val selected = folder
                scope.launch {
                    try {
                        val root = withContext(Dispatchers.IO) {
                            val path = Path.of(selected).toRealPath()
                            FileDirectory.open(path).use { }
                            path
                        }
                        current.decision.complete(FileAccessApproval.Grant(root))
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Choose an existing, accessible folder." }
                    finally { checking = false }
                }
            }) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = { current.decision.complete(null) }) { Text("Deny") } },
    )
}
