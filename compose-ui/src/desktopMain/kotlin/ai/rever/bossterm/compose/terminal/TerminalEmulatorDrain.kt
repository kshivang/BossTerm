package ai.rever.bossterm.compose.terminal

import ai.rever.bossterm.terminal.Terminal
import ai.rever.bossterm.terminal.TerminalDataStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import java.io.EOFException

private val parserThread = ThreadLocal<Boolean>()

internal fun isTerminalParserThread(): Boolean = parserThread.get() == true

/**
 * Drain a production terminal data stream and always reset terminal-only state on exit.
 *
 * [Terminal.disconnected] must run on this emulator thread: it clears an abandoned
 * text-buffer batch owned by the thread and disables DEC synchronized-update mode.
 * Without this finally path, EOF after `?2026h` leaves every later render capture gated.
 */
internal fun drainTerminalEmulator(
    emulator: BossEmulator,
    dataStream: BlockingTerminalDataStream,
    terminal: Terminal,
    shouldContinue: () -> Boolean,
    onProcessingError: (Exception) -> Unit = {},
    processCharacter: (Char) -> Unit = { emulator.processChar(it, terminal) },
) {
    val previousParserThread = parserThread.get()
    parserThread.set(true)
    try {
        while (shouldContinue()) {
            try {
                processCharacter(dataStream.readInstructionStart())
                dataStream.instructionComplete()
            } catch (_: EOFException) {
                break
            } catch (e: Exception) {
                if (e !is TerminalDataStream.EOF) onProcessingError(e)
                break
            }
        }
    } finally {
        try {
            try {
                terminal.disconnected()
            } finally {
                // Closing is idempotent and best-effort during teardown. Do not let a
                // future stream implementation's close failure replace the emulator error.
                runCatching { dataStream.close() }
            }
        } finally {
            parserThread.set(previousParserThread)
        }
    }
}
