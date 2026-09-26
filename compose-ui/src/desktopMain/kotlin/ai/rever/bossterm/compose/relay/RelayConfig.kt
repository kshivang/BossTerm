package ai.rever.bossterm.compose.relay

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Debug rollout is explicit on each app. Share links cannot choose an arbitrary relay origin. */
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
        fun current(): RelayConfig? {
            val enabled = System.getProperty("bossterm.relay.enabled") ?: System.getenv("BOSSTERM_RELAY_ENABLED")
            if (enabled != "true") return null
            val endpoint = System.getProperty("bossterm.relay.url") ?: System.getenv("BOSSTERM_RELAY_URL") ?: return null
            return runCatching { RelayConfig(endpoint) }.getOrNull()
        }
    }
}

internal data class RelayOffer(val endpoint: String, val room: String, val token: String)
