package ai.rever.bossterm.compose.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chrome around the terminal must take its colours from the theme, not from
 * literals.
 *
 * Every file in [converted] used to hold hardcoded VS-Code-ish greys and read
 * zero theme tokens. Settings and dialogs had followed the theme for a long time via
 * `SettingsTheme`, but the frame did not, so picking a light theme gave a light
 * terminal inside a dark window - with `Color.White` text in the tab-rename field and
 * on the search bar, on paper.
 *
 * This is a source scan for the same reason
 * [ai.rever.bossterm.compose.util.SubmitCharacterCoverageTest] is: the failure is a
 * literal in an argument list, and there is no seam to observe it through without
 * rendering each surface under two themes and reading pixels back. Its reach is the
 * limit of what it can promise - a colour arriving through a parameter has no literal
 * to match - so it catches the shape that actually accumulated here, not every
 * possible one.
 *
 * The list is the load-bearing part. 185 literals built up across 16 files precisely
 * because nothing failed when one more was added; a new hardcoded colour in converted
 * chrome now breaks the build instead of surfacing as a screenshot in review.
 */
class ChromeTokenCoverageTest {

    /**
     * Chrome converted to tokens, relative to `compose-ui/src/desktopMain/kotlin/`.
     *
     * `debug/DebugPanel*.kt` and `PreConnectScreen.kt` are deliberately absent: a
     * dev-only panel and the SSH pre-connect screen, still literal, still to do. They
     * are named here rather than left unmentioned so the gap is a known one.
     */
    private val converted = listOf(
        // Always on screen.
        "tabs/TabBar.kt",
        "window/TransparentWindowTitleBar.kt",
        "TabbedTerminal.kt",
        "scrollbar/AlwaysVisibleScrollbar.kt",
        "splits/SplitDivider.kt",
        "splits/SplitContainer.kt",
        // Frequently on screen.
        "search/SearchBar.kt",
        "palette/CommandPalette.kt",
        "history/HistorySearchOverlay.kt",
        "mcp/McpStatusIndicator.kt",
        "share/StatusStrip.kt",
        "update/UpdateUI.kt",
        "voice/HostCallBar.kt",
    )

    /**
     * Literals that are correct as literals, each with the reason it is not a token.
     *
     * Keyed by file so an allowlisted value cannot leak into a different file and be
     * silently accepted there.
     */
    private val allowed: Map<String, List<String>> = mapOf(
        // macOS paints the same red/yellow/green window controls in light and dark
        // appearance. A user finds the close button by colour before reading anything
        // else, so recolouring these per terminal theme would make BossTerm the only
        // app on the machine whose close button is not red.
        "window/TransparentWindowTitleBar.kt" to listOf(
            "0xFFFF6159", "0xFFBF4942", "0xFF4D0000", "0x33000000",
            "0xFFFFBD2E", "0xFFBF8E22", "0xFF995700",
            "0xFF28C941", "0xFF1D9730", "0xFF006500",
        ),
        // A modal scrim dims whatever is behind it. It is black under a light theme
        // too, exactly as in every light-mode design system.
        "palette/CommandPalette.kt" to listOf("0x88000000"),
        "history/HistorySearchOverlay.kt" to listOf("0x88000000"),
        // These are @Composable PARAMETER DEFAULTS for values the user owns, not
        // chrome. Every real call site passes the setting instead
        // (`ProperTerminal` -> scrollbarThumbColor / scrollbarColor /
        // searchMarkerColor, `TabbedTerminal` -> splitFocusBorderColor), and
        // `ThemeDefaultsTest.scrollbar search markers stay independent of the theme`
        // pins the markers as deliberately theme-independent. Pointing these at a
        // token would let the active theme silently override a colour the user set in
        // Settings, which is a worse bug than the one this test exists to catch.
        "scrollbar/AlwaysVisibleScrollbar.kt" to listOf(
            "Color.White", "0xFFFFFF00", "0xFFFF6600",
        ),
        "splits/SplitContainer.kt" to listOf("0xFF4A90E2"),
    )

    /** `Color(0xAARRGGBB)` / `Color(0xRRGGBB)`, plus the two named extremes. */
    private val literalPattern = Regex("""Color\((0x[0-9A-Fa-f_]{6,11})\)|Color\.(White|Black)""")

    @Test
    fun `converted chrome holds no colour literals outside the allowlist`() {
        val offenders = mutableListOf<String>()
        for (rel in converted) {
            val file = sourceFile(rel)
            val ok = allowed[rel].orEmpty()
            file.readLines().forEachIndexed { index, line ->
                // Comments quote literals when explaining why a token replaced one.
                if (line.trimStart().startsWith("//")) return@forEachIndexed
                for (match in literalPattern.findAll(line)) {
                    val literal = match.groupValues[1].ifEmpty { "Color.${match.groupValues[2]}" }
                    if (literal !in ok) {
                        offenders += "$rel:${index + 1}  $literal   (${line.trim().take(80)})"
                    }
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "hardcoded colours in converted chrome - use a BossUiTheme token, or add the " +
                "value to this test's allowlist WITH the reason it must stay literal:\n" +
                offenders.joinToString("\n"),
        )
    }

    /**
     * Every converted file must actually read a token.
     *
     * The scan above passes vacuously on a file that has no colours at all, which is
     * also what a botched merge that deleted the styling would look like.
     */
    @Test
    fun `every converted file reads a theme token`() {
        val silent = converted.filterNot { rel ->
            val text = sourceFile(rel).readText()
            "BossUiTheme" in text || "SettingsTheme" in text ||
                // TabBar collects the active terminal theme directly, which is the
                // same subscription by a different route.
                "ThemeManager.instance.currentTheme" in text
        }
        assertEquals(emptyList(), silent, "converted chrome that reads no theme token at all")
    }

    /**
     * The allowlist may not name a file that is not being scanned, or a literal that
     * is no longer in it.
     *
     * Without this, deleting the traffic lights or the scrim would leave an entry that
     * quietly excuses that value if it ever came back somewhere else in the file.
     */
    @Test
    fun `the allowlist has no stale entries`() {
        val stale = mutableListOf<String>()
        for ((rel, literals) in allowed) {
            if (rel !in converted) {
                stale += "$rel is allowlisted but not scanned"
                continue
            }
            val text = sourceFile(rel).readText()
            for (literal in literals) {
                if (literal !in text) stale += "$rel no longer contains $literal"
            }
        }
        assertEquals(emptyList(), stale, "stale allowlist entries")
    }

    private fun sourceFile(rel: String): File {
        val file = File(desktopMain, rel)
        // A path typo would otherwise scan nothing and pass - the same vacuous-green
        // failure SubmitCharacterCoverageTest's repoRoot note warns about.
        assertTrue(file.isFile, "not found: $file")
        return file
    }

    private companion object {
        /**
         * Injected by the build (`bossterm.repoRoot`), falling back to sniffing upward
         * for `settings.gradle.kts` so the class also works outside Gradle. Same
         * resolution as `SubmitCharacterCoverageTest`.
         */
        val repoRoot: File = System.getProperty("bossterm.repoRoot")?.let(::File)
            ?: generateSequence(File(".").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error(
                "repo root not found: pass -Dbossterm.repoRoot, or run from inside the repo " +
                    "(cwd was ${File(".").absolutePath})"
            )

        val desktopMain: File =
            File(repoRoot, "compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose")
    }
}
