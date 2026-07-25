package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The in-app call is a protocol state machine, so it is driven here with a fake socket and fake
 * audio: no mixer (CI has none), no network. Covers the session handshake, the tool round-trip and
 * its one-response.create-per-round rule, barge-in, mute, and teardown.
 */
class HostVoiceCallControllerTest {

    private class FakeTransport : RealtimeTransport {
        val sent = ConcurrentLinkedQueue<String>()
        var events: ((String) -> Unit)? = null
        var closed = false
        var connectedModel: String? = null
        var apiKey: String? = null

        override suspend fun connect(
            model: String,
            apiKey: String,
            events: (String) -> Unit,
            onClosed: (String?) -> Unit,
        ) {
            connectedModel = model
            this.apiKey = apiKey
            this.events = events
        }

        override fun send(json: String) { sent.add(json) }
        override fun close() { closed = true }

        fun deliver(json: String) = events?.invoke(json)
        fun sentOfType(type: String): List<JsonObject> = sent.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == type }
    }

    private class FakeAudio : VoiceAudioIo {
        var capturing = false
        var stopped = false
        var flushes = 0
        val played = mutableListOf<ByteArray>()
        private var onChunk: ((ByteArray) -> Unit)? = null

        override fun startCapture(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit) {
            capturing = true
            this.onChunk = onChunk
            onLevel(0.5f)
        }
        override fun play(pcm: ByteArray) { played.add(pcm) }
        override fun flushPlayback() { flushes += 1 }
        override fun stop() { stopped = true; capturing = false }

        /** Pretend the mic produced a frame. */
        fun emit(bytes: ByteArray) = onChunk?.invoke(bytes)
    }

    private class FakeExecutor : VoiceToolExecutor {
        val calls = mutableListOf<String>()
        override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL.filter { !it.guiOnly }
        override fun contextSnapshot(defaultTabId: String?): String = "- \"zsh\" (tab_id t1) [active]"
        override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
            calls.add(name)
            return """{"ok":true}"""
        }
    }

    private fun controller(
        transport: FakeTransport,
        audio: FakeAudio,
        executor: VoiceToolExecutor = FakeExecutor(),
        key: String? = "sk-test",
        enabled: Boolean = true,
    ) = HostVoiceCallController(
        scope = CoroutineScope(Dispatchers.Default),
        executor = executor,
        transport = transport,
        audio = audio,
        settings = { TerminalSettings.DEFAULT.copy(voiceCallEnabled = enabled) },
        loadKey = { key },
    )

    private fun await(timeoutMs: Long = 4000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { predicate() }.getOrDefault(false)) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `starting configures the session with the host's own key and PCM audio`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()

        assertTrue(await { transport.sentOfType("session.update").isNotEmpty() }, "must configure the session")
        assertEquals("sk-test", transport.apiKey, "the host connects with its OWN key, not an ephemeral secret")
        val session = transport.sentOfType("session.update").first()["session"]!!.jsonObject
        val audioCfg = session["audio"]!!.jsonObject
        assertEquals(
            "audio/pcm",
            audioCfg["input"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            24_000,
            audioCfg["input"]!!.jsonObject["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content.toInt(),
        )
        assertEquals(
            "semantic_vad",
            audioCfg["input"]!!.jsonObject["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        // Both directions need an explicit rate: the guide's example omits it on the output side
        // and the session is rejected with
        // "Missing required parameter: 'session.audio.output.format.rate'".
        val outFormat = audioCfg["output"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("audio/pcm", outFormat["type"]!!.jsonPrimitive.content)
        assertEquals(24_000, outFormat["rate"]!!.jsonPrimitive.content.toInt(), "output rate is required")
        assertEquals("marin", audioCfg["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertTrue(session["tools"]!!.toString().contains("read_scrollback"), "tools must be advertised")
        assertTrue(await { c.state.value.phase == HostCallPhase.Live }, "the call goes live once audio starts")
        assertTrue(audio.capturing)
        c.end()
    }

    @Test
    fun `mic frames are streamed as base64 appends, and muting stops them`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()
        assertTrue(await { audio.capturing })

        audio.emit(byteArrayOf(1, 2, 3, 4))
        assertTrue(await { transport.sentOfType("input_audio_buffer.append").isNotEmpty() })
        val appended = transport.sentOfType("input_audio_buffer.append").first()["audio"]!!.jsonPrimitive.content
        assertTrue(Base64.getDecoder().decode(appended).contentEquals(byteArrayOf(1, 2, 3, 4)))

        c.toggleMute()
        assertTrue(c.state.value.muted)
        val before = transport.sentOfType("input_audio_buffer.append").size
        audio.emit(byteArrayOf(9, 9))
        Thread.sleep(100)
        assertEquals(before, transport.sentOfType("input_audio_buffer.append").size, "muted mic must not be sent")
        c.end()
    }

    @Test
    fun `agent audio plays, and the user speaking cuts it off`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(5, 6, 7, 8))
        transport.deliver("""{"type":"response.output_audio.delta","delta":"$pcm"}""")
        assertTrue(await { audio.played.isNotEmpty() }, "output audio must reach the speaker")
        assertTrue(c.state.value.speaking)

        // Barge-in: don't let the agent keep talking over the user.
        transport.deliver("""{"type":"input_audio_buffer.speech_started"}""")
        assertTrue(await { audio.flushes == 1 }, "the queued reply must be dropped")
        assertFalse(c.state.value.speaking)
        c.end()
    }

    @Test
    fun `a tool round runs the tool and asks for exactly one follow-up turn`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val executor = FakeExecutor()
        val c = controller(transport, audio, executor)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        // Two calls in one response, the shape that made the browser path emit two response.creates.
        transport.deliver(
            """{"type":"response.function_call_arguments.done","call_id":"c1","name":"read_scrollback","arguments":"{}"}"""
        )
        transport.deliver(
            """{"type":"response.function_call_arguments.done","call_id":"c2","name":"read_scrollback","arguments":"{}"}"""
        )
        assertTrue(await { executor.calls.size == 2 }, "both tools must run")
        assertTrue(await { transport.sentOfType("conversation.item.create").size == 2 }, "both results returned")
        assertEquals(
            0,
            transport.sentOfType("response.create").size,
            "asking mid-response earns conversation_already_has_active_response",
        )

        transport.deliver("""{"type":"response.done","response":{"output":[]}}""")
        assertTrue(await { transport.sentOfType("response.create").size == 1 })
        Thread.sleep(150)
        assertEquals(1, transport.sentOfType("response.create").size, "exactly one per round")
        assertTrue(await { !c.state.value.working }, "the bar stops saying it's working")
        c.end()
    }

    @Test
    fun `a duplicated call id is executed once`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val executor = FakeExecutor()
        val c = controller(transport, audio, executor)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        val event =
            """{"type":"response.function_call_arguments.done","call_id":"dup","name":"read_scrollback","arguments":"{}"}"""
        transport.deliver(event)
        // The same call also arrives inside response.done's output[] — dedupe by call_id.
        transport.deliver(
            """{"type":"response.done","response":{"output":[{"type":"function_call","call_id":"dup","name":"read_scrollback","arguments":"{}"}]}}"""
        )
        assertTrue(await { executor.calls.isNotEmpty() })
        Thread.sleep(200)
        assertEquals(1, executor.calls.size, "one tool call, however many times it is announced")
        c.end()
    }

    @Test
    fun `no key and a disabled switch fail before any socket is opened`() {
        val keyless = FakeTransport()
        val noKey = controller(keyless, FakeAudio(), key = null)
        noKey.start()
        assertEquals(HostCallPhase.Error, noKey.state.value.phase)
        assertTrue(noKey.state.value.error?.contains("API key") == true, noKey.state.value.error ?: "")
        assertEquals(null, keyless.connectedModel, "no key → never connect")

        val off = FakeTransport()
        val c = controller(off, FakeAudio(), enabled = false)
        c.start()
        assertEquals(HostCallPhase.Error, c.state.value.phase)
        assertEquals(null, off.connectedModel, "disabled → never connect")
    }

    /**
     * The bar offers End call during "Connecting…", and the connect coroutine used to be unstored —
     * so it resumed after teardown, opened the mic, and flipped the state to Live with nothing left
     * holding a reference to stop it. The mic then stayed hot for the whole process.
     */
    @Test
    fun `ending while still connecting never leaves the mic open`() {
        val slowConnect = object : RealtimeTransport {
            val gate = CompletableDeferred<Unit>()
            var closed = false
            override suspend fun connect(
                model: String,
                apiKey: String,
                events: (String) -> Unit,
                onClosed: (String?) -> Unit,
            ) {
                gate.await() // park mid-connect, like a slow network
            }
            override fun send(json: String) {}
            override fun close() { closed = true }
        }
        val audio = FakeAudio()
        val c = HostVoiceCallController(
            scope = CoroutineScope(Dispatchers.Default),
            executor = FakeExecutor(),
            transport = slowConnect,
            audio = audio,
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
        )
        c.start()
        assertEquals(HostCallPhase.Connecting, c.state.value.phase)

        c.end() // hang up before the socket is even up
        slowConnect.gate.complete(Unit) // now let connect() finish
        Thread.sleep(250)

        assertEquals(HostCallPhase.Idle, c.state.value.phase, "a cancelled connect must not go Live")
        assertFalse(audio.capturing, "the mic must never open for an abandoned call")
        assertTrue(slowConnect.closed)
    }

    @Test
    fun `ending releases the mic and the socket`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })
        c.end()
        assertTrue(audio.stopped, "the microphone must be released")
        assertTrue(transport.closed, "the socket must be closed")
        assertEquals(HostCallPhase.Idle, c.state.value.phase)
    }
}
