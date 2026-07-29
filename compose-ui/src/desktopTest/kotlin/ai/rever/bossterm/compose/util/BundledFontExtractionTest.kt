package ai.rever.bossterm.compose.util

import ai.rever.bossterm.compose.daemon.BossTermPaths
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The point of the cache is that a launch does NOT hand the OS a brand-new multi-megabyte file
 * (see the dyld loader-lock note in AGENTS.md), so "the second launch writes nothing" is the
 * behavior worth pinning down.
 */
class BundledFontExtractionTest {

    private val resource = "fonts/MesloLGSNF-Regular.ttf"
    private lateinit var dir: File
    private var previousDir: String? = null

    @BeforeTest
    fun redirectBossTermDir() {
        previousDir = System.getProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        dir = java.nio.file.Files.createTempDirectory("bossterm-font-test").toFile()
        System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, dir.absolutePath)
    }

    @AfterTest
    fun restore() {
        previousDir?.let { System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, it) }
            ?: System.clearProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        dir.deleteRecursively()
    }

    @Test
    fun `extracts into the bossterm dir under a content-addressed name`() {
        val file = assertNotNull(extractBundledFont(resource))

        assertEquals(File(dir, "fonts").absolutePath, file.parentFile.absolutePath)
        assertTrue(file.isFile && file.length() > 0)
        // <base>-<12 hex>.ttf — the hash is what invalidates the copy when the bundled font changes.
        assertTrue(
            Regex("""^MesloLGSNF-Regular-[0-9a-f]{12}\.ttf$""").matches(file.name),
            "unexpected cache file name: ${file.name}"
        )
    }

    @Test
    fun `second launch reuses the file instead of rewriting it`() {
        val first = assertNotNull(extractBundledFont(resource))
        val bytes = first.readBytes()
        // Back-date well past any filesystem timestamp granularity: at 1-second resolution a
        // same-second rewrite would compare equal and pass while doing exactly what we forbid.
        val backdated = System.currentTimeMillis() - 60_000
        assertTrue(first.setLastModified(backdated), "could not back-date the cache file")

        val second = assertNotNull(extractBundledFont(resource))

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(backdated, second.lastModified(), "cache was rewritten on the second call")
        assertTrue(bytes.contentEquals(second.readBytes()))
        // Exactly one copy, and no temp left behind by the publish.
        assertEquals(
            listOf(first.name),
            File(dir, "fonts").listFiles()!!.map { it.name }.sorted()
        )
    }

    @Test
    fun `publishing prunes a superseded copy and an orphaned temp`() {
        val current = assertNotNull(extractBundledFont(resource))
        val fonts = File(dir, "fonts")
        // A previous version's copy, and a temp orphaned by a killed write (back-dated past the
        // age guard that protects a concurrent launch's in-flight temp).
        val superseded = File(fonts, "MesloLGSNF-Regular-000000000000.ttf").apply { writeBytes(ByteArray(16)) }
        val orphanTemp = File(fonts, ".MesloLGSNF-Regular999.tmp").apply {
            writeBytes(ByteArray(16))
            setLastModified(System.currentTimeMillis() - 7_200_000)
        }
        val freshTemp = File(fonts, ".MesloLGSNF-Regular111.tmp").apply { writeBytes(ByteArray(16)) }
        // Force a republish: truncating is what makes the length check miss.
        current.writeBytes(ByteArray(8))

        assertNotNull(extractBundledFont(resource))

        assertTrue(!superseded.exists(), "old-hash copy was left behind")
        assertTrue(!orphanTemp.exists(), "orphaned temp was left behind")
        assertTrue(freshTemp.exists(), "a concurrent launch's in-flight temp must not be deleted")
    }

    @Test
    fun `an unusable cache dir still yields a font file`() {
        // Point the store at a regular file so mkdirs() cannot succeed.
        val notADir = File.createTempFile("bossterm-not-a-dir", "")
        System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, notADir.absolutePath)
        try {
            val file = assertNotNull(extractBundledFont(resource), "fallback must still produce a font")
            assertTrue(file.isFile && file.length() > 0)
            assertTrue(
                file.parentFile.absolutePath != File(notADir, "fonts").absolutePath,
                "expected the temp fallback, not the unusable cache path"
            )
        } finally {
            notADir.delete()
        }
    }

    @Test
    fun `a truncated copy is republished rather than handed to Skia`() {
        val original = assertNotNull(extractBundledFont(resource))
        val full = original.readBytes()
        original.writeBytes(full.copyOfRange(0, full.size / 3))

        val repaired = assertNotNull(extractBundledFont(resource))

        assertEquals(original.absolutePath, repaired.absolutePath)
        assertTrue(full.contentEquals(repaired.readBytes()), "truncated cache was not repaired")
    }

    @Test
    fun `a missing resource reports null rather than an empty font file`() {
        assertNull(extractBundledFont("fonts/DefinitelyNotBundled-Regular.ttf"))
    }
}
