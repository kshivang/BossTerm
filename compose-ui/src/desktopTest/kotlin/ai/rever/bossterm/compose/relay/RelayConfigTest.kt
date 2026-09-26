package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.share.TerminalViewingPreferences
import kotlin.test.*

class RelayConfigTest {
    private val config = RelayConfig("wss://relay.example")
    private val room = "11111111-1111-4111-8111-111111111111"
    private val legacy = "https://terminal.example/?t=token#k=secret"

    @Test fun `old links retain their transport and offers require the configured relay`() {
        assertNull(config.offer(legacy))
        assertEquals(RelayOffer(config.endpoint, room, "token"), config.offer(legacy + config.fragment(room)))
        assertFailsWith<IllegalArgumentException> { config.offer(legacy + RelayConfig("wss://other.example").fragment(room)) }
        assertFailsWith<IllegalArgumentException> { config.offer((legacy + config.fragment(room)).replace("relay_v=1", "relay_v=2")) }
        assertFailsWith<IllegalArgumentException> { config.offer((legacy + config.fragment(room)).replace("k=secret", "k=")) }
    }

    @Test fun `focused visible and hidden panes select independent modes`() {
        val demand = RelayViewDemand(setOf("focused", "sibling"), "focused")
        for (mode in listOf("batch", "preview")) {
            val preferences = TerminalViewingPreferences(mode, 7)
            assertEquals("live", demand.mode("focused", preferences))
            assertEquals(mode, demand.mode("sibling", preferences))
            assertEquals("hidden", demand.mode("background", preferences))
        }
    }
}
