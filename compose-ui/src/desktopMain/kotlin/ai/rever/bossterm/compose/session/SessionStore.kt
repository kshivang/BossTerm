package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.TerminalSession
import ai.rever.bossterm.compose.splits.SplitNode
import ai.rever.bossterm.compose.splits.SplitOrientation
import ai.rever.bossterm.compose.splits.SplitViewState
import ai.rever.bossterm.compose.tabs.TerminalTab
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Serializable snapshot of a split tree node (structure + per-leaf cwd only). */
@Serializable
sealed class NodeSnap

@Serializable
@SerialName("pane")
data class PaneSnap(val cwd: String? = null) : NodeSnap()

@Serializable
@SerialName("vsplit")
data class VSplitSnap(val left: NodeSnap, val right: NodeSnap, val ratio: Float = 0.5f) : NodeSnap()

@Serializable
@SerialName("hsplit")
data class HSplitSnap(val top: NodeSnap, val bottom: NodeSnap, val ratio: Float = 0.5f) : NodeSnap()

@Serializable
data class TabSnap(val title: String, val tree: NodeSnap)

@Serializable
data class SessionSnap(val tabs: List<TabSnap>, val activeTab: Int = 0)

/**
 * Persists window structure (tabs + split layout + per-pane cwd, NOT terminal
 * content) to `~/.bossterm/session.json` for Phase 6 session restore. Honors
 * `bossterm.settings.dir` to match SettingsManager.
 */
object SessionStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private fun file(): File {
        val override = System.getProperty("bossterm.settings.dir")?.takeIf { it.isNotBlank() }
        val dir = if (override != null) File(override) else File(System.getProperty("user.home"), ".bossterm")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "session.json")
    }

    fun save(snap: SessionSnap) {
        runCatching { file().writeText(json.encodeToString(snap)) }
    }

    fun load(): SessionSnap? {
        val f = file()
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<SessionSnap>(f.readText()) }.getOrNull()
    }

    /** Build a snapshot from the live tabs. [splitStateFor] returns a tab's split state, if any. */
    fun capture(
        tabs: List<TerminalTab>,
        splitStateFor: (TerminalTab) -> SplitViewState?,
        activeTab: Int,
    ): SessionSnap = SessionSnap(
        tabs = tabs.map { tab ->
            val tree = splitStateFor(tab)?.rootNode?.let(::snapshotNode)
                ?: PaneSnap(tab.workingDirectory.value)
            TabSnap(title = tab.title.value, tree = tree)
        },
        activeTab = activeTab,
    )

    private fun snapshotNode(node: SplitNode): NodeSnap = when (node) {
        is SplitNode.Pane -> PaneSnap(node.session.workingDirectory.value)
        is SplitNode.VerticalSplit -> VSplitSnap(snapshotNode(node.left), snapshotNode(node.right), node.ratio)
        is SplitNode.HorizontalSplit -> HSplitSnap(snapshotNode(node.top), snapshotNode(node.bottom), node.ratio)
    }

    /** cwd of the leftmost/topmost leaf — used to seed a freshly-split pane's session. */
    fun firstLeafCwd(node: NodeSnap): String? = when (node) {
        is PaneSnap -> node.cwd
        is VSplitSnap -> firstLeafCwd(node.left)
        is HSplitSnap -> firstLeafCwd(node.top)
    }

    /**
     * Recreate [node]'s split layout inside [state], expanding the pane [paneId]
     * (which already holds the leftmost leaf's session). [makeSession] creates a
     * session seeded with a given cwd for each newly-split pane.
     */
    fun rebuildTree(
        node: NodeSnap,
        state: SplitViewState,
        paneId: String,
        makeSession: (String?) -> TerminalSession,
    ) {
        when (node) {
            is PaneSnap -> { /* leaf already present */ }
            is VSplitSnap -> {
                state.setFocusedPane(paneId)
                val newId = state.splitFocusedPane(
                    SplitOrientation.VERTICAL, makeSession(firstLeafCwd(node.right)), node.ratio
                ) ?: run {
                    // A split failed mid-rebuild (focused pane unresolved); abandon the
                    // rest of this subtree. The tab is left with a partial layout — no
                    // rollback, but log it so a half-restored split isn't silent.
                    System.err.println("WARN: SessionStore.rebuildTree — vertical split failed; tab restored with partial layout")
                    return
                }
                rebuildTree(node.left, state, paneId, makeSession)
                rebuildTree(node.right, state, newId, makeSession)
            }
            is HSplitSnap -> {
                state.setFocusedPane(paneId)
                val newId = state.splitFocusedPane(
                    SplitOrientation.HORIZONTAL, makeSession(firstLeafCwd(node.bottom)), node.ratio
                ) ?: run {
                    System.err.println("WARN: SessionStore.rebuildTree — horizontal split failed; tab restored with partial layout")
                    return
                }
                rebuildTree(node.top, state, paneId, makeSession)
                rebuildTree(node.bottom, state, newId, makeSession)
            }
        }
    }
}
