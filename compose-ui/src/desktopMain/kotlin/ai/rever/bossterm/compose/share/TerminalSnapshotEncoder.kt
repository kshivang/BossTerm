package ai.rever.bossterm.compose.share

import ai.rever.bossterm.terminal.TerminalColor
import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.BufferSnapshot
import ai.rever.bossterm.terminal.model.TerminalLine

/**
 * Serializes a terminal buffer to a **styled** escape/text blob for [ServerMessage.PaneSnapshot] —
 * scrollback + screen with per-run SGR escapes so a fresh viewer paints in color, then parks the
 * cursor at its real position. Shared by [MirrorShare] (GUI tabs) and the daemon's attach server
 * (headless [ai.rever.bossterm.compose.daemon.TerminalSessionCore]s) so both produce byte-identical
 * snapshots. Pure: takes a [BufferSnapshot] + cursor cell, returns the blob.
 */
object TerminalSnapshotEncoder {

    fun encode(snapshot: BufferSnapshot, cursorX: Int, cursorY: Int): String {
        val sb = StringBuilder()
        var row = -snapshot.historyLinesCount
        while (row < snapshot.height) {
            appendStyledLine(sb, snapshot.getLine(row))
            if (row < snapshot.height - 1) sb.append("\r\n")
            row++
        }
        sb.append("[0m") // reset trailing style
        // Park the cursor at its real screen position (1-based row;col) — otherwise the viewer
        // leaves it on the bottom row after the full-height blob (scrollback + screen).
        val cy = cursorY.coerceIn(1, snapshot.height)
        val cx = cursorX.coerceAtLeast(1)
        sb.append("[$cy;${cx}H")
        return sb.toString()
    }

    /** Append one buffer line as SGR-prefixed styled runs, trimming invisible trailing padding. */
    private fun appendStyledLine(sb: StringBuilder, line: TerminalLine) {
        val runs = ArrayList<TerminalLine.TextEntry>()
        for (e in line.entries) {
            if (e == null) continue
            if (e.isNul) break
            runs.add(e)
        }
        var end = runs.size
        while (end > 0 && runs[end - 1].let { it.text.toString().isBlank() && it.style.background == null }) end--
        for (i in 0 until end) {
            sb.append(ansiForStyle(runs[i].style)).append(runs[i].text.toString())
        }
    }

    /** SGR escape that resets then applies [style]'s colors + attributes (256-color / truecolor). */
    private fun ansiForStyle(style: TextStyle): String {
        val codes = ArrayList<String>()
        codes.add("0")
        if (style.hasOption(TextStyle.Option.BOLD)) codes.add("1")
        if (style.hasOption(TextStyle.Option.DIM)) codes.add("2")
        if (style.hasOption(TextStyle.Option.ITALIC)) codes.add("3")
        if (style.hasOption(TextStyle.Option.UNDERLINED)) codes.add("4")
        if (style.hasOption(TextStyle.Option.SLOW_BLINK)) codes.add("5")
        if (style.hasOption(TextStyle.Option.RAPID_BLINK)) codes.add("6")
        if (style.hasOption(TextStyle.Option.INVERSE)) codes.add("7")
        if (style.hasOption(TextStyle.Option.HIDDEN)) codes.add("8")
        style.foreground?.let { codes.add(sgrColor(it, fg = true)) }
        style.background?.let { codes.add(sgrColor(it, fg = false)) }
        return "[" + codes.joinToString(";") + "m"
    }

    private fun sgrColor(c: TerminalColor, fg: Boolean): String {
        val base = if (fg) "38" else "48"
        return if (c.isIndexed) "$base;5;${c.colorIndex}"
        else c.toColor().let { "$base;2;${it.red};${it.green};${it.blue}" }
    }
}
