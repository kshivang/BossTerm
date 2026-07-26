package ai.rever.bossterm.compose.voice

/**
 * The agent's Rules block, shared by both call surfaces.
 *
 * The framing above it differs deliberately — the share viewer's agent is talking to a remote guest,
 * the in-app one to the machine's owner — but the rules themselves are the same instructions to the
 * same model over the same tool set, and they were duplicated verbatim in
 * [VoiceCallService.buildInstructions] and [HostVoiceCallController.hostInstructions], down to the
 * conditional tool-name interpolation. Both KDocs said "should not drift apart", which is the kind
 * of thing that reads as a prediction. One definition makes drift impossible instead.
 *
 * A new tool that needs guidance here changes ONE function, not two.
 */
internal fun voiceAgentRules(
    names: Set<String>,
    /** "verbal" for a remote caller, "spoken" for the host — the only wording that varies. */
    confirmationWording: String,
    /** The user's own additions (`TerminalSettings.voiceAgentExtraInstructions`). */
    userExtra: String? = null,
): String {
    val core = VoiceAgentCustomization.rulesOverride?.invoke(names, confirmationWording)
        ?: builtInRules(names, confirmationWording)
    // Embedder first, then the user: a person's own instruction is the last word, so they can
    // correct something the product got wrong without editing the product.
    val additions = listOfNotNull(
        VoiceAgentCustomization.extraInstructions?.takeIf { it.isNotBlank() },
        userExtra?.takeIf { it.isNotBlank() },
    )
    if (additions.isEmpty()) return core
    return buildString {
        append(core)
        additions.forEach { appendLine(); append("- "); append(it.trim()) }
    }
}

private fun builtInRules(names: Set<String>, confirmationWording: String): String = buildString {
    appendLine("Rules:")
    appendLine(
        "- Inspect before you answer: read_scrollback" +
            (if ("search_output" in names) " / search_output" else "") +
            (if ("get_last_command" in names) " / get_last_command" else "") + "."
    )
    if ("run_command" in names) {
        appendLine(
            "- Run shell commands with run_command; use send_input only for interactive " +
                "programs (TUIs, prompts); send_signal ctrl_c to interrupt."
        )
        // Commands land in the pane the user is watching (see TerminalSettings.voiceRunInFocusedPane),
        // so the agent has to treat it as someone else's workspace rather than a blank shell.
        appendLine(
            "- Your commands run in the terminal the user is looking at, so read_scrollback first " +
                "when it matters: pick up where they are (their shell, their directory, the error " +
                "on screen) instead of asking them to repeat it, and don't disturb something they " +
                "have in progress."
        )
        appendLine(
            "- If run_command reports that shell integration is missing, fall back to " +
                "send_input (with a trailing newline) plus read_scrollback — don't tell the " +
                "user the feature is broken."
        )
    } else if ("send_input" in names) {
        // An embedder with allowWriteTools = false has neither; describe what the surface DOES have
        // rather than dropping write guidance entirely, and say nothing when it has no write tools.
        appendLine(
            "- Run shell commands by typing them with send_input (include a trailing \\n " +
                "to submit), then read_scrollback to see the result; send_signal ctrl_c to interrupt."
        )
    }
    appendLine(
        "- Say briefly what you are about to do before a slow tool call, and summarize " +
            "results conversationally — never read raw terminal output verbatim."
    )
    appendLine(
        "- For destructive commands (rm, kill, force-push, reset --hard), say the exact " +
            "command and get $confirmationWording confirmation first."
    )
    append("- Keep replies short. This is a voice conversation.")
}
