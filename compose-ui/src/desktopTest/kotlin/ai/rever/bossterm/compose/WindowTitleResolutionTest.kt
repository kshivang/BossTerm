package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.tabs.NotificationTitleProvider
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
        // Empty is the RESET written at each prompt start, not a real title - without the
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

    @Test
    fun `a whitespace OSC 2 title falls through like a blank rename does`() {
        // Same reasoning as the custom title: letting whitespace win leaves the window looking
        // nameless, which is worse than the fallback it was covering up.
        assertEquals("src", resolveWindowTitle(custom = null, osc2 = "   ", tabTitle = "src"))
        assertEquals("src", resolveWindowTitle(custom = "  ", osc2 = "", tabTitle = "src"))
    }

    // ---- the same precedence, as a completion notification sees it ----

    @Test
    fun `a notification names the session, not a program that already exited`() {
        // The regression this guards: display.iconTitle is never reset, so falling back to it left
        // a finished build announcing "vim" long after vim had quit. The tab title is re-asserted
        // at each prompt, so it reverts.
        assertEquals(
            "src",
            notificationTitle(custom = null, osc2 = "", tabTitle = "src", fallback = "BossTerm"),
        )
    }

    @Test
    fun `a notification prefers what the running program calls itself`() {
        assertEquals(
            "build.gradle.kts",
            notificationTitle(
                custom = null,
                osc2 = "build.gradle.kts",
                tabTitle = "src",
                fallback = "BossTerm",
            ),
        )
    }

    @Test
    fun `a notification falls back to the app name only with nothing to say`() {
        assertEquals(
            "BossTerm",
            notificationTitle(custom = null, osc2 = "", tabTitle = "", fallback = "BossTerm"),
        )
    }

    @Test
    fun `a notification before its tab is attached falls back rather than crashing`() {
        // NotificationTitleProvider is built before the TerminalTab exists and has the tab dropped
        // in afterwards. Nothing should invoke it in between, but if anything ever does, the
        // answer has to be the old behaviour rather than an exception on a background thread.
        val display = ComposeTerminalDisplay()
        display.windowTitle = "me@host: ~/src"
        val provider = NotificationTitleProvider(display, fallback = "BossTerm")

        // The app's own OSC 2 title still comes through with no tab attached...
        assertEquals("me@host: ~/src", provider())

        // ...and with nothing at all, the fallback, which is exactly pre-PR behaviour.
        display.windowTitle = ""
        assertEquals("BossTerm", provider())
    }
}
