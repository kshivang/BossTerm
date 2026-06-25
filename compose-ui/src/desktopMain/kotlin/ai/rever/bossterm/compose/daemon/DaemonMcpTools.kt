package ai.rever.bossterm.compose.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put

/**
 * Pure tool logic for the daemon's MCP server, operating directly on [SessionHost] /
 * [TerminalSessionCore]. Kept transport-free (like [DaemonControlHandler]) so it can be unit-tested
 * without an MCP client: each method takes JSON args and returns a JSON text payload.
 *
 * This is the daemon-hosted subset of BossTerm's MCP surface — the read/write tools that make sense
 * over a flat list of headless sessions (no GUI window/tab/split model): list / open / read /
 * send_input / send_signal / resize / close. The GUI's richer window-aware MCP
 * ([ai.rever.bossterm.compose.mcp.BossTermMcpServer]) is unchanged.
 */
class DaemonMcpTools(private val host: SessionHost) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        const val DEFAULT_SCROLLBACK_LINES = 200

        /** Control bytes for send_signal, matching BossTermMcpServer / CLAUDE.md. */
        private val SIGNAL_BYTES = mapOf(
            "ctrl_c" to 0x03.toByte(),
            "ctrl_d" to 0x04.toByte(),
            "ctrl_z" to 0x1A.toByte(),
        )
    }

    /** `{"sessions":[{id,title,cwd,alive}]}` */
    fun listSessions(): String = buildJsonObject {
        put("sessions", buildJsonArray {
            host.list().forEach {
                add(buildJsonObject {
                    put("id", it.id); put("title", it.title); put("cwd", it.cwd ?: ""); put("alive", it.alive)
                })
            }
        })
    }.toString()

    /** Open a new daemon session; `{"id": "<sessionId>"}`. */
    fun openSession(args: JsonObject): String {
        val cwd = args.str("cwd")
        val command = args.str("command")
        val cols = args.intOr("cols", 80)
        val rows = args.intOr("rows", 24)
        val id = host.openSession(cwd = cwd, command = command, cols = cols, rows = rows)
        return buildJsonObject { put("id", id) }.toString()
    }

    /** Read the last N lines (history + screen) of a session's buffer as plain text. */
    fun readScrollback(args: JsonObject): String {
        val id = args.str("session_id") ?: return err("Missing required argument: session_id")
        val requested = args.intOr("lines", DEFAULT_SCROLLBACK_LINES).coerceAtLeast(1)
        val core = host.get(id) ?: return err("Unknown session_id: $id")
        val snapshot = core.textBuffer.createSnapshot()
        val totalAvailable = snapshot.historyLinesCount + snapshot.height
        val take = minOf(requested, totalAvailable)
        val endExclusive = snapshot.height
        val startInclusive = endExclusive - take
        val sb = StringBuilder()
        var row = startInclusive
        while (row < endExclusive) {
            sb.append(snapshot.getLine(row).text.trimEnd())
            if (row < endExclusive - 1) sb.append('\n')
            row++
        }
        return buildJsonObject { put("text", sb.toString()) }.toString()
    }

    /** Write text to a session's stdin (caller appends '\n' to submit). */
    fun sendInput(args: JsonObject): String {
        val id = args.str("session_id") ?: return err("Missing required argument: session_id")
        val text = args.str("text") ?: return err("Missing required argument: text")
        val core = host.get(id) ?: return err("Unknown session_id: $id")
        core.writeInput(text)
        return ok()
    }

    /** Send a control signal (ctrl_c / ctrl_d / ctrl_z) to a session. */
    fun sendSignal(args: JsonObject): String {
        val id = args.str("session_id") ?: return err("Missing required argument: session_id")
        val signal = args.str("signal") ?: return err("Missing required argument: signal")
        val core = host.get(id) ?: return err("Unknown session_id: $id")
        val byte = SIGNAL_BYTES[signal] ?: return err("Unknown signal '$signal' (ctrl_c|ctrl_d|ctrl_z)")
        core.writeBytes(byteArrayOf(byte))
        return ok()
    }

    /** Resize a session's grid. */
    fun resizeSession(args: JsonObject): String {
        val id = args.str("session_id") ?: return err("Missing required argument: session_id")
        val cols = args.intOr("cols", 0)
        val rows = args.intOr("rows", 0)
        if (cols < 1 || rows < 1) return err("cols/rows must be >= 1")
        val core = host.get(id) ?: return err("Unknown session_id: $id")
        core.resize(cols, rows)
        return ok()
    }

    /** Close a daemon session. */
    fun closeSession(args: JsonObject): String {
        val id = args.str("session_id") ?: return err("Missing required argument: session_id")
        host.closeSession(id)
        return ok()
    }

    // ---- json helpers (defensive: a non-primitive/absent arg yields null, never throws) ----
    private fun JsonObject.str(key: String): String? = runCatching {
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    }.getOrNull()
    private fun JsonObject.intOr(key: String, default: Int): Int =
        runCatching { (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.int }.getOrNull() ?: default

    private fun ok(): String = buildJsonObject { put("ok", true) }.toString()
    private fun err(message: String): String = buildJsonObject { put("error", message) }.toString()
}
