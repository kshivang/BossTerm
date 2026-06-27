package ai.rever.bossterm.compose.daemon

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Pure-content guards for [LoginServiceManager]'s per-OS service definitions. The actual
 * install/uninstall shell out (launchctl/systemctl/reg) and are exercised at app runtime; here we
 * verify the generated plist/unit/desktop/registry value are well-formed and carry the command.
 */
class LoginServiceTest {

    // Use the real daemon main class (the `…Kt` facade name) so the fixture matches what
    // DaemonLauncher actually bakes — referencing the constant keeps it in sync if it ever changes.
    private val mainClass = DaemonLauncher.DEFAULT_DAEMON_MAIN_CLASS

    private val command = listOf(
        "/Applications/BossTerm.app/Contents/runtime/Contents/Home/bin/java",
        "-Djava.awt.headless=true",
        "-cp",
        "/Applications/BossTerm.app/Contents/app/compose-ui.jar:/Applications/BossTerm.app/Contents/app/has space.jar",
        mainClass,
    )

    @Test
    fun `macOS plist is well-formed and carries the command + RunAtLoad`() {
        val plist = LoginServiceManager.macPlist("ai.rever.bossterm.daemon", command, "/Users/x/.bossterm/daemon.log")
        assertTrue(plist.startsWith("<?xml"))
        assertTrue(plist.contains("<key>Label</key>"))
        assertTrue(plist.contains("<string>ai.rever.bossterm.daemon</string>"))
        assertTrue(plist.contains("<key>RunAtLoad</key>"))
        assertTrue(plist.contains(mainClass))
        assertTrue(plist.contains("daemon.log"))
        // Every command arg appears as a ProgramArguments <string>.
        command.forEach { assertTrue(plist.contains("<string>$it</string>"), "missing arg: $it") }
    }

    @Test
    fun `systemd unit restarts on failure and starts at login`() {
        val unit = LoginServiceManager.systemdUnit(command)
        assertTrue(unit.contains("ExecStart="))
        assertTrue(unit.contains(mainClass))
        assertTrue(unit.contains("Restart=on-failure"))
        assertTrue(unit.contains("WantedBy=default.target"))
        // An arg with a space must be POSIX-quoted in ExecStart.
        assertTrue(unit.contains("'/Applications/BossTerm.app/Contents/app/compose-ui.jar:/Applications/BossTerm.app/Contents/app/has space.jar'"))
    }

    @Test
    fun `xdg autostart desktop entry carries Exec`() {
        val d = LoginServiceManager.xdgDesktop(command)
        assertTrue(d.contains("[Desktop Entry]"))
        assertTrue(d.contains("Exec="))
        assertTrue(d.contains(mainClass))
    }

    @Test
    fun `windows run value double-quotes args with spaces only`() {
        assertEquals("plain", LoginServiceManager.winQuote("plain"))
        assertEquals("\"has space\"", LoginServiceManager.winQuote("has space"))
        val value = LoginServiceManager.windowsRunValue(command)
        assertTrue(value.contains("\"/Applications/BossTerm.app/Contents/app/compose-ui.jar:/Applications/BossTerm.app/Contents/app/has space.jar\""))
        assertTrue(value.contains(mainClass))
    }
}
