package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.share.NodeHarness
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The contracts that span Kotlin and `viewer.js`, asserted instead of asked for.
 *
 * Three things in this feature exist once per language with no shared type: the tool-deadline ladder,
 * the `VoiceError` code vocabulary, and the per-tool "what is it doing" captions. Every one of them
 * currently carries a comment in both files saying keep these in step — and a comment is the weakest
 * possible enforcement for a rule whose whole point is that the two sides are edited separately.
 *
 * These are deliberately source-text assertions, which is normally the wrong tool: `ShareViewerScriptTest`
 * exists precisely because grepping a script proves nothing about execution. The difference is that
 * what is being checked here is not behaviour but AGREEMENT between two files — a value written in
 * one place and mirrored in another. A grep is exactly the right shape for that, and the behavioural
 * half is covered by the Node harness.
 */
class VoiceCrossLanguageContractTest {

    private val viewerJs: String by lazy { NodeHarness.readResource("share-viewer/viewer.js") }

    /**
     * The outermost rung of [VoiceToolTimeouts]. If the viewer's watchdog drops to or below the host
     * budget, the two race over who answers a tool finishing on the boundary — the exact tie the
     * ladder was introduced to remove, and nothing else would notice.
     */
    @Test
    fun `the viewer's tool watchdog matches the ladder`() {
        val long = VoiceToolTimeouts.viewerMs("run_command").toString()
        val short = VoiceToolTimeouts.viewerMs("read_scrollback").toString()
        assertTrue(
            viewerJs.contains("""name === "run_command" ? $long : $short"""),
            "viewer.js must use the ladder's viewerMs values ($long / $short) — see VoiceToolTimeouts",
        )
        // And the ordering the ladder promises, checked against what the viewer actually ships.
        assertTrue(
            VoiceToolTimeouts.hostMs("run_command") < long.toLong(),
            "the host must decide before the viewer's backstop",
        )
    }

    /**
     * Every code the servers emit needs a sentence in the viewer, or the caller is told
     * "Couldn't start the call." for a condition the product has a real explanation for.
     *
     * [SELF_EXPLAINING_CODES] is the deliberate exception: `mint_failed` is the only code that
     * carries a `message`, and the default branch renders it. Its own assertion is below — that
     * branch must keep surfacing the message, or the one code allowed to skip a case loses its
     * detail too.
     */
    @Test
    fun `every emitted VoiceError code has viewer copy`() {
        val handled = Regex("""case "([a-z_]+)":""")
            .findAll(voiceErrorTextBody())
            .map { it.groupValues[1] }
            .toSet()
        val missing = EMITTED_CODES.filterNot { it in handled || it in SELF_EXPLAINING_CODES }
        assertEquals(emptyList(), missing, "viewer.js voiceErrorText has no case for: $missing")
    }

    @Test
    fun `the fallback branch still shows the message a mint failure carries`() {
        val body = voiceErrorTextBody()
        val default = body.substringAfter("default:")
        assertTrue(
            default.contains("m.message"),
            "mint_failed relies on the default branch to explain itself: $default",
        )
    }

    /**
     * The codes the servers can actually send. Kept as a literal list on purpose: deriving it from
     * the source would make this test agree with whatever the code does, which is the one thing a
     * contract test must not do.
     */
    private fun voiceErrorTextBody() =
        viewerJs.substringAfter("function voiceErrorText").substringBefore("\n  }")

    /**
     * The captions the two surfaces show while a tool runs. Not security-critical, but a tool added
     * on one side and missed on the other shows the caller "Running: search_output…" on one surface
     * and "Searching for …" on the other, which is the kind of divergence nobody notices for months.
     */
    @Test
    fun `both surfaces describe every advertised tool`() {
        val describedInViewer = Regex("""name === "([a-z_]+)"""")
            .findAll(viewerJs.substringAfter("function voiceDescribeTool").substringBefore("\n  }"))
            .map { it.groupValues[1] }
            .toSet()
        // EVERY catalog tool, including guiOnly ones: those are absent from the DAEMON surface, but
        // a MirrorShare share is the common case and advertises them, so the viewer can be asked to
        // caption any of them. Filtering to !guiOnly here is what let list_panes and get_last_command
        // fall through to "Running: get_last_command…" on the surface this test exists to protect.
        val shareTools = VoiceToolCatalog.ALL.map { it.name }
        val missing = shareTools.filterNot { it in describedInViewer || it in ORIENTING_TOOLS }
        assertEquals(
            emptyList(),
            missing,
            "viewer.js voiceDescribeTool has no caption for: $missing (add one, or add it to ORIENTING_TOOLS)",
        )
    }

    /**
     * The viewer must not advertise a tool the host does not implement — the reverse drift, where the
     * browser names something in its captions that no executor can run.
     */
    @Test
    fun `the viewer describes no tool the catalog does not have`() {
        val describedInViewer = Regex("""name === "([a-z_]+)"""")
            .findAll(viewerJs.substringAfter("function voiceDescribeTool").substringBefore("\n  }"))
            .map { it.groupValues[1] }
            .toSet()
        val known = VoiceToolCatalog.ALL.map { it.name }.toSet()
        val unknown = describedInViewer.filterNot { it in known }
        assertEquals(emptyList(), unknown, "viewer.js describes tools that do not exist: $unknown")
    }

    /**
     * Not just "both mention the tool" — the CLIP LENGTHS too. They had drifted: the viewer previewed
     * a command to 60 chars and the typed text to 40, while the host truncated at 48 and said a bare
     * "Typing…". Same call, same product, two different captions depending which surface you were on.
     */
    @Test
    fun `the two surfaces clip their captions to the same lengths`() {
        val describe = viewerJs.substringAfter("function voiceDescribeTool").substringBefore("\n  }")
        for ((label, len) in listOf("script" to 60, "pattern" to 40, "text" to 40)) {
            assertTrue(
                describe.contains("a.$label.slice(0, $len)"),
                "viewer.js must clip $label at $len to match HostVoiceCallController.describeTool",
            )
        }
    }

    /**
     * The RENDERED captions, not just the clip lengths.
     *
     * Comparing lengths let two real divergences through: the host wrapped a truncated search
     * pattern in its own ellipsis AND clip()'s, giving `Searching for "…"…`, and the viewer appended
     * an ellipsis to send_input unconditionally, so a two-character command read as `Typing: ls…`.
     * Both sides are asserted against the same fixtures here; the harness drives the JS half through
     * the real code path.
     */
    @Test
    fun `both surfaces render the same caption for the same call`() {
        val controller = HostVoiceCallController(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            executor = object : VoiceToolExecutor {
                override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL
                override fun contextSnapshot(defaultTabId: String?): String = ""
                override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?) = "{}"
            },
        )
        for ((tool, argsJson, expected) in CAPTION_FIXTURES) {
            assertEquals(expected, controller.describeTool(tool, argsJson), "$tool / $argsJson")
        }
    }

    /**
     * The viewer harness pins the same captions, through the real JS path.
     *
     * Only the fixtures that appear LITERALLY on both sides are cross-checked here: the harness
     * builds its long inputs with `"x".repeat(40)`, so a substring search cannot see the expansion.
     * The truncating cases are still pinned — exactly, by `strictEqual` in the harness and by the
     * test above here — this check is the cheaper guard that neither side quietly stops asserting.
     */
    @Test
    fun `the viewer harness pins the same caption fixtures`() {
        val harness = NodeHarness.readResource("share-viewer-harness/viewer-harness.cjs")
        val literals = CAPTION_FIXTURES.map { it.third }.filter { it.length < 40 }
        assertTrue(literals.size >= 3, "expected some short fixtures to cross-check")
        for (expected in literals) {
            assertTrue(
                harness.contains(expected),
                "viewer-harness.cjs must assert the caption \"$expected\" so the two stay in step",
            )
        }
        // And that it asserts the truncating ones at all, whatever the expansion looks like.
        for (tool in listOf("send_input", "search_output")) {
            assertTrue(
                harness.contains("caption(\"$tool\""),
                "viewer-harness.cjs must render $tool's caption for both a short and a long input",
            )
        }
    }

    /**
     * The dedupe trims must agree.
     *
     * The host drops the OLDEST completed id; the viewer used to keep only what was pending, which
     * discards every finished id and lets a replayed call_id re-execute its tool. One mirror carried
     * a written explanation of why the other was wrong, which is exactly the drift this class exists
     * to catch.
     */
    @Test
    fun `both surfaces trim their dedupe history the same way`() {
        assertTrue(
            viewerJs.contains("VOICE_SEEN_CALLS_LIMIT = ${HostVoiceCallController.SEEN_CALLS_LIMIT}"),
            "viewer.js must use the same dedupe limit as HostVoiceCallController",
        )
        val trim = viewerJs.substringAfter("function voiceHandleFunctionCall").substringBefore("\n  }")
        assertTrue(
            trim.contains("delete voice.seenCalls["),
            "viewer.js must DROP the oldest completed id, not rebuild from pending",
        )
        assertFalse(
            trim.contains("voice.seenCalls = keep"),
            "keeping only pending ids lets a replayed call_id re-execute its tool",
        )
    }

    private companion object {
        /** (tool, argsJson, rendered caption) — short and long for each truncating tool. */
        val CAPTION_FIXTURES = listOf(
            Triple("send_input", """{"text":"ls"}""", "Typing: ls"),
            Triple(
                "send_input",
                """{"text":"${"x".repeat(50)}"}""",
                "Typing: ${"x".repeat(40)}…",
            ),
            Triple("search_output", """{"pattern":"boom"}""", "Searching for “boom”…"),
            Triple(
                "search_output",
                """{"pattern":"${"y".repeat(50)}"}""",
                "Searching for “${"y".repeat(40)}”…",
            ),
            Triple("run_command", """{"script":"ls -la"}""", "Running: ls -la"),
            // No arguments to show: both fall back to the same generic caption rather than naming
            // the raw tool id at the user.
            Triple("list_tabs", "{}", "Working…"),
            Triple("some_unknown_tool", "{}", "Working…"),
        )

        /** Every code emitted by MirrorShare, DaemonShareServer and VoiceCallService. */
        val EMITTED_CODES = listOf(
            "no_key", "disabled", "not_controller", "unauthorized",
            "mint_failed", "rate_limited", "too_many_calls", "stale_call", "insecure_transport",
        )

        /** Codes that carry their own `message` and are rendered by the default branch. */
        val SELF_EXPLAINING_CODES = setOf("mint_failed")

        /**
         * Tools that resolve instantly and get the generic caption on purpose: naming them would put
         * "Listing tabs…" on screen for something that is already gone by the next frame.
         */
        val ORIENTING_TOOLS = setOf("list_tabs", "get_active_tab")
    }
}
