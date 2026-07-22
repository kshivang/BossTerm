package ai.rever.bossterm.compose

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TerminalSessionSlots is the safety mechanism that turns dispatcher exhaustion
 * into a visible refusal instead of a silent hang, so its invariants are pinned
 * here: reserve up to the budget, refuse beyond it, release restores capacity,
 * mis-paired release clamps instead of corrupting the budget, the accounting
 * stays balanced under concurrency, and the settings-driven budget clamps and
 * falls back to the machine-scaled default correctly.
 */
class TerminalSessionSlotsTest {

    // Pin a deterministic budget: tests must not depend on this machine's cores.
    private val budget = 256

    @BeforeTest
    fun pinBudget() {
        TerminalSessionSlots.applyConfiguredBudget(budget)
    }

    @AfterTest
    fun restore() {
        // Tests pair reserve/release themselves, but drain defensively so one
        // failing assertion can't poison the singleton for later tests. Relies
        // on release() clamping at zero. Then restore the automatic budget.
        TerminalSessionSlots.release(TerminalSessionSlots.HARD_MAX_THREADS)
        TerminalSessionSlots.applyConfiguredBudget(0)
    }

    @Test
    fun `reserves up to the budget and refuses beyond it`() {
        val fullSessions = budget / TerminalSessionSlots.THREADS_PER_SESSION
        repeat(fullSessions) { assertTrue(TerminalSessionSlots.tryReserve()) }
        assertEquals(fullSessions * TerminalSessionSlots.THREADS_PER_SESSION, TerminalSessionSlots.usedThreads)

        // 256 % 3 == 1: a full session no longer fits, a single mirror loop does.
        assertFalse(TerminalSessionSlots.tryReserve(), "a full session must be refused at the budget")
        assertTrue(TerminalSessionSlots.tryReserve(1), "a mirror loop still fits in the remainder")
        assertFalse(TerminalSessionSlots.tryReserve(1), "nothing fits once the budget is exactly consumed")
        assertEquals(budget, TerminalSessionSlots.usedThreads)
    }

    @Test
    fun `failed reserve reserves nothing`() {
        repeat(budget - 1) { assertTrue(TerminalSessionSlots.tryReserve(1)) }
        assertFalse(TerminalSessionSlots.tryReserve(TerminalSessionSlots.THREADS_PER_SESSION))
        // The refused reserve must not have consumed the remaining permit.
        assertEquals(budget - 1, TerminalSessionSlots.usedThreads)
        assertTrue(TerminalSessionSlots.tryReserve(1))
    }

    @Test
    fun `release restores capacity`() {
        repeat(budget) { assertTrue(TerminalSessionSlots.tryReserve(1)) }
        assertFalse(TerminalSessionSlots.tryReserve(1))

        TerminalSessionSlots.release()
        assertTrue(TerminalSessionSlots.tryReserve(), "releasing a session must readmit a session")
        assertFalse(TerminalSessionSlots.tryReserve(1))
    }

    @Test
    fun `unpaired release clamps at zero instead of corrupting the budget`() {
        TerminalSessionSlots.release(5)
        assertEquals(0, TerminalSessionSlots.usedThreads)

        // The budget must still admit exactly `budget` afterwards — a negative
        // counter would admit more sessions than the dispatcher can run.
        repeat(budget) { assertTrue(TerminalSessionSlots.tryReserve(1)) }
        assertFalse(TerminalSessionSlots.tryReserve(1))
    }

    @Test
    fun `concurrent reserve and release stay balanced`() {
        val workers = (1..8).map {
            Thread {
                repeat(5_000) {
                    if (TerminalSessionSlots.tryReserve()) {
                        check(TerminalSessionSlots.usedThreads <= TerminalSessionSlots.maxThreads) {
                            "reserve overshot the budget"
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

    @Test
    fun `configured budget clamps to the supported range`() {
        TerminalSessionSlots.applyConfiguredBudget(1)
        assertEquals(TerminalSessionSlots.MIN_THREADS, TerminalSessionSlots.maxThreads)

        TerminalSessionSlots.applyConfiguredBudget(Int.MAX_VALUE)
        assertEquals(TerminalSessionSlots.HARD_MAX_THREADS, TerminalSessionSlots.maxThreads)

        TerminalSessionSlots.applyConfiguredBudget(100)
        assertEquals(100, TerminalSessionSlots.maxThreads)
    }

    @Test
    fun `zero means the machine-scaled automatic default`() {
        TerminalSessionSlots.applyConfiguredBudget(0)
        assertEquals(TerminalSessionSlots.defaultMaxThreads(), TerminalSessionSlots.maxThreads)
        // The formula: 16 per core, clamped to [128, HARD_MAX_THREADS].
        assertEquals(
            (Runtime.getRuntime().availableProcessors() * 16)
                .coerceIn(128, TerminalSessionSlots.HARD_MAX_THREADS),
            TerminalSessionSlots.defaultMaxThreads()
        )
    }

    @Test
    fun `lowering the budget below current usage refuses new sessions until enough close`() {
        repeat(20) { assertTrue(TerminalSessionSlots.tryReserve(1)) }
        TerminalSessionSlots.applyConfiguredBudget(TerminalSessionSlots.MIN_THREADS) // 32

        assertTrue(TerminalSessionSlots.tryReserve(TerminalSessionSlots.THREADS_PER_SESSION)) // 23
        repeat(9) { assertTrue(TerminalSessionSlots.tryReserve(1)) } // 32 — at the new budget
        assertFalse(TerminalSessionSlots.tryReserve(1))

        TerminalSessionSlots.release(1) // 31
        assertTrue(TerminalSessionSlots.tryReserve(1), "capacity returns as sessions close")
    }
}
