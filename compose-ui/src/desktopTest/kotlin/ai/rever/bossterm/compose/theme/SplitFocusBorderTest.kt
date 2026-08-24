package ai.rever.bossterm.compose.theme

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.migrateSplitFocusBorderDefault
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.Theme
import ai.rever.bossterm.compose.settings.theme.UiTheme
import ai.rever.bossterm.compose.settings.toSettingsHex
import ai.rever.bossterm.compose.util.ColorUtils
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 * Three things worth pinning separately:
 *  1. the resolution rule (blank means follow the theme);
 *  2. the one-time migration, which is what reaches an *existing* user - a fix that
 *     only changed the default would leave every installed copy blue, because the old
 *     default is serialized into their file verbatim;
 *  3. the contrast floor, which is the regression this change introduces rather than
 *     one it inherits. A hardcoded blue was at least always visible; a theme-derived
 *     colour is only as visible as the theme makes it.
 */
class SplitFocusBorderTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A derived (non-builtin) theme shaped like the host's NVIDIA identity: pure-black
     * floor, near-white text, the brand green as the cursor.
     *
     * Built here rather than taken from [BuiltinThemes] because BossTerm has no NVIDIA
     * builtin - the host theme reaches the terminal as a theme BossConsole synthesizes,
     * so this fixture cannot drift-detect against the producing code and is not trying
     * to. What it covers is the derived path, whose signal has to come out green.
     */
    private val nvidiaLike = Theme(
        id = "host-derived-dark",
        name = "Host derived (dark)",
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

    // ----- 1. the resolution rule -------------------------------------------------

    @Test
    fun `a fresh install follows the theme`() {
        assertNull(
            TerminalSettings.DEFAULT.splitFocusBorderColorOverride,
            "the shipped default must be 'follow the theme', not a colour",
        )
    }

    @Test
    fun `a colour the user picked wins over the theme`() {
        val chosen = Color(0xFFFF00FF)
        val settings = TerminalSettings(splitFocusBorderColor = chosen.toSettingsHex())
        assertEquals(chosen, settings.splitFocusBorderColorOverride)
    }

    @Test
    fun `the old blue is selectable again once the migration has run`() {
        // The migration clears it from a legacy file; after that the value can only
        // have come from the picker, and it must be honoured like any other choice.
        // Reading it as "unset" forever would make it the one colour nobody can pick.
        val settings = TerminalSettings(splitFocusBorderColor = TerminalSettings.LEGACY_FOCUS_BORDER_BLUE)
        assertEquals(Color(0xFF4A90E2), settings.splitFocusBorderColorOverride)
    }

    @Test
    fun `a malformed colour falls back to the theme instead of throwing`() {
        // The other colour fields parse eagerly and would throw out of the
        // constructor; this one's default is blank, so a strict parse here would
        // affect every TerminalSettings.
        assertNull(TerminalSettings(splitFocusBorderColor = "not-a-colour").splitFocusBorderColorOverride)
        assertNull(TerminalSettings(splitFocusBorderColor = "0xZZZZZZZZ").splitFocusBorderColorOverride)
    }

    @Test
    fun `a hex that parses to full transparency is not an override`() {
        // "0xFF0000" looks like red and is alpha 0 - the mistake a 6-digit hex makes.
        // Without this it would read as a deliberate choice AND paint nothing, so the
        // border would vanish with Match Theme showing off.
        assertNull(TerminalSettings(splitFocusBorderColor = "0xFF0000").splitFocusBorderColorOverride)
        assertNull(TerminalSettings(splitFocusBorderColor = "0x0076B900").splitFocusBorderColorOverride)
    }

    // ----- 2. the migration -------------------------------------------------------

    @Test
    fun `an existing settings file carrying the old default is migrated to the theme`() {
        // Verbatim from a real settings.json written before this change, with no
        // version marker. This is the case the whole design turns on: the value is not
        // the user's choice, it is the factory default that was persisted on first save.
        val onDisk = json.decodeFromString<TerminalSettings>(
            """{"splitFocusBorderColor":"${TerminalSettings.LEGACY_FOCUS_BORDER_BLUE}"}""",
        )
        val migrated = migrateSplitFocusBorderDefault(onDisk, hasSplitFocusBorderVersion = false)

        assertEquals("", migrated.splitFocusBorderColor)
        assertNull(migrated.splitFocusBorderColorOverride, "a legacy install must pick the theme up")
        assertEquals(2, migrated.splitFocusBorderVersion, "the marker must stop this running twice")
    }

    @Test
    fun `the legacy value is recognised whatever case it was written in`() {
        // colorToHex uppercases, but a hand-edited file or an older writer need not.
        val lower = TerminalSettings(splitFocusBorderColor = "0xff4a90e2")
        assertEquals("", migrateSplitFocusBorderDefault(lower, hasSplitFocusBorderVersion = false).splitFocusBorderColor)
    }

    @Test
    fun `the migration leaves a real choice alone`() {
        val chosen = TerminalSettings(splitFocusBorderColor = "0xFFFF00FF")
        val migrated = migrateSplitFocusBorderDefault(chosen, hasSplitFocusBorderVersion = false)

        assertEquals("0xFFFF00FF", migrated.splitFocusBorderColor)
        assertEquals(2, migrated.splitFocusBorderVersion)
    }

    @Test
    fun `the migration does not run a second time`() {
        // Post-migration, the blue is a deliberate pick and must survive every
        // subsequent load. Without the marker check it would be cleared forever.
        val deliberate = TerminalSettings(
            splitFocusBorderColor = TerminalSettings.LEGACY_FOCUS_BORDER_BLUE,
            splitFocusBorderVersion = 2,
        )
        assertEquals(
            deliberate,
            migrateSplitFocusBorderDefault(deliberate, hasSplitFocusBorderVersion = true),
        )
    }

    // ----- 3. what the theme resolves to ------------------------------------------

    @Test
    fun `the resolved border is the identity's accent, not the old blue`() {
        val nvidiaRing = UiTheme.fromTheme(nvidiaLike).focusRing
        assertEquals(Color(0xFF76B900), nvidiaRing, "NVIDIA's focus border must be the brand green")
        assertNotEquals(Color(0xFF4A90E2), nvidiaRing)

        // Blueprint moves too - #4A90E2 to #0F5BFF. Less noticeable than the green,
        // but this is not a no-op for the identity that used to hide the bug, and
        // pinning the literal says which blue rather than re-checking a map lookup.
        assertEquals(Color(0xFF0F5BFF), UiTheme.fromTheme(BuiltinThemes.BOSS_BLUEPRINT).focusRing)
    }

    @Test
    fun `every builtin resolves a focus ring that clears the component contrast floor`() {
        // The regression this change introduces. `signal` is the cursor colour for a
        // derived theme and is nudged for legibility nowhere, so this is not free:
        // boss-daylight's amber is 2.63:1 on its own paper and solarized-light's is
        // 2.98:1. `focusRing` reaches for `signalText` in exactly those cases, which
        // is why this needs no exemption list.
        val under = BuiltinThemes.ALL.mapNotNull { theme ->
            val ui = UiTheme.fromTheme(theme)
            val ratio = ColorUtils.contrastRatio(ui.focusRing, ui.ink)
            if (ratio < UiTheme.FOCUS_RING_MIN_CONTRAST) "${theme.id}: %.2f:1".format(ratio) else null
        }
        assertEquals(
            emptyList(),
            under,
            "a focus border under ${UiTheme.FOCUS_RING_MIN_CONTRAST}:1 on its own floor is not an indicator",
        )
    }

    @Test
    fun `the focus ring only leaves signal behind when signal cannot carry it`() {
        // The guard must be a last resort, not a blanket swap: on a theme whose signal
        // already clears the floor, the accent the user recognises is the one that has
        // to show up.
        // Stated as the property rather than a list of names, so adding a theme does
        // not fail this for the wrong reason. Today the two that swap are
        // boss-daylight and solarized-light.
        val wronglySwapped = BuiltinThemes.ALL.mapNotNull { theme ->
            val ui = UiTheme.fromTheme(theme)
            val ratio = ColorUtils.contrastRatio(ui.signal, ui.ink)
            if (ui.focusRing != ui.signal && ratio >= UiTheme.FOCUS_RING_MIN_CONTRAST) {
                "${theme.id}: signal is %.2f:1 and was swapped anyway".format(ratio)
            } else {
                null
            }
        }
        assertEquals(emptyList(), wronglySwapped)

        // And the guard fires where it must, or the test above would pass vacuously
        // on a build where focusRing simply returned signalText everywhere.
        assertTrue(
            BuiltinThemes.ALL.any { UiTheme.fromTheme(it).let { ui -> ui.focusRing == ui.signal } },
            "focusRing must be signal for the themes that can carry it",
        )
    }
}
