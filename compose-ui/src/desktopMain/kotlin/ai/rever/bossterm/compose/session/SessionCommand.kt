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
    return if (command == null && arguments.isEmpty() && ShellCustomizationUtils.isMacOS() &&
        username != null && settings.useLoginSession && workingDir == null) {
        "/usr/bin/login" to listOf("-fp", username)
    } else {
        val shell = command ?: ShellCustomizationUtils.getValidShell(settings.windowsShell)
        val args = if (arguments.isEmpty() &&
            (shell.endsWith("/zsh") || shell.endsWith("/bash") || shell == "zsh" || shell == "bash")) {
            listOf("-l")
        } else arguments
        shell to args.toList()
    }
}
