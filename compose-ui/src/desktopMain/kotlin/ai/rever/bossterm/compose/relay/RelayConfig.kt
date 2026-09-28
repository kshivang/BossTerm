package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Persisted trust configuration. Share links cannot choose an arbitrary relay origin. */
internal data class RelayConfig(val endpoint: String) {
    init { RelayConnection.validateEndpoint(endpoint) }

    fun offer(link: String): RelayOffer? {
        val uri = URI(link)
        val fields = (uri.rawFragment ?: "").split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        if (fields["relay_v"] == null) return null
        require(fields["relay_v"] == "1") { "Unsupported terminal relay version" }
        require(fields["relay"]?.trimEnd('/') == endpoint.trimEnd('/')) { "This share uses a different relay" }
        val room = UUID.fromString(fields.getValue("room")).toString()
        require(!fields["k"].isNullOrBlank()) { "Relay links require encryption" }
        val token = (uri.rawQuery ?: "").split('&').firstOrNull { it.startsWith("t=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
        require(!token.isNullOrBlank() && token.length <= 256) { "Missing share token" }
        return RelayOffer(endpoint, room, token)
    }

    fun fragment(room: String) = "&relay_v=1&relay=${URLEncoder.encode(endpoint, "UTF-8")}&room=${UUID.fromString(room)}"

    companion object {
        data class Overrides(val enabled: String?, val endpoint: String?) {
            val present: Boolean get() = enabled != null || endpoint != null
        }

        fun overrides(
            property: (String) -> String? = System::getProperty,
            environment: (String) -> String? = System::getenv,
        ) = Overrides(
            property("bossterm.relay.enabled") ?: environment("BOSSTERM_RELAY_ENABLED"),
            property("bossterm.relay.url") ?: environment("BOSSTERM_RELAY_URL"),
        )

        fun enabled(settings: TerminalSettings, overrides: Overrides = overrides()): Boolean =
            overrides.enabled?.let { it != "false" } ?: settings.terminalRelayEnabled

        fun current(
            settings: TerminalSettings = SettingsManager.instance.settings.value,
            overrides: Overrides = overrides(),
        ): RelayConfig? {
            if (!enabled(settings, overrides)) return null
            if (overrides.enabled != null && overrides.enabled != "true") return null
            val endpoint = overrides.endpoint ?: settings.terminalRelayUrl
            return runCatching { RelayConfig(endpoint.trimEnd('/')) }.getOrNull()
        }

        fun offerFor(
            link: String,
            settings: TerminalSettings = SettingsManager.instance.settings.value,
            overrides: Overrides = overrides(),
        ): RelayOffer? {
            // Old links remain direct. Explicitly disabling relay also selects direct mode.
            if (URI(link).rawFragment?.split('&')?.any { it.substringBefore('=') == "relay_v" } != true ||
                !enabled(settings, overrides)) return null
            // Invalid enabled configuration must not silently downgrade a relay link.
            return requireNotNull(current(settings, overrides)) { "Invalid terminal relay configuration" }.offer(link)
        }
    }
}

internal data class RelayOffer(val endpoint: String, val room: String, val token: String)
