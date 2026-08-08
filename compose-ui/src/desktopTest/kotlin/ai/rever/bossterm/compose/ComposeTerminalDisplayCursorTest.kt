package ai.rever.bossterm.compose

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cursor must be readable as one value.
 *
 * `setCursor` writes x and then y. With four independent `@Volatile` fields a reader landing
 * between the two writes sees a position the terminal was never in - and the renderer reads them
 * on the UI thread while the emulator moves the caret many times per rendered frame, so the
 * window is hit in practice rather than in theory.
 *
 * This test fails reliably against separate volatiles and passes against the AtomicReference.
 */
class ComposeTerminalDisplayCursorTest {

    @Test
    fun aReaderNeverSeesAPositionTheTerminalWasNeverIn() {
        val display = ComposeTerminalDisplay()
        val a = 0 to 1
        val b = 79 to 50
        // Seed it, so the display's initial (0,0) is not itself a "torn" observation.
        display.setCursor(a.first, a.second)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val torn = java.util.concurrent.atomic.AtomicReference<Pair<Int, Int>?>(null)

        val writer = thread {
            var i = 0L
            while (running.get()) {
                if (i++ % 2 == 0L) display.setCursor(a.first, a.second)
                else display.setCursor(b.first, b.second)
            }
        }

        repeat(200_000) {
            val seen = display.cursorSnapshot.let { it.x to it.y }
            if (seen != a && seen != b) {
                torn.compareAndSet(null, seen)
            }
        }
        running.set(false)
        writer.join()

        assertEquals(
            null,
            torn.get(),
            "x and y must land together; a mixed pair means the two writes were observable apart",
        )
    }

    @Test
    fun theSingleFieldGettersAgreeWithTheSnapshot() {
        val display = ComposeTerminalDisplay()
        display.setCursor(12, 34)
        display.setCursorVisible(false)

        val snapshot = display.cursorSnapshot
        assertEquals(12, snapshot.x)
        assertEquals(34, snapshot.y)
        assertTrue(!snapshot.visible)
        assertEquals(snapshot.x, display.cursorXSnapshot)
        assertEquals(snapshot.y, display.cursorYSnapshot)
        assertEquals(snapshot.visible, display.cursorVisibleSnapshot)
        assertEquals(snapshot.shape, display.cursorShapeSnapshot)
    }

    @Test
    fun anUnchangedWriteIsStillReflectedWithoutDisturbingTheOtherFields() {
        val display = ComposeTerminalDisplay()
        display.setCursor(5, 6)
        display.setCursorShape(ai.rever.bossterm.terminal.CursorShape.STEADY_BLOCK)
        // A shape write must not clobber the position - the whole value is replaced by copy().
        assertEquals(5, display.cursorSnapshot.x)
        assertEquals(6, display.cursorSnapshot.y)
        assertEquals(
            ai.rever.bossterm.terminal.CursorShape.STEADY_BLOCK,
            display.cursorSnapshot.shape,
        )
    }
}
