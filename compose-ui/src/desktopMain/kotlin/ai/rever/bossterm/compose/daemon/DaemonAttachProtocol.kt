package ai.rever.bossterm.compose.daemon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol for the GUI ↔ daemon **attach** WebSocket — how the thin-client GUI renders and
 * steers daemon-hosted sessions. Loopback + secret-gated (the daemon's control secret), so it skips
 * the E2E/approval handshake the public share protocol needs. Purpose-built (not the full
 * window/tab/split [ai.rever.bossterm.compose.share.ShareProtocol]) because a daemon session is a
 * flat PTY: the GUI mirrors each one as a tab via `TabController.createRemoteSession`.
 *
 * Server→client: [SessionList] (full set on connect/change), [Snapshot] (initial styled paint),
 * [Output] (live bytes), [Resized], [Closed]. Client→server: [Input], [Open], [Close], [Resize].
 */
object DaemonAttachProtocol {
    val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeServer(m: Server): String = json.encodeToString(Server.serializer(), m)
    fun decodeServer(s: String): Server = json.decodeFromString(Server.serializer(), s)
    fun encodeClient(m: Client): String = json.encodeToString(Client.serializer(), m)
    fun decodeClient(s: String): Client = json.decodeFromString(Client.serializer(), s)

    @Serializable
    data class SessionMeta(val id: String, val title: String, val cwd: String? = null, val cols: Int = 80, val rows: Int = 24)

    @Serializable
    sealed class Server {
        /** The full set of daemon sessions; sent on connect and whenever it changes. */
        @Serializable @SerialName("sessions")
        data class SessionList(val sessions: List<SessionMeta>) : Server()

        /** One-time styled initial paint for a session (scrollback + screen as escapes). */
        @Serializable @SerialName("snapshot")
        data class Snapshot(val id: String, val data: String, val cols: Int, val rows: Int) : Server()

        /** Incremental raw PTY output for a session. */
        @Serializable @SerialName("output")
        data class Output(val id: String, val data: String) : Server()

        /** A session's grid changed (e.g. a TUI resized it). */
        @Serializable @SerialName("resized")
        data class Resized(val id: String, val cols: Int, val rows: Int) : Server()

        /** A session exited / was closed. */
        @Serializable @SerialName("closed")
        data class Closed(val id: String) : Server()

        /** Bring the attached GUI's window(s) to the front (menu-bar "Open BossTerm" with a window already open). */
        @Serializable @SerialName("focus")
        data object Focus : Server()
    }

    @Serializable
    sealed class Client {
        /** Keystrokes / paste for a session. */
        @Serializable @SerialName("input")
        data class Input(val id: String, val data: String) : Client()

        /** Open a new daemon session; the daemon assigns the id and announces it via SessionList. */
        @Serializable @SerialName("open")
        data class Open(val cwd: String? = null, val cols: Int = 80, val rows: Int = 24) : Client()

        /** Close (kill) a session. */
        @Serializable @SerialName("close")
        data class Close(val id: String) : Client()

        /** Resize a session's grid (the GUI's auto-fit drives this). */
        @Serializable @SerialName("resize")
        data class Resize(val id: String, val cols: Int, val rows: Int) : Client()
    }
}
