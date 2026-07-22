package ai.rever.bossterm.compose

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TerminalSessionSlots is the safety mechanism that turns dispatcher exhaustion
 * into a visible refusal instead of a silent hang, so its CAS reserve/release
 * invariants are pinned here: reserve up to the cap, refuse beyond it, release
 * restores capacity, mis-paired release clamps instead of corrupting the budget,
 * and the accounting stays balanced under concurrency.
 */
class TerminalSessionSlotsTest {

    @AfterTest
    fun drainReservations() {
        // Tests pair reserve/release themselves, but drain defensively so one
        // failing assertion can't poison the singleton for later tests. Relies
        // on release() clamping at zero.
        TerminalSessionSlots.release(TerminalSessionSlots.MAX_THREADS)
    }

    @Test
    fun `reserves up to the cap and refuses beyond it`() {
        val fullSessions = TerminalSessionSlots.MAX_THREADS / TerminalSessionSlots.THREADS_PER_SESSION
        repeat(fullSessions) { assertTrue(TerminalSessionSlots.tryReserve()) }
        assertEquals(
            fullSessions * TerminalSessionSlots.THREADS_PER_SESSION,
            TerminalSessionSlots.usedThreads
        )

        // 256 % 3 == 1: a full session no longer fits, a single mirror loop does.
        assertFalse(TerminalSessionSlots.tryReserve(), "a full session must be refused at the cap")
        assertTrue(TerminalSessionSlots.tryReserve(1), "a mirror loop still fits in the remainder")
        assertFalse(TerminalSessionSlots.tryReserve(1), "nothing fits once the budget is exactly consumed")
        assertEquals(TerminalSessionSlots.MAX_THREADS, TerminalSessionSlots.usedThreads)
    }

    @Test
    fun `failed reserve reserves nothing`() {
        repeat(TerminalSessionSlots.MAX_THREADS - 1) { assertTrue(TerminalSessionSlots.tryReserve(1)) }
        assertFalse(TerminalSessionSlots.tryReserve(TerminalSessionSlots.THREADS_PER_SESSION))
        // The refused reserve must not have consumed the remaining permit.
        assertEquals(TerminalSessionSlots.MAX_THREADS - 1, TerminalSessionSlots.usedThreads)
        assertTrue(TerminalSessionSlots.tryReserve(1))
    }

    @Test
    fun `release restores capacity`() {
        repeat(TerminalSessionSlots.MAX_THREADS) { assertTrue(TerminalSessionSlots.tryReserve(1)) }
        assertFalse(TerminalSessionSlots.tryReserve(1))

        TerminalSessionSlots.release()
        assertTrue(TerminalSessionSlots.tryReserve(), "releasing a session must readmit a session")
        assertFalse(TerminalSessionSlots.tryReserve(1))
    }

    @Test
    fun `unpaired release clamps at zero instead of corrupting the budget`() {
        TerminalSessionSlots.release(5)
        assertEquals(0, TerminalSessionSlots.usedThreads)

        // The budget must still admit exactly MAX_THREADS afterwards — a negative
        // counter would admit more sessions than the dispatcher can run.
        repeat(TerminalSessionSlots.MAX_THREADS) { assertTrue(TerminalSessionSlots.tryReserve(1)) }
        assertFalse(TerminalSessionSlots.tryReserve(1))
    }

    @Test
    fun `concurrent reserve and release stay balanced`() {
        val workers = (1..8).map {
            Thread {
                repeat(5_000) {
                    if (TerminalSessionSlots.tryReserve()) {
                        check(TerminalSessionSlots.usedThreads <= TerminalSessionSlots.MAX_THREADS) {
                            "reserve overshot the cap"
                        }
                        TerminalSessionSlots.release()
                    }
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(0, TerminalSessionSlots.usedThreads)
    }
}
