package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.shell.ShellIntegrationInjector
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real interactive shells with isolated startup files; no GUI or user dotfiles are involved. */
class ShellStartupIntegrationTest {
    @Test
    fun bashLoginStartupLoadsUserProfileOnceAndTracksCommands() = withHome { home ->
        val bash = shell("bash") ?: return@withHome
        home.resolve(".bash_profile").writeText("PROFILE_RUNS=\$((\${PROFILE_RUNS:-0}+1)); source \"\$HOME/.bashrc\"\n")
        home.resolve(".bashrc").writeText("RC_RUNS=\$((\${RC_RUNS:-0}+1))\n")
        val env = environment(home)
        ShellIntegrationInjector.injectForShell(bash.path, env)
        val args = ShellIntegrationInjector.argumentsForShell(bash.path, listOf("-l", "-i"), env)
        val output = runShell(bash, args, env,
            "printf '__STARTUP__=%s,%s,%s\\n' \"\$PROFILE_RUNS\" \"\$RC_RUNS\" \"\$BOSSTERM_SHELL_INTEGRATION_LOADED\"\nfalse\nexit\n")
        assertTrue(output.contains("__STARTUP__=1,1,1"), output)
        assertTrue(output.contains("\u001b]133;A\u0007"), output)
        assertTrue(output.contains("\u001b]133;B\u0007"), output)
        assertTrue(output.contains("\u001b]133;D;1\u0007"), output)
    }

    @Test
    fun bashNonLoginStartupLoadsOnlyBashrcAndPreservesUserEnv() = withHome { home ->
        val bash = shell("bash") ?: return@withHome
        home.resolve(".bash_profile").writeText("PROFILE_RUNS=1\n")
        home.resolve(".bashrc").writeText("RC_RUNS=\$((\${RC_RUNS:-0}+1))\n")
        val env = environment(home).apply { put("ENV", "/user/posix-startup") }
        ShellIntegrationInjector.injectForShell(bash.path, env)
        assertEquals("/user/posix-startup", env["ENV"])
        val args = ShellIntegrationInjector.argumentsForShell(bash.path, listOf("-i"), env)
        val output = runShell(bash, args, env,
            "printf '__STARTUP__=%s,%s,%s\\n' \"\${PROFILE_RUNS:-0}\" \"\$RC_RUNS\" \"\$BOSSTERM_SHELL_INTEGRATION_LOADED\"\nexit\n")
        assertTrue(output.contains("__STARTUP__=0,1,1"), output)
    }

    @Test
    fun explicitBashCommandsAndStartupControlsRemainAuthoritative() {
        for (args in listOf(listOf("-c", "printf hello"), listOf("--norc", "-i"),
            listOf("--noprofile", "-l"), listOf("--posix", "-i"), listOf("script.sh"))) {
            assertEquals(args, ShellIntegrationInjector.argumentsForShell("/bin/bash", args, mutableMapOf()))
        }
        assertEquals(listOf("-l"), ShellIntegrationInjector.argumentsForShell("/bin/bash", listOf("-l"), mutableMapOf(), enabled = false))
        assertEquals(listOf("-l"), ShellIntegrationInjector.argumentsForShell("/bin/zsh", listOf("-l"), mutableMapOf()))
    }

    @Test
    fun zshLoginStartupPreservesUserFilesAndCommandTracking() = withHome { home ->
        val zsh = shell("zsh") ?: return@withHome
        for (name in listOf(".zshenv", ".zprofile", ".zshrc", ".zlogin")) {
            home.resolve(name).writeText("typeset -g USER_STARTUP_COUNT=\$((\${USER_STARTUP_COUNT:-0}+1))\n")
        }
        // The zsh loader resolves its integration source beneath HOME.
        val dir = home.resolve(".bossterm/shell-integration").apply { mkdirs() }
        for (name in listOf(".zshenv", "bossterm_shell_integration.zsh")) {
            javaClass.classLoader.getResourceAsStream("shell-integration/$name")!!.use {
                dir.resolve(name).writeBytes(it.readBytes())
            }
        }
        val env = environment(home)
        ShellIntegrationInjector.injectForShell(zsh.path, env)
        env["ZDOTDIR"] = dir.path
        val output = runShell(zsh, listOf("-l", "-i"), env,
            "printf '__STARTUP__=%s,%s\\n' \"\$USER_STARTUP_COUNT\" \"\$BOSSTERM_SHELL_INTEGRATION_LOADED\"\n(exit 7)\nexit\n")
        assertTrue(output.contains("__STARTUP__=4,1"), output)
        assertTrue(output.contains("\u001b]133;D;7\u0007"), output)
    }

    private fun shell(name: String) = listOf("/bin/$name", "/usr/bin/$name").map(::File).firstOrNull { it.canExecute() }

    private fun environment(home: File) = System.getenv().toMutableMap().apply {
        put("HOME", home.path)
        put("TERM", "xterm-256color")
        remove("BOSSTERM_SHELL_INTEGRATION_LOADED")
        remove("BOSSTERM_ORIG_ZDOTDIR")
        remove("ZDOTDIR")
    }

    private fun runShell(shell: File, args: List<String>, env: Map<String, String>, input: String): String {
        val process = ProcessBuilder(listOf(shell.path) + args).redirectErrorStream(true).apply {
            environment().clear(); environment().putAll(env)
        }.start()
        try {
            process.outputStream.bufferedWriter().use { it.write(input) }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "interactive shell must exit")
            return process.inputStream.bufferedReader().readText()
        } finally { process.destroyForcibly() }
    }

    private fun withHome(block: (File) -> Unit) {
        val home = Files.createTempDirectory("bossterm-shell-startup").toFile()
        try { block(home) } finally { home.deleteRecursively() }
    }
}
