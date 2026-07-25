package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject

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
