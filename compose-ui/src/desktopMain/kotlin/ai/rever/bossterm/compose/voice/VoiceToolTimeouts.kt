package ai.rever.bossterm.compose.voice

/**
 * Every deadline that can end a tool call, in one place, because the ORDER between them is
 * load-bearing and was previously only asserted by prose at four separate sites.
 *
 * From innermost to outermost, for one `run_command` on the share surface:
 *
 * ```
 *   MCP's own clamp (600s, BossTermMcpServer.MAX_RUN_COMMAND_TIMEOUT_MS)
 *     < executor safety net      (EXEC)   — the handler itself hung
 *       < host budget            (HOST)   — the host reclaims the in-flight slot and answers
 *         < viewer watchdog      (VIEWER) — a pure backstop if the host never replies at all
 * ```
 *
 * [APPROVAL_MS] is not another rung: it is a slice of EXEC, spent before the handler is called at
 * all. It has to be inside rather than beside, or a gated tool's approval and its execution add up
 * past the watchdog — see its own doc.
 *
 * Each layer must be strictly longer than the one inside it, or the outer one fires first and
 * reports the wrong cause: equal host and viewer deadlines made "who answers a tool finishing on the
 * boundary" a coin flip, and an executor net BELOW the host budget would blame the handler for a
 * timeout the budget imposed. [VoiceToolTimeoutsTest] asserts the whole ladder.
 *
 * The viewer's copy in `viewer.js` cannot share this code — different language, different process —
 * so it names these values in a comment and this KDoc is the source of truth for both.
 */
internal object VoiceToolTimeouts {

    /** run_command carries a 600s clamp of its own; reads have no internal limit. */
    private const val LONG_TOOL = "run_command"

    /** Executor-level safety net: the MCP handler never returned at all. */
    fun execMs(tool: String): Long = if (tool == LONG_TOOL) 610_000L else 100_000L

    /**
     * How long a host approval modal ([VoiceToolPolicy.approve]) may sit unanswered.
     *
     * Inside [execMs], not beside it: approval and the call it authorises share ONE budget, so a
     * gated tool cannot cost approval + execution and overrun the watchdog between them. It was
     * beside it, at 120s — exactly [inAppMs] — which made the worst case 220s against a 120s
     * watchdog, and the failure it produced was the least good one available: a user who read the
     * dialog for 70 seconds and pressed Approve got the tool cancelled mid-flight while the model
     * was told it had timed out. On a tool whose whole point is that it cannot be undone.
     *
     * 45s is a person reading one sentence and deciding, with room to spare; overrunning it is a
     * refusal, and the round is answered long before the watchdog wants it.
     */
    const val APPROVAL_MS = 45_000L

    /**
     * How long the HOST waits before reclaiming the slot and answering the model.
     *
     * Deliberately shorter than [viewerMs] so the host is the side that decides; deliberately longer
     * than [execMs] so a hung handler is reported as a hung handler.
     */
    fun hostMs(tool: String): Long = if (tool == LONG_TOOL) 615_000L else 110_000L

    /**
     * The in-app surface's watchdog. No viewer is involved there, so it has no tie to break — it sits
     * where the viewer's would, one step outside the host budget.
     */
    fun inAppMs(tool: String): Long = if (tool == LONG_TOOL) 630_000L else 120_000L

    /** What `viewer.js` uses. Mirrored, not shared; kept here so the ladder is checkable in one test. */
    fun viewerMs(tool: String): Long = if (tool == LONG_TOOL) 630_000L else 120_000L

    /** The tools the ladder is defined for: the long one, and everything else. */
    val LADDER_SAMPLES = listOf(LONG_TOOL, "read_scrollback")
}
