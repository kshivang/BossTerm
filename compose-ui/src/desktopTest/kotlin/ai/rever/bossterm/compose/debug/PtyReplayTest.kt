package ai.rever.bossterm.compose.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The replay harness is only worth anything if a recording round-trips exactly. If the
 * escaper and the parser disagree by one character, every conclusion drawn from a replayed
 * grid is about the wrong bytes - and it would look like a terminal bug rather than a
 * tooling bug, which is the worst possible failure mode for a debugging aid.
 */
class PtyReplayTest {

    private fun roundTrip(payload: String): String {
        val file = File.createTempFile("pty-replay", ".log")
        file.deleteOnExit()
        val collector = DebugDataCollector(tab = null, maxChunks = 16, maxSnapshots = 2)
        collector.startFileLogging(file.absolutePath)
        collector.recordChunk(payload, ChunkSource.PTY_OUTPUT)
        collector.stopFileLogging()
        val chunks = PtyReplay.parseLog(file.readText())
        assertEquals(1, chunks.size, "expected exactly one PTY chunk in the recording")
        return chunks.single().data
    }

    @Test
    fun aRecordingRoundTripsThroughTheProductionEscaper() {
        // Through the real writeChunkToFile, not a reimplementation of it, so the two halves
        // cannot drift apart silently.
        listOf(
            "plain ascii",
            "\u001B[?2026h\u001B[?25l\u001B[H\r\u001B[27B",
            "\u001B[38;2;255;193;7m\u001B[1m\u001B[22m\u001B[39m",
            "tab\there\nnewline\rcarriage",
            "control\u0000nul\u0007bell\u007Fdel",
            "unicode ✻ ✽ ✶ 你好 👨\u200D💻 └─",
        ).forEach { payload ->
            assertEquals(payload, roundTrip(payload), "round trip lost data")
        }
    }

    @Test
    fun aLiteralBackslashIsNotConfusedWithAnEscape() {
        // The reason the escaper needed fixing. Unescaped, the two characters `\` and `e`
        // are indistinguishable from an ESC, so a Windows path or a regex in the output
        // would replay as a control sequence.
        listOf(
            """C:\Users\kshivang\dev""",
            """\e is not an escape here""",
            """\\e""",
            """grep -E '\d+\s*' file""",
            """trailing backslash \""",
        ).forEach { payload ->
            assertEquals(payload, roundTrip(payload), "backslash handling lost data")
        }
    }

    @Test
    fun onlyTerminalOutputIsReplayed() {
        // Replaying USR< would feed our own keystrokes back into the emulator as if the
        // shell had echoed them, inventing content the recording never contained.
        val file = File.createTempFile("pty-replay-mixed", ".log")
        file.deleteOnExit()
        val collector = DebugDataCollector(tab = null, maxChunks = 16, maxSnapshots = 2)
        collector.startFileLogging(file.absolutePath)
        collector.recordChunk("from-pty", ChunkSource.PTY_OUTPUT)
        collector.recordChunk("from-user", ChunkSource.USER_INPUT)
        collector.stopFileLogging()

        val parsed = PtyReplay.parseLog(file.readText())
        assertEquals(2, parsed.size, "the parser should surface both, and let replay filter")
        assertEquals(listOf("PTY>", "USR<"), parsed.map { it.source })

        val grid = PtyReplay.replay(parsed, width = 20, height = 2)
        assertEquals("from-pty", grid[0])
        assertTrue(grid.none { it.contains("from-user") }, "user input must not be replayed")
    }

    @Test
    fun aDifferentialRedrawReplaysToTheGridTheAppIntended() {
        // The shape that produced the reported glitch: absolute column addressing, no line
        // clear, only the changed characters rewritten. Frame two turns
        // "still thinking with xhigh effort" into "thinking more with xhigh effort" by
        // rewriting cols 1-2, skipping col 3, and resuming at col 4 - which is only correct
        // if our grid holds exactly what frame one left there.
        val esc = "\u001B"
        val frame1 = "$esc[H${esc}[Kstill thinking"

        // With the trailing clear the real capture ends on (chunk 16312 finishes with
        // CSI[K]), the shorter replacement lands cleanly.
        assertEquals(
            "thinking more",
            PtyReplay.replay(
                listOf(
                    PtyReplay.Chunk("PTY>", frame1),
                    PtyReplay.Chunk("PTY>", "$esc[H" + "th" + "$esc[4G" + "nking more" + "$esc[K"),
                ),
                width = 40, height = 2,
            )[0],
        )

        // WITHOUT it, the tail of the previous frame survives - "thinking moreg", the
        // trailing g of "thinking" left behind because nothing overwrote or cleared it.
        // That is the whole bug class this harness exists to catch: a differential redraw
        // leaves stale cells whenever the app's model of the grid and ours disagree about
        // what is already there, and no full repaint ever comes along to resync. Pinned
        // here so the emulator's behaviour is stated rather than assumed - it is correct,
        // and it is why one cell of divergence becomes permanent visible corruption.
        assertEquals(
            "thinking moreg",
            PtyReplay.replay(
                listOf(
                    PtyReplay.Chunk("PTY>", frame1),
                    PtyReplay.Chunk("PTY>", "$esc[H" + "th" + "$esc[4G" + "nking more"),
                ),
                width = 40, height = 2,
            )[0],
        )
    }

    @Test
    fun chunkBoundariesDoNotSplitAControlSequence() {
        // A CSI arriving across two PTY reads is normal and must not corrupt the grid. This
        // is the property BlockingTerminalDataStream exists for; replaying preserves the
        // boundaries so a recording can re-expose it rather than smoothing it over.
        val esc = "\u001B"
        val whole = PtyReplay.replay(
            listOf(PtyReplay.Chunk("PTY>", "$esc[H${esc}[2Jabc$esc[3Gz")),
            width = 10, height = 2,
        )
        val split = PtyReplay.replay(
            listOf(
                PtyReplay.Chunk("PTY>", "$esc[H${esc}[2Jabc$esc["),
                PtyReplay.Chunk("PTY>", "3Gz"),
            ),
            width = 10, height = 2,
        )
        assertEquals(whole, split, "a CSI split across reads changed the result")
        assertEquals("abz", whole[0])
    }
}
