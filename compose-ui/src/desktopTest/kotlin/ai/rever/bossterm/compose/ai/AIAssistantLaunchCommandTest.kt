package ai.rever.bossterm.compose.ai

import ai.rever.bossterm.compose.settings.AIAssistantConfigData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the launch commands the AI Assistant context menu writes to the PTY.
 * The yolo flags live in [AIAssistants.BUILTIN] as plain data, so a silent
 * upstream CLI change (e.g. Codex dropping --full-auto) only surfaces at
 * launch time — these tests document the expected flag strings instead.
 */
class AIAssistantLaunchCommandTest {

    private val provider = ToolCommandProvider()

    private fun builtin(id: String): AIAssistantDefinition {
        val assistant = AIAssistants.BUILTIN.find { it.id == id }
        assertNotNull(assistant, "missing built-in assistant: $id")
        return assistant
    }

    @Test
    fun `codex launches with danger full access sandbox`() {
        assertEquals(
            "codex --sandbox danger-full-access\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.CODEX))
        )
    }

    @Test
    fun `no built-in assistant uses the removed full-auto flag`() {
        AIAssistants.BUILTIN.forEach { assistant ->
            assertFalse(
                assistant.yoloFlag.contains("--full-auto"),
                "${assistant.id} still uses --full-auto, which Codex CLI removed"
            )
        }
    }

    @Test
    fun `yolo disabled launches the bare command`() {
        assertEquals(
            "codex\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.CODEX), AIAssistantConfigData(yoloEnabled = false))
        )
    }

    @Test
    fun `custom yolo flag overrides the built-in default`() {
        assertEquals(
            "codex --my-flag\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.CODEX), AIAssistantConfigData(customYoloFlag = "--my-flag"))
        )
    }

    /**
     * The open-source CLIs' auto-mode flags, verified against the installed binaries in July 2026:
     * `grok 0.2.3 --help` lists `--always-approve`, and the Kimi Code / Hermes docs both use
     * `--yolo`. Pinned here because each is a different word for the same thing and a wrong guess
     * fails only when a user clicks Launch.
     */
    @Test
    fun `open source assistants launch with their own auto-mode flags`() {
        assertEquals(
            "grok --always-approve\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.GROK_BUILD))
        )
        assertEquals(
            "kimi --yolo\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.KIMI_CODE))
        )
        assertEquals(
            "hermes --yolo\n",
            provider.getLaunchCommand(builtin(AIAssistantIds.HERMES))
        )
    }

    @Test
    fun `ollama has no auto-mode flag to add`() {
        // Ollama runs models; there are no tool calls to approve. A stray flag here would be
        // appended to every launch and break it.
        val ollama = builtin(AIAssistantIds.OLLAMA)
        assertEquals("", ollama.yoloFlag)
        assertEquals("ollama\n", provider.getLaunchCommand(ollama))
    }

    /**
     * Every coding agent must have a working `--dangerously-skip-permissions` equivalent — via a
     * flag, or an env prefix when the CLI offers no flag. What it must NOT do is advertise "(Auto)"
     * while enabling nothing, which is what the invalid `--auto-approve` did for OpenCode.
     */
    @Test
    fun `every AI assistant can actually enable auto mode`() {
        AIAssistants.AI_ASSISTANTS.forEach { assistant ->
            assertTrue(
                assistant.yoloFlag.isNotBlank() || assistant.yoloEnv.isNotBlank(),
                "${assistant.id} has no auto-mode mechanism — every agent needs a flag or an " +
                    "env prefix. If the CLI genuinely has neither, find its real mechanism " +
                    "rather than inventing a plausible-looking flag"
            )
            assertTrue(
                assistant.yoloLabel.isNotBlank(),
                "${assistant.id} can enable auto mode but has no yoloLabel to show for it"
            )
            // The mechanism has to reach the command the menu writes to the PTY.
            val launched = provider.getLaunchCommand(assistant).trim()
            if (assistant.yoloEnv.isNotBlank()) {
                assertTrue(
                    launched.startsWith(assistant.yoloEnv),
                    "${assistant.id}'s env prefix is missing from its launch command: $launched"
                )
            }
            if (assistant.yoloFlag.isNotBlank()) {
                assertTrue(
                    launched.contains(assistant.yoloFlag),
                    "${assistant.id}'s yolo flag is missing from its launch command: $launched"
                )
            }
        }
    }

    @Test
    fun `openclaw launches its tui subcommand with a session-scoped yolo`() {
        // Bare `openclaw` prints help, so launchArgs carries the `tui` subcommand. Its auto mode
        // is deliberately the SESSION override rather than `exec-policy preset yolo`, which would
        // permanently rewrite the user's exec policy outside BossTerm.
        val openclaw = builtin(AIAssistantIds.OPENCLAW)
        assertEquals("openclaw", openclaw.command, "command must stay a bare binary name for PATH detection")
        assertEquals(
            "openclaw tui --message '/exec security=full ask=off'\n",
            provider.getLaunchCommand(openclaw)
        )
        // Auto mode off still has to launch a usable session, i.e. keep the subcommand.
        assertEquals(
            "openclaw tui\n",
            provider.getLaunchCommand(openclaw, AIAssistantConfigData(yoloEnabled = false))
        )
        // A custom command replaces the whole invocation, subcommand included.
        assertEquals(
            "my-claw\n",
            provider.getLaunchCommand(
                openclaw,
                AIAssistantConfigData(customCommand = "my-claw", yoloEnabled = false)
            )
        )
    }

    @Test
    fun `launch args never leak into a bare-command assistant`() {
        // Guard the new field: only OpenClaw needs a subcommand today, and appending a stray one
        // to e.g. claude would break every launch.
        AIAssistants.AI_ASSISTANTS.filter { it.launchArgs.isBlank() }.forEach { assistant ->
            assertEquals(
                assistant.command,
                provider.getLaunchCommand(assistant, AIAssistantConfigData(yoloEnabled = false)).trim(),
                "${assistant.id} declares no launchArgs but its bare launch command changed"
            )
        }
    }

    @Test
    fun `local model runtimes advertise no auto mode`() {
        // Ollama runs models; there are no tool calls to approve, so a label here would be a lie.
        AIAssistants.LOCAL_MODEL_RUNTIMES.forEach { runtime ->
            assertEquals("", runtime.yoloFlag)
            assertEquals("", runtime.yoloEnv)
            assertEquals("", runtime.yoloLabel)
        }
    }

    @Test
    fun `opencode enables auto mode via OPENCODE_PERMISSION, not a flag`() {
        // Regression guard for the invalid `--auto-approve` we used to append: opencode 1.15.0's
        // TUI validates arguments strictly, so a bogus flag doesn't degrade gracefully — it prints
        // the usage banner and never starts the agent. Same for `--auto` and the run-only
        // `--dangerously-skip-permissions`. The env var is the documented equivalent.
        val opencode = builtin(AIAssistantIds.OPENCODE)
        assertEquals(
            "OPENCODE_PERMISSION='{\"read\":\"allow\",\"edit\":\"allow\",\"glob\":\"allow\"," +
                "\"grep\":\"allow\",\"bash\":\"allow\",\"task\":\"allow\",\"skill\":\"allow\"," +
                "\"lsp\":\"allow\",\"question\":\"allow\",\"webfetch\":\"allow\"," +
                "\"websearch\":\"allow\",\"external_directory\":\"allow\"," +
                "\"doom_loop\":\"allow\"}' opencode\n",
            provider.getLaunchCommand(opencode)
        )
        listOf("--auto-approve", "--auto", "--dangerously-skip-permissions").forEach { rejected ->
            assertFalse(
                opencode.yoloFlag.contains(rejected),
                "opencode's TUI rejects '$rejected' — it must not be in the launch command"
            )
        }
        // Auto mode OFF must drop the env prefix entirely, or it isn't really off.
        assertEquals(
            "opencode\n",
            provider.getLaunchCommand(opencode, AIAssistantConfigData(yoloEnabled = false))
        )
    }

    /**
     * `OPENCODE_PERMISSION` is `JSON.parse`d and **deep-merged** into opencode's `permission`
     * config, so the bare-string form the config file documents (`"permission": "allow"`) is a trap
     * here: merging a string into an object spreads it per character, resolving to
     * `{"0":"a","1":"l",…}` — configured-looking and approving nothing. Verified against opencode
     * 1.15.0 with `opencode debug config`. The value must stay a JSON object.
     */
    @Test
    fun `opencode auto-mode env is a json object of allow values`() {
        val env = builtin(AIAssistantIds.OPENCODE).yoloEnv
        val json = env.substringAfter("OPENCODE_PERMISSION='").removeSuffix("'")
        assertTrue(json.startsWith("{") && json.endsWith("}"), "must be a JSON object, got: $json")
        // Every key opencode documents, all set to allow — this IS the skip-all-permissions mode.
        listOf(
            "read", "edit", "glob", "grep", "bash", "task", "skill", "lsp",
            "question", "webfetch", "websearch", "external_directory", "doom_loop"
        ).forEach { key ->
            assertTrue(json.contains("\"$key\":\"allow\""), "missing \"$key\":\"allow\" in $json")
        }
    }

    @Test
    fun `open source assistants carry their license and source`() {
        // The openness ordering and the UI badges both read these, so an entry that forgets them
        // silently sorts as proprietary.
        AIAssistants.AI_ASSISTANTS.filter { it.openSource }.forEach { assistant ->
            assertFalse(
                assistant.license.isBlank(),
                "${assistant.id} is marked openSource but declares no license"
            )
            assertFalse(
                assistant.sourceUrl.isBlank(),
                "${assistant.id} is marked openSource but declares no sourceUrl"
            )
        }
    }
}
