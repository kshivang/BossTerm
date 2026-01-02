package ai.rever.bossterm.compose.hyperlinks

import java.io.File
import kotlin.test.*

/**
 * Unit tests for HyperlinkDetector file path detection.
 * Tests path pattern matching and validation with working directory.
 */
class HyperlinkDetectorPathTest {

    private val tempDir = System.getProperty("java.io.tmpdir")
    private val homeDir = System.getProperty("user.home")
    private lateinit var testFile: File
    private lateinit var testDir: File

    @BeforeTest
    fun setup() {
        // Clear path cache
        FilePathResolver.clearCache()

        // Create test file and directory
        testDir = File(tempDir, "bossterm_test_${System.currentTimeMillis()}")
        testDir.mkdirs()
        testFile = File(testDir, "test.txt")
        testFile.createNewFile()
    }

    @AfterTest
    fun cleanup() {
        testFile.delete()
        testDir.delete()
    }

    // ======================== Absolute Path Detection Tests ========================

    @Test
    fun testDetectAbsoluteUnixPath() {
        // /tmp should exist on Unix systems
        if (!File("/tmp").exists()) return

        val text = "Check the file at /tmp for logs"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        val pathLink = hyperlinks.find { it.url.contains("/tmp") }
        assertNotNull(pathLink, "Should detect /tmp path")
        assertTrue(pathLink.url.startsWith("file:"))
    }

    @Test
    fun testDetectAbsolutePathWithFilename() {
        val text = "Open ${testFile.absolutePath} now"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        val pathLink = hyperlinks.find { it.url.contains("test.txt") }
        assertNotNull(pathLink, "Should detect absolute path with filename")
    }

    @Test
    fun testNonExistentAbsolutePathNotDetected() {
        val text = "Open /nonexistent/path/that/does/not/exist.txt"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        val pathLink = hyperlinks.find { it.url.contains("nonexistent") }
        assertNull(pathLink, "Should NOT detect non-existent path")
    }

    // ======================== Home Path Detection Tests ========================

    @Test
    fun testDetectHomePath() {
        // Test with a known existing subdirectory under home
        // The pattern requires at least one character after ~/
        val homeSubdir = File(homeDir, ".bossterm")
        if (!homeSubdir.exists()) {
            homeSubdir.mkdirs()
        }

        val text = "Your config is at ~/.bossterm here"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        val homeLink = hyperlinks.find { it.url.contains(".bossterm") }
        assertNotNull(homeLink, "Should detect home path ~/.bossterm")
        assertTrue(homeLink.url.startsWith("file:"), "Should be file:// URL")
    }

    @Test
    fun testDetectHomePathWithSubdirectory() {
        // Create a known subdirectory in home
        val homeSubdir = File(homeDir, ".bossterm")
        if (homeSubdir.exists() || homeSubdir.mkdirs()) {
            try {
                val text = "Config at ~/.bossterm here"
                val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

                val configLink = hyperlinks.find { it.url.contains(".bossterm") }
                assertNotNull(configLink, "Should detect ~/.bossterm path")
            } finally {
                // Don't delete .bossterm if it existed before
            }
        }
    }

    // ======================== Relative Path Detection Tests ========================

    @Test
    fun testDetectRelativePathWithWorkingDirectory() {
        // Create ./subfile.txt in testDir
        val subFile = File(testDir, "subfile.txt")
        subFile.createNewFile()
        try {
            val text = "Edit ./subfile.txt"
            val hyperlinks = HyperlinkDetector.detectHyperlinks(
                text, 0, testDir.absolutePath, true
            )

            val relLink = hyperlinks.find { it.url.contains("subfile.txt") }
            assertNotNull(relLink, "Should detect relative path with working directory")
        } finally {
            subFile.delete()
        }
    }

    @Test
    fun testRelativePathWithoutWorkingDirectoryNotDetected() {
        val text = "Edit ./some/relative/path.txt"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        // Without working directory, relative paths can't be validated
        val relLink = hyperlinks.find { it.url.contains("relative") }
        assertNull(relLink, "Should NOT detect relative path without working directory")
    }

    @Test
    fun testDetectParentRelativePath() {
        // Create parent structure
        val childDir = File(testDir, "child")
        childDir.mkdirs()
        val siblingFile = File(testDir, "sibling.txt")
        siblingFile.createNewFile()
        try {
            val text = "Open ../sibling.txt"
            val hyperlinks = HyperlinkDetector.detectHyperlinks(
                text, 0, childDir.absolutePath, true
            )

            val parentLink = hyperlinks.find { it.url.contains("sibling.txt") }
            assertNotNull(parentLink, "Should detect ../sibling.txt relative path")
        } finally {
            siblingFile.delete()
            childDir.delete()
        }
    }

    // ======================== detectFilePaths Setting Tests ========================

    @Test
    fun testDetectFilePathsDisabled() {
        val text = "Check /tmp for logs"
        if (!File("/tmp").exists()) return

        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, false)

        val pathLink = hyperlinks.find { it.url.contains("/tmp") }
        assertNull(pathLink, "Should NOT detect paths when detectFilePaths=false")
    }

    @Test
    fun testUrlsStillDetectedWhenPathsDisabled() {
        val text = "Visit https://example.com and /tmp"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, false)

        val urlLink = hyperlinks.find { it.url.contains("example.com") }
        assertNotNull(urlLink, "URLs should still be detected when detectFilePaths=false")

        val pathLink = hyperlinks.find { it.url.contains("/tmp") && it.url.startsWith("file:") }
        assertNull(pathLink, "Paths should NOT be detected when detectFilePaths=false")
    }

    // ======================== Priority and Overlap Tests ========================

    @Test
    fun testUrlTakesPriorityOverPath() {
        // URL patterns have higher priority than path patterns
        val text = "Visit https://example.com/path/to/page"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        assertEquals(1, hyperlinks.size, "Should detect exactly one hyperlink")
        assertTrue(hyperlinks[0].url.startsWith("https://"), "Should be URL, not path")
    }

    @Test
    fun testFileUrlNotDuplicatedAsPath() {
        val text = "Open file:///tmp/test.txt"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        // Should detect file:// URL, not duplicate as path
        assertEquals(1, hyperlinks.size, "Should detect exactly one hyperlink")
        assertTrue(hyperlinks[0].url.startsWith("file:"), "Should be file URL")
    }

    // ======================== Pattern Matching Tests ========================

    @Test
    fun testPathAtStartOfLine() {
        if (!File("/tmp").exists()) return

        val text = "/tmp is the temp directory"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        assertFalse(hyperlinks.isEmpty(), "Should detect path at start of line")
    }

    @Test
    fun testPathInQuotes() {
        if (!File("/tmp").exists()) return

        val text = """File is at "/tmp" here"""
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        val pathLink = hyperlinks.find { it.url.contains("/tmp") }
        assertNotNull(pathLink, "Should detect path in quotes")
    }

    @Test
    fun testPathAfterColon() {
        if (!File("/tmp").exists()) return

        val text = "Location: /tmp"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        val pathLink = hyperlinks.find { it.url.contains("/tmp") }
        assertNotNull(pathLink, "Should detect path after colon")
    }

    @Test
    fun testMultiplePathsOnLine() {
        if (!File("/tmp").exists()) return

        val text = "Copy /tmp to /var if exists"
        val hyperlinks = HyperlinkDetector.detectHyperlinks(text, 0, null, true)

        // Both /tmp and /var might be detected if they exist
        val tmpLink = hyperlinks.find { it.url.contains("/tmp") }
        assertNotNull(tmpLink, "Should detect /tmp")

        if (File("/var").exists()) {
            val varLink = hyperlinks.find { it.url.contains("/var") }
            assertNotNull(varLink, "Should detect /var if it exists")
        }
    }

    // ======================== Registry Tests ========================

    @Test
    fun testPathPatternsRegistered() {
        val patterns = HyperlinkDetector.registry.getPatterns()
        val patternIds = patterns.map { it.id }

        assertTrue(patternIds.contains("builtin:path-home"), "Should have home path pattern")
        assertTrue(patternIds.contains("builtin:path-relative"), "Should have relative path pattern")
        assertTrue(patternIds.contains("builtin:path-unix"), "Should have Unix path pattern")
        assertTrue(patternIds.contains("builtin:path-windows"), "Should have Windows path pattern")
    }

    @Test
    fun testPathPatternsHavePathValidator() {
        val patterns = HyperlinkDetector.registry.getPatterns()

        val homePath = patterns.find { it.id == "builtin:path-home" }
        assertNotNull(homePath?.pathValidator, "Home path should have pathValidator")

        val relativePath = patterns.find { it.id == "builtin:path-relative" }
        assertNotNull(relativePath?.pathValidator, "Relative path should have pathValidator")

        val unixPath = patterns.find { it.id == "builtin:path-unix" }
        assertNotNull(unixPath?.pathValidator, "Unix path should have pathValidator")
    }

    @Test
    fun testPathPatternsPriorityOrder() {
        val patterns = HyperlinkDetector.registry.getPatterns()

        val homePath = patterns.find { it.id == "builtin:path-home" }
        val relativePath = patterns.find { it.id == "builtin:path-relative" }
        val unixPath = patterns.find { it.id == "builtin:path-unix" }
        val httpPattern = patterns.find { it.id == "builtin:http" }

        // HTTP should have higher priority than path patterns
        assertTrue(
            (httpPattern?.priority ?: 0) > (homePath?.priority ?: 0),
            "HTTP should have higher priority than home path"
        )

        // Home path should have higher priority than Unix path (to match ~/... first)
        assertTrue(
            (homePath?.priority ?: 0) > (unixPath?.priority ?: 0),
            "Home path should have higher priority than Unix path"
        )
    }

    // ======================== canContainHyperlink Tests ========================

    @Test
    fun testCanContainHyperlinkWithPath() {
        assertTrue(
            HyperlinkDetector.canContainHyperlink("/usr/bin/ls", true),
            "Should return true for Unix path"
        )
        assertTrue(
            HyperlinkDetector.canContainHyperlink("~/Documents", true),
            "Should return true for home path"
        )
        assertTrue(
            HyperlinkDetector.canContainHyperlink("./src/main.kt", true),
            "Should return true for relative path"
        )
        assertTrue(
            HyperlinkDetector.canContainHyperlink("C:\\Users\\test", true),
            "Should return true for Windows path"
        )
    }

    @Test
    fun testCanContainHyperlinkWithPathsDisabled() {
        assertFalse(
            HyperlinkDetector.canContainHyperlink("/usr/bin/ls", false),
            "Should return false for Unix path when paths disabled"
        )
        assertTrue(
            HyperlinkDetector.canContainHyperlink("https://example.com", false),
            "Should still return true for URLs when paths disabled"
        )
    }
}
