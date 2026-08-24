package ai.rever.bossterm.compose.theme

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.Theme
import ai.rever.bossterm.compose.settings.theme.UiTheme
import ai.rever.bossterm.compose.settings.toSettingsHex
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The focused pane's border follows the theme unless the user chose a colour.
 *
 * The bug: `splitFocusBorderColor` defaulted to the literal 0xFF4A90E2, a blue that
 * belongs to no BOSS identity. Every other piece of pane chrome had been converted to
 * tokens - the unfocused border is `UiTheme.line2`, the divider is `line`, the tab
 * chips take the theme's cursor - so the focus rectangle was the last hardcoded colour
 * on that surface. It went unseen for as long as Blueprint was the default theme,
 * because a generic blue on a blue identity looks intentional. On NVIDIA (green on
 * pure black) it is the only non-green thing on screen.
 *
 * Two halves worth pinning separately, because the second is the one that reaches a
 * real user: the resolution rule, and the fact that an *existing* settings file - which
 * has the old default serialized into it verbatim - resolves the same way as a fresh
 * one. A fix that only changed the default would pass the first and leave every
 * installed copy blue.
 */
class SplitFocusBorderTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * What the host bridge synthesizes for the NVIDIA identity: pure-black floor,
     * near-white text, the brand green as the cursor.
     *
     * Built here rather than taken from [BuiltinThemes] because BossTerm has no NVIDIA
     * builtin - the host theme reaches the terminal as a synthesized `boss-host-dark`,
     * and it is that derived path, not a curated one, whose signal has to come out
     * green.
     */
    private val nvidiaLike = Theme(
        id = "boss-host-dark",
        name = "BOSS Host (Dark)",
        foreground = "0xFFEEEEEE",
        background = "0xFF000000",
        cursor = "0xFF76B900",
        cursorText = "0xFF000000",
        selection = "0x4D76B900",
        selectionText = "0xFFEEEEEE",
        searchMatch = "0xFFEF9100",
        hyperlink = "0xFF10B1FB",
        black = "0xFF15202B",
        red = "0xFFFF8181",
        green = "0xFF1DBBA4",
        yellow = "0xFFEF9100",
        blue = "0xFF5C9FE0",
        magenta = "0xFFC792EA",
        cyan = "0xFF10B1FB",
        white = "0xFFC7D1DB",
        brightBlack = "0xFF3A4B5C",
        brightRed = "0xFFFF8A80",
        brightGreen = "0xFF8FE0A6",
        brightYellow = "0xFFFFC560",
        brightBlue = "0xFF82B7F0",
        brightMagenta = "0xFFDDB0F5",
        brightCyan = "0xFF7FD9EE",
        brightWhite = "0xFFE9EEF3",
        isBuiltin = false,
    )

    @Test
    fun `a fresh install follows the theme`() {
        assertNull(
            TerminalSettings.DEFAULT.splitFocusBorderColorOverride,
            "the shipped default must be 'follow the theme', not a colour",
        )
    }

    @Test
    fun `an existing settings file carrying the old default follows the theme`() {
        // Verbatim from a real ~/.boss/bossterm/settings.json written before this
        // change. This is the case the whole design turns on: the value is not the
        // user's choice, it is the factory default that was persisted on first save.
        val stored = json.decodeFromString<TerminalSettings>(
            """{"splitFocusBorderColor":"${TerminalSettings.LEGACY_FOCUS_BORDER_BLUE}"}""",
        )
        assertNull(
            stored.splitFocusBorderColorOverride,
            "an install still carrying the old default must pick the theme up",
        )
    }

    @Test
    fun `the legacy value is recognised whatever case it was written in`() {
        // colorToHex uppercases, but a hand-edited file or an older writer need not.
        val lower = TerminalSettings(splitFocusBorderColor = "0xff4a90e2")
        assertNull(lower.splitFocusBorderColorOverride)
    }

    @Test
    fun `a colour the user picked wins over the theme`() {
        val chosen = Color(0xFFFF00FF)
        val settings = TerminalSettings(splitFocusBorderColor = chosen.toSettingsHex())
        assertEquals(chosen, settings.splitFocusBorderColorOverride)
    }

    @Test
    fun `a malformed colour falls back to the theme instead of throwing`() {
        // The other colour fields parse eagerly and would throw out of the
        // constructor; this one's default is blank, so every TerminalSettings would
        // be affected by a strict parse here.
        assertNull(TerminalSettings(splitFocusBorderColor = "not-a-colour").splitFocusBorderColorOverride)
        assertNull(TerminalSettings(splitFocusBorderColor = "0xZZZZZZZZ").splitFocusBorderColorOverride)
    }

    @Test
    fun `the resolved border is the identity's accent, not the old blue`() {
        // What TabbedTerminal computes: override ?: UiTheme.signal.
        val legacyBlue = Color(0xFF4A90E2)

        val nvidiaSignal = UiTheme.fromTheme(nvidiaLike).signal
        assertEquals(Color(0xFF76B900), nvidiaSignal, "NVIDIA's focus border must be the brand green")
        assertNotEquals(legacyBlue, nvidiaSignal)

        // And the identity that used to hide the bug still gets a blue, so this is a
        // recolour of one theme's chrome rather than a change everyone sees.
        val blueprintSignal = UiTheme.fromTheme(BuiltinThemes.BOSS_BLUEPRINT).signal
        assertEquals(UiTheme.BOSS_BLUEPRINT.signal, blueprintSignal)
    }

    @Test
    fun `every builtin resolves a focus border distinct from its own floor`() {
        // A focus indicator the same colour as the surface it sits on is not an
        // indicator. Cheap to assert across the set, and it is the failure mode a
        // future derived theme would hit.
        for (theme in BuiltinThemes.ALL) {
            val ui = UiTheme.fromTheme(theme)
            assertNotEquals(ui.ink, ui.signal, "${theme.id}: focus border is invisible on its own floor")
        }
    }
}
