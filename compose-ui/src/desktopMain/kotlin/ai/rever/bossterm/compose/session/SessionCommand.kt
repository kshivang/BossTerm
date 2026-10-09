package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils

/** Login-session and login-shell defaults used by every terminal owner. */
fun resolveSessionCommand(
    settings: TerminalSettings,
    workingDir: String?,
    command: String? = null,
    arguments: List<String> = emptyList(),
): Pair<String, List<String>> {
    val username = System.getProperty("user.name")
    val shell = command ?: ShellCustomizationUtils.getValidShell(settings.windowsShell)
    // login cannot pass Bash's --rcfile hook to its shell. Direct Bash launch replays login
    // startup files through our loader; other shells retain login's session registration.
    val injectedBash = settings.autoInjectShellIntegration && java.io.File(shell).name == "bash"
    return if (command == null && arguments.isEmpty() && ShellCustomizationUtils.isMacOS() &&
        username != null && settings.useLoginSession && workingDir == null && !injectedBash) {
        "/usr/bin/login" to listOf("-fp", username)
    } else {
        val args = if (arguments.isEmpty() &&
            (shell.endsWith("/zsh") || shell.endsWith("/bash") || shell == "zsh" || shell == "bash")) {
            listOf("-l")
        } else arguments
        shell to args.toList()
    }
}
