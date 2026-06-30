package ai.rever.bossterm.compose.settings

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-"process" settings.json safety: with the daemon enabled, two JVMs (GUI + daemon) hold
 * independent SettingsManagers writing the same file. These tests use two SettingsManager instances
 * over one path to stand in for the two processes, exercising the field-merge-onto-disk write path
 * (so one instance's single-field write doesn't revert another's) and the unique-temp atomic write
 * (so concurrent writers never publish a torn file).
 */
class SettingsManagerCrossProcessTest {

    private fun tempSettingsPath(): String =
        File(Files.createTempDirectory("bossterm-settings").toFile(), "settings.json").absolutePath

    private fun readFromDisk(path: String): TerminalSettings =
        Json { ignoreUnknownKeys = true }.decodeFromString(File(path).readText())

    @Test
    fun `single-field update merges onto disk and does not revert another instance's field`() {
        val path = tempSettingsPath()
        val gui = SettingsManager.withCustomPath(path)
        val daemon = SettingsManager.withCustomPath(path)

        // The "daemon" enables MCP — persisted to disk. The GUI's in-memory copy is unaware (no reload).
        daemon.updateSetting { copy(mcpEnabled = true) }
        // The GUI then changes an UNRELATED field from its stale snapshot (mcpEnabled still false).
        gui.updateSetting { copy(sessionSharingEnabled = true) }

        val onDisk = readFromDisk(path)
        assertTrue(onDisk.sessionSharingEnabled, "GUI's own change must persist")
        assertTrue(onDisk.mcpEnabled, "daemon's mcpEnabled must NOT be reverted by the GUI's unrelated update")
    }

    @Test
    fun `mergeChangedFields merges onto the on-disk state, not the stale in-memory snapshot`() {
        val path = tempSettingsPath()
        val gui = SettingsManager.withCustomPath(path)
        val daemon = SettingsManager.withCustomPath(path)

        val baseline = gui.settings.value // GUI's snapshot before the daemon writes
        daemon.updateSetting { copy(mcpEnabled = true) }
        // Settings-window save path: only fontSize changed by the user.
        gui.mergeChangedFields(baseline = baseline, edited = baseline.copy(fontSize = 20f))

        val onDisk = readFromDisk(path)
        assertEquals(20f, onDisk.fontSize, "user's changed field must persist")
        assertTrue(onDisk.mcpEnabled, "daemon's mcpEnabled must survive the GUI's debounced merge save")
    }

    @Test
    fun `concurrent writers never publish a torn settings file`() {
        val path = tempSettingsPath()
        val a = SettingsManager.withCustomPath(path)
        val b = SettingsManager.withCustomPath(path)
        val readerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)

        // A reader continuously parses the published file; with the unique-temp + atomic rename it must
        // ALWAYS see a whole, parseable file (never a half-written / interleaved one).
        val reader = thread {
            while (!stop.get() && readerError.get() == null) {
                runCatching { if (File(path).exists()) readFromDisk(path) }
                    .onFailure { readerError.set(it) }
            }
        }
        val writers = (0 until 6).map { w ->
            thread {
                repeat(60) { i ->
                    val mgr = if (w % 2 == 0) a else b
                    mgr.updateSetting { copy(fontSize = (10 + (i % 12)).toFloat()) }
                }
            }
        }
        writers.forEach { it.join() }
        stop.set(true)
        reader.join()

        assertTrue(readerError.get() == null, "settings.json must never read as torn during concurrent writes: ${readerError.get()}")
        // And the final file is still valid.
        runCatching { readFromDisk(path) }.getOrElse { throw AssertionError("final settings file is corrupt", it) }
    }
}
