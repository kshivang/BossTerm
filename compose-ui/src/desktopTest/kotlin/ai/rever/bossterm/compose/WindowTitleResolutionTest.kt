package ai.rever.bossterm.compose

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which title the OS window shows, which is the behaviour that changed for every user: the window
 * used to track OSC 2 alone and sat on its startup name for the shells that never emit it.
 *
 * The three candidates are deliberately NOT interchangeable. A Rename… is the user talking and
 * outranks anything a program says; OSC 2 is what an app sets for the WINDOW specifically (xterm's
 * split, where OSC 1 names the tab instead); the tab title is the shared fallback that keeps the
 * title bar and the tab bar agreeing when nothing more specific has been said.
 */
class WindowTitleResolutionTest {
    @Test
    fun `a renamed tab outranks whatever the app called the window`() {
        assertEquals(
            "deploy",
            resolveWindowTitle(custom = "deploy", osc2 = "me@host: ~/src", tabTitle = "src"),
        )
    }

    @Test
    fun `an app's OSC 2 title outranks the tab title`() {
        // The case OSC 2 exists for: an app naming the window something longer or more specific
        // than the tab label beside it.
        assertEquals(
            "me@host: ~/src",
            resolveWindowTitle(custom = null, osc2 = "me@host: ~/src", tabTitle = "src"),
        )
    }

    @Test
    fun `an empty OSC 2 title falls back to the tab title`() {
        // Empty is the RESET written at each command start, not a real title - without the
        // fallback an exited program would leave the window nameless.
        assertEquals("src", resolveWindowTitle(custom = null, osc2 = "", tabTitle = "src"))
    }

    @Test
    fun `a rename still wins once the app title has been reset`() {
        assertEquals("deploy", resolveWindowTitle(custom = "deploy", osc2 = "", tabTitle = "src"))
    }

    @Test
    fun `nothing to show stays empty for the caller to suppress`() {
        // The collector guards on isNotEmpty, so this must not invent a placeholder - that guard
        // is what stops a window being retitled to nothing during startup.
        assertEquals("", resolveWindowTitle(custom = null, osc2 = "", tabTitle = ""))
    }
}
