package ai.rever.bossterm.compose.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The submit character is CR, and there must be exactly one of it.
 *
 * Pinned because the wrong answer is invisible on macOS and Linux — the pty line discipline's
 * `ICRNL` maps CR to NL on input, so LF, CR and CRLF all appear to work there and a regression would
 * only ever be reported by a Windows user. Measured on Windows PowerShell 5.1 over ConPTY: a bare LF
 * is Ctrl+J and leaves the command unrun at a `>>` continuation prompt, and CRLF runs the command
 * but leaves the FOLLOWING prompt in continuation mode.
 */
class TerminalSubmitTest {

    @Test
    fun `a plain command gets exactly one carriage return`() {
        assertEquals("claude --dangerously-skip-permissions\r", submitLine("claude --dangerously-skip-permissions"))
    }

    /** Callers are handed command strings from all over; none of them should have to check first. */
    @Test
    fun `every trailing separator collapses to one carriage return`() {
        for (input in listOf("ls", "ls\n", "ls\r", "ls\r\n", "ls\n\n", "ls\r\r", "ls\r\n\r\n")) {
            assertEquals("ls\r", submitLine(input), "input was ${input.debug()}")
        }
    }

    /** The CRLF trap: one Enter, never a trailing LF that lands on the next prompt. */
    @Test
    fun `never emits a line feed`() {
        val out = submitLine("git status\r\n")
        assertFalse(out.contains('\n'), "a trailing LF poisons the next prompt: ${out.debug()}")
        assertEquals(1, out.count { it == '\r' }, "exactly one Enter: ${out.debug()}")
    }

    /** A multi-line script is a sequence of commands, so each of its newlines is an Enter too. */
    @Test
    fun `interior newlines become carriage returns`() {
        assertEquals("cd /tmp\rls\r", submitLine("cd /tmp\nls"))
        assertEquals("cd /tmp\rls\r", submitLine("cd /tmp\r\nls\r\n"))
    }

    @Test
    fun `an empty command is a bare Enter`() {
        assertEquals("\r", submitLine(""))
        assertEquals("\r", submitLine("\n"))
    }

    /**
     * `run_in_panel`'s contract: "a trailing newline is optional".
     *
     * The tool hands its script to `initialCommand`, which submits it later, so a caller-supplied
     * Enter has to come off first. The old `removeSuffix("\n")` handled `"ls\n"` but left the CR of
     * `"ls\r\n"` behind — the script then arrived as `"ls\r"` and got a second CR appended, running
     * the command twice. That is the case worth pinning.
     */
    @Test
    fun `a caller-supplied Enter is stripped before a later submit`() {
        for (input in listOf("ls", "ls\n", "ls\r", "ls\r\n", "ls\n\r", "ls\r\n\r\n")) {
            assertEquals("ls", stripTrailingSubmit(input), "input was ${input.debug()}")
        }
        // Round-tripped through the submit it precedes: still exactly one Enter.
        assertEquals("ls\r", submitLine(stripTrailingSubmit("ls\r\n")))
        // Interior newlines are a multi-command script and must survive.
        assertEquals("cd /tmp\nls", stripTrailingSubmit("cd /tmp\nls\n"))
        // Empty stays empty — run_in_panel reads that as "just open the panel".
        assertEquals("", stripTrailingSubmit("\r\n"))
    }

    /**
     * The normalize-only helper must NOT add a submit — it backs paste-like writes where trailing
     * text is still being typed.
     */
    @Test
    fun `normalize does not append a submit of its own`() {
        assertEquals("git checkout -b ", normalizeSubmitNewlines("git checkout -b "))
        assertEquals("a\rb", normalizeSubmitNewlines("a\nb"))
        assertTrue(normalizeSubmitNewlines("echo hi\n").endsWith("\r"))
    }

    private fun String.debug() = replace("\r", "\\r").replace("\n", "\\n")
}
