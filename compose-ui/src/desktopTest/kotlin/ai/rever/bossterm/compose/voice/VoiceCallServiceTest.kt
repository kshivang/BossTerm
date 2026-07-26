package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /** Records what actually reached the executor, for "refused before it ran" assertions. */
    private class RecordingExecutor : VoiceToolExecutor {
        val ran: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL
        override fun contextSnapshot(defaultTabId: String?): String = ""
        override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
            ran.add(name)
            return "{}"
        }
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
        // Own budget AND own live-call map: both are shared process-wide in production (one key,
        // one ceiling), which would let one test's calls rate-limit or fill up the next.
        mintTimestamps = ArrayDeque(),
        sharedCalls = LinkedHashMap(),
    )

    private fun awaitReply(timeoutMs: Long = 10_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { predicate() }.getOrDefault(false)) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `status reflects enabled + key presence`() {
        assertEquals(ServerMessage.VoiceStatus(available = true), service().status(withReason = true, confidential = true))
        assertEquals("disabled", service(enabled = false).status(withReason = true, confidential = true).reason)
        assertEquals("no_key", service(key = null).status(withReason = true, confidential = true).reason)
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
                mintTimestamps = ArrayDeque(),
                sharedCalls = LinkedHashMap(),
            ).status(withReason = true, confidential = true).reason,
        )
    }

    /**
     * The remote surface has its own switch: turning it off must stop share viewers without touching
     * the in-app call (which reads voiceCallEnabled only).
     */
    @Test
    fun `the share surface can be turned off independently`() {
        val svc = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT.copy(voiceCallShareEnabled = false) },
            loadKey = { "sk-test" },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        assertEquals("disabled", svc.status(withReason = true, confidential = true).reason, "viewers are told it's unavailable")

        val replies = mutableListOf<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { replies.add(it) }
        assertEquals("disabled", (replies.single() as ServerMessage.VoiceError).code)

        // Both default on, so the stock install still serves viewers.
        assertTrue(TerminalSettings.DEFAULT.voiceCallShareEnabled)
    }

    /**
     * The `voiceSession` reply carries the ephemeral `ek_…`. On a plain `ws://` LAN share spoken by a
     * legacy plaintext client, that secret would cross the wire in the clear. The stock viewer hides
     * its Call button outside a secure context — but that is the CLIENT policing itself, and the host
     * will mint for anything that asks, so the refusal has to live here too.
     */
    @Test
    fun `an unencrypted connection is refused before anything is minted`() {
        val replies = mutableListOf<ServerMessage>()
        val svc = service()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = false) { replies.add(it) }

        assertEquals("insecure_transport", (replies.single() as ServerMessage.VoiceError).code)
        // Refused BEFORE the budget: an unusable connection must not be able to spend the host's
        // mints just by asking repeatedly.
        replies.clear()
        repeat(VoiceCallService.MAX_MINTS_PER_WINDOW + 2) {
            svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = false) { replies.add(it) }
        }
        assertTrue(
            replies.all { (it as ServerMessage.VoiceError).code == "insecure_transport" },
            "no attempt may reach the rate limiter: ${replies.map { (it as ServerMessage.VoiceError).code }}",
        )
    }

    /**
     * A call closed between the mint completing and the announcement must not report a start.
     *
     * isLiveCall() and announceCall() are two separate acquisitions of the same lock, and a
     * closeCall from the socket receive loop (voiceEnd, or the viewer dropping) can land between
     * them. The close correctly reports nothing — announced was still false — and the announcement
     * then fired anyway, incrementing a counter with nothing left to decrement it. RemoteVoiceCalls
     * would read "on a call" for the rest of the process, on the signal the host is meant to trust.
     */
    @Test
    fun `a call closed before its announcement never reports a start`() {
        val activity = mutableListOf<Boolean>()
        val svc = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            onCallActivity = { started -> synchronized(activity) { activity.add(started) } },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        val token = svc.openCall()
        assertNotNull(token)

        // The viewer hangs up while the mint is still in flight.
        svc.closeCall(token)
        assertEquals(emptyList(), synchronized(activity) { activity.toList() }, "nothing announced yet")

        // The mint completes and tries to announce a call that is gone.
        svc.announceCallForTest(token)
        assertEquals(
            emptyList(),
            synchronized(activity) { activity.toList() },
            "an unpaired start would pin the host's remote-call indicator forever",
        )
    }

    @Test
    fun `a live call still announces exactly once`() {
        val activity = mutableListOf<Boolean>()
        val svc = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            onCallActivity = { started -> synchronized(activity) { activity.add(started) } },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        val token = assertNotNull(svc.openCall())
        svc.announceCallForTest(token)
        assertEquals(listOf(true), synchronized(activity) { activity.toList() })
        svc.closeCall(token)
        assertEquals(listOf(true, false), synchronized(activity) { activity.toList() }, "and pairs")
    }

    /**
     * `withReason` is the per-viewer redaction: "disabled" vs "no_key" tells the reader whether the
     * host has an OpenAI key configured, which is host configuration a view-only guest has no
     * business knowing. Four sites implement this one policy and none of them had a test.
     */
    @Test
    fun `a view-only viewer is told availability but never why`() {
        val keyless = service(key = null)
        assertEquals("no_key", keyless.status(withReason = true, confidential = true).reason, "a controller can act on it")
        assertEquals(null, keyless.status(withReason = false, confidential = true).reason, "a guest learns only that it is off")
        assertFalse(keyless.status(withReason = false, confidential = true).available, "availability itself is not secret")

        val off = service(enabled = false)
        assertEquals("disabled", off.status(withReason = true, confidential = true).reason)
        assertEquals(null, off.status(withReason = false, confidential = true).reason)

        // And an available host has nothing to redact either way.
        assertEquals(null, service().status(withReason = true, confidential = true).reason)
        assertEquals(null, service().status(withReason = false, confidential = true).reason)
    }

    /**
     * No VoiceError that reaches a viewer may carry a JVM class name.
     *
     * The 5xx path is careful about this — OpenAI's prose and x-request-id are logged host-side and
     * the viewer gets a bare status code — but the catch-all returned
     * "Could not reach OpenAI (SSLHandshakeException)" verbatim through mint_failed. It is only a
     * class name, but it tells a stranger about the host's network and tells them nothing they can
     * act on, which is the asymmetry the careful branch was written to avoid.
     */
    @Test
    fun `no error message handed to a viewer names a JVM class`() {
        // A REFUSED connection, not a 404: a 404 takes the HTTP-status branch, which never carried a
        // class name, so routing to a stub would have passed this test without exercising anything.
        // Port 1 is not listenable; the client throws and the catch-all runs.
        val failing = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) { connectTimeoutMillis = 1_000; requestTimeoutMillis = 2_000 }
        }
        val svc = service(broker = VoiceSessionBroker(baseUrl = "http://127.0.0.1:1", httpOverride = failing))
        val replies = mutableListOf<ServerMessage>()
        svc.handleStart(
            ClientMessage.VoiceStart(),
            canControl = true,
            confidential = true,
        ) { synchronized(replies) { replies.add(it) } }

        assertTrue(awaitReply { synchronized(replies) { replies.any { it is ServerMessage.VoiceError } } })
        val messages = synchronized(replies) {
            replies.filterIsInstance<ServerMessage.VoiceError>().mapNotNull { it.message }
        }
        assertTrue(messages.isNotEmpty(), "the catch-all branch must actually have run")
        for (message in messages) {
            assertFalse(
                Regex("[A-Z][A-Za-z]*(Exception|Error|Throwable)").containsMatchIn(message),
                "a viewer must not be told the host's exception type: $message",
            )
        }
        failing.close()
    }

    /**
     * A non-E2E viewer is told so, and stays told.
     *
     * The gate at handleStart never wavered, but the STATUS the viewer sees is what decides whether
     * a Call button appears — and it is per-connection on two axes (the reason is for controllers
     * only; `available` depends on this connection's cipher). Both push paths used to compute one
     * status and broadcast it, so a plain-ws viewer refused at admit flipped to available on the
     * next settings change, with a button that could only ever come back insecure_transport.
     */
    @Test
    fun `an unencrypted connection is told why, whatever else is true of the host`() {
        val svc = service()
        val insecure = svc.status(withReason = true, confidential = false)
        assertFalse(insecure.available, "no Call button for a connection that cannot hold a secret")
        assertEquals("insecure_transport", insecure.reason)

        // A guest gets this one even with withReason = false. `withReason` redacts HOST
        // CONFIGURATION; insecure_transport describes the guest's OWN connection, which they already
        // know about and are the only party who can fix. Redacting it left them with no bar and no
        // explanation — the silence the reason field exists to prevent.
        val guest = svc.status(withReason = false, confidential = false)
        assertFalse(guest.available)
        assertEquals("insecure_transport", guest.reason)

        // And the same host, asked by an E2E connection, is available. Same service, same settings:
        // the difference is entirely per-connection, which is why one broadcast status was wrong.
        assertTrue(svc.status(withReason = true, confidential = true).available)
    }

    /**
     * The redaction line: host configuration is secret from a guest, their own connection is not.
     *
     * Getting this wrong in the safe direction still hurts — `not_controller` was unreachable for a
     * while precisely because withReason (canControl) and !callable (!canControl) are mutually
     * exclusive, so the branch added to explain that state could never fire.
     */
    @Test
    fun `only host-configuration reasons are redacted from a guest`() {
        // Host configuration: a guest is told unavailable, never why.
        assertEquals(null, service(key = null).status(withReason = false, confidential = true).reason)
        assertEquals(null, service(enabled = false).status(withReason = false, confidential = true).reason)

        // Their own connection and their own role: always told.
        assertEquals(
            "insecure_transport",
            service().status(withReason = false, confidential = false).reason,
        )
        assertEquals(
            "not_controller",
            service().status(withReason = false, confidential = true, callable = false).reason,
        )
    }

    /** A host problem outranks the transport: fix the key first, then the link. */
    @Test
    fun `a keyless host reports the key, not the transport`() {
        val keyless = service(key = null)
        assertEquals("no_key", keyless.status(withReason = true, confidential = false).reason)
    }

    /**
     * A call that ages out must tell BOTH sides.
     *
     * expireCalls only ran from openCall()/isLiveCall(), so a purely conversational call — no tool
     * calls, viewer still connected — sat past the ceiling until the next voiceStart on that share.
     * RemoteVoiceCalls.active is a security indicator, so it reading "on a call" for a call whose
     * tools are long dead is worse than the untidy map it resembles. And the caller was told nothing
     * at all: their tools just started answering "No active call".
     */
    @Test
    fun `an expired call notifies the host and the caller`() {
        var clock = 1_000L
        val activity = mutableListOf<Boolean>()
        val expired = mutableListOf<String>()
        val svc = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            nowMs = { clock },
            onCallActivity = { started -> synchronized(activity) { activity.add(started) } },
            onCallExpired = { token -> synchronized(expired) { expired.add(token) } },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        val token = assertNotNull(svc.openCall())
        svc.announceCallForTest(token)
        assertEquals(listOf(true), synchronized(activity) { activity.toList() })

        clock += VoiceCallService.MAX_CALL_DURATION_MS + 1

        // The sweeper runs on its own, without any further voice traffic on this share.
        assertTrue(
            awaitReply(VoiceCallService.EXPIRY_SWEEP_MS * 2) {
                synchronized(expired) { expired.isNotEmpty() }
            },
            "a conversational call that ages out must not need a tool call to be noticed",
        )
        assertEquals(listOf(token), synchronized(expired) { expired.toList() }, "the caller is told")
        assertTrue(
            awaitReply { synchronized(activity) { activity == listOf(true, false) } },
            "and the host's indicator pairs: ${synchronized(activity) { activity.toList() }}",
        )
    }

    @Test
    fun `voiceStart without control is refused server-side`() {
        val replies = mutableListOf<ServerMessage>()
        service().handleStart(ClientMessage.VoiceStart(), canControl = false, confidential = true) { replies.add(it) }
        val err = replies.single()
        assertIs<ServerMessage.VoiceError>(err)
        assertEquals("not_controller", err.code)
    }

    /**
     * A bail-out that never reaches OpenAI must hand its mint reservation back — the budget rations
     * requests to OpenAI, not attempts. The too_many_calls refusal always did; the two inside the
     * coroutine did not, so a share whose key had just been cleared burned one of twelve mints per
     * try and then rate-limited the host's own retry after they pasted a new one.
     *
     * MINT_MIN_GAP_MS is 3s and these run back-to-back, so an unrefunded reservation shows up
     * immediately as `rate_limited`.
     */
    @Test
    fun `a bail-out that never reaches OpenAI refunds its mint reservation`() {
        fun codesFrom(svc: VoiceCallService): List<String> {
            val replies = mutableListOf<ServerMessage>()
            repeat(2) {
                svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { r ->
                    synchronized(replies) { replies.add(r) }
                }
                Thread.sleep(250) // the bail-outs under test are inside scope.launch
            }
            return synchronized(replies) { replies.filterIsInstance<ServerMessage.VoiceError>().map { it.code } }
        }

        // The key vanished between the cheap keyPresent() check and the read inside the coroutine.
        val vanishingKey = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { null },
            keyPresent = { true },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        assertEquals(listOf("no_key", "no_key"), codesFrom(vanishingKey), "no_key must not spend a mint")

        // tools() throwing: the GUI executor builds its private MCP server on first use.
        val brokenTools = VoiceCallService(
            executor = object : VoiceToolExecutor {
                override fun tools(): List<VoiceToolDef> = error("MCP server unavailable")
                override fun contextSnapshot(defaultTabId: String?): String = ""
                override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?) = "{}"
            },
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        assertEquals(
            listOf("mint_failed", "mint_failed"),
            codesFrom(brokenTools),
            "an unavailable tool surface must not spend a mint either",
        )
    }

    /**
     * The live-call ceiling is a spend limit on ONE key, so the map is process-wide — but the sweep
     * that retires expired calls is owner-filtered (the "ended" notification belongs to the service
     * that opened the call). Counting the whole map against the cap therefore let another share's
     * EXPIRED calls deny capacity here until that share happened to sweep, and nothing sweeps
     * periodically: a share seeing no further voice traffic could hold a slot indefinitely.
     */
    @Test
    fun `another share's expired calls do not occupy the ceiling`() {
        val shared = LinkedHashMap<String, VoiceCallService.LiveCall>()
        var clock = 1_000L
        fun service() = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            nowMs = { clock },
            mintTimestamps = ArrayDeque(),
            sharedCalls = shared,
        )
        val other = service()
        repeat(VoiceCallService.MAX_LIVE_CALLS) { assertNotNull(other.openCall(), "share A fills the ceiling") }

        val mine = service()
        assertNull(mine.openCall(), "at capacity, a new call is refused rather than evicting a live one")

        // Everything share A opened is now past the duration ceiling, but share A has gone quiet, so
        // nothing has swept it.
        clock += VoiceCallService.MAX_CALL_DURATION_MS + 1
        assertEquals(
            VoiceCallService.MAX_LIVE_CALLS,
            shared.size,
            "the stale entries are still in the map — only their owner may announce them",
        )
        assertNotNull(mine.openCall(), "expired foreign calls must not deny capacity")
    }

    @Test
    fun `voiceStart validates enabled and key before minting`() {
        val replies = mutableListOf<ServerMessage>()
        service(enabled = false).handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { replies.add(it) }
        assertEquals("disabled", (replies.single() as ServerMessage.VoiceError).code)

        replies.clear()
        service(key = null).handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { replies.add(it) }
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
        val executor = RecordingExecutor()
        val svc = VoiceCallService(
            executor = executor,
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT.copy(voiceCallEnabled = enabled) },
            loadKey = { "sk-test" },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        val token = svc.openCall()
        enabled = false
        val replies = mutableListOf<ServerMessage>()
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c9", "read_scrollback", "{}", callToken = token),
            canControl = true, defaultTabId = "t1",
        ) { synchronized(replies) { replies.add(it) } }

        // The refusal arrives from the coroutine, not the caller's thread: the switch is re-read
        // beside executor.tools() rather than on the socket's receive loop, where on the daemon it
        // was a stat plus a possible read+parse of settings.json. Still before the tool runs.
        assertTrue(
            awaitReply { synchronized(replies) { replies.isNotEmpty() } },
            "a disabled host must still answer the call",
        )
        val refused = synchronized(replies) { replies.single() }
        assertIs<ServerMessage.VoiceToolResult>(refused)
        assertTrue(refused.isError)
        assertTrue(refused.resultJson.contains("turned off"), refused.resultJson)
        assertEquals(emptyList(), executor.ran, "and the tool itself never ran")
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
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        val replies = mutableListOf<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { replies.add(it) }
        // First one is admitted (it goes on to mint asynchronously — no error reply here).
        assertTrue(replies.none { it is ServerMessage.VoiceError }, "the first mint must be allowed")

        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { replies.add(it) }
        assertEquals("rate_limited", (replies.last() as ServerMessage.VoiceError).code)

        clock += 5_000 // past the minimum gap
        replies.clear()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { replies.add(it) }
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
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
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

    /**
     * At capacity the service refuses a NEW call instead of evicting an existing one: evicting left
     * the oldest caller's audio running while every tool answered "No active call".
     */
    @Test
    fun `at capacity a new call is refused rather than evicting a live one`() {
        val svc = service()
        val first = svc.openCall()
        val tokens = buildList { add(first); repeat(7) { add(svc.openCall()) } }
        assertTrue(tokens.all { it != null }, "the first 8 calls are admitted")
        assertEquals(null, svc.openCall(), "the 9th is refused")

        // The original call still works — it was not evicted.
        val replies = mutableListOf<ServerMessage>()
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c1", "read_scrollback", "{}", callToken = first),
            canControl = true, defaultTabId = "t1",
        ) { replies.add(it) }
        assertTrue(replies.isEmpty() || !(replies.first() as ServerMessage.VoiceToolResult).isError,
            "the first caller keeps its handle")
    }

    /**
     * An unbounded result would overflow the host outbox (dropping the viewer) or throw on an
     * oversized data-channel message AFTER the round was settled — leaving the agent narrating a
     * result the model never received.
     */
    @Test
    fun `an oversized tool result is truncated rather than sent whole`() = runBlocking {
        val huge = "x".repeat(400_000)
        val svc = VoiceCallService(
            executor = object : VoiceToolExecutor {
                override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL
                override fun contextSnapshot(defaultTabId: String?): String = ""
                override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?) =
                    """{"data":"$huge"}"""
            },
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        val reply = CompletableDeferred<ServerMessage>()
        svc.handleToolCall(
            ClientMessage.VoiceToolCall("c1", "read_scrollback", "{}", callToken = svc.openCall()),
            canControl = true, defaultTabId = "t1",
        ) { reply.complete(it) }
        val r = withTimeout(5000) { reply.await() } as ServerMessage.VoiceToolResult
        assertTrue(r.resultJson.length < 200_000, "must be clamped, was ${r.resultJson.length}")
        assertTrue(r.resultJson.contains("\"truncated\":true"), r.resultJson.take(120))
    }

    /**
     * A viewer that drops while the mint is in flight must get its reservation back. The token is
     * handed to the caller at RESERVATION time for exactly this reason: waiting for the
     * VoiceSession reply meant a mid-mint disconnect leaked the slot for the whole duration
     * ceiling, and eight of those wedge the share at too_many_calls.
     */
    @Test
    fun `a disconnect during the mint returns the slot and announces nothing`() = testApplication {
        val release = CompletableDeferred<Unit>()
        routing {
            post("/v1/realtime/client_secrets") {
                release.await() // hold the mint open, like a slow network
                call.respondText("""{"value":"ek_stub"}""", ContentType.Application.Json)
            }
        }
        val activity = mutableListOf<Boolean>()
        val svc = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            broker = VoiceSessionBroker(baseUrl = "", httpOverride = client),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            onCallActivity = { started -> synchronized(activity) { activity.add(started) } },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )

        var reserved: String? = null
        svc.handleStart(
            ClientMessage.VoiceStart(),
            canControl = true,
            confidential = true,
            onCallTokenChanged = { reserved = it },
        ) {}
        assertTrue(reserved != null, "the slot is reserved before the mint, not after it")
        val reservedToken = reserved

        // The viewer goes away; the server retires what it reserved for that connection.
        svc.closeCall(reservedToken)
        release.complete(Unit)
        Thread.sleep(200)

        // The reservation is back: a fresh call can be opened without hitting the cap...
        repeat(VoiceCallService.MAX_LIVE_CALLS) { assertTrue(svc.openCall() != null, "slot ${'$'}it should be free") }
        // ...and nothing was ever announced, since no call actually started.
        assertTrue(synchronized(activity) { activity.none { it } }, "saw ${'$'}activity")
    }

    /**
     * The live-call map is process-wide (one key, one spend ceiling) — but per-share teardown must
     * only touch its OWN calls. Clearing the whole map stopped other shares' calls dead: their audio
     * kept running while every tool answered "No active call".
     */
    @Test
    fun `stopping one share leaves another share's call alone`() {
        // One shared map, two services — production wiring, two open shares.
        val shared = LinkedHashMap<String, VoiceCallService.LiveCall>()
        fun serviceOn(map: LinkedHashMap<String, VoiceCallService.LiveCall>) = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            mintTimestamps = ArrayDeque(),
            sharedCalls = map,
        )
        val shareA = serviceOn(shared)
        val shareB = serviceOn(shared)
        val tokenA = shareA.openCall()!!
        val tokenB = shareB.openCall()!!

        shareA.closeCalls() // share A stops

        // Separate sinks per share: B's reply is produced asynchronously, so a shared list would
        // occasionally collect it after A's and make either assertion look wrong.
        val repliesB = java.util.concurrent.CopyOnWriteArrayList<ServerMessage>()
        shareB.handleToolCall(
            ClientMessage.VoiceToolCall("c1", "read_scrollback", "{}", callToken = tokenB),
            canControl = true, defaultTabId = "t1",
        ) { repliesB.add(it) }

        // A's own call is gone — refused synchronously, before any executor work.
        val repliesA = java.util.concurrent.CopyOnWriteArrayList<ServerMessage>()
        shareA.handleToolCall(
            ClientMessage.VoiceToolCall("c2", "read_scrollback", "{}", callToken = tokenA),
            canControl = true, defaultTabId = "t1",
        ) { repliesA.add(it) }
        assertTrue((repliesA.single() as ServerMessage.VoiceToolResult).isError, "A's own call is retired")

        // …while B's survived A stopping.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (repliesB.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(repliesB.isNotEmpty(), "share B's call should have answered")
        assertFalse(
            (repliesB.first() as ServerMessage.VoiceToolResult).isError,
            "share B's call must survive share A stopping: ${'$'}repliesB",
        )
    }

    /** A token issued for one share must not drive another's tools, even sharing one map. */
    @Test
    fun `a token from another share is refused`() {
        val shared = LinkedHashMap<String, VoiceCallService.LiveCall>()
        fun serviceOn() = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            mintTimestamps = ArrayDeque(),
            sharedCalls = shared,
        )
        val shareA = serviceOn()
        val shareB = serviceOn()
        val tokenA = shareA.openCall()!!

        val replies = mutableListOf<ServerMessage>()
        shareB.handleToolCall(
            ClientMessage.VoiceToolCall("c1", "read_scrollback", "{}", callToken = tokenA),
            canControl = true, defaultTabId = "t1",
        ) { replies.add(it) }
        assertTrue((replies.single() as ServerMessage.VoiceToolResult).isError, "wrong share's token")
    }

    /**
     * A mint that fails LATE must not clear a token a redial has since installed. Otherwise mint #2
     * succeeds, the viewer connects (audible and billed) and every tool call answers stale_call.
     */
    @Test
    fun `a late mint failure only clears its own token`() = testApplication {
        val hold = CompletableDeferred<Unit>()
        routing {
            post("/v1/realtime/client_secrets") {
                hold.await()
                call.respond(HttpStatusCode.Unauthorized) // mint #1 fails, late
            }
        }
        val svc = service(broker = VoiceSessionBroker(baseUrl = "", httpOverride = client))

        // Model the connection the way both servers do.
        var connectionToken: String? = null
        svc.handleStart(
            ClientMessage.VoiceStart(),
            canControl = true,
            confidential = true,
            onCallTokenChanged = { connectionToken = it },
            clearCallTokenIfCurrent = { stale -> if (connectionToken == stale) connectionToken = null },
        ) {}
        val tokenA = connectionToken
        assertTrue(tokenA != null)

        // A redial installs token B while mint #1 is still in flight.
        val tokenB = svc.openCall()
        connectionToken = tokenB

        hold.complete(Unit) // mint #1 now fails
        Thread.sleep(250)

        assertEquals(tokenB, connectionToken, "a late failure must not clear the newer token")
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
        svc.handleStart(ClientMessage.VoiceStart(activeTabId = "t1"), canControl = true, confidential = true) { reply.complete(it) }
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
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { reply.complete(it) }
        val r = withTimeout(5000) { reply.await() }
        assertIs<ServerMessage.VoiceError>(r)
        assertEquals("mint_failed", r.code)
        assertEquals("OpenAI returned HTTP 500", r.message)
        assertTrue(r.message?.contains("proj_secretname") != true, "account detail must stay host-side")
    }

    /**
     * A reservation that never became a call must not report one ending: onCallActivity(true) only
     * fires on a successful mint, so firing (false) on the failure path gave the host an "ended"
     * notification per bad-key attempt for a call that never started.
     */
    @Test
    fun `a failed mint reports no call activity at all`() = testApplication {
        routing {
            post("/v1/realtime/client_secrets") { call.respond(HttpStatusCode.Unauthorized) }
        }
        val activity = mutableListOf<Boolean>()
        val svc = VoiceCallService(
            executor = FakeExecutor(),
            scope = CoroutineScope(Dispatchers.Default),
            broker = VoiceSessionBroker(baseUrl = "", httpOverride = client),
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            onCallActivity = { started -> synchronized(activity) { activity.add(started) } },
            mintTimestamps = ArrayDeque(),
            sharedCalls = LinkedHashMap(),
        )
        val reply = CompletableDeferred<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { reply.complete(it) }
        withTimeout(5000) { reply.await() }
        Thread.sleep(150) // let any stray callback land
        assertTrue(synchronized(activity) { activity.isEmpty() }, "saw $activity")
    }

    /**
     * The mint BODY, asserted against the stub. End-to-end is still unverified, so this is the
     * closest available check that the request shape is one OpenAI accepts — a missing
     * `session.audio.output.format.rate` on the sibling WebSocket surface was only caught live.
     */
    @Test
    fun `the mint body carries the session shape OpenAI requires`() = testApplication {
        val captured = CompletableDeferred<JsonObject>()
        routing {
            post("/v1/realtime/client_secrets") {
                captured.complete(Json.parseToJsonElement(call.receiveText()).jsonObject)
                call.respondText("""{"value":"ek_stub"}""", ContentType.Application.Json)
            }
        }
        val svc = service(broker = VoiceSessionBroker(baseUrl = "", httpOverride = client))
        svc.handleStart(ClientMessage.VoiceStart(activeTabId = "t1"), canControl = true, confidential = true) {}
        val body = withTimeout(5000) { captured.await() }

        val expires = body["expires_after"]!!.jsonObject
        assertEquals("created_at", expires["anchor"]!!.jsonPrimitive.content)
        assertTrue(expires["seconds"]!!.jsonPrimitive.content.toInt() > 0)

        val session = body["session"]!!.jsonObject
        assertEquals("realtime", session["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("audio"),
            session["output_modalities"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("auto", session["tool_choice"]!!.jsonPrimitive.content)
        assertTrue(session["instructions"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(session["tools"]!!.jsonArray.isNotEmpty())
        // Every advertised tool must be a function with a name and an object schema.
        for (tool in session["tools"]!!.jsonArray) {
            val t = tool.jsonObject
            assertEquals("function", t["type"]!!.jsonPrimitive.content)
            assertTrue(t["name"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals("object", t["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }

        val audio = session["audio"]!!.jsonObject
        val input = audio["input"]!!.jsonObject
        assertEquals("semantic_vad", input["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("marin", audio["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        // NO pcm format on this path, deliberately: the viewer talks WebRTC, so the browser and
        // OpenAI negotiate the codec between themselves. Raw PCM formats belong to the in-app
        // WebSocket surface and are asserted in HostVoiceCallControllerTest — including the output
        // `rate` the docs' example omits and the API rejects the session without.
        assertEquals(null, input["format"], "WebRTC negotiates its own codec")
    }

    /**
     * The GA response puts the secret at the top level as `value`; the older beta shape nested it
     * under `client_secret.value`. The fallback exists for an account still routed to the old shape,
     * so it needs to actually work.
     */
    @Test
    fun `a beta-shaped mint response is still understood`() = testApplication {
        routing {
            post("/v1/realtime/client_secrets") {
                call.respondText(
                    """{"client_secret":{"value":"ek_beta_shape"}}""",
                    ContentType.Application.Json,
                )
            }
        }
        val svc = service(broker = VoiceSessionBroker(baseUrl = "", httpOverride = client))
        val reply = CompletableDeferred<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { reply.complete(it) }
        val r = withTimeout(5000) { reply.await() }
        assertIs<ServerMessage.VoiceSession>(r)
        assertEquals("ek_beta_shape", r.clientSecret)
    }

    /**
     * OpenAI's own 429 means the same thing to the caller as our budget refusal does — wait and
     * retry — so it must not arrive as a bare "OpenAI returned HTTP 429" they can't act on. The
     * viewer already renders `rate_limited` as "wait a moment and try again".
     */
    @Test
    fun `OpenAI's rate limit reaches the viewer as rate_limited`() = testApplication {
        routing {
            post("/v1/realtime/client_secrets") { call.respond(HttpStatusCode.TooManyRequests) }
        }
        val svc = service(broker = VoiceSessionBroker(baseUrl = "", httpOverride = client))
        val replies = mutableListOf<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { synchronized(replies) { replies.add(it) } }

        assertTrue(
            awaitReply { synchronized(replies) { replies.any { it is ServerMessage.VoiceError } } },
            "the refusal must reach the viewer",
        )
        val err = synchronized(replies) { replies.filterIsInstance<ServerMessage.VoiceError>().first() }
        assertEquals("rate_limited", err.code)
        assertNull(err.message, "and carries no OpenAI-side detail")
    }

    @Test
    fun `mint 401 replies unauthorized`() = testApplication {
        routing {
            post("/v1/realtime/client_secrets") { call.respond(HttpStatusCode.Unauthorized) }
        }
        val svc = service(broker = VoiceSessionBroker(baseUrl = "", httpOverride = client))
        val reply = CompletableDeferred<ServerMessage>()
        svc.handleStart(ClientMessage.VoiceStart(), canControl = true, confidential = true) { reply.complete(it) }
        val r = withTimeout(5000) { reply.await() }
        assertIs<ServerMessage.VoiceError>(r)
        assertEquals("unauthorized", r.code)
    }
}
