package ai.rever.bossterm.compose.palette

/**
 * Tiny subsequence fuzzy matcher (skim-style) for ranking command-palette
 * entries. Case-smart (a lowercase query char matches either case; an uppercase
 * query char must match exactly), rewards contiguous runs and word-boundary
 * hits, and prefers earlier first matches. Roughly O(text length) per candidate.
 */
object FuzzyMatch {

    data class Result(val score: Int, val matchedIndices: List<Int>)

    /** Returns null when [query] is not a subsequence of [text]; higher score = better. */
    fun score(text: String, query: String): Result? {
        if (query.isEmpty()) return Result(0, emptyList())
        var ti = 0
        var prev = -2
        var score = 0
        val idx = ArrayList<Int>(query.length)
        for (qc in query) {
            var found = -1
            while (ti < text.length) {
                val c = text[ti]
                val hit = if (qc.isUpperCase()) c == qc else c.lowercaseChar() == qc.lowercaseChar()
                if (hit) { found = ti; ti++; break }
                ti++
            }
            if (found < 0) return null
            idx += found
            score += 10
            if (found == prev + 1) score += 15                               // contiguous run
            if (found == 0 || !text[found - 1].isLetterOrDigit()) score += 8  // word boundary
            if (text[found] == qc) score += 4                                 // exact case
            score -= minOf(found, 20)                                         // earlier is better
            prev = found
        }
        return Result(score, idx)
    }
}
