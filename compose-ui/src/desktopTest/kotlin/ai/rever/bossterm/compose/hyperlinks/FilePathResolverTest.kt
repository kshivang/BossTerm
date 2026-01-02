package ai.rever.bossterm.compose.hyperlinks

import java.io.File
import kotlin.test.*

/**
 * Unit tests for FilePathResolver.
 * Tests path resolution, validation, and caching functionality.
 */
class FilePathResolverTest {

    private val homeDir = System.getProperty("user.home")
    private val tempDir = System.getProperty("java.io.tmpdir")
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val fileSeparator = File.separator

    @BeforeTest
    fun setup() {
        // Clear cache before each test
        FilePathResolver.clearCache()
    }

    // ======================== Path Resolution Tests ========================

    @Test
    fun testResolveAbsoluteUnixPath() {
        if (isWindows) return // Skip on Windows - Unix paths not applicable
        val path = "/usr/bin/ls"
        val resolved = FilePathResolver.resolvePath(path, null)
        assertNotNull(resolved)
        assertEquals("/usr/bin/ls", resolved.path)
    }

    @Test
    fun testResolveHomePath() {
        val path = "~/Documents"
        val resolved = FilePathResolver.resolvePath(path, null)
        assertNotNull(resolved)
        assertTrue(resolved.path.startsWith(homeDir))
        assertTrue(resolved.path.endsWith("Documents"))
    }

    @Test
    fun testResolveHomePathWithSubdirectory() {
        val path = "~/Documents/test/file.txt"
        val resolved = FilePathResolver.resolvePath(path, null)
        assertNotNull(resolved)
        // Use File to normalize path separators for cross-platform comparison
        val expected = File(homeDir, "Documents/test/file.txt").path
        assertEquals(expected, resolved.path)
    }

    @Test
    fun testResolveRelativePathWithWorkingDirectory() {
        val path = "./subdir/file.txt"
        val workingDir = File(tempDir, "testdir").absolutePath
        val resolved = FilePathResolver.resolvePath(path, workingDir)
        assertNotNull(resolved)
        // Check for normalized path component
        assertTrue(resolved.path.contains("subdir") && resolved.path.contains("file.txt"))
    }

    @Test
    fun testResolveParentRelativePath() {
        val path = "../sibling/file.txt"
        val workingDir = File(File(tempDir, "testdir"), "child").absolutePath
        val resolved = FilePathResolver.resolvePath(path, workingDir)
        assertNotNull(resolved)
        // Should resolve to parent/sibling/file.txt (canonical path)
        assertTrue(resolved.path.contains("sibling") && resolved.path.contains("file.txt"))
    }

    @Test
    fun testResolveRelativePathWithoutWorkingDirectory() {
        val path = "./subdir/file.txt"
        val resolved = FilePathResolver.resolvePath(path, null)
        // Should return null when no working directory provided for relative path
        assertNull(resolved)
    }

    @Test
    fun testResolveWindowsPath() {
        val path = "C:\\Users\\test\\file.txt"
        val resolved = FilePathResolver.resolvePath(path, null)
        assertNotNull(resolved)
        // On Unix systems, this creates a file with the literal path
        // On Windows, it would resolve correctly
    }

    @Test
    fun testResolveBlankPath() {
        val resolved = FilePathResolver.resolvePath("", null)
        assertNull(resolved)
    }

    @Test
    fun testResolveWhitespacePath() {
        val resolved = FilePathResolver.resolvePath("   ", null)
        assertNull(resolved)
    }

    @Test
    fun testResolveNonPathString() {
        // Should return null for strings that don't look like paths
        val resolved = FilePathResolver.resolvePath("hello world", null)
        assertNull(resolved)
    }

    // ======================== Path Validation Tests ========================

    @Test
    fun testResolveAndValidateExistingPath() {
        // /tmp should exist on Unix systems (may be symlinked to /private/tmp on macOS)
        val resolved = FilePathResolver.resolveAndValidate("/tmp", null)
        if (File("/tmp").exists()) {
            assertNotNull(resolved)
            // On macOS, /tmp is symlinked to /private/tmp, so canonical path may differ
            assertTrue(resolved.path.endsWith("tmp") || resolved.path.contains("tmp"))
        }
    }

    @Test
    fun testResolveAndValidateNonExistentPath() {
        val resolved = FilePathResolver.resolveAndValidate("/nonexistent/path/file.txt", null)
        assertNull(resolved)
    }

    @Test
    fun testResolveAndValidateHomeDirectory() {
        // Home directory should exist
        val resolved = FilePathResolver.resolveAndValidate("~/", null)
        assertNotNull(resolved)
        assertTrue(resolved.isDirectory)
    }

    @Test
    fun testExistsWithCaching() {
        val testFile = File(tempDir, "bossterm_test_${System.currentTimeMillis()}.txt")
        try {
            // File doesn't exist yet
            assertFalse(FilePathResolver.exists(testFile))

            // Create the file
            testFile.createNewFile()

            // Cache still says false (within TTL)
            // Note: This depends on timing, so we verify the method works
            val existsAfterCreate = FilePathResolver.exists(testFile)
            // After cache expires or on fresh check, should be true
            // For this test, we clear cache and check again
            FilePathResolver.clearCache()
            assertTrue(FilePathResolver.exists(testFile))
        } finally {
            testFile.delete()
        }
    }

    // ======================== URL Conversion Tests ========================

    @Test
    fun testToFileUrl() {
        val file = File("/tmp/test.txt")
        val url = FilePathResolver.toFileUrl(file)
        assertTrue(url.startsWith("file:"))
        assertTrue(url.contains("/tmp/test.txt"))
    }

    @Test
    fun testToFileUrlWithSpaces() {
        val file = File("/tmp/my file.txt")
        val url = FilePathResolver.toFileUrl(file)
        assertTrue(url.startsWith("file:"))
        // URL should encode spaces
        assertTrue(url.contains("my%20file.txt") || url.contains("my file.txt"))
    }

    // ======================== Quick Check Tests ========================

    @Test
    fun testLooksLikeUnixPath() {
        assertTrue(FilePathResolver.looksLikeUnixPath("/usr/bin/ls"))
        assertTrue(FilePathResolver.looksLikeUnixPath("Contains /path/here"))
        assertFalse(FilePathResolver.looksLikeUnixPath("https://example.com/path"))
        assertFalse(FilePathResolver.looksLikeUnixPath("no slash here"))
    }

    @Test
    fun testLooksLikeHomePath() {
        assertTrue(FilePathResolver.looksLikeHomePath("~/Documents"))
        assertTrue(FilePathResolver.looksLikeHomePath("Open ~/file.txt"))
        assertFalse(FilePathResolver.looksLikeHomePath("/home/user"))
        assertFalse(FilePathResolver.looksLikeHomePath("no home"))
    }

    @Test
    fun testLooksLikeRelativePath() {
        assertTrue(FilePathResolver.looksLikeRelativePath("./src/main.kt"))
        assertTrue(FilePathResolver.looksLikeRelativePath("../config.json"))
        assertTrue(FilePathResolver.looksLikeRelativePath("Open ./file"))
        assertFalse(FilePathResolver.looksLikeRelativePath("/absolute/path"))
        assertFalse(FilePathResolver.looksLikeRelativePath("no dots"))
    }

    @Test
    fun testLooksLikeWindowsPath() {
        assertTrue(FilePathResolver.looksLikeWindowsPath("C:\\Users\\test"))
        assertTrue(FilePathResolver.looksLikeWindowsPath("Found at D:\\Data\\file.txt"))
        assertFalse(FilePathResolver.looksLikeWindowsPath("/unix/path"))
        assertFalse(FilePathResolver.looksLikeWindowsPath("no colon backslash"))
    }

    // ======================== Edge Cases ========================

    @Test
    fun testResolvePathWithSpecialCharacters() {
        val path = "/tmp/file with spaces.txt"
        val resolved = FilePathResolver.resolvePath(path, null)
        assertNotNull(resolved)
        // On macOS, /tmp may resolve to /private/tmp
        assertTrue(resolved.path.contains("file with spaces.txt"))
    }

    @Test
    fun testResolvePathWithUnicode() {
        val path = "/tmp/文件.txt"
        val resolved = FilePathResolver.resolvePath(path, null)
        assertNotNull(resolved)
        assertTrue(resolved.path.contains("文件"))
    }

    @Test
    fun testCacheClear() {
        // Add something to cache
        FilePathResolver.exists(File("/tmp"))
        // Clear cache
        FilePathResolver.clearCache()
        // No way to directly verify cache is empty, but method should not throw
    }
}
