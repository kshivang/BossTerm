package ai.rever.bossterm.compose.blocks

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.compose.TerminalSession
import ai.rever.bossterm.compose.actions.KeyStroke
import ai.rever.bossterm.compose.actions.TerminalAction
import ai.rever.bossterm.compose.selection.SelectionEngine
import ai.rever.bossterm.compose.settings.SettingsManager

/**
 * Keyboard actions for command blocks (Phase 1). All actions are gated by
 * `commandBlocksEnabled`: their `enabled` lambda returns false when the feature
 * is off, so the key-dispatch loop skips them and the event falls through —
 * keeping behavior unchanged when disabled.
 *
 * Bindings avoid combos already used by builtin/tab/split actions:
 *   - Jump prev/next: Cmd/Ctrl+Shift+Up / Down
 *   - Copy last output: Cmd/Ctrl+Shift+C   (plain Cmd/Ctrl+C remains "copy selection")
 *   - Re-run last command: Cmd/Ctrl+Shift+R
 *
 * Self-contained: everything is reached through the [TerminalSession] interface
 * (`commandBlockTracker`, `selectionTracker`, `textBuffer`, `scrollOffset`,
 * `display`), so the same actions work for tabs and split panes.
 */
object CommandBlockActions {

    fun create(
        session: TerminalSession,
        clipboardManager: ClipboardManager,
        isMacOS: Boolean,
    ): List<TerminalAction> {

        fun enabled() = SettingsManager.instance.settings.value.commandBlocksEnabled

        fun blocks(): List<CommandBlock> = session.commandBlockTracker?.blocks?.value.orEmpty()

        /** Current top visible buffer row (scrollOffset is lines scrolled up). */
        fun viewportTop(): Int = -session.scrollOffset.value

        /** Resolve every block's start anchor to a buffer row against a fresh snapshot. */
        fun startRows(): List<Int> {
            val snap = session.textBuffer.createIncrementalSnapshot()
            return blocks().mapNotNull { session.selectionTracker.resolveAnchorRow(it.startAnchor, snap) }
        }

        /** Scroll so [row] sits near the top of the viewport. */
        fun jumpToRow(row: Int) {
            val historySize = session.textBuffer.historyLinesCount
            session.scrollOffset.value = (-row + 2).coerceIn(0, historySize)
            session.display.requestImmediateRedraw()
        }

        fun keys(key: Key, shift: Boolean = false): List<KeyStroke> =
            if (isMacOS) listOf(KeyStroke(key = key, meta = true, shift = shift))
            else listOf(KeyStroke(key = key, ctrl = true, shift = shift))

        val jumpPrev = TerminalAction(
            id = "block_jump_prev",
            name = "Jump to Previous Command",
            keyStrokes = keys(Key.DirectionUp, shift = true),
            enabled = { enabled() },
            handler = handler@{ _ ->
                if (!enabled()) return@handler false
                val top = viewportTop()
                val target = startRows().filter { it < top }.maxOrNull() ?: return@handler false
                jumpToRow(target)
                true
            }
        )

        val jumpNext = TerminalAction(
            id = "block_jump_next",
            name = "Jump to Next Command",
            keyStrokes = keys(Key.DirectionDown, shift = true),
            enabled = { enabled() },
            handler = handler@{ _ ->
                if (!enabled()) return@handler false
                val top = viewportTop()
                val target = startRows().filter { it > top }.minOrNull() ?: return@handler false
                jumpToRow(target)
                true
            }
        )

        val copyOutput = TerminalAction(
            id = "block_copy_last_output",
            name = "Copy Last Command Output",
            keyStrokes = keys(Key.C, shift = true),
            enabled = { enabled() && blocks().any { it.endAnchor != null } },
            handler = handler@{ _ ->
                if (!enabled()) return@handler false
                val snap = session.textBuffer.createIncrementalSnapshot()
                val block = blocks().lastOrNull { it.endAnchor != null } ?: return@handler false
                val startRow = session.selectionTracker.resolveAnchorRow(block.startAnchor, snap)
                    ?: return@handler false
                val endRow = block.endAnchor?.let { session.selectionTracker.resolveAnchorRow(it, snap) }
                    ?: return@handler false
                // Output spans the lines between the command line and the next prompt.
                val from = startRow + 1
                val to = endRow - 1
                if (to < from) return@handler false
                val text = SelectionEngine.extractSelectedText(
                    session.textBuffer,
                    start = 0 to from,
                    end = (session.textBuffer.width - 1) to to,
                    mode = SelectionMode.NORMAL,
                )
                if (text.isBlank()) return@handler false
                clipboardManager.setText(AnnotatedString(text))
                true
            }
        )

        val copyCommand = TerminalAction(
            id = "block_copy_last_command",
            name = "Copy Last Command",
            keyStrokes = emptyList(), // menu/programmatic only — avoids extra key bindings
            enabled = { enabled() && blocks().lastOrNull { it.commandText != null } != null },
            handler = handler@{ _ ->
                if (!enabled()) return@handler false
                val cmd = blocks().lastOrNull { it.commandText != null }?.commandText ?: return@handler false
                clipboardManager.setText(AnnotatedString(cmd))
                true
            }
        )

        val rerun = TerminalAction(
            id = "block_rerun_last",
            name = "Re-run Last Command",
            keyStrokes = keys(Key.R, shift = true),
            enabled = { enabled() && blocks().lastOrNull { it.commandText != null } != null },
            handler = handler@{ _ ->
                if (!enabled()) return@handler false
                val cmd = blocks().lastOrNull { it.commandText != null }?.commandText ?: return@handler false
                session.writeUserInput(cmd + "\n")
                true
            }
        )

        return listOf(jumpPrev, jumpNext, copyOutput, copyCommand, rerun)
    }
}
