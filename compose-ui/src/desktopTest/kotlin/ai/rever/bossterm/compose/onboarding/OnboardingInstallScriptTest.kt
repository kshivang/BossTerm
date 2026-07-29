package ai.rever.bossterm.compose.onboarding

import ai.rever.bossterm.compose.ai.AIAssistantIds
import ai.rever.bossterm.compose.ai.AIAssistants
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the onboarding wizard's generated tool-install script (the in-app "Welcome to BossTerm"
 * flow). [buildInstallCommand] is platform-sensitive (it reads `os.name`), so on each CI OS this
 * exercises that OS's branch with the wizard's default selections (Zsh + Starship + git + gh +
 * the fully-open AI assistants) and nothing pre-installed — the realistic "accept defaults" install.
 *
 * Note that the defaults are all *script*-installed CLIs now, so the npm-hardening guard gets its
 * own scenario that explicitly selects an npm-installed assistant.
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

    /** The default script plus Codex, whose registry entry installs via `npm install -g`. */
    private fun scriptWithNpmAssistant(): String =
        buildInstallCommand(
            OnboardingSelections(
                aiAssistants = AIAssistants.DEFAULT_ONBOARDING_SELECTION + AIAssistantIds.CODEX
            ),
            InstalledTools()
        )

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
        // Defaults include Starship → sudo is pre-authed so the guarded sudo calls run unattended.
        assertTrue(
            script.contains("Authenticating administrator access"),
            "script should authenticate sudo upfront when it contains sudo steps:\n$script"
        )
    }

    @Test
    fun `npm-installed assistants keep the EACCES hardening`() {
        if (isWindows) return
        val script = scriptWithNpmAssistant()
        // AI-CLI npm global install must go through the writability-gated $NPM_SUDO with npm
        // resolved to an absolute path ($NPM_BIN) — a bare `npm install -g` EACCES'es on a
        // root-owned prefix, and a bare `sudo npm` can hit command-not-found under env_reset.
        assertTrue(
            script.contains("\$NPM_SUDO \"\$NPM_BIN\" install -g"),
            "AI-CLI npm install must use \$NPM_SUDO + resolved \$NPM_BIN:\n$script"
        )
    }

    @Test
    fun `script-installed assistants use their own installers`() {
        if (isWindows) return
        val script = defaultScript()
        // The single-binary CLIs (Kimi Code, Hermes) are not npm packages — Hermes has no npm
        // package at all — so onboarding must run their installer scripts rather than silently
        // dropping them from the batch, which is what the old npm-only path did.
        AIAssistants.AI_ASSISTANTS
            .filter { it.id in OnboardingSelections().aiAssistants }
            .forEach { assistant ->
                assertTrue(
                    script.contains(assistant.installCommand),
                    "${assistant.id} was selected by default but its install command is missing:\n$script"
                )
            }
    }

    /**
     * Not an assertion — when `BOSSTERM_EMIT_INSTALL_SCRIPT` is set, writes the generated
     * default install script to that path so the CI live-install job can execute it. A no-op
     * (passes) otherwise, so it's harmless in the normal test run.
     *
     * Alongside it, writes `<path>.expected-commands`: the command name of every tool the default
     * selection installs, one per line. The live job asserts each of those resolves afterwards.
     * Emitting the list from the registry rather than hardcoding a CLI in the workflow is the point
     * — the previous version asserted `command -v claude`, which silently became wrong the moment
     * the default selection changed, and the workflow is not somewhere that drift gets noticed.
     */
    @Test
    fun `emit install script for CI when requested`() {
        val target = System.getenv("BOSSTERM_EMIT_INSTALL_SCRIPT") ?: return
        File(target).writeText(defaultScript())
        assertTrue(File(target).length() > 0)

        val selection = OnboardingSelections().aiAssistants
        val commands = (AIAssistants.AI_ASSISTANTS + AIAssistants.LOCAL_MODEL_RUNTIMES)
            .filter { it.id in selection }
            .map { it.command }
        File("$target.expected-commands").writeText(commands.joinToString("\n", postfix = "\n"))
        assertTrue(commands.isNotEmpty(), "default onboarding selection installs no AI tools")
    }
}
