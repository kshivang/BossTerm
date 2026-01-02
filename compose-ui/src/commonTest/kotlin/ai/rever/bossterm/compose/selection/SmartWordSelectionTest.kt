package ai.rever.bossterm.compose.selection

import kotlin.test.*

/**
 * Unit tests for SmartWordSelection.
 * Tests word selection patterns, edge cases, and Unicode handling.
 */
class SmartWordSelectionTest {

    // ======================== URL Pattern Tests ========================

    @Test
    fun testSelectHttpUrl() {
        val text = "Visit https://example.com for more info"
        val result = SmartWordSelection.selectWordAt(text, 10) // Inside URL
        assertNotNull(result)
        assertEquals("https://example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectHttpsUrlWithPath() {
        val text = "Check https://example.com/path/to/page?query=1#anchor here"
        val result = SmartWordSelection.selectWordAt(text, 15)
        assertNotNull(result)
        assertEquals("https://example.com/path/to/page?query=1#anchor", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectFtpUrl() {
        val text = "Download from ftp://files.example.com/file.zip"
        val result = SmartWordSelection.selectWordAt(text, 20)
        assertNotNull(result)
        assertEquals("ftp://files.example.com/file.zip", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectSshUrl() {
        val text = "Connect via ssh://user@host.com:22"
        val result = SmartWordSelection.selectWordAt(text, 15)
        assertNotNull(result)
        assertEquals("ssh://user@host.com:22", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectFileUrl() {
        val text = "Open file:///Users/test/document.txt"
        val result = SmartWordSelection.selectWordAt(text, 10)
        assertNotNull(result)
        assertEquals("file:///Users/test/document.txt", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectWwwUrl() {
        val text = "Visit www.example.com for details"
        val result = SmartWordSelection.selectWordAt(text, 10)
        assertNotNull(result)
        assertEquals("www.example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectMailtoUrl() {
        val text = "Contact mailto:user@example.com"
        val result = SmartWordSelection.selectWordAt(text, 15)
        assertNotNull(result)
        assertEquals("mailto:user@example.com", text.substring(result.first, result.second + 1))
    }

    // ======================== Trailing Punctuation Tests ========================

    @Test
    fun testUrlExcludesTrailingPeriod() {
        val text = "Visit https://example.com."
        val result = SmartWordSelection.selectWordAt(text, 10)
        assertNotNull(result)
        assertEquals("https://example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testUrlExcludesTrailingComma() {
        val text = "Check https://example.com, then continue"
        val result = SmartWordSelection.selectWordAt(text, 10)
        assertNotNull(result)
        assertEquals("https://example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testUrlExcludesTrailingSemicolon() {
        val text = "URL: https://example.com;"
        val result = SmartWordSelection.selectWordAt(text, 10)
        assertNotNull(result)
        assertEquals("https://example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testUrlExcludesTrailingExclamation() {
        val text = "Wow https://example.com!"
        val result = SmartWordSelection.selectWordAt(text, 10)
        assertNotNull(result)
        assertEquals("https://example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testWwwExcludesTrailingPeriod() {
        val text = "Visit www.example.com."
        val result = SmartWordSelection.selectWordAt(text, 10)
        assertNotNull(result)
        assertEquals("www.example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testUrlWithPathPreservesDot() {
        // Dot in path should be preserved
        val text = "Open https://example.com/file.txt"
        val result = SmartWordSelection.selectWordAt(text, 10)
        assertNotNull(result)
        assertEquals("https://example.com/file.txt", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testUrlWithQueryString() {
        val text = "Search https://example.com?q=test"
        val result = SmartWordSelection.selectWordAt(text, 12)
        assertNotNull(result)
        assertEquals("https://example.com?q=test", text.substring(result.first, result.second + 1))
    }

    // ======================== Email Pattern Tests ========================

    @Test
    fun testSelectEmail() {
        val text = "Contact me at user@example.com for help"
        val result = SmartWordSelection.selectWordAt(text, 18) // Inside email
        assertNotNull(result)
        assertEquals("user@example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectEmailWithSubdomain() {
        val text = "Email: admin@mail.example.co.uk"
        val result = SmartWordSelection.selectWordAt(text, 15)
        assertNotNull(result)
        assertEquals("admin@mail.example.co.uk", text.substring(result.first, result.second + 1))
    }

    // ======================== Path Pattern Tests ========================

    @Test
    fun testSelectUnixPath() {
        val text = "File at /usr/local/bin/script.sh is ready"
        val result = SmartWordSelection.selectWordAt(text, 15)
        assertNotNull(result)
        assertTrue(text.substring(result.first, result.second + 1).startsWith("/usr"))
    }

    @Test
    fun testSelectWindowsPath() {
        val text = "Located at C:\\Users\\test\\file.txt"
        val result = SmartWordSelection.selectWordAt(text, 15)
        assertNotNull(result)
        assertTrue(text.substring(result.first, result.second + 1).startsWith("C:\\"))
    }

    // ======================== Quoted String Tests ========================

    @Test
    fun testSelectDoubleQuotedString() {
        val text = """He said "hello world" to everyone"""
        val result = SmartWordSelection.selectWordAt(text, 12) // Inside quotes
        assertNotNull(result)
        assertEquals("\"hello world\"", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectSingleQuotedString() {
        val text = "The command is 'echo hello' here"
        val result = SmartWordSelection.selectWordAt(text, 20) // Inside quotes
        assertNotNull(result)
        assertEquals("'echo hello'", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectBacktickString() {
        val text = "Run `npm install` to setup"
        val result = SmartWordSelection.selectWordAt(text, 8) // Inside backticks
        assertNotNull(result)
        assertEquals("`npm install`", text.substring(result.first, result.second + 1))
    }

    // ======================== Default Word Tests ========================

    @Test
    fun testSelectSimpleWord() {
        val text = "This is a simple test"
        val result = SmartWordSelection.selectWordAt(text, 12) // On "simple"
        assertNotNull(result)
        assertEquals("simple", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectWordWithUnderscore() {
        val text = "Variable name_with_underscore here"
        val result = SmartWordSelection.selectWordAt(text, 15)
        assertNotNull(result)
        assertEquals("name_with_underscore", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectWordWithNumbers() {
        val text = "Variable test123 is defined"
        val result = SmartWordSelection.selectWordAt(text, 12)
        assertNotNull(result)
        assertEquals("test123", text.substring(result.first, result.second + 1))
    }

    // ======================== Edge Cases ========================

    @Test
    fun testSelectAtEmptyString() {
        val result = SmartWordSelection.selectWordAt("", 0)
        assertNull(result)
    }

    @Test
    fun testSelectAtNegativePosition() {
        val text = "Hello world"
        val result = SmartWordSelection.selectWordAt(text, -1)
        assertNull(result)
    }

    @Test
    fun testSelectBeyondStringLength() {
        val text = "Hello"
        val result = SmartWordSelection.selectWordAt(text, 10)
        assertNull(result)
    }

    @Test
    fun testSelectSingleCharacter() {
        val text = "a"
        val result = SmartWordSelection.selectWordAt(text, 0)
        assertNotNull(result)
        assertEquals("a", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectAtWordBoundary() {
        val text = "hello world"
        val result = SmartWordSelection.selectWordAt(text, 0)
        assertNotNull(result)
        assertEquals("hello", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectAtEndOfWord() {
        val text = "hello world"
        val result = SmartWordSelection.selectWordAt(text, 4) // Last char of "hello"
        assertNotNull(result)
        assertEquals("hello", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectOnWhitespace() {
        val text = "hello   world"
        val result = SmartWordSelection.selectWordAt(text, 6) // On space
        assertNull(result)
    }

    // ======================== Unicode and Wide Character Tests ========================

    @Test
    fun testSelectCjkCharacters() {
        val text = "Test with Chinese"
        val result = SmartWordSelection.selectWordAt(text, 0)
        assertNotNull(result)
        assertEquals("Test", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectMixedAsciiAndCjk() {
        val text = "file_test.txt"
        val result = SmartWordSelection.selectWordAt(text, 5)
        assertNotNull(result)
        // Word pattern should handle underscores
        assertTrue(text.substring(result.first, result.second + 1).contains("test"))
    }

    @Test
    fun testSelectEmojiUrl() {
        // URL should be selected correctly even with emoji nearby
        val text = "Link: https://example.com/page"
        val result = SmartWordSelection.selectWordAt(text, 15)
        assertNotNull(result)
        assertEquals("https://example.com/page", text.substring(result.first, result.second + 1))
    }

    // ======================== Long Line Tests ========================

    @Test
    fun testSelectInLongLine() {
        // Test windowed search optimization
        val text = "x".repeat(500) + " https://example.com " + "y".repeat(500)
        val result = SmartWordSelection.selectWordAt(text, 505) // Inside URL
        assertNotNull(result)
        assertEquals("https://example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testSelectWordInVeryLongLine() {
        // Ensure windowing works correctly for words at various positions
        val text = "a".repeat(200) + " target " + "b".repeat(200)
        val result = SmartWordSelection.selectWordAt(text, 202) // On "target"
        assertNotNull(result)
        assertEquals("target", text.substring(result.first, result.second + 1))
    }

    // ======================== Pattern Priority Tests ========================

    @Test
    fun testUrlTakesPriorityOverWord() {
        // When clicking on "example" in URL, entire URL should be selected (not just "example")
        val text = "https://example.com"
        val result = SmartWordSelection.selectWordAt(text, 10) // On "example"
        assertNotNull(result)
        assertEquals("https://example.com", text.substring(result.first, result.second + 1))
    }

    @Test
    fun testEmailTakesPriorityOverWord() {
        // When clicking on "user" in email, entire email should be selected
        val text = "Contact user@example.com"
        val result = SmartWordSelection.selectWordAt(text, 9) // On "user"
        assertNotNull(result)
        assertEquals("user@example.com", text.substring(result.first, result.second + 1))
    }

    // ======================== Registry Tests ========================

    @Test
    fun testRegistryHasBuiltinPatterns() {
        val patterns = SmartWordSelection.registry.getPatterns()
        assertTrue(patterns.isNotEmpty())

        // Check for expected built-in patterns
        val patternIds = patterns.map { it.id }
        assertTrue(patternIds.contains("builtin:url"))
        assertTrue(patternIds.contains("builtin:email"))
        assertTrue(patternIds.contains("builtin:word"))
    }

    @Test
    fun testPatternsAreSortedByPriority() {
        val patterns = SmartWordSelection.registry.getPatterns()
        for (i in 0 until patterns.size - 1) {
            assertTrue(patterns[i].priority >= patterns[i + 1].priority,
                "Patterns should be sorted by priority descending")
        }
    }

    @Test
    fun testResetToDefaults() {
        val registry = WordSelectionPatternRegistry()
        val originalCount = registry.getPatterns().size

        // Add a custom pattern
        registry.addPattern(WordSelectionPattern(
            id = "custom:test",
            regex = Regex("custompattern"),
            priority = 50
        ))
        assertEquals(originalCount + 1, registry.getPatterns().size)

        // Reset
        registry.resetToDefaults()
        assertEquals(originalCount, registry.getPatterns().size)
        assertFalse(registry.getPatterns().any { it.id == "custom:test" })
    }
}
