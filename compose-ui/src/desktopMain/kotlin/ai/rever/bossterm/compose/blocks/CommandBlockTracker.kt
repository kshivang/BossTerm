package ai.rever.bossterm.compose.blocks

import ai.rever.bossterm.compose.selection.SelectionAnchor
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.terminal.model.CommandStateListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-tab tracker that turns OSC 133 command-state events into [CommandBlock]s.
 *
 * Mirrors [ai.rever.bossterm.compose.mcp.LastCommandTracker]: it is registered on
 * the terminal's command-state listeners and its callbacks may arrive off the UI
 * thread (driven by the emulator). Captured blocks are published through [blocks];
 * the renderer and block actions read them only when `commandBlocksEnabled` is on,
 * so this tracker is capture-only (no visible effect) when the feature is disabled.
 *
 * The typed command line is supplied out-of-band by
 * [ai.rever.bossterm.compose.osc.CommandLineOSCListener] (OSC 1341;BossTermCmd)
 * into [pendingCommandText] just before [onCommandStarted].
 */
class CommandBlockTracker(
    private val tab: TerminalTab,
    private val maxBlocks: Int = 500,
) : CommandStateListener {

    private val _blocks = MutableStateFlow<List<CommandBlock>>(emptyList())
    val blocks: StateFlow<List<CommandBlock>> = _blocks.asStateFlow()

    /** Set by the OSC-1341 command-line listener just before [onCommandStarted]. */
    @Volatile
    var pendingCommandText: String? = null

    private val lock = Any()
    private var nextId = 0L
    private var openId = -1L

    /** Anchor at the prompt line (OSC 133;A), used as the block's start so the
     *  gutter spans the prompt + command + output (not just the output). */
    @Volatile
    private var pendingPromptAnchor: SelectionAnchor? = null

    /** Anchor at the current cursor row (screen-relative; history is negative). */
    private fun anchorAtCursor(): SelectionAnchor =
        SelectionAnchor.fromBufferCoordinates(
            col = 0,
            row = tab.terminal.cursorY,
            textBuffer = tab.textBuffer,
        )

    override fun onPromptStarted() {
        // OSC 133;A — remember where the prompt begins; the next command's block
        // starts here so the gutter aligns with the prompt line, not the output.
        pendingPromptAnchor = anchorAtCursor()
    }

    override fun onCommandStarted() {
        // OSC 133;B — start the block at the prompt line captured at 133;A.
        val cmd = pendingCommandText
        pendingCommandText = null
        val anchor = pendingPromptAnchor ?: anchorAtCursor()
        pendingPromptAnchor = null
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val id = nextId++
            openId = id
            val block = CommandBlock(
                id = id,
                commandText = cmd,
                startAnchor = anchor,
                endAnchor = null,
                exitCode = null,
                startedAtMs = now,
                finishedAtMs = null,
            )
            _blocks.value = (_blocks.value + block).takeLast(maxBlocks)
        }
    }

    override fun onCommandOutputEnded() {
        // OSC 133;C — not emitted by every shell; treated as best-effort.
        val end = anchorAtCursor()
        synchronized(lock) { update(openId) { it.copy(endAnchor = end) } }
    }

    override fun onCommandFinished(exitCode: Int) {
        // OSC 133;D — close the block and record the exit code.
        val end = anchorAtCursor()
        val finishedAt = System.currentTimeMillis()
        val closed: CommandBlock?
        synchronized(lock) {
            closed = update(openId) {
                it.copy(
                    endAnchor = it.endAnchor ?: end,
                    exitCode = exitCode,
                    finishedAtMs = finishedAt,
                )
            }
            openId = -1L
        }
        // Backfill the MCP last-command record with the captured command text
        // (LastCommandTracker intentionally leaves commandText null).
        closed?.commandText?.let { text ->
            tab.lastCommand.value = tab.lastCommand.value?.copy(commandText = text)
        }
    }

    /** Replace the block with [id] via [transform]. Caller must hold [lock]. */
    private fun update(id: Long, transform: (CommandBlock) -> CommandBlock): CommandBlock? {
        if (id < 0L) return null
        val list = _blocks.value
        val idx = list.indexOfLast { it.id == id }
        if (idx < 0) return null
        val updated = transform(list[idx])
        _blocks.value = list.toMutableList().also { it[idx] = updated }
        return updated
    }
}
