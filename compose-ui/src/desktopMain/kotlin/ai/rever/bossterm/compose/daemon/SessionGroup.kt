package ai.rever.bossterm.compose.daemon

import java.util.UUID

/** Orientation of a daemon-side pane split. Kept separate from the GUI's
 *  `ai.rever.bossterm.compose.splits.SplitOrientation` so this module has no GUI dependency. */
enum class SplitOrientation { HORIZONTAL, VERTICAL }

/**
 * Daemon-side split tree: the headless analogue of the GUI's `SplitNode`, but leaves hold a
 * session id (String) rather than a `TerminalSession`, so this module never depends on GUI-only
 * session types. One [GroupNode] tree = one "window" the GUI renders as a tab with its own split
 * layout; a single-pane tree is an ordinary daemon tab.
 */
sealed class GroupNode {
    abstract val id: String

    data class Pane(
        override val id: String = UUID.randomUUID().toString(),
        val sessionId: String,
    ) : GroupNode()

    data class VerticalSplit(
        override val id: String = UUID.randomUUID().toString(),
        val left: GroupNode,
        val right: GroupNode,
        val ratio: Float = 0.5f,
    ) : GroupNode()

    data class HorizontalSplit(
        override val id: String = UUID.randomUUID().toString(),
        val top: GroupNode,
        val bottom: GroupNode,
        val ratio: Float = 0.5f,
    ) : GroupNode()
}

/** Find a pane by its node id. */
fun GroupNode.findPane(targetId: String): GroupNode.Pane? = when (this) {
    is GroupNode.Pane -> if (this.id == targetId) this else null
    is GroupNode.VerticalSplit -> left.findPane(targetId) ?: right.findPane(targetId)
    is GroupNode.HorizontalSplit -> top.findPane(targetId) ?: bottom.findPane(targetId)
}

/** Find a pane by the session id it hosts. */
fun GroupNode.findPaneBySessionId(sessionId: String): GroupNode.Pane? = when (this) {
    is GroupNode.Pane -> if (this.sessionId == sessionId) this else null
    is GroupNode.VerticalSplit -> left.findPaneBySessionId(sessionId) ?: right.findPaneBySessionId(sessionId)
    is GroupNode.HorizontalSplit -> top.findPaneBySessionId(sessionId) ?: bottom.findPaneBySessionId(sessionId)
}

/** All panes (leaf nodes) in the tree. */
fun GroupNode.getAllPanes(): List<GroupNode.Pane> = when (this) {
    is GroupNode.Pane -> listOf(this)
    is GroupNode.VerticalSplit -> left.getAllPanes() + right.getAllPanes()
    is GroupNode.HorizontalSplit -> top.getAllPanes() + bottom.getAllPanes()
}

/** All session ids in the tree. */
fun GroupNode.getAllSessionIds(): List<String> = getAllPanes().map { it.sessionId }

/** Replace a node in the tree with a new node. Returns the new tree root, or the original if
 *  [targetId] isn't found. */
fun GroupNode.replaceNode(targetId: String, transform: (GroupNode) -> GroupNode): GroupNode {
    if (this.id == targetId) return transform(this)
    return when (this) {
        is GroupNode.Pane -> this
        is GroupNode.VerticalSplit -> copy(
            left = left.replaceNode(targetId, transform),
            right = right.replaceNode(targetId, transform),
        )
        is GroupNode.HorizontalSplit -> copy(
            top = top.replaceNode(targetId, transform),
            bottom = bottom.replaceNode(targetId, transform),
        )
    }
}

/** Remove a pane from the tree, collapsing its parent split (the sibling takes the parent's
 *  slot). Returns null if [targetId] is the root pane (can't remove the last pane). */
fun GroupNode.removePane(targetId: String): GroupNode? {
    if (this is GroupNode.Pane && this.id == targetId) return null
    return when (this) {
        is GroupNode.Pane -> this
        is GroupNode.VerticalSplit -> when {
            left.id == targetId -> right
            right.id == targetId -> left
            else -> {
                val newLeft = left.removePane(targetId)
                val newRight = right.removePane(targetId)
                when {
                    newLeft == null -> right
                    newRight == null -> left
                    newLeft != left || newRight != right -> copy(left = newLeft, right = newRight)
                    else -> this
                }
            }
        }
        is GroupNode.HorizontalSplit -> when {
            top.id == targetId -> bottom
            bottom.id == targetId -> top
            else -> {
                val newTop = top.removePane(targetId)
                val newBottom = bottom.removePane(targetId)
                when {
                    newTop == null -> bottom
                    newBottom == null -> top
                    newTop != top || newBottom != bottom -> copy(top = newTop, bottom = newBottom)
                    else -> this
                }
            }
        }
    }
}

/** Update the ratio of a split node. */
fun GroupNode.updateRatio(targetId: String, newRatio: Float, minRatio: Float = 0.1f): GroupNode {
    val clampedRatio = newRatio.coerceIn(minRatio, 1f - minRatio)
    return when (this) {
        is GroupNode.Pane -> this
        is GroupNode.VerticalSplit -> if (this.id == targetId) {
            copy(ratio = clampedRatio)
        } else {
            copy(left = left.updateRatio(targetId, newRatio, minRatio), right = right.updateRatio(targetId, newRatio, minRatio))
        }
        is GroupNode.HorizontalSplit -> if (this.id == targetId) {
            copy(ratio = clampedRatio)
        } else {
            copy(top = top.updateRatio(targetId, newRatio, minRatio), bottom = bottom.updateRatio(targetId, newRatio, minRatio))
        }
    }
}

/** The parent split's id for a given node, or null if [targetId] is the root or not found. */
fun GroupNode.findParentId(targetId: String): String? = when (this) {
    is GroupNode.Pane -> null
    is GroupNode.VerticalSplit -> when {
        left.id == targetId || right.id == targetId -> this.id
        else -> left.findParentId(targetId) ?: right.findParentId(targetId)
    }
    is GroupNode.HorizontalSplit -> when {
        top.id == targetId || bottom.id == targetId -> this.id
        else -> top.findParentId(targetId) ?: bottom.findParentId(targetId)
    }
}

/** Convert to the wire-serializable [GroupTreeDto]. */
fun GroupNode.toDto(): GroupTreeDto = when (this) {
    is GroupNode.Pane -> GroupTreeDto.Pane(paneId = id, sessionId = sessionId)
    is GroupNode.VerticalSplit -> GroupTreeDto.Split(id, "v", ratio, left.toDto(), right.toDto())
    is GroupNode.HorizontalSplit -> GroupTreeDto.Split(id, "h", ratio, top.toDto(), bottom.toDto())
}
