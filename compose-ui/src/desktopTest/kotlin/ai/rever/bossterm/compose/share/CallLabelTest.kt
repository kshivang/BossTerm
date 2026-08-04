package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.settings.sections.bossCallingEnableDescription
import ai.rever.bossterm.compose.settings.sections.bossCallingIndicatorDescription
import ai.rever.bossterm.compose.settings.sections.bossCallingIndicatorLabel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `TabbedTerminal(callLabel = …)` renames Boss Calling's in-app button, and standalone BossTerm must
 * not notice.
 *
 * Two halves, and the second is the one that rots: it is easy to make the pill configurable and
 * leave the settings copy describing a button that no longer exists by that name. So every in-app
 * site is asserted here against its exact pre-existing string, and a scan of the sources keeps a new
 * one from being hardcoded past this test.
 */
class CallLabelTest {

    private val embedder = "Call Boss"

    // ---- resolving the parameter ----

    /** Null is what `bossterm-app` passes, by never passing anything. */
    @Test
    fun `standalone renders Call BossTerm`() {
        assertEquals("Call BossTerm", DEFAULT_CALL_LABEL)
        assertEquals("Call BossTerm", resolveCallLabel(null))
    }

    @Test
    fun `an embedder's label is what renders`() {
        assertEquals(embedder, resolveCallLabel(embedder))
    }

    /** An embedder deriving this from its own branding must not be able to produce an empty pill. */
    @Test
    fun `blank is treated as unset`() {
        for (blank in listOf("", "   ", "\t\n")) {
            assertEquals("Call BossTerm", resolveCallLabel(blank), "for [$blank]")
        }
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(embedder, resolveCallLabel("  Call Boss  "))
    }

    // ---- the status strip ----

    /**
     * Byte-for-byte what master rendered. The point of the change is that standalone output does not
     * move, so these are spelled out rather than derived from the constant.
     */
    @Test
    fun `every status segment reads exactly as it did before`() {
        val default = resolveCallLabel(null)
        assertEquals("Call BossTerm", callSegmentLabel(CallSegmentState.Ready, default))
        assertEquals("Call BossTerm", callSegmentLabel(CallSegmentState.NeedsKey, default))
        assertEquals("Call BossTerm", callSegmentLabel(CallSegmentState.Hidden, default))
        assertEquals("Calling…", callSegmentLabel(CallSegmentState.Connecting, default))
        assertEquals("Call BossTerm · working", callSegmentLabel(CallSegmentState.Working, default))
        assertEquals("Call BossTerm · live", callSegmentLabel(CallSegmentState.Live, default))
        assertEquals("Call BossTerm · live", callSegmentLabel(CallSegmentState.Speaking, default))
        assertEquals("Call BossTerm · failed", callSegmentLabel(CallSegmentState.Failed, default))
    }

    /**
     * The states are the part a rename misses: shipping "Call Boss" beside "Call BossTerm · live" is
     * exactly the half-done rename this test exists to catch.
     */
    @Test
    fun `the embedder's label reaches every state, not just the idle one`() {
        for (state in CallSegmentState.entries) {
            val label = callSegmentLabel(state, embedder)
            assertFalse(label.contains("BossTerm"), "$state still says BossTerm: $label")
            if (state != CallSegmentState.Connecting) {
                assertTrue(label.startsWith(embedder), "$state does not lead with the label: $label")
            }
        }
        assertEquals("Call Boss · live", callSegmentLabel(CallSegmentState.Live, embedder))
        assertEquals("Call Boss · working", callSegmentLabel(CallSegmentState.Working, embedder))
        assertEquals("Call Boss · failed", callSegmentLabel(CallSegmentState.Failed, embedder))
    }

    /** "Calling…" is about the call in flight, not the product — it must stay name-free. */
    @Test
    fun `connecting names nothing`() {
        assertEquals("Calling…", callSegmentLabel(CallSegmentState.Connecting, embedder))
    }

    // ---- the settings copy ----

    @Test
    fun `settings copy is unchanged standalone`() {
        val default = resolveCallLabel(null)
        assertEquals("Show the Call BossTerm indicator", bossCallingIndicatorLabel(default))
        assertEquals(
            "Show the \"Call BossTerm\" segment in the tab-bar status strip, " +
                "alongside the MCP and Sharing indicators. It is also how you START a call " +
                "from this window, so turning it off leaves calling available only to share " +
                "viewers.",
            bossCallingIndicatorDescription(default),
        )
        assertEquals(
            "Show a \"Call BossTerm\" button - in this window and in the share viewer - " +
                "that starts a voice conversation with an AI agent (OpenAI Realtime) able to " +
                "inspect this session and run commands. Starting a call sends your tab titles " +
                "and working directories to OpenAI, and anything the agent then reads from the " +
                "terminal goes with it. On, but idle until you set the API key below; calling " +
                "from a share also requires control of it.",
            bossCallingEnableDescription(default),
        )
    }

    @Test
    fun `settings copy about the in-app button follows the embedder`() {
        assertEquals("Show the Call Boss indicator", bossCallingIndicatorLabel(embedder))
        val indicator = bossCallingIndicatorDescription(embedder)
        assertTrue(indicator.startsWith("Show the \"Call Boss\" segment"), indicator)
        assertFalse(indicator.contains("BossTerm"), indicator)
    }

    /**
     * The one sentence naming both surfaces. They genuinely differ under an embedder — the viewer's
     * button is a static web asset that does not follow `callLabel` — so it must name both rather
     * than claim one label covers both.
     */
    @Test
    fun `the enable description names the viewer's own button when they differ`() {
        val text = bossCallingEnableDescription(embedder)
        assertTrue(text.contains("\"Call Boss\" button in this window"), text)
        assertTrue(text.contains("\"Call BossTerm\" button in the share viewer"), text)
        // The shared tail is untouched either way.
        assertTrue(text.endsWith("from a share also requires control of it."), text)
    }

    // ---- no site left behind ----

    /**
     * Every in-app label site must render the resolved `callLabel`. A grep is a poor test of behavior
     * and a good test of THIS: the failure mode is a new pill, tooltip or dialog title that hardcodes
     * the string and is invisible to every assertion above.
     *
     * The literal is allowed only as a named constant — [DEFAULT_CALL_LABEL] (what standalone
     * renders) and `BossCallingSection.VIEWER_CALL_LABEL` (the share viewer's fixed button,
     * deliberately not the embedder's). Comments and KDoc are stripped: they discuss the label
     * constantly and none of it renders.
     */
    @Test
    fun `no source hardcodes the call label outside a named constant`() {
        val root = desktopMainSources()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                stripComments(file.readText()).lineSequence()
                    .withIndex()
                    .filter { (_, line) -> line.contains("Call BossTerm") }
                    .filterNot { (_, line) -> line.contains("const val") }
                    .map { (i, line) -> "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "hardcoded call label - render TabbedTerminal's resolved callLabel instead:\n" +
                offenders.joinToString("\n"),
        )
    }

    /**
     * The wiring, which no assertion above can see: every composable that renders the label has a
     * `callLabel` parameter defaulting to [DEFAULT_CALL_LABEL], so a call site that simply forgets to
     * pass it compiles, runs, and quietly renders "Call BossTerm" inside BossConsole. Dropping one
     * argument is the whole failure mode, so the argument lists are checked directly.
     */
    @Test
    fun `every host threads the label into the composables that render it`() {
        val root = desktopMainSources()
        val expected = mapOf(
            "TabbedTerminal.kt" to
                listOf("StatusStrip", "VoiceKeyDialog", "ShareWindow", "DaemonShareWindow"),
            "share/ShareWindow.kt" to listOf("BossCallingSection"),
            "daemon/DaemonShareWindow.kt" to listOf("BossCallingSection", "ShareDetails"),
        )
        for ((path, callees) in expected) {
            val file = File(root, "ai/rever/bossterm/compose/$path")
            assertTrue(file.isFile, "moved or renamed: $file")
            val source = stripComments(file.readText())
            for (callee in callees) {
                val calls = invocationArguments(source, callee)
                assertTrue(calls.isNotEmpty(), "no $callee( call left in $path - did it move?")
                for (args in calls) {
                    assertTrue(
                        args.contains("callLabel"),
                        "$path calls $callee(…) without passing callLabel, so it silently renders " +
                            "the standalone name inside an embedder:\n$args",
                    )
                }
            }
        }
    }

    /**
     * The argument text of each `callee(...)` invocation in [source] — declarations excluded. Naive
     * paren matching, which is enough: none of these argument lists contains a parenthesis inside a
     * string literal.
     */
    private fun invocationArguments(source: String, callee: String): List<String> {
        val call = Regex("(?<![A-Za-z0-9_])$callee\\s*\\(")
        return call.findAll(source)
            .filterNot { source.take(it.range.first).trimEnd().endsWith("fun") }
            .mapNotNull { match ->
                var depth = 0
                var i = match.range.last
                while (i < source.length) {
                    when (source[i]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) return@mapNotNull source.substring(match.range.last + 1, i)
                        }
                    }
                    i++
                }
                null
            }
            .toList()
    }

    /**
     * Both allowed constants still say what standalone has always said. Paired with the scan above,
     * this is what pins "standalone output is unchanged" — the scan proves nothing else spells it
     * out, these prove the two that do are right.
     */
    @Test
    fun `the viewer's button and the standalone default agree with the shipped web asset`() {
        assertEquals("Call BossTerm", DEFAULT_CALL_LABEL)
        val html = CallLabelTest::class.java.classLoader
            .getResourceAsStream("share-viewer/index.html")
            ?.bufferedReader()?.readText()
            ?: fail("share-viewer/index.html is not on the test classpath")
        assertTrue(
            html.contains("<span id=\"voicelabel\">Call BossTerm</span>"),
            "the viewer's button was renamed - VIEWER_CALL_LABEL in BossCallingSection.kt is now a lie",
        )
    }

    /** Gradle runs tests from the module directory; walk up in case something else does not. */
    private fun desktopMainSources(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val here = dir ?: return@repeat
            for (candidate in listOf("src/desktopMain/kotlin", "compose-ui/src/desktopMain/kotlin")) {
                val f = File(here, candidate)
                if (f.isDirectory) return f
            }
            dir = here.parentFile
        }
        fail("could not locate compose-ui/src/desktopMain/kotlin from ${File("").absolutePath}")
    }

    /**
     * Crude but sufficient: drop `//` tails and block comments so KDoc discussing the label does not
     * read as a site. Does not understand the string literal `"//"`, which no label site has.
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inBlock = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inBlock && c == '*' && next == '/' -> { inBlock = false; i += 2 }
                inBlock -> { if (c == '\n') out.append(c); i++ }
                c == '/' && next == '*' -> { inBlock = true; i += 2 }
                c == '/' && next == '/' -> { while (i < source.length && source[i] != '\n') i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
