package ai.rever.bossterm.compose.hyperlinks

import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.terminal.ArrayTerminalDataStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.CharBuffer
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RightAlignedTableHyperlinksTest {
    private val lines = javaClass.getResourceAsStream("/hyperlinks/codex-right-aligned-table.txt")!!
        .bufferedReader().use { it.readLines() }
    private val url = "https://github.com/risa-labs-inc/BossConsole/pull/1671"

    private fun assertEveryFragment(buffer: TerminalTextBuffer) {
        for (row in 2..6) {
            val links = HyperlinkDetector.detectForBufferRow(buffer.createIncrementalSnapshot(), row, buffer.width, null, false)
            val link = links.single()
            assertEquals(url, link.url, "complete target on fragment row $row")
            assertEquals((2..6).toSet(), link.rowSpans.keys)
            for ((r, span) in link.rowSpans) {
                assertTrue(link.containsPosition(span.first, r))
                assertTrue(link.containsPosition(span.second - 1, r))
                assertFalse(link.containsPosition(span.first - 1, r))
                assertFalse(link.containsPosition(21, r), "other table column must not be clickable")
            }
        }
    }

    @Test
    fun capturedRightAlignedFragmentsUseHeavyHeaderAndLightRowRule() {
        val buffer = TerminalTextBuffer(100, 20, StyleState(), 100)
        lines.forEachIndexed { row, text ->
            buffer.writeString(0, row + 1, CharBuffer(text.toCharArray(), 0, text.length))
        }
        assertEveryFragment(buffer)
    }

    @Test
    fun capturedTextReplayedAsAnsiOutputKeepsBothRuleGeometryAndClickTargets() {
        val style = StyleState()
        val buffer = TerminalTextBuffer(100, 20, style, 100)
        val terminal = BossTerminal(HeadlessTerminalDisplay(), buffer, style)
        // Replay terminal output, including colour/style transitions, through the real parser.
        val output = lines.joinToString("\r\n") { "\u001b[34;4m$it\u001b[0m" }
        val emulator = BossEmulator(ArrayTerminalDataStream(output.toCharArray()), terminal)
        while (emulator.hasNext()) emulator.next()
        assertEveryFragment(buffer)
    }

    @Test
    fun malformedOrMisalignedFragmentsKeepTheOriginalBoundary() {
        for (replacement in listOf("       unrelated prose", "       (new-link)", "    github.com/")) {
            val buffer = TerminalTextBuffer(100, 20, StyleState(), 100)
            lines.mapIndexed { row, text -> if (row == 3) replacement else text }.forEachIndexed { row, text ->
                buffer.writeString(0, row + 1, CharBuffer(text.toCharArray(), 0, text.length))
            }
            assertTrue(HyperlinkDetector.detectForBufferRow(buffer.createIncrementalSnapshot(), 2, buffer.width, null, false)
                .none { it.url == url })
        }
    }
}
