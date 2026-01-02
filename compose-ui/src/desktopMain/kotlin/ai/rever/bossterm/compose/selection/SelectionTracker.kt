package ai.rever.bossterm.compose.selection

import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.model.pool.VersionedBufferSnapshot
import java.util.IdentityHashMap

/**
 * Tracks selection across buffer changes and resolves anchors to coordinates.
 *
 * Uses identity-based line tracking (same pattern as IncrementalSnapshotBuilder).
 * When lines move in the cyclic buffer during scroll, their object identity
 * remains the same, so selection automatically follows content.
 *
 * Thread safety: This class is NOT thread-safe. Use from UI thread only.
 */
class SelectionTracker(private val textBuffer: TerminalTextBuffer) {
    private var selection: ContentAnchoredSelection? = null

    /**
     * Line identity cache: maps TerminalLine reference to its current row index.
     * Rebuilt when snapshot changes (tracked via lastCachedSnapshot).
     */
    private val lineIndexCache = IdentityHashMap<TerminalLine, Int>()

    /**
     * Reference to the snapshot used for the last cache build.
     * Used for cache invalidation - if snapshot identity changes, rebuild cache.
     */
    private var lastCachedSnapshot: VersionedBufferSnapshot? = null

    /**
     * Set selection from buffer coordinates.
     * Anchors are created from the current line objects at those positions.
     */
    fun setSelection(
        startCol: Int,
        startRow: Int,
        endCol: Int,
        endRow: Int,
        mode: SelectionMode
    ) {
        selection = ContentAnchoredSelection.fromBufferCoordinates(
            startCol, startRow, endCol, endRow, mode, textBuffer
        )
    }

    /**
     * Update only the end anchor (used during drag).
     */
    fun updateEnd(endCol: Int, endRow: Int) {
        val current = selection ?: return
        selection = current.copy(
            end = SelectionAnchor.fromBufferCoordinates(endCol, endRow, textBuffer)
        )
    }

    /**
     * Clear the current selection.
     */
    fun clearSelection() {
        selection = null
    }

    /**
     * Check if there is an active selection.
     */
    fun hasSelection(): Boolean = selection != null

    /**
     * Get the raw anchored selection (for debugging/inspection).
     */
    fun getSelection(): ContentAnchoredSelection? = selection

    /**
     * Resolve current selection to buffer coordinates.
     *
     * Uses identity-based lookup to find current row indices for anchored lines.
     * Returns null if selection is invalidated (both lines evicted/garbage collected).
     *
     * @param snapshot The current buffer snapshot for coordinate resolution
     * @return ResolvedSelection with concrete coordinates, or null if invalid
     */
    fun resolveToCoordinates(snapshot: VersionedBufferSnapshot): ResolvedSelection? {
        val sel = selection ?: return null

        // Rebuild identity cache from current snapshot
        buildLineIndexCache(snapshot)

        // Get line references (may be null if garbage collected)
        val startLine = sel.start.line
        val endLine = sel.end.line

        // Look up current positions by object identity
        // If line was garbage collected, treat as evicted (startRow/endRow will be null)
        val startRow = startLine?.let { lineIndexCache[it] }
        val endRow = endLine?.let { lineIndexCache[it] }

        return when {
            startRow != null && endRow != null -> {
                // Both anchors valid - normal case
                ResolvedSelection(
                    startCol = sel.start.column,
                    startRow = startRow,
                    endCol = sel.end.column,
                    endRow = endRow,
                    mode = sel.mode,
                    startContentChanged = sel.start.hasContentChanged(),
                    endContentChanged = sel.end.hasContentChanged()
                )
            }
            startRow != null -> {
                // End anchor evicted/GC'd - clamp to oldest history line
                val oldestRow = -snapshot.historyLinesCount
                ResolvedSelection(
                    startCol = sel.start.column,
                    startRow = startRow,
                    endCol = snapshot.width - 1,
                    endRow = oldestRow,
                    mode = sel.mode,
                    startContentChanged = sel.start.hasContentChanged(),
                    endContentChanged = true,
                    wasTruncated = true
                )
            }
            endRow != null -> {
                // Start anchor evicted/GC'd - clamp to oldest history line
                val oldestRow = -snapshot.historyLinesCount
                ResolvedSelection(
                    startCol = 0,
                    startRow = oldestRow,
                    endCol = sel.end.column,
                    endRow = endRow,
                    mode = sel.mode,
                    startContentChanged = true,
                    endContentChanged = sel.end.hasContentChanged(),
                    wasTruncated = true
                )
            }
            else -> {
                // Both evicted/GC'd - selection is invalid, clear it
                selection = null
                null
            }
        }
    }

    /**
     * Resolve to legacy Pair format for compatibility with existing rendering code.
     */
    fun resolveToLegacyPairs(snapshot: VersionedBufferSnapshot): Pair<Pair<Int, Int>?, Pair<Int, Int>?> {
        val resolved = resolveToCoordinates(snapshot) ?: return Pair(null, null)
        return Pair(resolved.toStartPair(), resolved.toEndPair())
    }

    /**
     * Build the line identity cache from the current snapshot.
     * Maps each ORIGINAL TerminalLine object to its current row index.
     *
     * CRITICAL: Must use originalLine (from buffer) not line (the copy).
     * SelectionAnchor stores WeakReferences to original line objects, so identity
     * lookup must match against originals to track lines across buffer scrolling.
     *
     * Cache invalidation: Only rebuilds if snapshot has changed (identity check).
     * This is safe because a new snapshot object is created on each buffer mutation.
     */
    private fun buildLineIndexCache(snapshot: VersionedBufferSnapshot) {
        // Skip rebuild if we're looking at the same snapshot
        if (lastCachedSnapshot === snapshot) {
            return
        }

        lineIndexCache.clear()

        // Map screen lines (indices 0 to height-1) using ORIGINAL reference
        for (i in 0 until snapshot.screenLines.size) {
            val versionedLine = snapshot.screenLines.getOrNull(i)
            if (versionedLine != null) {
                lineIndexCache[versionedLine.originalLine] = i
            }
        }

        // Map history lines (negative indices: -1, -2, ..., -historyLinesCount)
        for (i in 0 until snapshot.historyLinesCount) {
            val versionedLine = snapshot.historyLines.getOrNull(i)
            if (versionedLine != null) {
                // History index 0 = row -(historyLinesCount), index historyLinesCount-1 = row -1
                lineIndexCache[versionedLine.originalLine] = i - snapshot.historyLinesCount
            }
        }

        // Remember which snapshot we built the cache for
        lastCachedSnapshot = snapshot
    }

    /**
     * Invalidate the cache, forcing a rebuild on next resolveToCoordinates() call.
     * Call this if external factors may have changed line positions.
     */
    fun invalidateCache() {
        lastCachedSnapshot = null
    }
}
