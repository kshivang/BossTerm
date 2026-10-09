package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.share.TerminalSnapshotEncoder
import ai.rever.bossterm.terminal.TerminalMode
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import ai.rever.bossterm.terminal.model.BufferSnapshot

/** A complete repaint also restores input modes, which cannot be inferred from styled text.
 * Call while holding the core's atomic output-subscription lock. */
internal object DaemonTerminalSnapshot {
    fun encode(core: TerminalSessionCore): String = buildString {
        val buffer = core.textBuffer
        if (core.display.usingAlternateBuffer) {
            // Preserve the normal screen and history, so leaving an attached TUI reveals the
            // shell that was underneath it, rather than an empty/reset main screen.
            buffer.lock()
            val main = try {
                val screen = buffer.getScreenLinesStorageOrBackup()
                val history = buffer.getHistoryLinesStorageOrBackup()
                BufferSnapshot(
                    (0 until screen.size).map { screen[it].copy() },
                    (0 until history.size).map { history[it].copy() },
                    buffer.width, buffer.height, history.size, false,
                )
            } finally { buffer.unlock() }
            val saved = core.terminal.savedCursorPosition ?: (1 to 1)
            append(TerminalSnapshotEncoder.encode(main, saved.first, saved.second,
                cursorVisible = core.display.cursorVisible, cursorShape = core.display.cursorShape))
            append("\u001b[?1049h")
        }
        append(TerminalSnapshotEncoder.encode(buffer.createSnapshot(), core.terminal.cursorX, core.terminal.cursorY,
            cursorVisible = core.display.cursorVisible, cursorShape = core.display.cursorShape))
        fun mode(number: Int, enabled: Boolean) { append("\u001b[?$number${if (enabled) 'h' else 'l'}") }
        // Saved DECSC state affects later ESC 8 / alternate-buffer exit. Paint has left SGR
        // reset, so reconstruct that slot before restoring the active cursor/style/modes.
        val (top, bottom) = core.terminal.scrollRegion
        append("\u001b[$top;${bottom}r")
        core.terminal.savedCursorPosition?.let { (x, y) ->
            val savedOrigin = core.terminal.savedCursorOriginMode ?: false
            mode(6, savedOrigin)
            val savedRow = if (savedOrigin) y - top + 1 else y
            append("\u001b[${savedRow.coerceAtLeast(1)};${x}H")
            core.terminal.savedCursorStyle?.let { append(TerminalSnapshotEncoder.encodeStyle(it)) }
            mode(7, core.terminal.savedCursorAutoWrap ?: true)
            append("\u001b7")
        }
        mode(6, core.terminal.isOriginMode)
        val row = if (core.terminal.isOriginMode) core.terminal.cursorY - top + 1 else core.terminal.cursorY
        append("\u001b[${row.coerceAtLeast(1)};${core.terminal.cursorX}H")
        append("\u001b[4${if (core.terminal.isModelEnabled(TerminalMode.InsertMode)) 'h' else 'l'}")
        append("\u001b[20${if (core.terminal.isAutoNewLine()) 'h' else 'l'}")
        append(TerminalSnapshotEncoder.encodeStyle(core.terminal.currentTextStyle()))
        mode(1, core.terminal.isModelEnabled(TerminalMode.CursorKey))
        mode(7, core.terminal.isAutoWrap)
        mode(2004, core.display.bracketedPasteMode)
        append(if (core.terminal.isModelEnabled(TerminalMode.Keypad)) "\u001b=" else "\u001b>")
        val mouseMode = when (core.display.mouseModeFlow.value) {
            MouseMode.MOUSE_REPORTING_NORMAL -> 1000
            MouseMode.MOUSE_REPORTING_HILITE -> 1001
            MouseMode.MOUSE_REPORTING_BUTTON_MOTION -> 1002
            MouseMode.MOUSE_REPORTING_ALL_MOTION -> 1003
            MouseMode.MOUSE_REPORTING_FOCUS -> 1004
            MouseMode.MOUSE_REPORTING_NONE -> null
        }
        mouseMode?.let { mode(it, true) }
        when (core.display.mouseFormat) {
            MouseFormat.MOUSE_FORMAT_XTERM_EXT -> mode(1005, true)
            MouseFormat.MOUSE_FORMAT_SGR -> mode(1006, true)
            MouseFormat.MOUSE_FORMAT_URXVT -> mode(1015, true)
            MouseFormat.MOUSE_FORMAT_XTERM -> Unit
        }
    }
}
