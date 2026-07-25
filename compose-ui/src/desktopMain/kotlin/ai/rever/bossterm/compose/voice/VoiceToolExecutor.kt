package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Cap on one tool result, applied by BOTH surfaces before the payload crosses a wire.
 *
 * Neither backend bounds bytes: the GUI's read_scrollback clips to the lines that exist (the whole
 * scrollback, not a byte budget) and the daemon's doesn't clip at all, so `{"lines": 1000000}` — or
 * `run_command` on a build log — produces one enormous JSON string. Too large to send is worse than
 * truncated: the share path can overflow the viewer's outbox, and an oversized data-channel or
 * WebSocket frame throws *after* the round was settled, leaving the agent narrating a result the
 * model never received.
 */
internal const val MAX_TOOL_RESULT_CHARS = 128 * 1024

/** Bound [resultJson], substituting a self-describing payload when it is too large to send. */
internal fun clampToolResult(tool: String, resultJson: String): String {
    if (resultJson.length <= MAX_TOOL_RESULT_CHARS) return resultJson
    LoggerFactory.getLogger("ai.rever.bossterm.compose.voice.ToolResult")
        .warn("Voice tool {} returned {} chars; truncating", tool, resultJson.length)
    return buildJsonObject {
        put("truncated", true)
        put("originalLength", resultJson.length)
        put("note", "The result was too large to send; ask for less (fewer lines, a tighter pattern).")
        put("head", resultJson.take(MAX_TOOL_RESULT_CHARS / 2))
    }.toString()
}

/** A voice tool call failed before/without reaching the underlying tool (unknown name, scope). */
class VoiceToolException(message: String) : Exception(message)

/**
 * Executes the voice agent's tool calls against one shared session. Two implementations:
 * [GuiVoiceToolExecutor] (in-app shares — full surface via the GUI MCP handlers) and
 * [DaemonVoiceToolExecutor] (daemon shares — the smaller headless surface). Both hard-limit
 * targets to the share's scope: the agent can never read or type into tabs the share
 * doesn't cover.
 */
interface VoiceToolExecutor {

    /** The tools this host supports, advertised to OpenAI verbatim. */
    fun tools(): List<VoiceToolDef>

    /**
     * A short plain-text snapshot of the session (tab list + the viewer's current tab) baked
     * into the agent's instructions so it starts oriented. May be stale — the agent is told
     * to use tools for fresh data.
     */
    fun contextSnapshot(defaultTabId: String?): String

    /**
     * Execute [name] with the model's [args], defaulting tab-targeting to [defaultTabId]
     * (the tab the viewer is looking at). Returns the tool's JSON payload; tool-level
     * failures come back as `{"error": …}` JSON, while unknown names / out-of-scope targets
     * throw [VoiceToolException].
     */
    suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String
}
