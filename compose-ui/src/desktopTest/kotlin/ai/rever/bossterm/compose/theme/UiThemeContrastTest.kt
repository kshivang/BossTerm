package ai.rever.bossterm.compose.theme

import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.Theme
import ai.rever.bossterm.compose.settings.theme.UiTheme
import ai.rever.bossterm.compose.util.ColorUtils
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chrome-token half of [LightThemeContrastTest].
 *
 * That test guards the terminal palette. Nothing guarded `UiTheme`, and the gap had a
 * cost: the light identities shipped with `muted` at 2.4:1 carrying real 10-11 sp
 * labels, while the same review round rejected `alert` at 4.2:1 and swapped out `warn`
 * at 3.3:1 for exactly that reason. A 2.4:1 label is a bigger violation than either of
 * the two that were fixed by hand. This is the test that would have said so.
 *
 * **Scoped to the two BOSS light identities**, deliberately, and not for convenience.
 * A sweep over `BuiltinThemes.ALL` cannot pass and should not be written to look as
 * though it does - measured worst-case over `ink`/`panel`/`raised`, `alert` is 2.14:1
 * on gruvbox-dark, 2.43:1 on nord, 2.88:1 on solarized-dark and 2.99:1 on monokai, all
 * below the 3:1 component floor as a *fill*, before any question of text. `mist` is
 * under the TEXT floor on six themes for the same underlying reason: a derived theme's
 * tokens are mixed off whatever foreground the ported palette happens to carry. That is
 * pre-existing debt across ports of other people's palettes, it predates the light
 * identities, and folding it in here would mean an exemption list longer than the test.
 * [derivedThemesAreNotCoveredHere] records the measurement so the next pass starts from
 * data rather than re-deriving it.
 *
 * The two identities in scope are ours, hand-authored, and mirror the host, so they can
 * be held to the floors without exemption.
 */
class UiThemeContrastTest {

    /** WCAG 1.4.3 AA for body text. */
    private val textFloor = 4.5f

    /**
     * WCAG 1.4.11 for a UI component - a fill, a 2.dp indicator, a status dot.
     *
     * `signal` on `boss-daylight` sits just under this at 2.63:1 and is exempted by
     * name below rather than by a lowered floor, because the reason is specific: amber
     * on paper cannot reach 3:1 without ceasing to be the identity's amber, which is
     * the same trade [BuiltinThemes.BOSS_DAYLIGHT] records for its cursor.
     */
    private val componentFloor = 3.0f

    private val identities = listOf(
        BuiltinThemes.BOSS_BLUEPRINT_LIGHT,
        BuiltinThemes.BOSS_DAYLIGHT,
    )

    private fun surfaces(u: UiTheme) = listOf("ink" to u.ink, "panel" to u.panel, "raised" to u.raised)

    private fun assertOnEverySurface(theme: Theme, name: String, color: Color, floor: Float) {
        val u = UiTheme.fromTheme(theme)
        for ((surfaceName, surface) in surfaces(u)) {
            val ratio = ColorUtils.contrastRatio(color, surface)
            assertTrue(
                ratio >= floor,
                "${theme.id}: $name is ${"%.2f".format(ratio)}:1 on $surfaceName, below the " +
                    "${"%.1f".format(floor)}:1 floor",
            )
        }
    }

    @Test
    fun `text tokens clear the text floor on every surface they can be painted on`() {
        for (t in identities) {
            val u = UiTheme.fromTheme(t)
            assertOnEverySurface(t, "chalk", u.chalk, textFloor)
            assertOnEverySurface(t, "mist", u.mist, textFloor)
            // The accent's glyph variant. Held here as well as in UiThemeTest because
            // this is where the text/fill split is the subject.
            assertOnEverySurface(t, "signalText", u.signalText, textFloor)
        }
    }

    /**
     * `muted` is NOT a text colour, and this test exists to say so in a place that
     * cannot rot.
     *
     * It is 2.37:1 and 2.48:1 on these two identities - under the component floor, let
     * alone the text one - and that is faithful to the host, whose `textMuted` was
     * authored for disabled state rather than for reading. `UiTheme`'s own comment
     * calls it "tertiary / disabled".
     *
     * So the assertion is inverted on purpose: it pins `muted` as sub-floor, so anyone
     * who reaches for it as a label colour finds a test that already decided this, and
     * anyone who "fixes" it by darkening it toward `mist` has to delete an assertion
     * that explains why the two tiers are not interchangeable.
     */
    @Test
    fun `muted is deliberately below the text floor and must not carry text`() {
        for (t in identities) {
            val u = UiTheme.fromTheme(t)
            val worst = surfaces(u).minOf { ColorUtils.contrastRatio(u.muted, it.second) }
            assertTrue(
                worst < textFloor,
                "${t.id}: muted now clears the text floor at ${"%.2f".format(worst)}:1. If that " +
                    "was deliberate, it is no longer the disabled tier and this test and " +
                    "UiTheme's KDoc both need updating; use mist for secondary text.",
            )
        }
    }

    @Test
    fun `fill tokens clear the component floor on every surface`() {
        // boss-daylight's amber signal is the one exemption, by name and with a reason.
        val exempt = setOf("boss-daylight" to "signal")
        for (t in identities) {
            val u = UiTheme.fromTheme(t)
            val fills = listOf(
                "signal" to u.signal,
                "ok" to u.ok,
                "warn" to u.warn,
                "alert" to u.alert,
                "data" to u.data,
            )
            for ((name, color) in fills) {
                if (t.id to name in exempt) continue
                assertOnEverySurface(t, name, color, componentFloor)
            }
        }
    }

    /**
     * The exemption cannot quietly grow, and cannot go stale either.
     *
     * Written as "which fills fall short" rather than "daylight's signal is exempt", so
     * that darkening the amber makes this fail and the exemption get deleted, instead
     * of leaving a stale excuse behind.
     */
    @Test
    fun `daylight's amber signal is the only fill below the component floor`() {
        val short = mutableSetOf<String>()
        for (t in identities) {
            val u = UiTheme.fromTheme(t)
            val fills = mapOf(
                "signal" to u.signal, "ok" to u.ok, "warn" to u.warn,
                "alert" to u.alert, "data" to u.data,
            )
            for ((name, color) in fills) {
                val worst = surfaces(u).minOf { ColorUtils.contrastRatio(color, it.second) }
                if (worst < componentFloor) short += "${t.id}:$name"
            }
        }
        assertEquals(
            setOf("boss-daylight:signal"),
            short,
            "the set of sub-floor fills changed: fix the token, or record why it cannot be fixed",
        )
    }

    @Test
    fun `content on a signal fill is legible against it`() {
        for (t in identities) {
            val u = UiTheme.fromTheme(t)
            val ratio = ColorUtils.contrastRatio(u.onSignal, u.signal)
            assertTrue(
                ratio >= textFloor,
                "${t.id}: onSignal is ${"%.2f".format(ratio)}:1 on signal - buttons filled with " +
                    "the accent would be hard to read",
            )
        }
    }

    /**
     * The surface ladder needs a real step, not just a direction.
     *
     * `UiThemeTest` asserts strict monotonicity across every builtin, which catches a
     * fully collapsed rung. It cannot catch a rung that is technically distinct and
     * visually identical - and that is not hypothetical: the render guard rewrote an
     * ANSI slot to within one channel step of its neighbour, and an assertion of the
     * same shape passed. Held only for the authored identities, since a derived theme's
     * rungs come from `mix(bg, fg, 0.05f)` off whatever floor it has.
     */
    @Test
    fun `the authored light ladder has a perceptible step between rungs`() {
        for (t in identities) {
            val u = UiTheme.fromTheme(t)
            for ((from, to) in listOf("ink" to "panel", "panel" to "raised")) {
                val a = if (from == "ink") u.ink else u.panel
                val b = if (to == "panel") u.panel else u.raised
                val ratio = ColorUtils.contrastRatio(a, b)
                assertTrue(
                    ratio >= 1.02f,
                    "${t.id}: $from -> $to is only ${"%.4f".format(ratio)}:1 - distinct values, " +
                        "but not a step anyone can see",
                )
            }
        }
    }

    /**
     * Records why the sweep stops at two themes, with the numbers.
     *
     * A prose note in a KDoc goes stale silently. This fails if the pre-existing debt
     * is ever paid down, which is the moment the scope of this file should widen.
     */
    @Test
    fun derivedThemesAreNotCoveredHere() {
        val subFloorAlert = BuiltinThemes.ALL
            .filter { it !in identities }
            .filter { t ->
                val u = UiTheme.fromTheme(t)
                surfaces(u).minOf { ColorUtils.contrastRatio(u.alert, it.second) } < componentFloor
            }
            .map { it.id }
            .toSet()
        assertEquals(
            setOf("nord", "solarized-dark", "gruvbox-dark", "monokai"),
            subFloorAlert,
            "the set of themes whose `alert` is below the component floor changed. If it " +
                "shrank, the debt that keeps this test scoped to two identities is being paid " +
                "down and the scope can widen.",
        )
    }
}
