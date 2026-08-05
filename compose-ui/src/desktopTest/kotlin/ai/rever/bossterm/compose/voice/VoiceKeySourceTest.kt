package ai.rever.bossterm.compose.voice

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Precedence and failure isolation for Boss Calling's key: an embedder-supplied source wins over
 * `voice.json`, but must never be able to break the call if it throws.
 *
 * BossTerm ships standalone as well as embedded, so the file has to keep working untouched when
 * nothing is registered — that is what most of these assert.
 */
class VoiceKeySourceTest {

    private fun tempFile() = createTempDirectory("voice-key-src").resolve("voice.json").toFile()

    @AfterTest
    fun deregister() {
        // The source is process-wide; leaving one registered would leak into every other suite.
        VoiceKeySource.setEmbedderSource(null)
    }

    @Test
    fun `with no embedder registered the file is used`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-from-file"), file)

        assertEquals("sk-from-file", VoiceKeySource.resolve(file))
        assertFalse(VoiceKeySource.embedderKeyPresent())
    }

    @Test
    fun `the embedder source wins over the file`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-from-file"), file)
        VoiceKeySource.setEmbedderSource { "sk-from-embedder" }

        assertEquals("sk-from-embedder", VoiceKeySource.resolve(file))
        assertTrue(VoiceKeySource.embedderKeyPresent())
    }

    /**
     * The BOSS case: the user has no OpenAI provider configured, so the plugin's lambda returns
     * null. That must fall through to the file rather than reporting "no key" — an embedded user
     * who pasted a key into BossTerm's own settings keeps working.
     */
    @Test
    fun `an embedder returning null falls through to the file`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-from-file"), file)
        VoiceKeySource.setEmbedderSource { null }

        assertEquals("sk-from-file", VoiceKeySource.resolve(file))
        assertFalse(VoiceKeySource.embedderKeyPresent())
    }

    @Test
    fun `an embedder returning blank falls through to the file`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-from-file"), file)
        VoiceKeySource.setEmbedderSource { "   " }

        assertEquals("sk-from-file", VoiceKeySource.resolve(file))
        assertFalse(VoiceKeySource.embedderKeyPresent())
    }

    /**
     * The embedder's lambda runs host code across a plugin classloader. A `LinkageError` there —
     * an api symbol the host does not serve, say — must not take a voice call or a share
     * advertisement down with it.
     */
    @Test
    fun `a throwing embedder falls through instead of propagating`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-from-file"), file)
        VoiceKeySource.setEmbedderSource { throw NoSuchMethodError("getLlmProvider") }

        assertEquals("sk-from-file", VoiceKeySource.resolve(file))
        assertFalse(VoiceKeySource.embedderKeyPresent())
    }

    @Test
    fun `no key anywhere resolves to null`() {
        val file = tempFile() // never written
        VoiceKeySource.setEmbedderSource { null }
        assertNull(VoiceKeySource.resolve(file))
    }

    @Test
    fun `deregistering restores the file as the only source`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-from-file"), file)
        VoiceKeySource.setEmbedderSource { "sk-from-embedder" }
        assertEquals("sk-from-embedder", VoiceKeySource.resolve(file))

        // An embedder must deregister when it unloads, or its dead classloader stays reachable.
        VoiceKeySource.setEmbedderSource(null)

        assertEquals("sk-from-file", VoiceKeySource.resolve(file))
        assertFalse(VoiceKeySource.embedderKeyPresent())
    }

    /**
     * `embedderKeyPresent` reports on the embedder alone — the share server ORs it with two
     * separate file-backed checks, so answering true for a file-only key would make one of those
     * redundant and hide a regression in it.
     */
    @Test
    fun `embedderKeyPresent ignores a key that only exists in the file`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-from-file"), file)

        assertFalse(VoiceKeySource.embedderKeyPresent())
        assertEquals("sk-from-file", VoiceKeySource.resolve(file))
    }
}
