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

        val evictableFlags = mutableListOf<Boolean>()
        /** Mirrors the contract: guaranteed frames report acceptance, and [refuseSends] simulates
         *  a stalled socket so the caller's handling of a dropped protocol frame is exercisable. */
        var refuseSends = false
        override fun send(json: String, evictable: Boolean): Boolean {
            if (refuseSends && !evictable) return false
            sent.add(json)
            evictableFlags.add(evictable)
            return true
        }
        override fun close() { closed = true }

        fun deliver(json: String) = events?.invoke(json)
        fun sentOfType(type: String): List<JsonObject> = sent.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == type }

        /** How many function_call_output items were sent for [callId]. */
        fun outputsFor(callId: String): Int = sentOfType("conversation.item.create").count {
            it["item"]?.jsonObject?.get("call_id")?.jsonPrimitive?.content == callId
        }
    }

    private class FakeAudio : VoiceAudioIo {
        var capturing = false
        var stopped = false
        var flushes = 0
        val played: MutableList<ByteArray> = java.util.concurrent.CopyOnWriteArrayList()
        private var onChunk: ((ByteArray) -> Unit)? = null

        private var onEnded: ((String) -> Unit)? = null
        override fun startCapture(
            onChunk: (ByteArray) -> Unit,
            onLevel: (Float) -> Unit,
            onEnded: (String) -> Unit,
        ) {
            capturing = true
            this.onChunk = onChunk
            this.onEnded = onEnded
            onLevel(0.5f)
        }

        /** Pretend the capture line died on its own (unplugged headset, device stolen). */
        fun loseCapture(reason: String) = onEnded?.invoke(reason)
        /** Mirrors the real line: muted means the capture line is STOPPED, not merely ignored. */
        var lineMuted = false
        override fun setCaptureMuted(muted: Boolean) { lineMuted = muted }
        override fun play(pcm: ByteArray) { played.add(pcm) }
        override fun flushPlayback() { flushes += 1; queued = 0 }
        /** Decoded audio the speaker has not reached yet, in ms — drives the "still audible" wait. */
        @Volatile var queued = 0
        override fun queuedPlaybackMs(): Int = queued

        /**
         * How loud the speaker is right now — the gate's echo reference.
         *
         * Defaults to a normal playback level so a test that emits mic frames while the agent is
         * speaking exercises the real path: with this at zero the gate would see "nothing is playing",
         * drop its bar to the user floor, and let frames through for the wrong reason.
         */
        @Volatile var speakerLevel = 0.30f
        override fun audiblePlaybackLevel(windowMs: Int): Float = if (played.isEmpty()) 0f else speakerLevel
        override fun stop() { stopped = true; capturing = false }

        /** Pretend the mic produced a frame. */
        fun emit(bytes: ByteArray) = onChunk?.invoke(bytes)
    }

    private class FakeExecutor : VoiceToolExecutor {
        // Thread-safe on purpose: a round can run four tools at once, and an unsynchronized
        // ArrayList silently LOST entries under that concurrency — which read as "the tool didn't
        // run" and cost a while to tell apart from a real scheduling bug.
        val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
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
        clock: () -> Long = { System.currentTimeMillis() },
        confirmations: VoiceConfirmationGate? = null,
    ) = HostVoiceCallController(
        scope = CoroutineScope(Dispatchers.Default),
        executor = executor,
        transport = transport,
        newAudio = { audio },
        settings = { TerminalSettings.DEFAULT.copy(voiceCallEnabled = enabled) },
        loadKey = { key },
        nowMs = clock,
        confirmations = confirmations,
    )

    /**
     * These are "eventually" assertions on work handed to background coroutines, so the window has
     * to survive a fully-loaded machine: at 4s they passed in isolation and failed in the full suite,
     * which is a flaky test rather than a real signal. A generous ceiling costs time only on a
     * genuine failure.
     */
    private fun await(timeoutMs: Long = 15_000, predicate: () -> Boolean): Boolean {
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
        // Whatever the sensitivity setting says — the default is a tunable server_vad, because
        // semantic_vad has no threshold to lower for a noisy room. VoiceTurnDetectionTest owns the
        // mapping; this pins that session.update carries it rather than a hardcoded mode.
        assertEquals(
            VoiceTurnDetection.json(TerminalSettings.DEFAULT.voiceMicSensitivity),
            audioCfg["input"]!!.jsonObject["turn_detection"]!!.jsonObject,
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

        // Mic frames must be the droppable ones; a queued function_call_output must not be.
        val appendIndex = transport.sent.indexOfFirst { it.contains("input_audio_buffer.append") }
        assertTrue(transport.evictableFlags[appendIndex], "mic audio is evictable")
        val sessionIndex = transport.sent.indexOfFirst { it.contains("session.update") }
        assertFalse(transport.evictableFlags[sessionIndex], "protocol frames are guaranteed")

        c.toggleMute()
        assertTrue(c.state.value.muted)
        // Mute has to reach the LINE, not just the send: leaving the capture line started kept the
        // platform's microphone indicator lit, which reads as "still listening" to the person who
        // just pressed Mute — on a feature whose whole trust story is that you can see what it does.
        assertTrue(audio.lineMuted, "muting stops the capture line, not just the append")
        val before = transport.sentOfType("input_audio_buffer.append").size
        audio.emit(byteArrayOf(9, 9))
        Thread.sleep(100)
        assertEquals(before, transport.sentOfType("input_audio_buffer.append").size, "muted mic must not be sent")

        c.toggleMute()
        assertFalse(c.state.value.muted)
        assertFalse(audio.lineMuted, "and unmuting restarts it")
        audio.emit(byteArrayOf(7, 7))
        assertTrue(
            await { transport.sentOfType("input_audio_buffer.append").size > before },
            "audio flows again after unmute",
        )
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

    /**
     * Exactly one answer per call. The watchdog and the executor race by design, and a second
     * function_call_output for one call_id is a protocol error.
     */
    @Test
    fun `a late result after the watchdog fired sends no second output`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val gate = CompletableDeferred<Unit>()
        val slow = object : VoiceToolExecutor {
            override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL.filter { !it.guiOnly }
            override fun contextSnapshot(defaultTabId: String?): String = ""
            override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
                gate.await()
                return """{"late":true}"""
            }
        }
        // A 150ms tool timeout drives the REAL watchdog — no production test hook needed.
        val c = HostVoiceCallController(
            scope = CoroutineScope(Dispatchers.Default),
            executor = slow,
            transport = transport,
            newAudio = { audio },
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-test" },
            toolTimeoutMs = { 150L },
        )
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        transport.deliver(
            """{"type":"response.function_call_arguments.done","call_id":"slow","name":"read_scrollback","arguments":"{}"}"""
        )
        assertTrue(await { transport.outputsFor("slow") == 1 }, "the watchdog answered")
        gate.complete(Unit)
        Thread.sleep(300)
        assertEquals(1, transport.outputsFor("slow"), "the late result must NOT answer again")
        c.end()
    }

    /**
     * The protocol lane is advertised as guaranteed, so a refused function_call_output must end the
     * call rather than leave it quiet: the round is already settled locally, which makes a dropped
     * frame indistinguishable from a hang.
     */
    @Test
    fun `a dropped tool result ends the call instead of going quiet`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        transport.refuseSends = true // stalled socket: the guaranteed lane has no room
        transport.deliver(
            """{"type":"response.function_call_arguments.done","call_id":"c1","name":"read_scrollback","arguments":"{}"}"""
        )
        assertTrue(await { c.state.value.phase == HostCallPhase.Error }, "a lost result must not be silent")
        assertTrue(c.state.value.error?.contains("Lost the connection") == true, c.state.value.error ?: "")
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
        // The key read moved off the click (it's a file read + parse, and the click runs on the
        // Compose thread), so this failure is reported asynchronously — but still before any socket.
        assertTrue(await { noKey.state.value.phase == HostCallPhase.Error }, "no key must fail the call")
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
            override fun send(json: String, evictable: Boolean) = true
            override fun close() { closed = true }
        }
        val audio = FakeAudio()
        val c = HostVoiceCallController(
            scope = CoroutineScope(Dispatchers.Default),
            executor = FakeExecutor(),
            transport = slowConnect,
            newAudio = { audio },
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

    /**
     * The in-app surface holds the microphone AND bills the host's account for the whole session, so
     * it carries the same ceilings as the share path rather than running until someone notices.
     */
    @Test
    fun `an idle in-app call hangs up on its own`() {
        var clock = 1_000L
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio, clock = { clock })
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        clock += HostVoiceCallController.IDLE_TIMEOUT_MS + 1
        assertTrue(await { c.state.value.phase == HostCallPhase.Error }, "an idle call must end itself")
        assertTrue(c.state.value.error?.contains("nothing happened") == true, c.state.value.error ?: "")
        assertTrue(audio.stopped, "and release the microphone")
    }

    /**
     * The idle cut-off (10 min) is SHORTER than the run_command budget (10.5 min), and the MCP server
     * lets a command run the full 10. Keying "idle" off the last event therefore hung up on the one
     * caller who was definitely doing something: ask for a long build, wait quietly for it, get
     * "nothing happened for 10 minutes" at almost the instant the command's own clamp expired.
     */
    @Test
    fun `a call running a long tool is not idle`() {
        var clock = 1_000L
        val gate = CompletableDeferred<Unit>()
        val slow = object : VoiceToolExecutor {
            override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL.filter { !it.guiOnly }
            override fun contextSnapshot(defaultTabId: String?): String = ""
            override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
                gate.await()
                return """{"ok":true}"""
            }
        }
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio, executor = slow, clock = { clock })
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        transport.deliver(
            """{"type":"response.function_call_arguments.done","call_id":"build","name":"run_command","arguments":"{\"script\":\"./gradlew test\"}"}"""
        )
        assertTrue(await { c.state.value.working }, "the tool is in flight")

        // Well past the cut-off, with nothing else arriving — exactly the reported scenario.
        clock += HostVoiceCallController.IDLE_TIMEOUT_MS + 1
        assertFalse(
            await(HostVoiceCallController.LIMIT_TICK_MS + 1_500) { c.state.value.phase != HostCallPhase.Live },
            "a pending tool call must hold the idle cut-off off",
        )

        // And the deadline must be fresh when it finishes, or the next tick hangs up on the caller
        // while the agent is starting to read the result out.
        gate.complete(Unit)
        assertTrue(await { transport.outputsFor("build") == 1 }, "the result was delivered")
        assertFalse(
            await(HostVoiceCallController.LIMIT_TICK_MS + 1_500) { c.state.value.phase != HostCallPhase.Live },
            "the call must not be judged idle the moment a long tool answers",
        )
        c.end()
    }

    /**
     * The owner arms its kill-switch collector around start(), so a flip landing while we connect
     * can be missed by both sides — and what is left over is an open microphone with nothing holding
     * a reference to stop it. This is the last check before the call goes live, with the mic known
     * open, so it closes that window for good.
     */
    @Test
    fun `turning the feature off mid-connect stops the call before it goes live`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        // Enabled for start()'s own gate and the session read; off by the time the mic is open.
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val c = HostVoiceCallController(
            scope = CoroutineScope(Dispatchers.Default),
            executor = FakeExecutor(),
            transport = transport,
            newAudio = { audio },
            settings = {
                TerminalSettings.DEFAULT.copy(voiceCallEnabled = reads.incrementAndGet() <= 2)
            },
            loadKey = { "sk-test" },
        )
        c.start()

        assertTrue(await { c.state.value.phase == HostCallPhase.Error }, "the call must not go live")
        assertTrue(c.state.value.error?.contains("turned off") == true, c.state.value.error ?: "")
        assertTrue(await { audio.stopped }, "and the microphone must be released")
        assertTrue(transport.closed, "with the billed socket closed too")
    }

    /**
     * The owner's release signal, driven through the REAL terminal paths.
     *
     * This replaces a helper that inferred "the call ended itself" from the state flow, and the
     * inference did not work: `endWith` writes Idle (inside end()) and then Error back-to-back on one
     * thread, and a conflating StateFlow hands a collector on another dispatcher only the Error — so
     * the ceiling path, the very case it was written for, never fired. The old test drove the helper
     * with hand-written intermediate states, which is exactly why it passed. Assert the event.
     */
    @Test
    fun `every way a call can end reports terminal exactly once`() {
        // 1. the user hangs up
        run {
            val ended = java.util.concurrent.atomic.AtomicInteger(0)
            val c = terminalController(FakeTransport(), FakeAudio()) { ended.incrementAndGet() }
            c.start()
            assertTrue(await { c.state.value.phase == HostCallPhase.Live })
            c.end()
            assertEquals(1, ended.get(), "end() reports terminal")
            c.end()
            assertEquals(1, ended.get(), "and only once")
        }
        // 2. a ceiling fires (endWith → end() then Error — the conflated pair)
        run {
            var clock = 1_000L
            val ended = java.util.concurrent.atomic.AtomicInteger(0)
            val audio = FakeAudio()
            val c = HostVoiceCallController(
                scope = CoroutineScope(Dispatchers.Default),
                executor = FakeExecutor(),
                transport = FakeTransport(),
                newAudio = { audio },
                settings = { TerminalSettings.DEFAULT },
                loadKey = { "sk-test" },
                nowMs = { clock },
                onTerminal = { ended.incrementAndGet() },
            )
            c.start()
            assertTrue(await { c.state.value.phase == HostCallPhase.Live })
            clock += HostVoiceCallController.IDLE_TIMEOUT_MS + 1
            assertTrue(await { c.state.value.phase == HostCallPhase.Error }, "the idle ceiling fires")
            assertTrue(await { ended.get() == 1 }, "a self-ended call must tell its owner")
        }
        // 3. it fails outright
        run {
            val ended = java.util.concurrent.atomic.AtomicInteger(0)
            val c = terminalController(FakeTransport(), FakeAudio(), key = null) { ended.incrementAndGet() }
            c.start()
            assertTrue(await { c.state.value.phase == HostCallPhase.Error })
            assertTrue(await { ended.get() == 1 }, "a failed call releases its owner too")
        }
    }

    private fun terminalController(
        transport: FakeTransport,
        audio: FakeAudio,
        key: String? = "sk-test",
        onTerminal: () -> Unit,
    ) = HostVoiceCallController(
        scope = CoroutineScope(Dispatchers.Default),
        executor = FakeExecutor(),
        transport = transport,
        newAudio = { audio },
        settings = { TerminalSettings.DEFAULT },
        loadKey = { key },
        onTerminal = onTerminal,
    )

    /**
     * Losing the microphone mid-call — headset unplugged, another app grabs the device — used to be
     * silent: the capture thread exited, the bar went on saying "Listening…" with the meter frozen,
     * and the call kept billing while the agent could no longer hear anything.
     */
    @Test
    fun `losing the microphone ends the call instead of going deaf`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        audio.loseCapture("The microphone stopped working")

        assertTrue(await { c.state.value.phase == HostCallPhase.Error }, "the call must not stay 'Live'")
        assertTrue(
            c.state.value.error?.contains("microphone") == true,
            "and must say what happened: ${c.state.value.error}",
        )
        assertTrue(audio.stopped, "the audio lines are released")
        assertTrue(transport.closed, "and the billed socket is closed")
    }

    /**
     * Hanging up during connect must not be undone by the transition that follows it.
     *
     * end() writes a fresh Idle state, and cancelling startJob cannot interrupt a non-suspending
     * update() — so a bare `copy(phase = Live)` promoted that Idle back to Live and armed the
     * ceilings against a call whose mic and socket were already gone. The bar would read
     * "Listening…" on a dead call.
     *
     * Driven through the injected `settings` lambda rather than by sleeping: the last thing the
     * connect path does before the transition is re-read the master switch, so ending from inside
     * that read lands end() exactly in the window. A timing-based version of this test passed
     * against the unfixed code — the window is a couple of instructions wide.
     */
    @Test
    fun `ending during connect is not undone by the transition to Live`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        var holder: HostVoiceCallController? = null
        val endedInWindow = java.util.concurrent.atomic.AtomicBoolean(false)
        val c = HostVoiceCallController(
            scope = CoroutineScope(Dispatchers.Default),
            executor = FakeExecutor(),
            transport = transport,
            newAudio = { audio },
            settings = {
                // The re-check is the first settings() read after the mic opens — i.e. precisely
                // between the guard and the state transition.
                if (audio.capturing && endedInWindow.compareAndSet(false, true)) holder?.end()
                TerminalSettings.DEFAULT
            },
            loadKey = { "sk-test" },
        )
        holder = c
        c.start()

        assertTrue(await { endedInWindow.get() }, "the test never reached the window it exists for")
        assertFalse(
            await(1_500) { c.state.value.phase == HostCallPhase.Live },
            "a call ended during connect must never reach Live; it did",
        )
        assertTrue(await { !audio.capturing }, "and must not be left holding the mic")
    }

    /**
     * `response.output_audio.done` means the API finished SENDING, not that the user finished
     * HEARING: up to ~4s can still be in the play queue. Clearing "speaking" on the event flipped the
     * bar to "Listening…" while Boss was audibly mid-sentence. The viewer has no equivalent problem
     * because output_audio_buffer.stopped is playback-accurate — this is what makes the two agree.
     */
    @Test
    fun `speaking stays true until the speaker has actually drained`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        transport.deliver("""{"type":"response.output_audio.delta","delta":"AAAA"}""")
        assertTrue(await { c.state.value.speaking }, "the agent is talking")

        audio.queued = 900 // ~0.9s still ahead of the speaker, well above AUDIBLE_TAIL_MS
        transport.deliver("""{"type":"response.output_audio.done"}""")
        Thread.sleep(200)
        assertTrue(c.state.value.speaking, "still audible, so still 'Speaking'")

        audio.queued = 0
        assertTrue(await { !c.state.value.speaking }, "and clears once the speaker catches up")
        c.end()
    }

    /** Barge-in empties the queue, so the answer is immediate rather than after a drain. */
    @Test
    fun `barge-in clears speaking at once`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        transport.deliver("""{"type":"response.output_audio.delta","delta":"AAAA"}""")
        assertTrue(await { c.state.value.speaking })
        audio.queued = 900
        transport.deliver("""{"type":"response.output_audio.done"}""")
        transport.deliver("""{"type":"input_audio_buffer.speech_started"}""")

        assertTrue(await { !c.state.value.speaking }, "the user is talking; the agent is not")
        assertEquals(0, audio.queued, "and what was queued is dropped, not played over them")
        c.end()
    }

    /**
     * A rejected key must SAY so on the in-app surface.
     *
     * The default openSocket ends in CompletableFuture.join(), which wraps everything in
     * CompletionException — so a 401 at the WebSocket handshake surfaced as "Couldn't reach OpenAI
     * (CompletionException)" on the surface a user hits first when setting the feature up.
     * FATAL_ERROR_CODES has invalid_api_key, but that needs a Realtime error EVENT, and with a bad
     * Bearer no socket ever opens. Nothing pinned the classification, which is why it went unseen.
     */
    @Test
    fun `a rejected key is reported as a rejected key, not a network error`() {
        val refusing = object : RealtimeTransport {
            override suspend fun connect(
                model: String,
                apiKey: String,
                events: (String) -> Unit,
                onClosed: (String?) -> Unit,
            ): Unit = throw java.io.IOException("handshake failed")
            override fun send(json: String, evictable: Boolean) = false
            override fun close() {}
        }
        val c = HostVoiceCallController(
            scope = CoroutineScope(Dispatchers.Default),
            executor = FakeExecutor(),
            transport = refusing,
            newAudio = { FakeAudio() },
            settings = { TerminalSettings.DEFAULT },
            loadKey = { "sk-bad" },
        )
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Error })
        // A plain IO failure IS a network problem, so it keeps the generic wording...
        assertTrue(c.state.value.error?.contains("Couldn't reach OpenAI") == true, c.state.value.error ?: "")

        // ...but the message must never be the wrapper's own class name, which is what join() throws.
        assertFalse(
            c.state.value.error?.contains("CompletionException") == true,
            "the wrapper leaked instead of its cause: ${c.state.value.error}",
        )
    }

    /**
     * A transient error must not caption the bar for the rest of the call.
     *
     * Nothing asserted on `activity`, which is how a dead `when` branch shipped: the clear was added
     * as a SECOND "response.created" case, and Kotlin only warns on a duplicate label. Eight tests
     * over this state machine stayed green while the fix did nothing.
     */
    @Test
    fun `a new turn clears a stale error caption`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        transport.deliver("""{"type":"error","error":{"message":"transient hiccup"}}""")
        assertTrue(
            await { c.state.value.activity?.contains("transient hiccup") == true },
            "the error is shown: ${c.state.value.activity}",
        )
        assertEquals(HostCallPhase.Live, c.state.value.phase, "a non-fatal error does not end the call")

        transport.deliver("""{"type":"response.created"}""")
        assertTrue(
            await { c.state.value.activity == null },
            "a new turn clears it; saw ${c.state.value.activity}",
        )
        c.end()
    }

    /** But a caption describing a RUNNING tool is not stale, so a new turn must not wipe it. */
    @Test
    fun `a new turn leaves a working caption alone`() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : VoiceToolExecutor {
            override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL.filter { !it.guiOnly }
            override fun contextSnapshot(defaultTabId: String?): String = ""
            override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
                gate.await()
                return "{}"
            }
        }
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio, executor = slow)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        transport.deliver(
            """{"type":"response.function_call_arguments.done","call_id":"t1","name":"read_scrollback","arguments":"{}"}"""
        )
        assertTrue(await { c.state.value.working }, "the tool is in flight")
        val caption = c.state.value.activity

        transport.deliver("""{"type":"response.created"}""")
        Thread.sleep(150)
        assertEquals(caption, c.state.value.activity, "a live tool's caption must survive a new turn")
        gate.complete(Unit)
        c.end()
    }

    /**
     * PCM16 frames at a chosen loudness, so these tests exercise the real levels a room produces.
     *
     * A frame of `byteArrayOf(5, 6)` is silence as far as [pcm16Rms] is concerned, so asserting on
     * one proves nothing about a gate whose entire job is comparing loudnesses.
     */
    private fun frameAt(rms: Float): ByteArray {
        val amplitude = (rms * Short.MAX_VALUE).toInt().toShort()
        val out = ByteArray(1920)
        for (i in out.indices step 2) {
            out[i] = (amplitude.toInt() and 0xFF).toByte()
            out[i + 1] = ((amplitude.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Talking over the agent reaches the server; the agent's own echo does not.
     *
     * Both blanket answers were tried on real calls and both failed — see [VoiceDuplexGate]. This
     * pins the middle: the frames carrying a real interruption must be SENT, because barge-in runs
     * off the server's `speech_started` and that cannot fire for frames withheld locally.
     */
    @Test
    fun `talking over the agent gets through while its echo does not`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        // The user's turn: the server confirms these frames are their voice, which is what calibrates
        // the gate. Around 0.10 RMS — VoiceAudioIo puts conversational speech at 0.05-0.15.
        transport.deliver("""{"type":"input_audio_buffer.speech_started"}""")
        repeat(20) { audio.emit(frameAt(0.10f)) }
        transport.deliver("""{"type":"input_audio_buffer.speech_stopped"}""")

        transport.deliver("""{"type":"response.output_audio.delta","delta":"AAAA"}""")
        assertTrue(await { c.state.value.speaking })

        // The reply coming back off the room, attenuated. None of it may reach the server.
        val beforeEcho = transport.sentOfType("input_audio_buffer.append").size
        repeat(40) { audio.emit(frameAt(0.04f)) }
        Thread.sleep(120)
        assertEquals(
            beforeEcho,
            transport.sentOfType("input_audio_buffer.append").size,
            "sending the agent its own voice is what makes it interrupt itself",
        )

        // The user cutting in, at the level the server already agreed is their voice.
        repeat(6) { audio.emit(frameAt(0.10f)) }
        assertTrue(
            await { transport.sentOfType("input_audio_buffer.append").size > beforeEcho },
            "withholding these frames is what broke barge-in",
        )
        assertTrue(await { !c.state.value.speaking }, "and playback must stop locally, immediately")
        assertTrue(audio.flushes > 0, "queued audio must be dropped, not played out after the cut")
        c.end()
    }

    @Test
    fun `a long in-app call stops at the duration ceiling`() {
        var clock = 1_000L
        val transport = FakeTransport()
        val audio = FakeAudio()
        val c = controller(transport, audio, clock = { clock })
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        // Keep it busy so the idle cut-off can't be what ends it.
        repeat(4) {
            clock += HostVoiceCallController.MAX_CALL_DURATION_MS / 4
            transport.deliver("""{"type":"input_audio_buffer.speech_started"}""")
            Thread.sleep(30)
        }
        clock += HostVoiceCallController.LIMIT_TICK_MS
        assertTrue(await { c.state.value.phase == HostCallPhase.Error }, "the ceiling must end it")
        assertTrue(c.state.value.error?.contains("minute limit") == true, c.state.value.error ?: "")
    }

    /**
     * The controller's contribution to the confirmation interlock: it is the only thing that knows
     * either party has spoken. Without these two lines an irreversible embedder tool can never be
     * confirmed — and, worse, nothing else in the suite would notice, because every other test of
     * [VoiceConfirmationGate] pumps it by hand.
     *
     * Both signals, in order: the agent's announcement (`response.output_audio.done`) and then the
     * user's answer (`input_audio_buffer.speech_stopped`). The user's alone is not enough — see the
     * barge-in case in [VoiceToolSafetyTest].
     */
    @Test
    fun `the agent announcing and the user answering is what unlocks a pending confirmation`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val gate = VoiceConfirmationGate()
        val c = controller(transport, audio, confirmations = gate)
        c.start()
        assertTrue(await { c.state.value.phase == HostCallPhase.Live })

        val args = JsonObject(emptyMap())
        val minted = gate.redeem("git_discard", args, null)
        val token = (minted as VoiceConfirmationGate.Decision.Confirm).token
        assertTrue(
            gate.redeem("git_discard", args, token) is VoiceConfirmationGate.Decision.Confirm,
            "redeemable before anyone said anything",
        )

        // The agent says what it is about to do.
        transport.deliver("""{"type":"response.output_audio.done"}""")
        assertTrue(
            gate.redeem("git_discard", args, token) is VoiceConfirmationGate.Decision.Confirm,
            "redeemable before the user answered",
        )

        // The user answers.
        transport.deliver("""{"type":"input_audio_buffer.speech_stopped"}""")

        assertTrue(
            await { gate.redeem("git_discard", args, token) is VoiceConfirmationGate.Decision.Allowed },
            "both speech signals must reach the gate",
        )
        c.end()
    }

    /**
     * The status strip is the user's only sight of what the agent decided to do before it happens,
     * and with an embedder source there can be a hundred tools with no hand-written caption.
     *
     * Both halves matter and a mutation sweep found neither was pinned: captioning an embedder tool
     * by name, AND leaving BossTerm's own on the shared "Working…" that
     * [VoiceCrossLanguageContractTest] holds `viewer.js` to. Getting the second wrong would break
     * the cross-language contract from a direction that test cannot see, since it captions an
     * unstarted controller.
     */
    @Test
    fun `an embedder tool is captioned by name and BossTerm's own are not`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        // The default in-app configuration: voiceExposeAllTools is ON, so BossTerm advertises its
        // whole MCP registry, not the curated catalog. `show_image` and `read_debug_console` are
        // BossTerm's own and outside VoiceToolCatalog — deciding ownership by checking the catalog
        // called them foreign and captioned them "Running show image…", with no embedder source
        // present at all. Ownership comes from the executor now.
        val bossTermsOwn = VoiceToolCatalog.ALL.map { it.name } + listOf("show_image", "read_debug_console")
        val executor = object : VoiceToolExecutor {
            override fun tools(): List<VoiceToolDef> =
                (bossTermsOwn + "git_status").map {
                    VoiceToolDef(it, "d", kotlinx.serialization.json.buildJsonObject { }, write = false)
                }
            override fun externalAdvertisedNames(): Set<String> = setOf("git_status")
            override fun contextSnapshot(defaultTabId: String?): String = ""
            override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?) = "{}"
        }
        val c = controller(transport, audio, executor)
        c.start()
        assertTrue(await { transport.sentOfType("session.update").isNotEmpty() })

        assertEquals("Running git status…", c.describeTool("git_status", "{}"))
        assertEquals("Working…", c.describeTool("list_tabs", "{}"), "curated tools keep the shared caption")
        assertEquals("Working…", c.describeTool("show_image", "{}"), "BossTerm's own, outside the catalog")
        assertEquals("Working…", c.describeTool("read_debug_console", "{}"))
        assertEquals("Working…", c.describeTool("never_advertised", "{}"))
        c.end()
    }

    /** With no embedder source at all, nothing may be captioned by name. */
    @Test
    fun `the standalone surface captions nothing by name`() {
        val transport = FakeTransport()
        val audio = FakeAudio()
        val executor = object : VoiceToolExecutor {
            override fun tools(): List<VoiceToolDef> =
                listOf("show_image", "read_debug_console", "manage_tools").map {
                    VoiceToolDef(it, "d", kotlinx.serialization.json.buildJsonObject { }, write = false)
                }
            override fun contextSnapshot(defaultTabId: String?): String = ""
            override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?) = "{}"
        }
        val c = controller(transport, audio, executor)
        c.start()
        assertTrue(await { transport.sentOfType("session.update").isNotEmpty() })

        for (name in listOf("show_image", "read_debug_console", "manage_tools")) {
            assertEquals("Working…", c.describeTool(name, "{}"), name)
        }
        c.end()
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
