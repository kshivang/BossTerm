package ai.rever.bossterm.compose.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared control-verb contract for the daemon's [DaemonControlChannel], used by both the daemon
 * (server) and [DaemonClient] (GUI). Verbs are newline-framed text: `<secret> <VERB> [json-arg]`;
 * responses are `OK [json]` or `ERR <message>`. HELLO/PING are answered inside the channel; the
 * rest are dispatched to a handler backed by [SessionHost].
 */
object DaemonProtocol {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // verbs
    const val HELLO = "HELLO"
    const val PING = "PING"
    const val STATUS = "STATUS"
    const val OPEN_SESSION = "OPEN_SESSION"
    const val CLOSE_SESSION = "CLOSE_SESSION"
    const val LIST_SESSIONS = "LIST_SESSIONS"
    const val WRITE_INPUT = "WRITE_INPUT"
    const val RESIZE_SESSION = "RESIZE_SESSION"
    const val SHUTDOWN = "SHUTDOWN"

    @Serializable
    data class OpenSessionRequest(
        val cwd: String? = null,
        val command: String? = null,
        val arguments: List<String> = emptyList(),
        val cols: Int = 80,
        val rows: Int = 24,
    )

    @Serializable
    data class OpenSessionResponse(val id: String)

    @Serializable
    data class WriteInputRequest(val id: String, val text: String)

    @Serializable
    data class ResizeRequest(val id: String, val cols: Int, val rows: Int)

    @Serializable
    data class ShutdownRequest(val killSessions: Boolean = false)

    @Serializable
    data class Status(
        val pid: Long,
        val version: String,
        val protocolVersion: Int,
        val uptimeMs: Long,
        val sessionCount: Int,
        val mcpPort: Int? = null,
        /** Loopback port of the GUI-attach WebSocket, or null if not running. */
        val attachPort: Int? = null,
    )
}
