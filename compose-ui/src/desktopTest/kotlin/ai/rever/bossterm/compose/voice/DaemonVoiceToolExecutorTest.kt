package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.daemon.SessionHost
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The daemon voice executor against a real PTY-backed [SessionHost]: `tab_id` → `session_id`
 * mapping, scope filtering/rejection, and the reduced (non-guiOnly) tool surface.
 */
class DaemonVoiceToolExecutorTest {

    private fun executor(host: SessionHost, scope: () -> Set<String>, anchor: () -> String?) =
        DaemonVoiceToolExecutor(host, inScopeSessionIds = scope, anchorSessionId = anchor)

    @Test
    fun `read_scrollback maps tab_id onto the daemon session`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        try {
            val marker = "VOICE_EXEC_OK_7311"
            val id = host.openSession(command = "/bin/sh", arguments = listOf("-c", "printf '%s\\n' $marker; sleep 0.4"))
            val exec = executor(host, { setOf(id) }, { id })
            assertTrue(await(8000) {
                runBlocking {
                    exec.execute("read_scrollback", buildJsonObject { put("tab_id", id) }, defaultTabId = null)
                }.contains(marker)
            }, "read_scrollback via the voice executor should see the command output")
            // Omitted tab_id falls back to the viewer's/anchor session.
            assertTrue(runBlocking {
                exec.execute("read_scrollback", buildJsonObject { }, defaultTabId = null)
            }.contains(marker))
        } finally {
            host.shutdownAll()
        }
    }

    @Test
    fun `out-of-scope sessions are rejected and filtered`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        try {
            val inScope = host.openSession(command = "/bin/cat")
            val outOfScope = host.openSession(command = "/bin/cat")
            val exec = executor(host, { setOf(inScope) }, { inScope })

            // A SESSION-scoped share must not let the agent touch the other session…
            assertFailsWith<VoiceToolException> {
                runBlocking {
                    exec.execute("send_input", buildJsonObject { put("tab_id", outOfScope); put("text", "x") }, null)
                }
            }
            // …or even see it.
            val tabs = runBlocking { exec.execute("list_tabs", buildJsonObject { }, null) }
            assertTrue(tabs.contains(inScope))
            assertFalse(tabs.contains(outOfScope))
        } finally {
            host.shutdownAll()
        }
    }

    /**
     * `defaultTabId` is whatever the viewer last claimed to be looking at (Focus / VoiceStart), and
     * the lookups behind `get_active_tab` scan every session on the host — so a scope check that
     * only guarded the generic tool path let a viewer name a foreign session and read back its
     * title and cwd. Every tool, including the locally-answered ones, must be scope-checked.
     */
    @Test
    fun `get_active_tab cannot be pointed at a session outside the share`() {
        if (ShellCustomizationUtils.isWindows()) return
        val host = SessionHost(TerminalSettings.DEFAULT)
        try {
            val inScope = host.openSession(command = "/bin/cat")
            val outOfScope = host.openSession(command = "/bin/cat")
            val exec = executor(host, { setOf(inScope) }, { inScope })

            // As the viewer's claimed "current tab"…
            assertFailsWith<VoiceToolException> {
                runBlocking { exec.execute("get_active_tab", buildJsonObject { }, defaultTabId = outOfScope) }
            }
            // …and named outright.
            assertFailsWith<VoiceToolException> {
                runBlocking {
                    exec.execute("get_active_tab", buildJsonObject { put("tab_id", outOfScope) }, null)
                }
            }
            // list_tabs must not treat it as the viewing tab either.
            assertFailsWith<VoiceToolException> {
                runBlocking { exec.execute("list_tabs", buildJsonObject { }, defaultTabId = outOfScope) }
            }
            // The in-scope session still answers normally.
            assertTrue(
                runBlocking { exec.execute("get_active_tab", buildJsonObject { }, defaultTabId = inScope) }
                    .contains(inScope)
            )
        } finally {
            host.shutdownAll()
        }
    }

    @Test
    fun `guiOnly tools are not advertised and are rejected`() {
        val host = SessionHost(TerminalSettings.DEFAULT)
        try {
            val exec = executor(host, { emptySet() }, { null })
            assertEquals(
                setOf("list_tabs", "get_active_tab", "read_scrollback", "send_input", "send_signal"),
                exec.tools().map { it.name }.toSet(),
            )
            assertFailsWith<VoiceToolException> {
                runBlocking { exec.execute("run_command", buildJsonObject { put("script", "ls") }, null) }
            }
        } finally {
            host.shutdownAll()
        }
    }

    private fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { predicate() }.getOrDefault(false)) return true
            Thread.sleep(100)
        }
        return false
    }
}
