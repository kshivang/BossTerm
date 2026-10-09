package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import ai.rever.bossterm.compose.share.ShareProtocol
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real protocol regressions, with no browser, GUI or external tunnel. */
class DaemonShareLifecycleTest {
    private val settings = TerminalSettings.DEFAULT.copy(
        sessionSharingEnabled = true,
        sessionSharingBind = "loopback",
        sessionSharingPort = 18_477,
        shareTailscaleMode = "off",
        sessionSharingApprovalScope = "all",
    )

    @Test
    fun `malformed handshake cannot become a viewer`() = withServer(settings.copy(sessionSharingApprovalScope = "off")) { server, client ->
        val token = assertNotNull(server.startShare(DaemonAttachProtocol.ShareScopeKind.ALL))
        val ws = client.webSocketSession(socketUrl(server, token))
        ws.send(Frame.Text("{}"))
        withTimeout(3_000) { ws.closeReason.await() }
        assertEquals(0, server.state.value.shares.single().viewers)
        assertTrue(server.state.value.pending.isEmpty())
    }

    @Test
    fun `disconnect while awaiting approval removes pending request promptly`() = withServer { server, client ->
        val token = assertNotNull(server.startShare(DaemonAttachProtocol.ShareScopeKind.ALL))
        val ws = client.webSocketSession(socketUrl(server, token))
        ws.hello("gone")
        assertTrue(ws.nextMessage() is ServerMessage.Pending)
        assertEquals("gone", server.state.value.pending.single().clientId)
        ws.close()
        awaitCondition { server.state.value.pending.isEmpty() }
    }

    @Test
    fun `foreign device grant replay does not revoke original grant and view link cannot upgrade`() = withServer { server, client ->
        val token = assertNotNull(server.startShare(DaemonAttachProtocol.ShareScopeKind.ALL))
        val controlWs = client.webSocketSession(socketUrl(server, token, control = true))
        controlWs.hello("owner")
        assertTrue(controlWs.nextMessage() is ServerMessage.Pending)
        server.approveViewer(token, "owner", control = true)
        val grant = controlWs.nextMessage() as ServerMessage.Grant
        assertTrue(grant.control)
        controlWs.close()

        val foreignWs = client.webSocketSession(socketUrl(server, token))
        foreignWs.hello("other-device", grant.key)
        assertTrue(foreignWs.nextMessage() is ServerMessage.Pending)
        server.denyViewer(token, "other-device")
        assertTrue(foreignWs.nextMessage() is ServerMessage.Denied)
        foreignWs.close()

        val ownerWs = client.webSocketSession(socketUrl(server, token))
        ownerWs.hello("owner", grant.key)
        val resumed = ownerWs.nextMessage()
        assertTrue(resumed is ServerMessage.Grant, "original key should still resume: $resumed")
        assertEquals(grant.key, resumed.key)
        assertFalse(resumed.control, "a view URL must cap a resumed grant to view-only")
        ownerWs.close()
    }

    @Test
    fun `all scope normalizes irrelevant ids and stop prevents new shares`() = withServer { server, _ ->
        val token = assertNotNull(server.startShare(DaemonAttachProtocol.ShareScopeKind.ALL, sessionId = "ignored"))
        assertEquals(token, server.startShare(DaemonAttachProtocol.ShareScopeKind.ALL))
        assertNull(server.startShare("unknown"))
        assertNull(server.startShare(DaemonAttachProtocol.ShareScopeKind.SESSION, "missing"))
        assertNull(server.startShare(DaemonAttachProtocol.ShareScopeKind.GROUP, groupId = "missing"))
        server.stop()
        assertNull(server.startShare(DaemonAttachProtocol.ShareScopeKind.ALL))
        assertTrue(server.state.value.shares.isEmpty())
    }

    @Test
    fun `group scope closes only after its last pane closes`() {
        if (ShellCustomizationUtils.isWindows()) return
        withSettingsDir {
            val host = SessionHost(TerminalSettings.DEFAULT)
            val server = DaemonShareServer(host, { settings })
            try {
                val (sessionId, groupId) = host.openWindow(command = "/bin/cat")
                val sibling = assertNotNull(host.splitPane(sessionId, SplitOrientation.VERTICAL))
                val token = assertNotNull(server.startShare(DaemonAttachProtocol.ShareScopeKind.GROUP, groupId = groupId))
                assertEquals(groupId, server.state.value.shares.single().groupId)
                host.closeSession(sessionId)
                assertTrue(server.state.value.shares.any { it.token == token }, "surviving sibling keeps the group share")
                host.closeSession(sibling)
                runBlocking { awaitCondition { server.state.value.shares.isEmpty() } }
            } finally {
                server.stop()
                host.shutdownAll()
            }
        }
    }


    @Test
    fun `share sends old grid bytes before resize and new grid bytes after it`() = withSettingsDir {
        val handle = StreamingHandle()
        val services = object : PlatformServices by getPlatformServices() {
            override fun getProcessService() = object : PlatformServices.ProcessService {
                override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig) = handle
            }
        }
        val host = SessionHost(TerminalSettings.DEFAULT, platformServices = services)
        val server = DaemonShareServer(host, { settings.copy(sessionSharingApprovalScope = "off") })
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            runBlocking {
                withTimeout(10_000) {
                    val id = host.openSession(command = "fake-terminal")
                    val token = assertNotNull(server.startShare(DaemonAttachProtocol.ShareScopeKind.ALL))
                    val ws = client.webSocketSession(socketUrl(server, token))
                    ws.hello("resize-order")
                    while (true) {
                        val message = ws.nextMessage()
                        if (message is ServerMessage.PaneSnapshot && message.paneId == id) break
                    }
                    val core = assertNotNull(host.get(id))
                    val beforeParsed = CountDownLatch(1)
                    val afterParsed = CountDownLatch(1)
                    // Registered after the share baseline: its output tap has queued the chunk
                    // by the time these witnesses fire on the parser thread.
                    core.addRawOutputListener { chunk ->
                        if (chunk.contains("BEFORE_GRID")) beforeParsed.countDown()
                        if (chunk.contains("AFTER_GRID")) afterParsed.countDown()
                    }
                    handle.chunks.send("BEFORE_GRID")
                    assertTrue(beforeParsed.await(2, TimeUnit.SECONDS))
                    core.resize(43, 14)
                    handle.chunks.send("AFTER_GRID")
                    assertTrue(afterParsed.await(2, TimeUnit.SECONDS))
                    val events = mutableListOf<String>()
                    while ("after" !in events) {
                        when (val message = ws.nextMessage()) {
                            is ServerMessage.PaneOutput -> {
                                if (message.data.contains("BEFORE_GRID")) events.add("before")
                                if (message.data.contains("AFTER_GRID")) events.add("after")
                            }
                            is ServerMessage.PaneResize -> if (message.cols == 43 && message.rows == 14) events.add("resize")
                            else -> Unit
                        }
                    }
                    assertEquals(listOf("before", "resize", "after"), events)
                    ws.close()
                }
            }
        } finally {
            client.close()
            server.stop()
            host.close()
        }
    }

    private class StreamingHandle : PlatformServices.ProcessService.ProcessHandle {
        val chunks = Channel<String>(Channel.UNLIMITED)
        private val exit = CompletableDeferred<Int>()
        @Volatile private var alive = true
        override suspend fun read(): String? = chunks.receiveCatching().getOrNull()
        override suspend fun write(data: String) {}
        override suspend fun writeBytes(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun waitFor(): Int = exit.await()
        override suspend fun kill() { alive = false; chunks.close(); exit.complete(0) }
        override fun isAlive(): Boolean = alive
        override fun getExitCode(): Int? = if (alive) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory(): String? = null
    }

    private suspend fun DefaultClientWebSocketSession.hello(clientId: String, key: String? = null) =
        send(Frame.Text(ShareProtocol.encodeClient(ClientMessage.Hello(clientId = clientId, key = key))))

    private suspend fun DefaultClientWebSocketSession.nextMessage(): ServerMessage = withTimeout(5_000) {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Text) return@withTimeout ShareProtocol.decodeServer(frame.readText())
        }
        error("unreachable")
    }

    private fun socketUrl(server: DaemonShareServer, token: String, control: Boolean = false): String {
        val share = server.state.value.shares.first { it.token == token }
        val uri = URI(if (control) share.controlUrl else share.url)
        val linkToken = uri.query.substringAfter("t=")
        return "ws://127.0.0.1:${uri.port}/ws/$linkToken"
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) = withTimeout(4_000) {
        while (!predicate()) delay(20)
    }

    private fun withServer(
        terminalSettings: TerminalSettings = settings,
        block: suspend (DaemonShareServer, HttpClient) -> Unit,
    ) = withSettingsDir {
        val host = SessionHost(TerminalSettings.DEFAULT)
        val server = DaemonShareServer(host, { terminalSettings })
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            runBlocking { withTimeout(15_000) { block(server, client) } }
        } finally {
            client.close()
            server.stop()
            host.shutdownAll()
        }
    }

    private fun <T> withSettingsDir(block: () -> T): T {
        val previous = System.getProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        val directory = java.nio.file.Files.createTempDirectory("bossterm-share-regression").toFile()
        System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, directory.absolutePath)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
            else System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, previous)
            directory.deleteRecursively()
        }
    }
}
