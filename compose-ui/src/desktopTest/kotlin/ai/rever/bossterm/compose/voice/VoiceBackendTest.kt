package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * The backend decision, which is the one branch that can quietly cost a user money or put their
 * microphone on the network. Asserted as a pure function for exactly that reason.
 */
class VoiceBackendTest {

    private fun settings(
        backend: String = "OPENAI",
        localModel: String = "",
        model: String = "gpt-realtime-2.1",
    ) = TerminalSettings(voiceBackend = backend, voiceLocalModel = localModel, voiceCallModel = model)

    @Test
    fun `openai backend resolves to the openai socket with the key`() {
        val result = VoiceEndpointResolver.resolve(settings(), loadKey = { "sk-test" })
        val endpoint = assertIs<VoiceEndpointResult.Ready>(result).endpoint
        assertEquals(JdkRealtimeTransport.REALTIME_WS_URL, endpoint.url)
        assertEquals("sk-test", endpoint.apiKey)
        assertEquals("gpt-realtime-2.1", endpoint.model)
        assertEquals(VoiceBackend.OPENAI, endpoint.backend)
    }

    @Test
    fun `openai backend without a key names where to add one`() {
        val result = VoiceEndpointResolver.resolve(settings(), loadKey = { null })
        val message = assertIs<VoiceEndpointResult.Unavailable>(result).message
        assertTrue(message.contains("Settings"), "the message must say where to go, got: $message")
    }

    /**
     * A blank key is the shape a cleared settings field leaves behind, and `!= null` would let it
     * through as a Bearer token of empty string - a 401 from OpenAI reported as "connection
     * closed" rather than as a missing key.
     */
    @Test
    fun `a blank key counts as no key`() {
        val result = VoiceEndpointResolver.resolve(settings(), loadKey = { "   " })
        assertIs<VoiceEndpointResult.Unavailable>(result)
    }

    @Test
    fun `local backend resolves to the running local url with no credential`() {
        val result = VoiceEndpointResolver.resolve(
            settings(backend = "LOCAL"),
            loadKey = { "sk-test" },
            localEndpointUrl = "ws://127.0.0.1:8765/v1/realtime",
        )
        val endpoint = assertIs<VoiceEndpointResult.Ready>(result).endpoint
        assertEquals("ws://127.0.0.1:8765/v1/realtime", endpoint.url)
        // The key EXISTS and must still not be sent: the local server performs no authentication,
        // and shipping a live OpenAI credential to an arbitrary local process is a leak.
        assertNull(endpoint.apiKey, "a local endpoint must not carry the user's OpenAI key")
        assertEquals(VoiceBackend.LOCAL, endpoint.backend)
    }

    /**
     * The regression this whole design exists to prevent. A user who selected LOCAL but still has
     * an OpenAI key on disk must NOT have their call silently placed through the metered backend:
     * that bills them and puts their audio on the network, both without asking.
     */
    @Test
    fun `local backend never falls back to openai when it cannot serve`() {
        val result = VoiceEndpointResolver.resolve(
            settings(backend = "LOCAL"),
            loadKey = { "sk-test" },
            localEndpointUrl = null,
        )
        val unavailable = assertIs<VoiceEndpointResult.Unavailable>(result)
        assertTrue(unavailable.needsLocalSetup, "the UI needs to know an install/start is the fix")
        assertTrue(
            !unavailable.message.contains("api.openai.com"),
            "a local failure must not point at the paid backend: ${unavailable.message}",
        )
    }

    @Test
    fun `local backend does not report an openai model`() {
        val result = VoiceEndpointResolver.resolve(
            settings(backend = "LOCAL"),
            loadKey = { null },
            localEndpointUrl = "ws://127.0.0.1:8765/v1/realtime",
        )
        val endpoint = assertIs<VoiceEndpointResult.Ready>(result).endpoint
        // Reusing voiceCallModel here would put `gpt-realtime-2.1` in the logs and status of a call
        // that never touched OpenAI, making a local call unauditable after the fact.
        assertEquals(VoiceEndpointResolver.LOCAL_DEFAULT_MODEL, endpoint.model)
        assertTrue(!endpoint.model.startsWith("gpt-"), "got: ${endpoint.model}")
    }

    @Test
    fun `a user-named local model is used verbatim`() {
        val result = VoiceEndpointResolver.resolve(
            settings(backend = "LOCAL", localModel = "qwen3-omni"),
            loadKey = { null },
            localEndpointUrl = "ws://127.0.0.1:8765/v1/realtime",
        )
        assertEquals("qwen3-omni", assertIs<VoiceEndpointResult.Ready>(result).endpoint.model)
    }

    /**
     * settings.json is hand-editable, so an unknown backend must not throw during settings load -
     * that would take the entire app's configuration down over one typo in a voice field. It also
     * must degrade to OPENAI rather than LOCAL: falling to a backend the user never set up would
     * turn a typo into a broken call, while OPENAI is simply what the build did before.
     */
    @Test
    fun `an unparseable backend degrades to openai`() {
        assertEquals(VoiceBackend.OPENAI, VoiceBackend.parse("not-a-backend"))
        assertEquals(VoiceBackend.OPENAI, VoiceBackend.parse(null))
        assertEquals(VoiceBackend.OPENAI, VoiceBackend.parse(""))
    }

    @Test
    fun `backend parsing tolerates case and padding`() {
        assertEquals(VoiceBackend.LOCAL, VoiceBackend.parse("local"))
        assertEquals(VoiceBackend.LOCAL, VoiceBackend.parse("  LOCAL  "))
    }

    /** A fresh install must behave exactly as it did before this setting existed. */
    @Test
    fun `the default backend is openai`() {
        assertEquals(VoiceBackend.OPENAI, VoiceBackend.parse(TerminalSettings().voiceBackend))
    }
}
