package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.terminal.model.CommandStateListener
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Immutable record of the most recently completed shell command for a tab.
 *
 * Populated from OSC 133 shell-integration sequences (A/B/C/D). See
 * [CommandStateListener] and `.claude/rules/shell-integration.md` for the
 * sequence semantics and required shell setup.
 *
 * @property commandText Best-effort capture of the command line that ran.
 *   Currently always `null` because reliably extracting the typed command from
 *   the text buffer at OSC 133;B time is fragile (multi-line prompts, prompt
 *   markers not yet present in buffer, RPROMPT, etc.) and we prefer "null over
 *   wrong". The field is kept so future work can populate it without changing
 *   the wire shape consumed by the MCP tool.
 * @property exitCode Process exit code reported by OSC 133;D (0 = success).
 * @property startedAtMs Epoch millis when OSC 133;B (command start) fired.
 *   Falls back to the OSC 133;A time, or the finish time if neither was seen.
 * @property finishedAtMs Epoch millis when OSC 133;D (command finished) fired.
 * @property cwd Working directory captured at command-start time (OSC 7).
 *   May be `null` if no OSC 7 had been emitted before the command ran.
 */
data class LastCommand(
    val commandText: String?,
    val exitCode: Int,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val cwd: String?
)

/**
 * Per-tab tracker that listens for OSC 133 command-state events and publishes
 * the most recently completed command into [TerminalTab.lastCommand].
 *
 * Lifetime: one instance per tab, registered alongside the existing
 * [ai.rever.bossterm.compose.notification.CommandNotificationHandler]. The
 * notification handler is left untouched; this listener is additive.
 *
 * Threading: listener callbacks may arrive off the UI thread (driven by the
 * emulator on `Dispatchers.Default`). All mutable state lives in plain fields
 * on this object — guarded by [lock] for write visibility — and is published
 * through [TerminalTab.lastCommand] (a [MutableStateFlow], which is
 * thread-safe). Compose snapshot state is intentionally not touched here.
 */
class LastCommandTracker(
    private val tab: TerminalTab
) : CommandStateListener {

    private val lock = Any()

    // In-progress state for the currently-running command. Reset on D.
    private var promptStartedAtMs: Long = 0L
    private var commandStartedAtMs: Long = 0L
    private var cwdAtStart: String? = null
    private var pendingCapture: Boolean = false

    override fun onPromptStarted() {
        synchronized(lock) {
            promptStartedAtMs = System.currentTimeMillis()
        }
    }

    override fun onCommandStarted() {
        // OSC 133;B — user pressed Enter, command is about to execute.
        // Capture CWD now (before the command can chdir) and start the clock.
        val nowMs = System.currentTimeMillis()
        val cwdNow = tab.workingDirectory.value
        synchronized(lock) {
            commandStartedAtMs = nowMs
            cwdAtStart = cwdNow
            pendingCapture = true
        }
        // NOTE: We intentionally do NOT attempt to read the command text from
        // the text buffer here. See [LastCommand.commandText] kdoc.
    }

    override fun onCommandFinished(exitCode: Int) {
        val finishedAtMs = System.currentTimeMillis()
        val started: Long
        val cwd: String?
        synchronized(lock) {
            // If we never saw a B, fall back to A, then to the finish time so
            // duration is non-negative.
            started = when {
                pendingCapture && commandStartedAtMs > 0L -> commandStartedAtMs
                promptStartedAtMs > 0L -> promptStartedAtMs
                else -> finishedAtMs
            }
            cwd = cwdAtStart ?: tab.workingDirectory.value
            // Reset transient state for the next command.
            commandStartedAtMs = 0L
            cwdAtStart = null
            pendingCapture = false
        }

        tab.lastCommand.value = LastCommand(
            commandText = null,
            exitCode = exitCode,
            startedAtMs = started,
            finishedAtMs = finishedAtMs,
            cwd = cwd
        )
    }
}
