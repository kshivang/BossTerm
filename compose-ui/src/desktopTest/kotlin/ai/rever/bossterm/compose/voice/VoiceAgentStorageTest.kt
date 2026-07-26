package ai.rever.bossterm.compose.voice

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Save/load/clear + permission hardening for the Boss Calling key file (`voice.json`). */
class VoiceAgentStorageTest {

    private fun tempFile() = createTempDirectory("voice-test").resolve("voice.json").toFile()

    @Test
    fun `save then load round-trips the key`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-test-123"), file)
        assertEquals("sk-test-123", VoiceAgentStorage.load(file)?.openaiApiKey)
        assertTrue(VoiceAgentStorage.keyPresent(file))
    }

    @Test
    fun `clear removes the file`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-test-123"), file)
        VoiceAgentStorage.clear(file)
        assertFalse(file.exists())
        assertNull(VoiceAgentStorage.load(file))
        assertFalse(VoiceAgentStorage.keyPresent(file))
    }

    @Test
    fun `saved file is owner-only on POSIX filesystems`() {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "sk-test-123"), file)
        val perms = Files.getPosixFilePermissions(file.toPath())
        val forbidden = setOf(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        )
        assertTrue(perms.intersect(forbidden).isEmpty(), "voice.json must not be group/world accessible: $perms")
    }

    @Test
    fun `corrupt file loads as null instead of throwing`() {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("{not json")
        assertNull(VoiceAgentStorage.load(file))
        assertFalse(VoiceAgentStorage.keyPresent(file))
    }

    @Test
    fun `blank key does not count as present`() {
        val file = tempFile()
        VoiceAgentStorage.save(StoredVoiceConfig(openaiApiKey = "  "), file)
        assertFalse(VoiceAgentStorage.keyPresent(file))
    }
}
