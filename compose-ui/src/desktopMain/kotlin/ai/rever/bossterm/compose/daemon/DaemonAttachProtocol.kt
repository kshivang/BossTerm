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
 *
 * Phase 2 (session sharing in the daemon) adds a share-management lane on this same socket: the GUI
 * starts/stops/approves daemon-hosted public shares ([Client.StartShare]/[Client.StopShare]/
 * [Client.ApproveViewer]/[Client.DenyViewer]/[Client.SetShareRemoteMode]) and observes them via
 * [Server.ShareState]. The daemon owns the actual share server (E2E xterm.js viewer over Ktor), so a
 * share survives the GUI closing; this lane is only the thin GUI control/observation channel.
 */
object DaemonAttachProtocol {
    /**
     * Attach-protocol wire version. Bumped on an incompatible change to the frame set / field
     * semantics. The client sends it as `?v=`; the daemon's attach server checks it in `serve` and
     * refuses a mismatch — so GUI/daemon skew is detected instead of silently mis-rendering (the
     * relaxed [Json.ignoreUnknownKeys] alone can't catch a renamed/re-semantic'd field).
     */
    const val PROTOCOL_VERSION = 1

    /** Header carrying the daemon control secret on the attach WS handshake (not a ?query= param, so
     *  it doesn't leak into request-line logs / proxies). Shared by the GUI client and the daemon. */
    const val TOKEN_HEADER = "X-BossTerm-Token"

    val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeServer(m: Server): String = json.encodeToString(Server.serializer(), m)
    fun decodeServer(s: String): Server = json.decodeFromString(Server.serializer(), s)
    fun encodeClient(m: Client): String = json.encodeToString(Client.serializer(), m)
    fun decodeClient(s: String): Client = json.decodeFromString(Client.serializer(), s)

    @Serializable
    data class SessionMeta(val id: String, val title: String, val cwd: String? = null, val cols: Int = 80, val rows: Int = 24)

    /** Scope of a daemon share: every session as tabs, or a single session. */
    object ShareScopeKind {
        const val ALL = "all"
        const val SESSION = "session"
    }

    /**
     * A daemon-hosted share as the GUI sees it (one entry per active share). [scope] is
     * [ShareScopeKind]; [sessionId] is set only for a SESSION-scoped share. [remoteStatus] is one of
     * off|starting|installing|verifying|retrying|active|fellback (mirrors SessionShareManager's
     * RemoteStatus, as a string so the protocol stays decoupled from that enum).
     */
    @Serializable
    data class ShareView(
        val token: String,
        val scope: String = ShareScopeKind.ALL,
        val sessionId: String? = null,
        val url: String,
        val controlUrl: String,
        val secure: Boolean = true,
        val e2eCode: String? = null,
        val viewers: Int = 0,
        val sessionName: String? = null,
        val remoteMode: String = "off",
        val remoteStatus: String = "off",
        val remoteAttempt: Int = 0,
        val remoteMaxAttempts: Int = 0,
    )

    /** A viewer awaiting the host's approval (surfaced to attached GUIs; honors sessionSharingApprovalScope). */
    @Serializable
    data class PendingApproval(
        val token: String,
        val clientId: String,
        val name: String? = null,
        val control: Boolean = false,
    )

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

        /**
         * Current daemon-hosted shares + viewers awaiting approval. Sent on connect and whenever a
         * share starts/stops, a viewer connects/leaves, a remote tunnel changes state, or an approval
         * is requested/resolved. The GUI's daemon share UI binds to this.
         */
        @Serializable @SerialName("shareState")
        data class ShareState(
            val shares: List<ShareView> = emptyList(),
            val pending: List<PendingApproval> = emptyList(),
        ) : Server()

        /**
         * The daemon's MCP server state: [port] is the bound loopback port, or null when MCP is off.
         * Broadcast to attached GUIs after a live [Client.SetMcpEnabled] so the MCP status indicator
         * reflects reality (the GUI's in-process manager isn't running in daemon mode).
         */
        @Serializable @SerialName("mcpState")
        data class McpState(val port: Int? = null) : Server()
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

        /**
         * Start (or update) a daemon-hosted public share. [scope] is [ShareScopeKind]; [sessionId] is
         * required for SESSION scope. [remoteMode] optionally sets the tunnel mode (off|serve|funnel|
         * cloudflare) at creation. The daemon replies asynchronously via [Server.ShareState].
         */
        @Serializable @SerialName("startShare")
        data class StartShare(
            val scope: String = ShareScopeKind.ALL,
            val sessionId: String? = null,
            val remoteMode: String? = null,
        ) : Client()

        /** Stop a daemon-hosted share (kills its tunnel; frees the port if it was the last share). */
        @Serializable @SerialName("stopShare")
        data class StopShare(val token: String) : Client()

        /** Change a share's remote-access mode (off|serve|funnel|cloudflare) — mints a fresh link. */
        @Serializable @SerialName("setShareRemoteMode")
        data class SetShareRemoteMode(val token: String, val mode: String) : Client()

        /** Set the viewer-facing name of a share (defaults to the host's username). */
        @Serializable @SerialName("setShareName")
        data class SetShareName(val token: String, val name: String) : Client()

        /** Approve a pending viewer (grants a 24h rolling access key); [control] also grants typing. */
        @Serializable @SerialName("approveViewer")
        data class ApproveViewer(val token: String, val clientId: String, val control: Boolean = false) : Client()

        /** Deny a pending viewer. */
        @Serializable @SerialName("denyViewer")
        data class DenyViewer(val token: String, val clientId: String) : Client()

        /**
         * Turn the daemon's MCP server on/off live — the GUI's MCP settings toggle in daemon mode,
         * where the in-process manager isn't running. The daemon replies by broadcasting
         * [Server.McpState] with the resulting port (or null when off).
         */
        @Serializable @SerialName("setMcpEnabled")
        data class SetMcpEnabled(val enabled: Boolean) : Client()
    }
}
