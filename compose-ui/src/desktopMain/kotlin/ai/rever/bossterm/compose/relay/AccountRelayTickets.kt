package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.auth.BossAccountManager
import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.auth.SupabaseAuthConfig
import ai.rever.bossterm.compose.share.AccountSessionSource
import ai.rever.bossterm.compose.share.HostTerminalRelay
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** Uses the same login as the host without exposing that login to the relay. */
internal object AccountRelayTickets {
    private val http by lazy {
        HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 8_000; connectTimeoutMillis = 5_000 } }
    }

    suspend fun mint(expectedUserId: String, roomId: String, role: String): String {
        require(role == "host" || role == "account")
        val room = UUID.fromString(roomId).toString()
        fun checkIdentity() = check((AccountSessionSource.state.value as? AccountState.SignedIn)?.userId == expectedUserId) {
            "Terminal account changed"
        }
        checkIdentity()
        val host = AccountSessionSource.host
        val body = if (host != null) {
            checkNotNull(host as? HostTerminalRelay) { "Host relay unavailable" }
                .relayTicket(expectedUserId, room, role)
        } else {
            val token = checkNotNull(BossAccountManager.accessToken()) { "Sign in to connect" }
            checkIdentity()
            val response = http.post("${SupabaseAuthConfig.url}/rest/v1/rpc/mint_terminal_relay_ticket") {
                header("apikey", SupabaseAuthConfig.anonKey)
                header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
                setBody(buildJsonObject {
                    put("p_expected_user_id", expectedUserId); put("p_room_id", room); put("p_role", role)
                }.toString())
            }
            check(response.status.value in 200..299) { "Relay admission unavailable" }
            response.bodyAsText()
        }
        checkIdentity()
        val result = Json.parseToJsonElement(body).jsonObject
        require(result["room_id"]?.jsonPrimitive?.content == room) { "Invalid admission room" }
        val ticket = result["ticket"]?.jsonPrimitive?.content
        require(ticket != null && Regex("[A-Za-z0-9_-]{43}").matches(ticket)) { "Invalid admission ticket" }
        return ticket
    }
}
