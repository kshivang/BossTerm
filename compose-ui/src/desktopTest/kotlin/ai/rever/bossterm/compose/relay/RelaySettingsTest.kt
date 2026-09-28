package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RelaySettingsTest {
    private val none = RelayConfig.Companion.Overrides(null, null)
    private val defaults = TerminalSettings.DEFAULT
    private val room = "11111111-1111-4111-8111-111111111111"
    private val legacy = "https://terminal.example/?t=token#k=secret"

    @Test fun `new and existing settings enable the production relay by default`() {
        val old = Json.decodeFromString(TerminalSettings.serializer(), "{}")
        for (settings in listOf(defaults, old)) {
            assertTrue(settings.terminalRelayEnabled)
            assertEquals("wss://boss-terminal-relay.risa-boss-debug.workers.dev", RelayConfig.current(settings, none)?.endpoint)
        }
        val saved = defaults.copy(terminalRelayEnabled = false, terminalRelayUrl = "wss://custom.example")
        val restored = Json.decodeFromString(TerminalSettings.serializer(), Json.encodeToString(TerminalSettings.serializer(), saved))
        assertFalse(restored.terminalRelayEnabled)
        assertEquals(saved.terminalRelayUrl, restored.terminalRelayUrl)
    }

    @Test fun `launch properties override environment which overrides persisted settings`() {
        val props = mapOf("bossterm.relay.enabled" to "false", "bossterm.relay.url" to "wss://property.example")
        val env = mapOf("BOSSTERM_RELAY_ENABLED" to "true", "BOSSTERM_RELAY_URL" to "wss://environment.example")
        val propertyOverride = RelayConfig.overrides(props::get, env::get)
        assertFalse(RelayConfig.enabled(defaults, propertyOverride))
        assertEquals("wss://property.example", propertyOverride.endpoint)
        assertNull(RelayConfig.current(defaults, propertyOverride))
        val environmentOverride = RelayConfig.overrides({ null }, env::get)
        assertEquals("wss://environment.example", RelayConfig.current(defaults.copy(terminalRelayEnabled = false), environmentOverride)?.endpoint)
        assertEquals(defaults.terminalRelayUrl, RelayConfig.current(defaults, none)?.endpoint)
    }

    @Test fun `invalid enabled endpoints fail closed for relay links and never replace trusted origin`() {
        val link = legacy + RelayConfig(defaults.terminalRelayUrl).fragment(room)
        for (endpoint in listOf("", "https://relay.example", "ws://relay.example", "wss://user:pass@relay.example",
            "wss://relay.example/path", "wss://relay.example/?query=value", "wss://relay.example/#fragment")) {
            val settings = defaults.copy(terminalRelayUrl = endpoint)
            assertNull(RelayConfig.current(settings, none), endpoint)
            assertFailsWith<IllegalArgumentException>(endpoint) { RelayConfig.offerFor(link, settings, none) }
        }
        assertFailsWith<IllegalArgumentException> {
            RelayConfig.offerFor(legacy + RelayConfig("wss://other.example").fragment(room), defaults, none)
        }
    }

    @Test fun `invalid enable override is not interpreted as an intentional downgrade`() {
        val invalid = RelayConfig.Companion.Overrides("tru", null)
        assertTrue(RelayConfig.enabled(defaults, invalid))
        assertNull(RelayConfig.current(defaults, invalid))
        assertFailsWith<IllegalArgumentException> {
            RelayConfig.offerFor(legacy + RelayConfig(defaults.terminalRelayUrl).fragment(room), defaults, invalid)
        }
    }

    @Test fun `legacy links and explicit relay off retain direct transport`() {
        val link = legacy + RelayConfig(defaults.terminalRelayUrl).fragment(room)
        assertNull(RelayConfig.offerFor(legacy, defaults, none))
        assertNotNull(RelayConfig.offerFor(link, defaults, none))
        assertNull(RelayConfig.offerFor(link, defaults.copy(terminalRelayEnabled = false), none))
        assertNull(RelayConfig.offerFor(link, defaults, RelayConfig.Companion.Overrides("false", null)))
    }

    @Test fun `host settings changes cancel old session before starting replacement and logout stops it`() = runTest {
        val account = MutableStateFlow<AccountState>(AccountState.SignedIn("fixture@example.invalid", "owner"))
        val shares = MutableStateFlow(setOf("tab"))
        val settings = MutableStateFlow(defaults)
        val events = mutableListOf<String>()
        val watcher = launch {
            observeRelayHostConfiguration(account, shares, settings, { none }) { target ->
                if (target == null) { events += "inactive"; return@observeRelayHostConfiguration }
                val name = target.relay.endpoint
                events += "start:$name"
                try { awaitCancellation() } finally { events += "stop:$name" }
            }
        }
        runCurrent()
        assertEquals(listOf("start:${defaults.terminalRelayUrl}"), events)
        // Unrelated settings edits and changes to the shared tab set keep one connection.
        settings.value = defaults.copy(fontSize = defaults.fontSize + 1f)
        shares.value = setOf("tab", "second")
        runCurrent()
        assertEquals(1, events.size)
        settings.value = settings.value.copy(terminalRelayUrl = "wss://replacement.example")
        runCurrent()
        assertEquals(listOf("stop:${defaults.terminalRelayUrl}", "start:wss://replacement.example"), events.takeLast(2))
        settings.value = settings.value.copy(terminalRelayEnabled = false)
        runCurrent()
        assertEquals(listOf("stop:wss://replacement.example", "inactive"), events.takeLast(2))
        settings.value = settings.value.copy(terminalRelayEnabled = true)
        runCurrent()
        assertEquals("start:wss://replacement.example", events.last())
        account.value = AccountState.SignedOut
        runCurrent()
        assertEquals(listOf("stop:wss://replacement.example", "inactive"), events.takeLast(2))
        watcher.cancelAndJoin()
    }

    @Test fun `no shares or invalid config prevent host startup and valid settings recover`() = runTest {
        val account = MutableStateFlow<AccountState>(AccountState.SignedIn("fixture@example.invalid", "owner"))
        val shares = MutableStateFlow(emptySet<String>())
        val settings = MutableStateFlow(defaults.copy(terminalRelayUrl = "not a relay"))
        val seen = mutableListOf<RelayHostConfiguration?>()
        val watcher = launch { observeRelayHostConfiguration(account, shares, settings, { none }) { seen += it } }
        runCurrent()
        shares.value = setOf("tab")
        runCurrent()
        assertEquals(listOf<RelayHostConfiguration?>(null), seen)
        settings.value = defaults
        runCurrent()
        assertEquals(RelayHostConfiguration("owner", RelayConfig(defaults.terminalRelayUrl)), seen.last())
        shares.value = emptySet()
        runCurrent()
        assertNull(seen.last())
        watcher.cancelAndJoin()
    }
}
