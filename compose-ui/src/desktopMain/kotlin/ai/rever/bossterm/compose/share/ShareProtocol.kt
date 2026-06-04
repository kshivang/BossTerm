package ai.rever.bossterm.compose.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol for BossTerm session sharing (issue #276).
 *
 * JSON messages over a WebSocket between the host (a shared BossTerm tab) and a
 * browser viewer. This is an **independent** protocol — Warp's AGPLv3
 * `session-sharing-protocol` is only a design reference, not reused.
 *
 * The host streams the raw PTY byte/escape stream ([Output]) preceded by a
 * one-time [Snapshot]; the browser re-emulates it with xterm.js, so fidelity is
 * exact (htop/vim/claude render correctly). Control (viewer → host input) is
 * Phase 2; [ClientMessage.Input]/[ClientMessage.RequestControl] are defined now
 * but only honored once a controller role is granted.
 *
 * Backward-compat note: add new fields with defaults so older viewers tolerate them.
 */
object ShareProtocol {
    /**
     * Shared Json: tolerant of unknown keys (forward-compat with newer hosts/viewers),
     * omits a class-discriminator collision by using "t" as the type key, and
     * encodes defaults so the browser side can rely on every field being present.
     */
    val json: Json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeServer(msg: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), msg)
    fun decodeClient(text: String): ClientMessage = json.decodeFromString(ClientMessage.serializer(), text)
}

/** Host → viewer messages. */
@Serializable
sealed class ServerMessage {
    /** One-time initial paint: the current scrollback+screen as a raw escape/text blob. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val data: String, val cols: Int, val rows: Int) : ServerMessage()

    /** Incremental raw PTY output to feed straight into the viewer's emulator. */
    @Serializable
    @SerialName("output")
    data class Output(val data: String) : ServerMessage()

    /** Terminal dimensions changed; viewer should resize its emulator. */
    @Serializable
    @SerialName("resize")
    data class Resize(val cols: Int, val rows: Int) : ServerMessage()

    /** Number of connected viewers (for the host's presence display). */
    @Serializable
    @SerialName("presence")
    data class Presence(val viewers: Int) : ServerMessage()

    /** Whether this viewer currently has write/control access (Phase 2; false in P1). */
    @Serializable
    @SerialName("control")
    data class Control(val granted: Boolean) : ServerMessage()

    /**
     * The host's terminal theme so the browser viewer renders identically to BossTerm:
     * core colors + the 16 ANSI colors (all "#RRGGBB") + font. Sent once before the
     * first [Snapshot].
     */
    @Serializable
    @SerialName("theme")
    data class Theme(
        val background: String,
        val foreground: String,
        val cursor: String,
        val cursorAccent: String,
        val selectionBackground: String,
        /** 16 ANSI colors (indices 0–15) as "#RRGGBB". */
        val ansi: List<String>,
        val fontFamily: String,
        val fontSize: Int,
    ) : ServerMessage()
}

/** Viewer → host messages. */
@Serializable
sealed class ClientMessage {
    /** Viewer handshake with an optional display name (shown in the host's presence list). */
    @Serializable
    @SerialName("hello")
    data class Hello(val name: String? = null) : ClientMessage()

    /** Keystrokes/text to write to the PTY. Honored only with controller role (Phase 2). */
    @Serializable
    @SerialName("input")
    data class Input(val data: String) : ClientMessage()

    /** Request write/control access from the host (Phase 2). */
    @Serializable
    @SerialName("requestControl")
    data object RequestControl : ClientMessage()
}
