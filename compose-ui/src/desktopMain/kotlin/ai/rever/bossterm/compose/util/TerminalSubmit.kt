/**
 * The bytes that press Enter at a shell prompt: CR, never LF.
 *
 * CR (0x0D) is what `TerminalKeyEncoder` sends for the Enter key (`putCode(VK_ENTER, Ascii.CR.code)`)
 * and what `TerminalTab.pasteText` normalizes pasted newlines to, so anything writing a command
 * programmatically has to agree with them or it is not simulating a keypress at all.
 *
 * On a Unix pty the distinction is invisible: the line discipline's `ICRNL` maps CR to NL on input,
 * so a bare LF submits too. That is why LF sat in this codebase for years without anyone noticing.
 * ConPTY does no such translation — it parses the input stream as VT and turns a bare LF into
 * Ctrl+J, which PSReadLine binds to "insert a newline in the edit buffer". Measured on Windows
 * PowerShell 5.1: `echo TEST` + LF printed nothing and left the shell showing a `>>` continuation
 * prompt; the command only ran once a CR arrived.
 *
 * CRLF is not the fix either, and this is the trap worth writing down. The CR submits, and then the
 * orphan LF is delivered to the *next* prompt and drops that one into continuation mode — so the
 * command works and the user's following keystrokes land in a multiline buffer. Measured the same
 * way: `echo TEST-CRLF` ran, and the prompt after it was `>>`. Exactly one trailing CR, no LF.
 *
 * `TerminalTab.writeUserInput` deliberately does NOT normalize, which is why these helpers exist at
 * the call sites instead. It is the verbatim path that MCP `send_input` needs: a bare LF is a
 * keystroke a caller may genuinely want (a newline inside a multi-line prompt to an AI CLI), and
 * normalizing centrally would remove the only way to send one.
 */
package ai.rever.bossterm.compose.util

private const val SUBMIT = '\r'

/**
 * Newlines as a terminal receives them from a keyboard: CRLF/LF → CR.
 *
 * The same normalization `pasteText` applies to clipboard text (it calls this), minus the
 * bracketed-paste wrapper. Use this on text that is a command — or several — but whose trailing
 * separator the caller controls: a menu writer handed `"git status\n"`, or a public `write()` whose
 * documented contract lets an embedder decide whether to submit. For a single command you are
 * definitely running, prefer [submitLine], which also guarantees the trailing Enter.
 *
 * Adds nothing of its own, so `normalizeSubmitNewlines("git checkout -b ")` still leaves the caret
 * at the prompt for the user to finish typing.
 */
fun normalizeSubmitNewlines(text: String): String =
    text.replace("\r\n", "\n").replace('\n', SUBMIT)

/**
 * [command] plus exactly one Enter, whatever newlines it arrived with.
 *
 * Idempotent over the trailing separator: `"ls"`, `"ls\n"`, `"ls\r"` and `"ls\r\n"` all produce
 * `"ls\r"`, so callers don't have to know whether the string they were handed already ends in one.
 * An empty [command] yields a bare Enter, which is a blank line at the prompt.
 *
 * Trailing blank lines are collapsed along with it, so this cannot express "submit, then press
 * Enter again" — which some TUIs want as a confirmation. That is deliberate for a helper whose job
 * is one command, and MCP `send_input` is the escape hatch for driving keystrokes precisely.
 */
fun submitLine(command: String): String =
    normalizeSubmitNewlines(command).trimEnd(SUBMIT) + SUBMIT
