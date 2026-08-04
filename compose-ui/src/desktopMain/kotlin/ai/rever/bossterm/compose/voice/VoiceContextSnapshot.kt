package ai.rever.bossterm.compose.voice

/**
 * Sanitizing for the session snapshot both executors build.
 *
 * The snapshot lists each in-scope tab's title and working directory, and it is baked into the
 * agent's SYSTEM INSTRUCTIONS at mint time. Both fields are terminal-controlled: a title is whatever
 * the last OSC 0/1 said, so any program the terminal renders — a build log, `curl` output, a
 * dependency's startup banner — chooses that text. A working directory is a path someone can create.
 *
 * That makes it a stronger injection surface than tool output, on two counts: it lands in the system
 * prompt rather than in a tool result the model is told to be skeptical of, and it is there on the
 * FIRST call, before any tool has run. A title containing a newline could otherwise close the tab
 * list and open what reads like a new instruction.
 *
 * So: one line per field, control characters gone, and everything bounded. None of this makes an
 * injected *claim* untrue — the agent still reads attacker-influenceable text — but it stops that
 * text from impersonating the structure around it, and it stops one 4 KB title from filling the
 * whole prompt.
 */
internal object VoiceContextSnapshot {

    /** Per-field clip. Long enough for a real title or path, short enough that 40 of them are small. */
    const val MAX_FIELD_CHARS = 80

    /** Tabs listed before the rest are summarised. A share with more than this is unusual. */
    const val MAX_ENTRIES = 40

    /**
     * One field, safe to append to a line-oriented block.
     *
     * Control characters become spaces rather than being dropped, so `a\nb` reads as `a b` instead of
     * silently becoming the plausible-looking identifier `ab`. Runs of whitespace collapse, because a
     * title padded with 200 spaces is the same clipping problem in another shape.
     */
    fun field(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val flattened = buildString(raw.length) {
            for (ch in raw) {
                // isISOControl covers U+0000-U+001F and U+007F-U+009F: CR, LF, TAB, ESC and the
                // whole C1 range - everything that could break the line structure or open an escape
                // sequence. (An earlier version put a redundant literal beside this and claimed ESC
                // was outside the range. It is U+001B; it is not.)
                append(if (ch.isISOControl()) ' ' else ch)
            }
        }
        val collapsed = flattened.replace(WHITESPACE_RUN, " ").trim()
        return if (collapsed.length <= MAX_FIELD_CHARS) {
            collapsed
        } else {
            // The ellipsis is part of the budget, so the result never exceeds MAX_FIELD_CHARS.
            collapsed.take(MAX_FIELD_CHARS - 1).trimEnd() + "…"
        }
    }

    /**
     * Close a snapshot, noting anything left out.
     *
     * Said out loud rather than silently truncated: an agent told about 40 of 60 tabs should know the
     * list is partial, or it will confidently report that a tab does not exist.
     */
    fun withOverflowNote(body: String, shown: Int, total: Int): String {
        if (total <= shown) return body
        val hidden = total - shown
        return if (body.isEmpty()) {
            "- ($hidden more not listed - use list_tabs)"
        } else {
            "$body\n- (…and $hidden more not listed - use list_tabs)"
        }
    }

    /**
     * Whitespace to collapse, including the two Unicode line separators.
     *
     * U+2028 and U+2029 are the gap: not control characters, so isISOControl misses them, and Java's
     * ASCII-only \s misses them too - yet plenty of renderers and models treat them as line breaks.
     * For a function whose job is stopping terminal-controlled text from impersonating the structure
     * around it, they belong here.
     */
    private val WHITESPACE_RUN = Regex("[\\s\\u2028\\u2029]+")

}
