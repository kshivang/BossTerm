package ai.rever.bossterm.compose.scrollbar

import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollbarThumbGeometryTest {
    @Test fun noScrollbackShowsAFullFiniteThumb() {
        assertEquals(ScrollbarThumbGeometry(400.0, 0.0), scrollbarThumbGeometry(400.0, 0.0, 0.0, 32.0))
        assertEquals(ScrollbarThumbGeometry(0.0, 0.0), scrollbarThumbGeometry(0.0, 0.0, 0.0, 32.0))
    }

    @Test fun shortTracksClampTheMinimumThumbSize() {
        assertEquals(ScrollbarThumbGeometry(20.0, 0.0), scrollbarThumbGeometry(20.0, 100.0, 50.0, 32.0))
    }

    @Test fun overflowingContentMapsScrollExtentsToTrackExtents() {
        assertEquals(ScrollbarThumbGeometry(200.0, 0.0), scrollbarThumbGeometry(400.0, 400.0, -1.0, 32.0))
        assertEquals(ScrollbarThumbGeometry(200.0, 100.0), scrollbarThumbGeometry(400.0, 400.0, 200.0, 32.0))
        assertEquals(ScrollbarThumbGeometry(200.0, 200.0), scrollbarThumbGeometry(400.0, 400.0, 500.0, 32.0))
    }

    @Test fun defaultsUseThumbOnlyAutoHideAndPreserveExplicitAlwaysVisible() {
        val defaults = Json.decodeFromString(TerminalSettings.serializer(), "{}")
        assertEquals(true, defaults.showScrollbar)
        assertEquals(false, defaults.showScrollbarGutter)
        assertEquals(false, defaults.scrollbarAlwaysVisible)
        assertEquals(true, Json.decodeFromString(TerminalSettings.serializer(),
            """{"scrollbarAlwaysVisible":true}""").scrollbarAlwaysVisible)
    }
}
