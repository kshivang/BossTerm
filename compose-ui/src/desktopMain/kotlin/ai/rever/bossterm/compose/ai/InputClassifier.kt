package ai.rever.bossterm.compose.ai

/**
 * Lightweight, purely heuristic classifier that guesses whether a line of input
 * is **natural language** (a request to be handed to an AI agent) rather than a
 * shell command. There is no ML here — it simply counts natural-language words
 * against shell-ish tokens and breaks ties toward "command".
 *
 * The intent is conservative: only flag input as natural language when there is
 * a clear majority of natural-language words, so that genuine shell commands are
 * never misrouted to an AI agent.
 */
object InputClassifier {

    /** Common natural-language words that rarely appear in shell commands. */
    private val NL_WORDS: Set<String> = setOf(
        "how", "what", "why", "when", "where", "who", "which",
        "show", "list", "find", "create", "delete", "remove", "add",
        "the", "a", "an", "please", "can", "could", "would", "should",
        "i", "my", "me", "we", "you", "your",
        "to", "of", "in", "on", "for", "with", "from", "into", "about",
        "make", "get", "tell", "give", "help", "explain", "write",
        "is", "are", "do", "does", "did", "want", "need",
    )

    /** Substrings that strongly suggest a shell command rather than prose. */
    private val SHELL_TOKENS: List<String> = listOf(
        "&&", "||", "./", ".sh", "--", "|", "/", "-", "$", "~", ">", "<",
    )

    private val BARE_COMMAND = Regex("[a-z][a-z0-9_-]*")

    /**
     * Returns `true` when [input] looks like a natural-language request rather
     * than a shell command. Blank input always returns `false`.
     */
    fun isNaturalLanguage(input: String): Boolean {
        if (input.isBlank()) return false

        val words = input.trim().split(Regex("\\s+"))
        val lowerWords = words.map { it.lowercase() }

        val nlScore = lowerWords.count { it in NL_WORDS }

        var shellScore = SHELL_TOKENS.count { token -> input.contains(token) }

        // A bare first token (e.g. "ls", "git") in a very short line is a strong
        // command signal.
        val firstToken = words.first()
        if (BARE_COMMAND.matches(firstToken) && words.size <= 3) {
            shellScore += 1
        }

        return nlScore >= 2 && nlScore > shellScore
    }
}
