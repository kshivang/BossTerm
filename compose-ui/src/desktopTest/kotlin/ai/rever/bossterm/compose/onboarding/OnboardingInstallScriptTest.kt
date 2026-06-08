package ai.rever.bossterm.compose.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the onboarding wizard's generated tool-install script (the in-app "Welcome to BossTerm"
 * flow). [buildInstallCommand] is platform-sensitive (it reads `os.name`), so on each CI OS this
 * exercises that OS's branch with the wizard's default selections (Zsh + Starship + git + gh +
 * all AI assistants) and nothing pre-installed — the realistic "accept defaults" install.
 *
 * Catches the class of bugs we've actually shipped: malformed shell (the bash that won't parse),
 * the missing-`/usr/local/bin` Starship abort, and the AI-CLI `npm install -g` EACCES when npm's
 * global prefix is root-owned. The companion CI job (.github/workflows/test-install.yml) actually
 * RUNS the emitted script on Ubuntu; this test is the fast, deterministic, every-PR guard.
 */
class OnboardingInstallScriptTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun defaultScript(): String =
        buildInstallCommand(OnboardingSelections(), InstalledTools())

    @Test
    fun `default install script is syntactically valid`() {
        val script = defaultScript()
        assertTrue(script.isNotBlank(), "generated install script must not be empty")
        if (isWindows) return // Windows emits an &&-joined PowerShell-flavored line, not bash.

        assertTrue(script.startsWith("#!/bin/bash"), "unix script should start with a shebang:\n$script")
        // `bash -n` parses without executing — the real guard against malformed commands.
        val tmp = File.createTempFile("onboarding-install", ".sh")
        try {
            tmp.writeText(script)
            val proc = ProcessBuilder("bash", "-n", tmp.absolutePath).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals(0, proc.waitFor(), "bash -n rejected the generated script:\n$out\n---\n$script")
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `default install script keeps the known-bug guards`() {
        if (isWindows) return
        val script = defaultScript()
        // Starship's installer aborts if /usr/local/bin is missing (Apple Silicon) — must be guarded.
        assertTrue(
            script.contains("[ -d /usr/local/bin ]"),
            "Starship install must guard /usr/local/bin existence:\n$script"
        )
        // AI-CLI npm global install must go through the writability-gated \$NPM_SUDO, never a bare
        // `npm install -g` (which EACCES'es on a root-owned global prefix).
        assertTrue(
            script.contains("\$NPM_SUDO npm install -g"),
            "AI-CLI npm install must use the \$NPM_SUDO guard:\n$script"
        )
        // Defaults include Starship → sudo is pre-authed so the guarded sudo calls run unattended.
        assertTrue(
            script.contains("Authenticating administrator access"),
            "script should authenticate sudo upfront when it contains sudo steps:\n$script"
        )
    }

    /**
     * Not an assertion — when `BOSSTERM_EMIT_INSTALL_SCRIPT` is set, writes the generated
     * default install script to that path so the CI live-install job can execute it. A no-op
     * (passes) otherwise, so it's harmless in the normal test run.
     */
    @Test
    fun `emit install script for CI when requested`() {
        val target = System.getenv("BOSSTERM_EMIT_INSTALL_SCRIPT") ?: return
        File(target).writeText(defaultScript())
        assertTrue(File(target).length() > 0)
    }
}
