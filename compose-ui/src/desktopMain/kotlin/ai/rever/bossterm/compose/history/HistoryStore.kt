package ai.rever.bossterm.compose.history

import java.io.File

/**
 * Loads shell command history from the common shell history files in the user's
 * home directory and merges it with the terminal's own block-command history.
 *
 * Each history file is stored oldest-first. The merged result is returned
 * newest-first and de-duplicated, capped at [MAX_ENTRIES] entries. Missing or
 * unreadable files are skipped gracefully.
 */
object HistoryStore {

    private const val MAX_ENTRIES = 2000

    /**
     * Reads shell history files and merges them with [blockCommands] (which are
     * treated as the most recent entries). Returns a newest-first,
     * de-duplicated list capped at [MAX_ENTRIES].
     */
    fun load(blockCommands: List<String>): List<String> {
        val home = System.getProperty("user.home") ?: return dedupeNewestFirst(blockCommands)

        // Oldest-first accumulation: shell files first, then block commands so
        // that block commands end up being the newest entries after merge.
        val merged = ArrayList<String>()

        merged += readZsh(File(home, ".zsh_history"))
        merged += readBash(File(home, ".bash_history"))
        merged += readFish(File(home, ".local/share/fish/fish_history"))
        merged += blockCommands

        return dedupeNewestFirst(merged)
    }

    /** Reverse to newest-first, then keep first occurrence of each command. */
    private fun dedupeNewestFirst(oldestFirst: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (cmd in oldestFirst.asReversed()) {
            val trimmed = cmd.trim()
            if (trimmed.isNotEmpty()) seen.add(trimmed)
            if (seen.size >= MAX_ENTRIES) break
        }
        return seen.toList()
    }

    private fun readZsh(file: File): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines(Charsets.ISO_8859_1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            // Extended history: ": <timestamp>:<duration>;<command>"
            if (line.startsWith(":")) line.substringAfter(';', "").ifBlank { null }
            else line
        }
    }.getOrDefault(emptyList())

    private fun readBash(file: File): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun readFish(file: File): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines().mapNotNull { line ->
            // Fish history YAML-ish: "- cmd: <command>"
            val trimmed = line.trimStart()
            if (trimmed.startsWith("- cmd: ")) trimmed.removePrefix("- cmd: ").ifBlank { null }
            else null
        }
    }.getOrDefault(emptyList())
}
