package ai.rever.bossterm.compose.session

import ai.rever.bossterm.compose.util.submitLine
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.CommandStateListener
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Runs a command against a session model and input sink, independently of UI and transport.
 * Callers serialize runs on the same session and apply their own settings/argument limits. */
object SessionCommandRunner {
    private val log = org.slf4j.LoggerFactory.getLogger(SessionCommandRunner::class.java)
    private const val TUI_POLL_INTERVAL_MS = 100L

    suspend fun run(
        terminal: BossTerminal,
        textBuffer: TerminalTextBuffer,
        writeInput: (String) -> Unit,
        script: String,
        timeoutMs: Int,
        freshlyCreated: Boolean = false,
        shellReadyTimeoutMs: Long = 0,
        maxOutputChars: Int,
        isAlive: (() -> Boolean)? = null,
    ): Result {

        val promptReadySignal = CompletableDeferred<Unit>()
        val finishedSignal = CompletableDeferred<CommandFinish>()
        // Snapshotted on the FIRST onCommandStarted that fires after our
        // writeUserInput. -1 sentinel = "no B observed for our command yet";
        // falls back to (historyAtSend, cursorYAtSend) sampled right before
        // write. The "first-B" gate covers both:
        //  - compound scripts (multi-statement, embedded \n) — keep the
        //    start mark anchored at our command's FIRST B, don't let later
        //    statements' B events stomp it.
        //  - prior pane activity — `weHaveWritten` is flipped right before
        //    we send, so any B/D events from a prior writer (run_in_panel,
        //    send_input, user typing) are ignored.
        //
        // Atomic primitives because the listener fires on the emulator
        // thread while our coroutine writes from a Dispatcher worker;
        // captured `var`s would be a kotlin.jvm.internal.Ref whose `element`
        // field isn't volatile, no JMM ordering guarantees.
        val historyAtB = AtomicInteger(-1)
        val cursorYAtB = AtomicInteger(-1)
        // End-of-command marks captured at D-time, inside the listener — NOT
        // after the coroutine resumes, by which point the shell may have drawn
        // the next prompt or scrolled. -1 sentinel = "D hasn't fired for our
        // command" (timeout / TUI path), in which case we fall back to a live
        // sample.
        val historyAtD = AtomicInteger(-1)
        val cursorYAtD = AtomicInteger(-1)
        val weHaveWritten = AtomicBoolean(false)
        val ourCommandStarted = AtomicBoolean(false)

        val listener = object : CommandStateListener {
            override fun onPromptStarted() {
                // SDK uses a one-shot deferred; subsequent A events (the next
                // prompt that fires right after D) are ignored.
                if (!promptReadySignal.isCompleted) promptReadySignal.complete(Unit)
            }
            override fun onCommandStarted() {
                if (!weHaveWritten.get()) return    // prior writer's B
                if (!ourCommandStarted.compareAndSet(false, true)) return // later statement
                historyAtB.set(textBuffer.historyLinesCount)
                cursorYAtB.set(terminal.cursorY - 1)
            }
            override fun onCommandFinished(exitCode: Int) {
                if (!ourCommandStarted.get()) return // D from a prior writer's command
                if (!finishedSignal.isCompleted) {
                    // Snapshot end position at D-time, before completing the
                    // signal wakes the coroutine and the emulator races ahead.
                    historyAtD.set(textBuffer.historyLinesCount)
                    cursorYAtD.set(terminal.cursorY - 1)
                    finishedSignal.complete(CommandFinish.Done(exitCode))
                }
            }
        }
        terminal.addCommandStateListener(listener)

        return try {
            // Freshly-created panes haven't seen their first prompt yet.
            // Reused panes already received A from PROMPT_COMMAND after the
            // previous D, so our listener wouldn't see another A — skip the wait.
            if (freshlyCreated && shellReadyTimeoutMs > 0) {
                val ready = withTimeoutOrNull(shellReadyTimeoutMs) {
                    coroutineScope {
                        val exitPoller = isAlive?.let { alive -> launch {
                            while (isActive) {
                                if (!alive()) { promptReadySignal.complete(Unit); break }
                                delay(TUI_POLL_INTERVAL_MS)
                            }
                        } }
                        try { promptReadySignal.await() } finally { exitPoller?.cancel() }
                    }
                }
                if (ready == null) {
                    log.debug(
                        "run_command: OSC 133;A not seen within {} ms on fresh pane; " +
                                "sending script anyway (shell integration may be missing)",
                        shellReadyTimeoutMs
                    )
                }
            }

            if (isAlive?.invoke() == false) {
                return Result(null, "", false, "Terminal session exited before the command could be submitted.")
            }

            // Fallback start mark, in case B never fires (shell-integration missing
            // or a degenerate command path).
            val historyAtSend = textBuffer.historyLinesCount
            val cursorYAtSend = terminal.cursorY - 1

            val toWrite = submitLine(script)
            // Flip the gate BEFORE the write so the listener counts the next
            // B as ours. This races ONLY with the user concurrently running
            // their own command in this same pane: if they hit Enter (firing a
            // B) in the microsecond between this flip and the shell consuming
            // our bytes, their command's B would be attributed to us and anchor
            // historyAtB to the wrong row. The per-pane mutex does NOT guard
            // this — it serializes MCP callers, not the human at the keyboard.
            // Accepted: a user typing into the MCP scratch pane mid-call is
            // rare, and the alternative (flipping after the write) would lose B
            // events for very-fast shells, which is the common case.
            weHaveWritten.set(true)
            writeInput(toWrite)

            val finish = withTimeoutOrNull(timeoutMs.toLong()) {
                coroutineScope {
                    // Alternate-screen poll runs alongside the OSC 133;D wait.
                    // Whichever fires first completes finishedSignal. TUI detection
                    // doesn't kill the process — the user can still send_input.
                    //
                    // Corner case: a command that briefly enters the alternate
                    // screen and then exits cleanly (emitting D) can be reported
                    // as "TUI detected" if a poll tick lands during that window,
                    // since the poller wins the race. Persistent TUIs (vim, a
                    // pager you must quit) — the case this is for — are detected
                    // correctly; only self-exiting alt-screen excursions can
                    // false-positive, which is rare enough to accept.
                    val tuiPoller = launch {
                        while (isActive) {
                            if (isAlive?.invoke() == false) {
                                finishedSignal.complete(CommandFinish.SessionExited)
                                break
                            }
                            if (textBuffer.isUsingAlternateBuffer) {
                                if (!finishedSignal.isCompleted) {
                                    finishedSignal.complete(CommandFinish.TuiDetected)
                                }
                                break
                            }
                            delay(TUI_POLL_INTERVAL_MS)
                        }
                    }
                    val outcome = finishedSignal.await()
                    tuiPoller.cancel()
                    outcome
                }
            }

            // Prefer the D-time snapshot (captured inside the listener); fall
            // back to a live sample only when D never fired (timeout / TUI).
            val dSnapHistory = historyAtD.get()
            val dSnapCursorY = cursorYAtD.get()
            val historyAtEnd = if (dSnapHistory >= 0) dSnapHistory else textBuffer.historyLinesCount
            val cursorYAtEnd = if (dSnapCursorY >= 0) dSnapCursorY else terminal.cursorY - 1
            val bSnapHistory = historyAtB.get()
            val bSnapCursorY = cursorYAtB.get()
            val startHistory = if (bSnapHistory >= 0) bSnapHistory else historyAtSend
            val startCursorY = if (bSnapCursorY >= 0) bSnapCursorY else cursorYAtSend

            when (finish) {
                null, CommandFinish.SessionExited -> {
                    val sliced = sliceCommandOutput(
                        textBuffer = textBuffer,
                        startHistory = startHistory,
                        startCursorY = startCursorY,
                        endHistory = historyAtEnd,
                        endCursorY = cursorYAtEnd,
                        maxOutputChars = maxOutputChars
                    )
                    Result(
                        exitCode = null,
                        output = stripEchoedCommandLine(sliced.text, script),
                        truncated = true,
                        error = if (finish == null) {
                            "Timed out after ${timeoutMs}ms waiting for command to finish. Partial output captured."
                        } else "Terminal session exited before command completion. Partial output captured."
                    )
                }
                is CommandFinish.TuiDetected -> Result(
                    exitCode = null,
                    output = "",
                    truncated = false,
                    error = "TUI detected (alternate screen entered). Use send_input + " +
                            "read_scrollback to drive the program, or rerun with " +
                            "non-interactive flags."
                )
                is CommandFinish.Done -> {
                    val sliced = sliceCommandOutput(
                        textBuffer = textBuffer,
                        startHistory = startHistory,
                        startCursorY = startCursorY,
                        endHistory = historyAtEnd,
                        endCursorY = cursorYAtEnd,
                        maxOutputChars = maxOutputChars
                    )
                    Result(
                        exitCode = finish.exitCode,
                        output = stripEchoedCommandLine(sliced.text, script),
                        truncated = sliced.truncated,
                        error = null
                    )
                }
            }
        } finally {
            terminal.removeCommandStateListener(listener)
        }
    }

    /**
     * Drop a leading echoed-command line from captured output. Some shells
     * (notably zsh, which fires OSC 133;B inside `preexec`) emit the start
     * mark before the prompt line's trailing newline, so the slice can begin
     * on the `<prompt> <command>` line. If the first line ends with the script
     * we sent, it's treated as that echo and removed.
     *
     * Best-effort, NOT airtight: the match is a plain `endsWith` on the first
     * physical line, so a genuine first output line that happens to end with
     * the command text would be stripped too (e.g. command `echo foo`, output
     * `bar echo foo`). A prompt-sigil-anchored match would be tighter but the
     * sigil varies per shell/theme (`$ % # ❯`, custom), so we accept the rare
     * false strip over a brittle prompt parser.
     */
    private fun stripEchoedCommandLine(output: String, script: String): String {
        if (output.isEmpty()) return output
        // Compare against the FIRST physical line of the script: that's what the
        // shell echoes on the prompt line (for compound `cd foo && ls` it's the
        // whole command; for a multi-line script it's the first line).
        val firstCmdLine = script.trim().substringBefore('\n').trimEnd()
        if (firstCmdLine.isEmpty()) return output
        val nl = output.indexOf('\n')
        val firstLine = if (nl >= 0) output.substring(0, nl) else output
        if (!firstLine.trimEnd().endsWith(firstCmdLine)) return output
        return if (nl >= 0) output.substring(nl + 1) else ""
    }

    /**
     * Capture the buffer slice covering the most-recent command's output.
     *
     * Coordinate system: absolute "history-line" numbers, captured as
     * `historyLinesCount + cursorY-0-indexed` at B-time and D-time. Translating
     * back to current-snapshot row indices uses the delta between historyAtB
     * and historyAtEnd, so the slice stays correct when output scrolls into
     * history during the command.
     *
     * Falls back to "last visible screen" if the start mark scrolled past the
     * history cap (very long outputs). Caps total length at [maxOutputChars]
     * (the clamped `mcpRunCommandMaxOutputChars` setting) and reports
     * `truncated=true` in that case.
     */
    private fun sliceCommandOutput(
        textBuffer: TerminalTextBuffer,
        startHistory: Int,
        startCursorY: Int,
        endHistory: Int,
        endCursorY: Int,
        maxOutputChars: Int
    ): SlicedOutput {
        val snapshot = textBuffer.createSnapshot()
        val historyDelta = endHistory - startHistory
        // First output row at end-snapshot time. May be negative (in history).
        var startRow = startCursorY - historyDelta
        // Last output row inclusive. cursorYAtEnd is the row where the *next*
        // prompt will be drawn (after the final newline), so the last output
        // line is one row above it.
        val endRowInclusive = endCursorY - 1

        val oldestAvailableRow = -snapshot.historyLinesCount
        if (startRow < oldestAvailableRow) startRow = oldestAvailableRow
        if (endRowInclusive < startRow) return SlicedOutput("", false)

        val sb = StringBuilder()
        var truncated = false
        var row = startRow
        while (row <= endRowInclusive) {
            val line = snapshot.getLine(row).text.trimEnd()
            // +1 for the newline that joins lines.
            val sep = if (sb.isEmpty()) 0 else 1
            if (sb.length + sep + line.length > maxOutputChars) {
                // Append as much of this line as still fits rather than dropping
                // it whole — otherwise a single oversize line (e.g. a one-line
                // JSON dump bigger than the cap) would yield empty output.
                val remaining = maxOutputChars - sb.length - sep
                if (remaining > 0) {
                    if (sep == 1) sb.append('\n')
                    sb.append(line, 0, remaining)
                }
                truncated = true
                break
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
            row++
        }
        return SlicedOutput(sb.toString(), truncated)
    }

    data class Result(
        val exitCode: Int?,
        val output: String,
        val truncated: Boolean,
        val error: String?
    )

    private sealed class CommandFinish {
        data class Done(val exitCode: Int) : CommandFinish()
        object TuiDetected : CommandFinish()
        object SessionExited : CommandFinish()
    }

    private data class SlicedOutput(val text: String, val truncated: Boolean)
}
