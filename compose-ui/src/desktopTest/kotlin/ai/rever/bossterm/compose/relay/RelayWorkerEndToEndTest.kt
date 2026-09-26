package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.*
import ai.rever.bossterm.compose.tabs.TabController
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** Paired-repository test: real terminal + admission + native clients + actual Worker/DO. */
class RelayWorkerEndToEndTest {
    @Test fun `real host fans out resumes hidden panes rotates departed keys and reconnects`() = runBlocking {
        val workerDir = System.getProperty("bossterm.relay.workerDir")
        assumeTrue("Set bossterm.relay.workerDir to run the paired Worker integration", workerDir != null)
        val fixture = ProcessBuilder("node", "test/native-fixture.mjs")
            .directory(File(workerDir!!)).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val lines = Channel<String>(Channel.UNLIMITED)
        val outputReader = launch(Dispatchers.IO) { fixture.inputStream.bufferedReader().useLines { rows -> rows.forEach { lines.trySend(it) } } }
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settingsDir = createTempDirectory("relay-real-host").toFile()
        val settings = SettingsManager(File(settingsDir, "settings.json").absolutePath)
        val controller = TabController(TerminalSettings.DEFAULT.copy(mcpEnabled = false), {})
        val state = TabbedTerminalState().also { it.tabController = controller }
        val inputs = CopyOnWriteArrayList<String>()
        val terminal = controller.createRemoteSession("relay fixture host", onUserInput = { inputs += it })
        val viewers = mutableListOf<Viewer>()
        var host: RelayHostSession? = null
        try {
            val endpoint = withTimeout(30_000) {
                var found: String? = null
                while (found == null) {
                    val line = lines.receive()
                    if (line.startsWith("RELAY_FIXTURE_URL=")) found = line.substringAfter('=')
                }
                found
            }
            settings.updateSettings(TerminalSettings.DEFAULT.copy(
                sessionSharingEnabled = true, sessionSharingBind = "loopback",
                sessionSharingPort = ServerSocket(0).use { it.localPort }, shareTailscaleMode = "off", mcpEnabled = false,
            ))
            SessionShareManager.settingsManagerOverrideForTest = settings
            controller.tabs.add(terminal)
            McpTerminalRegistry.register(state)
            SessionShareManager.start()
            terminal.dataStream.append("initial relay screen")
            terminal.dataStream.atQueuedCheckpoint { Unit }
            val share = assertNotNull(SessionShareManager.share(terminal.id))
            val link = assertNotNull(share.accountUrl)
            val uri = URI(link)
            val token = uri.rawQuery.split('&').first { it.startsWith("t=") }.substring(2)
            val secret = SessionCrypto.decodeSecretB64Url(uri.rawFragment.split('&').first { it.startsWith("k=") }.substring(2))
            val room = UUID.randomUUID().toString()
            suspend fun startHost(): RelayHostSession {
                val ready = CompletableDeferred<Unit>()
                val session = RelayHostSession(endpoint, room, "fixture-owner",
                    ticketProvider = { SessionCrypto.encodeSecretB64Url(SessionCrypto.newSessionSecret()) },
                    allowLoopbackForTests = true, readyChanged = { if (it) ready.complete(Unit) })
                testScope.launch { runCatching { session.run() }.onFailure { ready.completeExceptionally(it) } }
                withTimeout(10_000) { ready.await() }
                return session
            }
            fun connect(): Viewer = Viewer(endpoint, room, token, secret, terminal.id, testScope).also { viewers += it }
            host = startHost()
            val first = connect(); val second = connect()
            await("initial snapshots", viewers) { first.snapshots().any { "initial relay screen" in it.data } && second.snapshots().any { "initial relay screen" in it.data } }
            terminal.dataStream.append(" broadcast-one")
            await("initial output", viewers) { first.output().contains("broadcast-one") && second.output().contains("broadcast-one") }
            assertEquals(1, Regex("broadcast-one").findAll(first.output()).count())
            assertEquals(1, Regex("broadcast-one").findAll(second.output()).count())

            // A view-link guest uses the actual host approval flow, never account auto-admission.
            val guestUri = URI(share.url)
            val guest = Viewer(endpoint, room,
                guestUri.rawQuery.split('&').first { it.startsWith("t=") }.substring(2),
                SessionCrypto.decodeSecretB64Url(guestUri.rawFragment.split('&').first { it.startsWith("k=") }.substring(2)),
                terminal.id, testScope).also { viewers += it }
            await("guest requests host approval", viewers) { SessionShareManager.pendingRequests.value.isNotEmpty() }
            val approval = SessionShareManager.pendingRequests.value.single()
            assertFalse(approval.wantsControl)
            SessionShareManager.approveRequest(approval.id)
            await("approved read-only guest snapshot", viewers) { guest.snapshots().isNotEmpty() }
            guest.send(ClientMessage.Input(terminal.id, "blocked-readonly-input"))
            guest.send(ClientMessage.VoiceStart(terminal.id))
            // This private reply also proves the guest's earlier ordered input was processed.
            await("read-only voice denial stays private", viewers) {
                guest.messages.filterIsInstance<ServerMessage.VoiceError>().any { it.code == "not_controller" }
            }
            assertTrue(inputs.isEmpty(), "view-only input must never reach the terminal")
            assertTrue(first.messages.none { it is ServerMessage.VoiceError })
            assertTrue(second.messages.none { it is ServerMessage.VoiceError })
            first.send(ClientMessage.Input("not-in-share", "blocked-scope-input"))
            first.send(ClientMessage.Input(terminal.id, "allowed-account-input"))
            await("account input reaches only in-scope pane", viewers) { inputs.isNotEmpty() }
            assertEquals(listOf("allowed-account-input"), inputs.toList())
            guest.close()

            first.visibility.value = RelayViewDemand()
            delay(300) // Allow subscription acknowledgement to cross the real relay.
            val outputBeforeHidden = first.output()
            terminal.dataStream.append(" hidden-history")
            await("hidden output survives on second viewer", viewers) { second.output().contains("hidden-history") }
            delay(100)
            assertEquals(outputBeforeHidden, first.output())
            first.visibility.value = RelayViewDemand(setOf(terminal.id), terminal.id)
            await("hidden resume snapshot", viewers) { first.snapshots().any { "hidden-history" in it.data } }

            val snapshotsBeforeDeparture = second.snapshots().size
            first.close()
            await("departure rekeys survivor", viewers) { second.snapshots().size > snapshotsBeforeDeparture }
            terminal.dataStream.append(" after-rekey")
            await("post rekey output", viewers) { second.output().contains("after-rekey") }
            val third = connect()
            await("new viewer snapshot", viewers) { third.snapshots().any { "after-rekey" in it.data } }

            host.close()
            await("host departure closes viewers", viewers) { second.job.isCompleted && third.job.isCompleted }
            host = startHost()
            val reconnected = connect()
            await("host reconnect snapshot", viewers) { reconnected.snapshots().any { "after-rekey" in it.data } }
            terminal.dataStream.append(" after-host-reconnect")
            await("host reconnect output", viewers) { reconnected.output().contains("after-host-reconnect") }
        } finally {
            viewers.forEach { it.close() }
            host?.close()
            testScope.cancel()
            SessionShareManager.shutdown()
            SessionShareManager.settingsManagerOverrideForTest = null
            McpTerminalRegistry.unregister(state)
            if (terminal !in controller.tabs) terminal.dispose()
            controller.disposeAll()
            fixture.destroy()
            withContext(Dispatchers.IO) { if (!fixture.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) fixture.destroyForcibly() }
            outputReader.cancel()
            settingsDir.deleteRecursively()
        }
    }

    private suspend fun await(stage: String, viewers: List<Viewer>, predicate: () -> Boolean) {
        val success = withTimeoutOrNull(20_000) { while (!predicate()) delay(10); true } ?: false
        assertTrue(success, "$stage timed out: " + viewers.map { it.diagnostic() })
    }

    private class Viewer(endpoint: String, room: String, token: String, secret: ByteArray, pane: String, scope: CoroutineScope) {
        val messages = CopyOnWriteArrayList<ServerMessage>()
        val visibility = MutableStateFlow(RelayViewDemand(setOf(pane), pane))
        private val input = Channel<ClientMessage>(16)
        private val connection = RelayRemoteConnection(endpoint, room, token, secret,
            ClientMessage.Hello("integration viewer", UUID.randomUUID().toString()), { messages += it },
            visibility, MutableStateFlow(TerminalViewingPreferences()), allowLoopbackForTests = true)
        @Volatile private var failure: Throwable? = null
        val job = scope.launch { runCatching { connection.run(input) }.onFailure { failure = it } }
        fun diagnostic() = "done=${job.isCompleted}, failure=$failure, messages=${messages.map { it::class.simpleName }}, snapshots=${snapshots().map { it.data.take(150) }}"
        fun send(message: ClientMessage) { check(input.trySend(message).isSuccess) }
        fun snapshots() = messages.filterIsInstance<ServerMessage.PaneSnapshot>()
        fun output() = messages.filterIsInstance<ServerMessage.PaneOutput>().joinToString("") { it.data }
        fun close() { connection.close(); input.close(); job.cancel() }
    }
}
