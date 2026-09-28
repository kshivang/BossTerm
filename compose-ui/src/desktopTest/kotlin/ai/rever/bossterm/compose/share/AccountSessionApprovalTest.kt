package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.tabs.TabController
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** Real loopback WebSocket handshakes through the same host admission path used by relay. */
class AccountSessionApprovalTest {
    @Test fun `older settings default to auto approval and opt out survives serialization`() {
        val json = kotlinx.serialization.json.Json
        val old = json.decodeFromString(TerminalSettings.serializer(), "{}")
        assertTrue(old.autoApproveAccountSessions)
        val saved = json.encodeToString(TerminalSettings.serializer(), old.copy(autoApproveAccountSessions = false))
        assertFalse(json.decodeFromString(TerminalSettings.serializer(), saved).autoApproveAccountSessions)
    }

    @Test fun `encrypted account link bypasses general approval by default`() = runBlocking {
        withHost(TerminalSettings.DEFAULT.copy(sessionSharingApprovalScope = "all")) { share, client ->
            client.connect(assertNotNull(share.accountUrl)).useViewer { viewer ->
                viewer.awaitLayout()
                assertTrue(SessionShareManager.pendingRequests.value.isEmpty())
            }
        }
    }

    @Test fun `account opt out requires approval even when general approval is off`() = runBlocking {
        withHost(TerminalSettings.DEFAULT.copy(
            sessionSharingApprovalScope = "off", autoApproveAccountSessions = false,
        )) { share, client ->
            val accountUrl = assertNotNull(share.accountUrl)
            var grant: String? = null
            client.connect(accountUrl).useViewer { viewer ->
                assertIs<ServerMessage.Pending>(viewer.receive())
                val pending = SessionShareManager.pendingRequests.value.single()
                assertTrue(pending.wantsControl)
                SessionShareManager.approveRequest(pending.id)
                grant = assertIs<ServerMessage.Grant>(viewer.receive()).key
                viewer.awaitLayout()
            }
            // Previously approved devices retain their existing 24-hour grant semantics.
            client.connect(accountUrl, grant = assertNotNull(grant)).useViewer { viewer ->
                assertIs<ServerMessage.Grant>(viewer.receive())
                viewer.awaitLayout()
                assertTrue(SessionShareManager.pendingRequests.value.isEmpty())
            }
        }
    }

    @Test fun `encrypted guest link still needs general device approval`() = runBlocking {
        withHost(TerminalSettings.DEFAULT.copy(sessionSharingApprovalScope = "all")) { share, client ->
            client.connect(share.url).useViewer { viewer ->
                assertIs<ServerMessage.Pending>(viewer.receive())
                SessionShareManager.denyRequest(SessionShareManager.pendingRequests.value.single().id)
                assertIs<ServerMessage.Denied>(viewer.receive())
            }
        }
    }

    @Test fun `account opt out does not change ordinary guest policy`() = runBlocking {
        withHost(TerminalSettings.DEFAULT.copy(
            sessionSharingApprovalScope = "off", autoApproveAccountSessions = false,
        )) { share, client ->
            client.connect(share.url).useViewer { viewer ->
                viewer.awaitLayout()
                assertTrue(SessionShareManager.pendingRequests.value.isEmpty())
            }
        }
    }

    @Test fun `account token without encrypted proof does not bypass approval`() = runBlocking {
        withHost(TerminalSettings.DEFAULT.copy(
            sessionSharingApprovalScope = "off", autoApproveAccountSessions = false,
        )) { share, client ->
            client.connect(assertNotNull(share.accountUrl), encrypted = false).useViewer { viewer ->
                assertIs<ServerMessage.Pending>(viewer.receive())
                SessionShareManager.denyRequest(SessionShareManager.pendingRequests.value.single().id)
                assertIs<ServerMessage.Denied>(viewer.receive())
            }
        }
    }

    @Test fun `guest secret cannot authenticate the account token`() = runBlocking {
        withHost(TerminalSettings.DEFAULT.copy(sessionSharingApprovalScope = "off")) { share, client ->
            client.connect(assertNotNull(share.accountUrl), secretOverride = secretOf(share.url)).useViewer { viewer ->
                val reason = withTimeout(5_000) { viewer.socket.closeReason.await() }
                assertNotNull(reason)
                assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason.code)
                assertTrue(SessionShareManager.pendingRequests.value.isEmpty())
            }
        }
    }

    private suspend fun withHost(
        configuration: TerminalSettings,
        block: suspend (SessionShareManager.ShareInfo, HttpClient) -> Unit,
    ) {
        val dir = createTempDirectory("account-session-approval").toFile()
        val settings = SettingsManager(File(dir, "settings.json").absolutePath)
        val controller = TabController(TerminalSettings.DEFAULT.copy(mcpEnabled = false), {})
        val state = TabbedTerminalState().also { it.tabController = controller }
        val terminal = controller.createRemoteSession("approval fixture", onUserInput = {})
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            settings.updateSettings(configuration.copy(
                // This fixture owns its local transport; never auto-connect the production relay.
                terminalRelayEnabled = false, sessionSharingEnabled = true, sessionSharingBind = "loopback",
                sessionSharingPort = ServerSocket(0).use { it.localPort },
                sessionSharingPublicUrl = "", shareTailscaleMode = "off", mcpEnabled = false,
            ))
            SessionShareManager.settingsManagerOverrideForTest = settings
            controller.tabs.add(terminal)
            McpTerminalRegistry.register(state)
            SessionShareManager.start()
            block(assertNotNull(SessionShareManager.share(terminal.id)), client)
        } finally {
            SessionShareManager.pendingRequests.value.forEach { SessionShareManager.denyRequest(it.id) }
            client.close()
            SessionShareManager.shutdown()
            SessionShareManager.settingsManagerOverrideForTest = null
            McpTerminalRegistry.unregister(state)
            if (terminal !in controller.tabs) terminal.dispose()
            controller.disposeAll()
            dir.deleteRecursively()
        }
    }

    private suspend fun HttpClient.connect(
        link: String, encrypted: Boolean = true, secretOverride: ByteArray? = null, grant: String? = null,
    ): Viewer {
        val uri = URI(link)
        val token = uri.rawQuery.split('&').first { it.startsWith("t=") }.substring(2)
        val socket = webSocketSession("ws://${uri.rawAuthority}/ws/$token")
        var inbound: SessionCrypto.FrameCipher? = null
        var outbound: SessionCrypto.FrameCipher? = null
        if (encrypted) {
            val salt = SessionCrypto.randomSalt()
            socket.send(Frame.Text(ShareProtocol.encodeKex(Kex(v = 1, salt = SessionCrypto.encodeSecretB64Url(salt)))))
            val reply = assertNotNull(ShareProtocol.decodeKex((withTimeout(5_000) { socket.incoming.receive() } as Frame.Text).readText()))
            val keys = SessionCrypto.deriveKeys(secretOverride ?: secretOf(link), salt, SessionCrypto.decodeSecretB64Url(reply.salt))
            // The wrong-secret test intentionally sends an invalid encrypted Hello, exercising
            // host verification instead of trusting the client's confirmation check alone.
            if (secretOverride == null) assertTrue(SessionCrypto.confirmMatches(keys.confirm, reply.confirm))
            inbound = SessionCrypto.FrameCipher(keys.kS2c, SessionCrypto.DIR_S2C)
            outbound = SessionCrypto.FrameCipher(keys.kC2s, SessionCrypto.DIR_C2S)
        }
        val hello = ShareProtocol.encodeClient(ClientMessage.Hello("approval test", UUID.randomUUID().toString(), key = grant))
        socket.send(outbound?.let { Frame.Binary(true, it.encrypt(hello)) } ?: Frame.Text(hello))
        return Viewer(socket, inbound)
    }

    private fun secretOf(link: String): ByteArray = SessionCrypto.decodeSecretB64Url(
        URI(link).rawFragment.split('&').first { it.startsWith("k=") }.substring(2),
    )

    private class Viewer(val socket: DefaultClientWebSocketSession, val cipher: SessionCrypto.FrameCipher?) {
        suspend fun receive(): ServerMessage = withTimeout(5_000) {
            val frame = socket.incoming.receive()
            val text = if (cipher != null) cipher.decrypt((frame as Frame.Binary).data) else (frame as Frame.Text).readText()
            ShareProtocol.decodeServer(text)
        }
        suspend fun awaitLayout() = withTimeout(5_000) {
            while (true) {
                when (receive()) {
                    is ServerMessage.Layout -> return@withTimeout
                    is ServerMessage.Pending, is ServerMessage.Denied -> fail("Expected admission, not approval or denial")
                    else -> Unit
                }
            }
        }
        suspend fun useViewer(block: suspend (Viewer) -> Unit) {
            try { block(this) } finally { socket.close() }
        }
    }
}
