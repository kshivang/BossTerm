package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.remote.RemoteSession
import ai.rever.bossterm.compose.remote.RemoteStatus
import ai.rever.bossterm.compose.share.RemoteFileEntry
import ai.rever.bossterm.compose.settings.DialogTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.window.GlassWindow
import ai.rever.bossterm.compose.window.GlassAlertDialog3
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.nio.file.Files
import java.nio.file.Path

private data class FileTask(val local: Path, val remote: String, val upload: Boolean)
private class Replacement(val name: String) { val answer = CompletableDeferred<Boolean>() }

/** Group-level file browser. Its target is always this direct host, never a nested tab's origin. */
@Composable
internal fun RemoteFilesWindow(session: RemoteSession, uploadInitially: Boolean, openRequest: Int, onDismiss: () -> Unit) =
    RemoteFilesTheme { RemoteFilesWindowContent(session, uploadInitially, openRequest, onDismiss) }

@Composable
private fun RemoteFilesWindowContent(session: RemoteSession, uploadInitially: Boolean, openRequest: Int, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = session.files
    var root by remember { mutableStateOf<String?>(null) }
    var writable by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<RemoteFileEntry>()) }
    var next by remember { mutableStateOf<Int?>(null) }
    var hidden by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var fraction by remember { mutableStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var retry by remember { mutableStateOf<List<FileTask>>(emptyList()) }
    var replacement by remember { mutableStateOf<Replacement?>(null) }
    val connected = session.statusState.value is RemoteStatus.Connected && session.filesAvailable.value

    suspend fun load(target: String, append: Boolean = false) {
        val reply = client.request("list", target, if (append) next?.toLong() ?: 0 else 0)
        path = target
        entries = if (append) entries + reply.entries else reply.entries
        next = reply.next
    }
    suspend fun ensureAccess(upload: Boolean) {
        if (upload && !session.canControlState.value) {
            progress = "Requesting control from the host…"
            val granted = try { session.requestControlForFiles() } finally { progress = "" }
            check(granted) { "Control was not granted. Click Upload to request it again." }
        }
        if (root == null || (session.canControlState.value && !writable)) {
            val grant = client.request("access")
            val firstOpen = root == null
            root = grant.root; writable = grant.writable
            if (firstOpen) {
                val prefix = grant.root.orEmpty().trimEnd('/') + "/"
                val cwd = session.preferredFileDirectory()
                val initial = cwd?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix).orEmpty()
                try { load(initial) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { load("") }
            }
        }
        progress = ""
    }
    fun browse(target: String, append: Boolean = false) {
        if (busy) return
        busy = true; error = null
        job = scope.launch {
            try { load(target, append) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not read folder" }
            finally { busy = false }
        }
    }
    suspend fun replace(name: String): Boolean {
        val prompt = Replacement(name)
        replacement = prompt
        return try { prompt.answer.await() } finally { replacement = null }
    }
    fun transfer(tasks: List<FileTask>) {
        if (busy || tasks.isEmpty()) return
        busy = true; error = null; retry = emptyList()
        job = scope.launch {
            var index = 0
            try {
                for ((i, task) in tasks.withIndex()) {
                    index = i; fraction = 0f
                    var overwrite = false
                    if (!task.upload && Files.exists(task.local)) {
                        if (!replace(task.local.fileName.toString())) continue
                        overwrite = true
                    }
                    val report: (Long, Long) -> Unit = { done, size ->
                        progress = "${i + 1}/${tasks.size} · ${task.local.fileName} · ${formatSize(done)} / ${formatSize(size)}"
                        fraction = if (size == 0L) 1f else (done.toDouble() / size).toFloat()
                    }
                    if (task.upload) {
                        try { client.upload(task.local, task.remote, false, report) }
                        catch (e: IllegalStateException) {
                            if (e.message != "File already exists") throw e
                            if (replace(task.local.fileName.toString())) client.upload(task.local, task.remote, true, report)
                        }
                    } else client.download(task.remote, task.local, overwrite, report)
                }
                progress = "Transfers complete"; fraction = 1f
                load(path)
            } catch (e: CancellationException) {
                retry = tasks.drop(index); progress = "Transfer cancelled"; throw e
            } catch (e: Exception) {
                retry = tasks.drop(index); error = e.message ?: "Transfer failed"
            } finally { busy = false }
        }
    }

    GlassWindow(onCloseRequest = onDismiss, title = "Files — ${session.customName.value ?: session.hostName.value ?: "Remote BossTerm"}",
        state = rememberWindowState(size = DpSize(760.dp, 580.dp))) {
        fun chooseUploads() {
            val picker = FileDialog(window, "Upload files to remote folder", FileDialog.LOAD)
            picker.isMultipleMode = true
            try {
                picker.isVisible = true
                transfer(picker.files.filter { it.isFile }.map { FileTask(it.toPath(), joinPath(path, it.name), true) })
            } finally { picker.dispose() }
        }
        fun requestUpload() {
            if (busy || !connected) return
            if (session.canControlState.value && writable) { chooseUploads(); return }
            busy = true; error = null
            job = scope.launch {
                var allowed = false
                try { ensureAccess(true); allowed = writable }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not obtain upload access" }
                finally { busy = false }
                if (allowed) chooseUploads()
            }
        }
        fun download(entry: RemoteFileEntry) {
            val picker = FileDialog(window, "Download ${entry.name}", FileDialog.SAVE)
            picker.file = entry.name
            try {
                picker.isVisible = true
                val name = picker.file ?: return
                transfer(listOf(FileTask(Path.of(picker.directory, name), joinPath(path, entry.name), false)))
            } finally { picker.dispose() }
        }
        val acceptDrop by rememberUpdatedState<(List<java.io.File>) -> Unit>({ files ->
            if (writable && !busy && connected && root != null) {
                transfer(files.filter { it.isFile }.map { FileTask(it.toPath(), joinPath(path, it.name), true) })
            }
        })
        val dropAllowed by rememberUpdatedState(writable && !busy && connected && root != null)
        DisposableEffect(window) {
            val previous = window.dropTarget
            val target = DropTarget(window, object : DropTargetAdapter() {
                override fun dragEnter(event: DropTargetDragEvent) {
                    if (dropAllowed && event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) event.acceptDrag(DnDConstants.ACTION_COPY)
                    else event.rejectDrag()
                }
                override fun drop(event: DropTargetDropEvent) {
                    if (!dropAllowed || !event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { event.rejectDrop(); return }
                    try {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val files = (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<java.io.File>().orEmpty()
                        acceptDrop(files); event.dropComplete(true)
                    } catch (_: Exception) { event.dropComplete(false) }
                }
            })
            onDispose { target.isActive = false; window.dropTarget = previous }
        }
        LaunchedEffect(window) { window.minimumSize = java.awt.Dimension(580, 400) }
        LaunchedEffect(openRequest) {
            window.toFront()
            window.requestFocus()
            if (busy) return@LaunchedEffect
            busy = true; error = null
            job = currentCoroutineContext()[Job]
            try { ensureAccess(uploadInitially) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "File access failed" }
            finally { busy = false; job = null }
            if (uploadInitially && writable && root != null && error == null) chooseUploads()
        }
        LaunchedEffect(session.canControlState.value) {
            // Revocation must immediately disable uploads; the host independently rechecks each RPC.
            if (!session.canControlState.value) writable = false
            else {
                snapshotFlow { busy }.first { !it }
                if (root != null && !writable && connected) {
                    busy = true
                    try { ensureAccess(false) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Could not refresh file access" }
                    finally { busy = false }
                }
            }
        }
        LaunchedEffect(connected, session.statusState.value) {
            if (session.statusState.value is RemoteStatus.Closed) { onDismiss(); return@LaunchedEffect }
            if (!connected) { job?.cancel(); root = null; error = "Connection interrupted. Close and reopen Files after reconnecting." }
        }
        Surface(color = BackgroundColor, contentColor = TextPrimary, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(root ?: if (uploadInitially) "Waiting for control approval…" else "Opening remote files…", maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (writable) "Read/write access · Drop files here to upload" else "Read-only file access", color = TextMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { browse(path.substringBeforeLast('/', "")) }, enabled = !busy && path.isNotEmpty() && connected) { Text("Up") }
                    TextButton(onClick = { browse(path) }, enabled = root != null && !busy && connected) { Text("Refresh") }
                    Button(onClick = { requestUpload() }, enabled = !busy && connected) { Text("Upload Files…") }
                    Spacer(Modifier.weight(1f))
                    Checkbox(hidden, { hidden = it }); Text("Hidden files")
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { browse("") }, enabled = !busy && connected && root != null) { Text("Shared folder") }
                    val parts = path.split('/').filter { it.isNotEmpty() }
                    parts.forEachIndexed { index, part ->
                        TextButton(onClick = { browse(parts.take(index + 1).joinToString("/")) }, enabled = !busy && connected) { Text("/ $part") }
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(entries.filter { hidden || !it.name.startsWith('.') }, key = { it.name }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy && connected && entry.directory) { browse(joinPath(path, entry.name)) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (entry.directory) "▸" else "", Modifier.width(20.dp))
                            Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (entry.directory) "Folder" else formatSize(entry.size), color = TextMuted, modifier = Modifier.padding(horizontal = 12.dp))
                            if (!entry.directory) TextButton(onClick = { download(entry) }, enabled = !busy && connected) { Text("Download") }
                        }
                        HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
                    }
                    if (next != null) item { TextButton(onClick = { browse(path, true) }, enabled = !busy && connected) { Text("Load more") } }
                    if (entries.isEmpty() && root != null && !busy) item { Text("This folder is empty", color = TextMuted, modifier = Modifier.padding(16.dp)) }
                }
                if (busy) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = AccentColor)
                if (progress.isNotEmpty()) Text(progress, maxLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (retry.isNotEmpty()) TextButton(onClick = { transfer(retry) }, enabled = !busy && connected && root != null) { Text("Retry remaining") }
                    if (busy && job != null) TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
        replacement?.let { prompt ->
            GlassAlertDialog3(onDismissRequest = { prompt.answer.complete(false) },
                containerColor = SettingsTheme.SurfaceColor, minimumSurfaceOpacity = 0.94f,
                title = { Text("Replace existing file?") }, text = { Text("${prompt.name} already exists. Replace it?") },
                confirmButton = { TextButton(onClick = { prompt.answer.complete(true) }) { Text("Replace") } },
                dismissButton = { TextButton(onClick = { prompt.answer.complete(false) }) { Text("Skip") } })
        }
    }
}

private fun joinPath(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GiB".format(bytes / (1024.0 * 1024 * 1024))
}
