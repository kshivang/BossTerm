package ai.rever.bossterm.compose.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tool-deadline ladder. These four numbers used to live at four sites in two languages, with the
 * ordering between them explained in prose at each — so an edit to one could silently invert who
 * decides a tool has taken too long, and nothing would fail.
 */
class VoiceToolTimeoutsTest {

    @Test
    fun `each layer allows strictly more time than the one inside it`() {
        for (tool in VoiceToolTimeouts.LADDER_SAMPLES) {
            val exec = VoiceToolTimeouts.execMs(tool)
            val host = VoiceToolTimeouts.hostMs(tool)
            val viewer = VoiceToolTimeouts.viewerMs(tool)
            val inApp = VoiceToolTimeouts.inAppMs(tool)

            assertTrue(exec < host, "$tool: the executor net ($exec) must fire before the host budget ($host)")
            assertTrue(host < viewer, "$tool: the host ($host) must decide before the viewer backstop ($viewer)")
            assertTrue(
                exec < inApp,
                "$tool: the executor net ($exec) must fire before the in-app watchdog ($inApp)",
            )
        }
    }

    /**
     * A tie is the specific bug this ladder replaced: with host and viewer equal, a tool finishing on
     * the boundary was a coin flip over which side answered it.
     */
    @Test
    fun `the host budget is never equal to the viewer watchdog`() {
        for (tool in VoiceToolTimeouts.LADDER_SAMPLES) {
            assertTrue(
                VoiceToolTimeouts.hostMs(tool) != VoiceToolTimeouts.viewerMs(tool),
                "$tool: equal deadlines make authority ambiguous",
            )
        }
    }

    /**
     * run_command's own clamp is 600s (BossTermMcpServer.MAX_RUN_COMMAND_TIMEOUT_MS), and every rung
     * above it must leave room for the command to use all of it and still report its own result.
     */
    @Test
    fun `run_command's clamp fits inside the innermost net`() {
        assertTrue(
            VoiceToolTimeouts.execMs("run_command") > 600_000L,
            "a command allowed to run 600s must not be killed by the net above it",
        )
    }

    @Test
    fun `reads get far less than the long tool`() {
        val read = VoiceToolTimeouts.hostMs("read_scrollback")
        assertTrue(read < VoiceToolTimeouts.hostMs("run_command") / 4, "a read is not a build")
        // search_output over a large scrollback is legitimately slow, so not too little either.
        assertTrue(read >= 60_000L, "reads still need room for a big scrollback")
    }

    /** Anything not named is a read as far as the ladder is concerned. */
    @Test
    fun `an unknown tool is treated as a read`() {
        assertEquals(VoiceToolTimeouts.hostMs("read_scrollback"), VoiceToolTimeouts.hostMs("some_new_tool"))
    }
}
