package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Policy + mint-path checks for [VoiceCallService]: control gating, enable/key validation,
 * unknown-tool recovery, and the OpenAI client-secret mint against a loopback stub server.
 */
class VoiceCallServiceTest {

    private class FakeExecutor : VoiceToolExecutor {
        override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL
        override fun contextSnapshot(defaultTabId: String?): String = "- \"zsh\" (tab_id t1) [active]"
        override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String =
            """{"ok":true,"tool":"$name","defaultTabId":"$defaultTabId"}"""
    }

    private fun service(
        enabled: Boolean = true,
        key: String? = "sk-test",
        broker: VoiceSessionBroker = VoiceSessionBroker(),
    ) = VoiceCallService(
        executor = FakeExecutor(),
        scope = CoroutineScope(Dispatchers.Default),
        broker = broker,
        settings = { TerminalSettings.DEFAULT.copy(voiceCallEnabled = enabled) },
        loadKey = { key },
        sessionName = { "test-session" },
    )

    @Test
    fun `status reflects enabled + key presence`() {
        assertEquals(ServerMessage.VoiceStatus(available = true), service().status())
        assertEquals("disabled", service(enabled = false).status().reason)
        assertEquals("no_key", service(key = null).status().reason)
    }

    /**
     * Boss Calling ships ON: storing a key is the opt-in, so a fresh install must not need a
     * settings visit as well. The key alone still gates availability — hence "no_key" here.
     */
    @Test
    fun `a stock install is enabled and merely keyless`() {
        assertTrue(TerminalSettings.DEFAULT.voiceCallEnabled)
        assertEquals(
            "no_key",
            VoiceCallService(
                executor = FakeExecutor(),
                scope = CoroutineScope(Dispatchers.Default),
                settings = { TerminalSettings.DEFAULT },
                loadKey = { null },
            ).status().reason,
        )
    }

    @Test
    fun `voiceStart without control is refused server-side`() {
        val replies = mutableListOf<ServerMessage>()
        service().handleStart(ClientMessage.VoiceStart(), canControl = false) { replies.add(it) }
        val err = replies.single()
        assertIs<ServerMessage.VoiceError>(err)
        assertEquals("not_controller", err.code)
    }

    @Test
    fun `voiceStart validates enabled and key before minting`() {
        val replies = mutableListOf<ServerMessage>()
        service(enabled = false).handleStart(ClientMessage.VoiceStart(), canControl = true) { replies.add(it) }
        assertEquals("disabled", (replies.single() as ServerMessage.VoiceError).code)

        replies.clear()
        service(key = null).handleStart(ClientMessage.VoiceStart(), canControl = true) { replies.add(it) }
        assertEquals("no_key", (replies.single() as ServerMessage.VoiceError).code)
    }

    @Test
    fun `unknown tools and write tools without control come back as tool errors`() {
        val svc = service()
        val token = svc.openCall()
        val replies = mutableListOf<ServerMessage>()

        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c1", "made_up_tool", "{}", callToken = token),
            canControl = true, defaultTabId = "t1",
        ) { replies.add(it) }
        val unknown = replies.single()
        assertIs<ServerMessage.VoiceToolResult>(unknown)
        assertTrue(unknown.isError)
        assertEquals("c1", unknown.callId)

        replies.clear()
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c2", "run_command", """{"script":"ls"}""", callToken = token),
            canControl = false, defaultTabId = "t1",
        ) { replies.add(it) }
        val gated = replies.single()
        assertIs<ServerMessage.VoiceToolResult>(gated)
        assertTrue(gated.isError)
    }

    @Test
    fun `a valid tool call executes and echoes the call id`() = runBlocking {
        val svc = service()
        val reply = CompletableDeferred<ServerMessage>()
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c3", "read_scrollback", """{"lines":50}""", callToken = svc.openCall()),
            canControl = true, defaultTabId = "t1",
        ) { reply.complete(it) }
        val r = withTimeout(5000) { reply.await() }
        assertIs<ServerMessage.VoiceToolResult>(r)
        assertEquals("c3", r.callId)
        assertTrue(!r.isError)
        assertTrue(r.resultJson.contains("\"defaultTabId\":\"t1\""))
    }

    /**
     * The share socket must not double as a standing tool RPC: without a token from a call this
     * host actually minted, a viewer's voiceToolCall goes nowhere near the session.
     */
    @Test
    fun `a tool call outside a live call is refused`() {
        val svc = service()
        val replies = mutableListOf<ServerMessage>()
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c1", "read_scrollback", "{}", callToken = null),
            canControl = true, defaultTabId = "t1",
        ) { replies.add(it) }
        assertTrue((replies.single() as ServerMessage.VoiceToolResult).isError, "no token → no tools")

        replies.clear()
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c2", "read_scrollback", "{}", callToken = "not-a-real-token"),
            canControl = true, defaultTabId = "t1",
        ) { replies.add(it) }
        assertTrue((replies.single() as ServerMessage.VoiceToolResult).isError, "forged token → no tools")
    }

    /**
     * Hanging up (or dropping the socket) must retire the token. Before this, VoiceEnd was a no-op
     * and a token stayed valid for its whole TTL — so a viewer that hung up, or was later demoted to
     * view-only, kept a working handle for the read tools.
     */
    @Test
    fun `a retired call's token stops working`() {
        val svc = service()
        val token = svc.openCall()
        val replies = mutableListOf<ServerMessage>()

        svc.closeCall(token)
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c1", "read_scrollback", "{}", callToken = token),
            canControl = true, defaultTabId = "t1",
        ) { replies.add(it) }
        val refused = replies.single()
        assertIs<ServerMessage.VoiceToolResult>(refused)
        assertTrue(refused.isError)
        assertTrue(refused.resultJson.contains("No active call"), refused.resultJson)
    }

    /** The master switch is a kill switch: flipping it off must stop an agent already mid-call. */
    @Test
    fun `turning Boss Calling off stops tools on a call already in progress`() {
        var enabled = true
        val svc = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT.copy(voiceCallEnabled = enabled) },
            loadKey = { "sk-test" },
        )
        val token = svc.openCall()
        enabled = false
        val replies = mutableListOf<ServerMessage>()
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c9", "read_scrollback", "{}", callToken = token),
            canControl = true, defaultTabId = "t1",
        ) { replies.add(it) }
        val refused = replies.single()
        assertIs<ServerMessage.VoiceToolResult>(refused)
        assertTrue(refused.isError)
        assertTrue(refused.resultJson.contains("turned off"), refused.resultJson)
    }

    /**
     * Each mint is a billed request against the host's own key, so a spamming controller (or a
     * reconnect loop) must be throttled before it reaches OpenAI.
     */
    @Test
    fun `back-to-back voiceStart is rate limited`() {
        var clock = 1_000L
        val svc = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            nowMs = { clock },
        )
        val replies = mutableListOf<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true) { replies.add(it) }
        // First one is admitted (it goes on to mint asynchronously — no error reply here).
        assertTrue(replies.none { it is ServerMessage.VoiceError }, "the first mint must be allowed")

        svc.handleStart(ClientMessage.VoiceStart(), canControl = true) { replies.add(it) }
        assertEquals("rate_limited", (replies.last() as ServerMessage.VoiceError).code)

        clock += 5_000 // past the minimum gap
        replies.clear()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true) { replies.add(it) }
        assertTrue(replies.none { it is ServerMessage.VoiceError }, "a later attempt is allowed again")
    }

    /**
     * The in-flight cap is claimed with getAndUpdate on the PREVIOUS count; a plain
     * get()-then-increment let concurrent socket messages both see room and exceed it.
     */
    @Test
    fun `the in-flight cap admits exactly four concurrent tool calls`() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : VoiceToolExecutor {
            override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL
            override fun contextSnapshot(defaultTabId: String?): String = ""
            override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
                gate.await() // park every call so the cap is what decides
                return "{}"
            }
        }
        val svc = VoiceCallService(
            executor = slow,
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
        )
        val token = svc.openCall()
        val replies = mutableListOf<ServerMessage>()
        repeat(6) { i ->
            svc.handleToolCall(
                ClientMessage.VoiceToolCall("c$i", "read_scrollback", "{}", callToken = token),
                canControl = true, defaultTabId = "t1",
            ) { synchronized(replies) { replies.add(it) } }
        }
        // The two beyond the cap are refused immediately; the admitted four are still parked.
        val refusals = synchronized(replies) {
            replies.filterIsInstance<ServerMessage.VoiceToolResult>().filter { it.isError }
        }
        assertEquals(2, refusals.size, "6 calls against a cap of 4 → 2 refusals")
        assertTrue(refusals.all { it.resultJson.contains("Too many tool calls") }, refusals.toString())
        gate.complete(Unit)
    }

    /** Tokens age out, so a call handle can't be replayed indefinitely. */
    @Test
    fun `a token past its TTL stops working`() {
        var clock = 1_000L
        val svc = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            nowMs = { clock },
        )
        val token = svc.openCall()
        clock += 7L * 60 * 60 * 1000 // past the 6h TTL
        val replies = mutableListOf<ServerMessage>()
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c1", "read_scrollback", "{}", callToken = token),
            canControl = true, defaultTabId = "t1",
        ) { replies.add(it) }
        assertTrue((replies.single() as ServerMessage.VoiceToolResult).isError, "an expired handle is refused")
    }

    @Test
    fun `mint success replies voiceSession with the ephemeral secret only`() = testApplication {
        routing {
            post("/v1/realtime/client_secrets") {
                call.respondText(
                    """{"value":"ek_stub_1","expires_at":1720000000}""",
                    ContentType.Application.Json,
                )
            }
        }
        val svc = service(broker = VoiceSessionBroker(baseUrl = "", httpOverride = client))
        val reply = CompletableDeferred<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(activeTabId = "t1"), canControl = true) { reply.complete(it) }
        val r = withTimeout(5000) { reply.await() }
        assertIs<ServerMessage.VoiceSession>(r)
        assertEquals("ek_stub_1", r.clientSecret)
        assertTrue(r.callToken.isNotBlank(), "a minted call must hand the viewer a token to echo")
        // The user's API key must never ride a protocol message.
        assertTrue(!ai.rever.bossterm.compose.share.ShareProtocol.encodeServer(r).contains("sk-test"))
    }

    /**
     * A 5xx is an account-side fault as often as a bad request (a broken OpenAI project 500s on
     * every endpoint), and its body can name the org/project. The host logs that detail; the
     * remote caller gets the status code and nothing else.
     */
    @Test
    fun `mint 5xx tells the viewer the code without OpenAI's error text`() = testApplication {
        routing {
            post("/v1/realtime/client_secrets") {
                call.respondText(
                    """{"error":{"message":"Project proj_secretname is not usable","type":"server_error"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
        val svc = service(broker = VoiceSessionBroker(baseUrl = "", httpOverride = client))
        val reply = CompletableDeferred<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true) { reply.complete(it) }
        val r = withTimeout(5000) { reply.await() }
        assertIs<ServerMessage.VoiceError>(r)
        assertEquals("mint_failed", r.code)
        assertEquals("OpenAI returned HTTP 500", r.message)
        assertTrue(r.message?.contains("proj_secretname") != true, "account detail must stay host-side")
    }

    @Test
    fun `mint 401 replies unauthorized`() = testApplication {
        routing {
            post("/v1/realtime/client_secrets") { call.respond(HttpStatusCode.Unauthorized) }
        }
        val svc = service(broker = VoiceSessionBroker(baseUrl = "", httpOverride = client))
        val reply = CompletableDeferred<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true) { reply.complete(it) }
        val r = withTimeout(5000) { reply.await() }
        assertIs<ServerMessage.VoiceError>(r)
        assertEquals("unauthorized", r.code)
    }
}
