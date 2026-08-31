package ai.rever.bossterm.compose.debug

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.drainTerminalEmulator
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Replay a recorded PTY byte stream into a headless terminal and read the grid back.
 *
 * Why this exists: a redraw glitch in a TUI is intermittent, and a screenshot of one is
 * evidence that something is wrong but not evidence of what. The byte stream that produced
 * it, on the other hand, is a deterministic input - the same bytes must always produce the
 * same grid. So a recording turns "sometimes the wrapped line garbles" into a test.
 *
 * This matters most for applications that redraw DIFFERENTIALLY. Claude Code, for one,
 * brackets each frame in DEC 2026 and then rewrites only the characters that changed, using
 * absolute column addressing and no line clears:
 *
 *     CSI 35 C   "th"   CSI 39 G   "nking more with xhigh ef"   CSI 64 G   "ort"
 *
 * It is diffing against its own model of our grid. One cell of disagreement anywhere becomes
 * permanent visible corruption, because nothing ever repaints the line to resync - and it
 * surfaces later, somewhere unrelated to where it started. Comparing a replayed grid against
 * the expected one is how that divergence gets localised to the chunk that caused it.
 *
 * Recordings come from `BOSSTERM_PTY_LOG=1`, which writes the same escaped format this
 * parser reverses.
 */
object PtyReplay {

    data class Chunk(val source: String, val data: String)

    private val LINE = Regex("""^\[[^]]*] (PTY>|USR<|EMU!|LOG#) (.*)$""")

    /**
     * Reverse [DebugDataCollector]'s file format.
     *
     * Only `PTY>` lines are terminal output; the others are this side's own writes and log
     * noise, and feeding them back would replay input the shell never saw.
     */
    fun parseLog(text: String): List<Chunk> =
        text.lineSequence()
            .mapNotNull { LINE.find(it) }
            .map { Chunk(it.groupValues[1], unescape(it.groupValues[2])) }
            .toList()

    /** Inverse of the collector's escaper. See its comment for why `\\` must come first. */
    fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.lastIndex) {
                out.append(c); i++; continue
            }
            when (val next = s[i + 1]) {
                '\\' -> { out.append('\\'); i += 2 }
                'e' -> { out.append('\u001B'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'x' -> {
                    val hex = s.substring(i + 2, minOf(i + 4, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 2) {
                        out.append(code.toChar()); i += 4
                    } else {
                        out.append(c); i++
                    }
                }
                else -> { out.append(c).append(next); i += 2 }
            }
        }
        return out.toString()
    }

    /**
     * Feed [chunks] through a real emulator at [width] x [height] and return the visible
     * grid, one string per row, trailing blanks trimmed.
     *
     * Chunk boundaries are preserved rather than concatenated: the batch begin/end they
     * drive is part of what is being reproduced, and a CSI split across two reads is
     * exactly the kind of thing a recording should be able to re-expose.
     */
    fun replay(chunks: List<Chunk>, width: Int, height: Int): List<String> {
        val display = ComposeTerminalDisplay()
        val styleState = StyleState()
        val textBuffer = TerminalTextBuffer(width, height, styleState)
        val terminal = BossTerminal(display, textBuffer, styleState)
        val dataStream = BlockingTerminalDataStream()
        val emulator = BossEmulator(dataStream, terminal)

        dataStream.onChunkStart = textBuffer::beginBatch
        dataStream.onChunkEnd = textBuffer::endBatch

        val executor = Executors.newSingleThreadExecutor()
        val drain = executor.submit {
            drainTerminalEmulator(
                emulator = emulator,
                dataStream = dataStream,
                terminal = terminal,
                shouldContinue = { true },
            )
        }
        try {
            chunks.filter { it.source == "PTY>" }.forEach { dataStream.append(it.data) }
            dataStream.close()
            drain.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        textBuffer.lock()
        try {
            return (0 until height).map { row ->
                textBuffer.getLine(row).text.trimEnd()
            }
        } finally {
            textBuffer.unlock()
        }
    }

    /** Convenience: parse and replay in one step. */
    fun replayLog(text: String, width: Int, height: Int): List<String> =
        replay(parseLog(text), width, height)
}
