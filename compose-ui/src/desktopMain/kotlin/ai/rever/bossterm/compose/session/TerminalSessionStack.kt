package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream
import ai.rever.bossterm.compose.terminal.PerformanceMode
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer

/** Terminal model and parser, independent of process ownership and display implementation. */
class TerminalSessionStack(
    val display: TerminalDisplay,
    val textBuffer: TerminalTextBuffer,
    val terminal: BossTerminal,
    val dataStream: BlockingTerminalDataStream,
    val emulator: BossEmulator,
) {
    internal var publisher: ParsedOutputPublisher? = null; private set

    companion object {
        /** Reuse a display/model wired by a host while installing the shared parsed-output lane. */
        fun create(
            settings: TerminalSettings,
            display: TerminalDisplay,
            textBuffer: TerminalTextBuffer? = null,
            terminal: BossTerminal? = null,
            dataStream: BlockingTerminalDataStream? = null,
            initialCols: Int = 80,
            initialRows: Int = 24,
        ): TerminalSessionStack {
            require((textBuffer == null) == (terminal == null)) { "Supply the terminal and its buffer together" }
            val style = StyleState()
            val buffer = textBuffer ?: TerminalTextBuffer(
                initialCols.coerceIn(1, 2000), initialRows.coerceIn(1, 2000), style, settings.bufferMaxLines,
            )
            val model = terminal ?: BossTerminal(display, buffer, style)
            val stream = dataStream ?: BlockingTerminalDataStream(performanceMode = PerformanceMode.fromString(settings.performanceMode))
            val publisher = ParsedOutputPublisher(stream)
            return TerminalSessionStack(
                display, buffer, model, stream, BossEmulator(publisher, model, settings.allowKittyFileTransfers),
            ).also { it.publisher = publisher }
        }
    }
}
