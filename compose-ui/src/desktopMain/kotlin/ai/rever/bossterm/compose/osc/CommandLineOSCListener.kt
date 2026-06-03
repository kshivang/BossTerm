package ai.rever.bossterm.compose.osc

import ai.rever.bossterm.terminal.TerminalCustomCommandListener

/**
 * Listens for the BossTerm command-line sequence emitted by shell integration:
 * `OSC 1341 ; BossTermCmd ; <command line>`.
 *
 * The emulator forwards OSC 1341 payloads to custom command listeners after
 * stripping the leading `1341`, so [process] receives `["BossTermCmd", <cmd...>]`
 * (mirrors how [WorkingDirectoryOSCListener] consumes its args). A command that
 * itself contains ';' is split into extra args, so they are re-joined here.
 *
 * The captured text populates [ai.rever.bossterm.compose.blocks.CommandBlock.commandText],
 * which powers copy-command and re-run. Buffer-scraping is intentionally avoided
 * (fragile with multi-line prompts / RPROMPT); the shell tells us directly.
 */
class CommandLineOSCListener(
    private val onCommandLine: (String) -> Unit,
) : TerminalCustomCommandListener {

    override fun process(args: MutableList<String?>) {
        if (args.size >= 2 && args[0] == MARKER) {
            val cmd = args.subList(1, args.size).joinToString(";") { it ?: "" }
            if (cmd.isNotEmpty()) onCommandLine(cmd)
        }
    }

    companion object {
        /** Sub-code following OSC 1341 that identifies a command-line payload. */
        const val MARKER = "BossTermCmd"
    }
}
