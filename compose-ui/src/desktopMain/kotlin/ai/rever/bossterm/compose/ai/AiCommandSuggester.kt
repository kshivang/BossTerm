package ai.rever.bossterm.compose.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Asks an external AI agent CLI to translate a natural-language request into a
 * single shell command.
 *
 * This depends on the chosen agent CLI supporting a one-shot "print" flag (for
 * example `--print` / `-p`) that emits the answer to stdout and exits. When the
 * CLI is unavailable, errors out, or exceeds the timeout, this degrades
 * gracefully to `null`.
 *
 * The returned command never has a trailing newline appended — the caller
 * decides how (or whether) to submit it.
 */
object AiCommandSuggester {

    private const val TIMEOUT_SECONDS = 20L

    /**
     * Runs [agentCommand] (split on spaces) with [printFlag] and a prompt
     * derived from [naturalLanguage], returning the first non-blank stdout line
     * trimmed, or `null` on timeout / any failure.
     */
    suspend fun suggest(
        agentCommand: String,
        printFlag: String,
        naturalLanguage: String,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = "Output ONLY a single shell command, no explanation, for: $naturalLanguage"

            val args = agentCommand.split(" ").filter { it.isNotBlank() } + printFlag + prompt
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }

            output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
        }.getOrNull()
    }
}
