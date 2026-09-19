package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.nio.file.Files
import kotlin.test.*

class FileHostCapabilityTest {
    @Test
    fun `capability matches supported host platform`() {
        val supported = ShellCustomizationUtils.isMacOS() || ShellCustomizationUtils.isLinux()
        assertEquals(supported, RemoteFileStore.supported())
        assertEquals(supported, RemoteFileStore.available)
        if (!supported) {
            val root = Files.createTempDirectory("unsupported-file-host")
            try {
                val error = assertFailsWith<IllegalStateException> { FileDirectory.open(root) }
                assertEquals("Secure remote file access is unavailable on this filesystem", error.message)
            } finally { root.toFile().deleteRecursively() }
        }
    }
}
