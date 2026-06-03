package ai.rever.bossterm.compose.blocks

import ai.rever.bossterm.compose.selection.SelectionAnchor

/** Lifecycle state of a captured command, used to pick the gutter color. */
enum class BlockState { RUNNING, SUCCESS, ERROR }

/**
 * One command's vertical span in the buffer, captured from OSC 133 transitions.
 *
 * Endpoints are CONTENT anchors ([SelectionAnchor] = `WeakReference<TerminalLine>`
 * + `lineVersion`) — the same primitive the selection engine uses — so a block
 * survives scrolling and history eviction and resolves to live coordinates at
 * render time via
 * [ai.rever.bossterm.compose.selection.SelectionTracker.resolveAnchorRow].
 *
 * @property startAnchor anchor on the prompt/command line (OSC 133;B).
 * @property endAnchor anchor on the line where output ended (OSC 133;C/D);
 *   `null` while the command is still running.
 * @property exitCode process exit code (OSC 133;D); `null` while running.
 */
data class CommandBlock(
    val id: Long,
    val commandText: String?,
    val startAnchor: SelectionAnchor,
    val endAnchor: SelectionAnchor?,
    val exitCode: Int?,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
) {
    val state: BlockState
        get() = when {
            exitCode == null -> BlockState.RUNNING
            exitCode == 0 -> BlockState.SUCCESS
            else -> BlockState.ERROR
        }
}
