package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Boss Calling glue shared by both share servers ([ai.rever.bossterm.compose.share.MirrorShare]
 * and [ai.rever.bossterm.compose.daemon.DaemonShareServer]): answers the viewer's voice messages
 * — mint an ephemeral Realtime session on `voiceStart`, execute agent tool calls on
 * `voiceToolCall` — enforcing the policy in one place: controller role required to start a call
 * and for every write tool, targets limited to the share's scope (via [executor]), the API key
 * never serialized into any reply.
 */
internal class VoiceCallService(
    private val executor: VoiceToolExecutor,
    private val scope: CoroutineScope,
    private val broker: VoiceSessionBroker = VoiceSessionBroker(),
    private val settings: () -> TerminalSettings = { SettingsManager.instance.settings.value },
    private val loadKey: () -> String? = { VoiceAgentStorage.load()?.openaiApiKey?.takeIf { it.isNotBlank() } },
    /** The host's display name for this shared session, for the agent's instructions. */
    private val sessionName: () -> String? = { null },
) {

    private val log = LoggerFactory.getLogger(VoiceCallService::class.java)
    private val inFlightToolCalls = AtomicInteger(0)

    /** Current availability, for the viewer's Call pill (sent on connect + on change). */
    fun status(): ServerMessage.VoiceStatus {
        val s = settings()
        return when {
            !s.voiceCallEnabled -> ServerMessage.VoiceStatus(available = false, reason = "disabled")
            loadKey() == null -> ServerMessage.VoiceStatus(available = false, reason = "no_key")
            else -> ServerMessage.VoiceStatus(available = true)
        }
    }

    /**
     * `voiceStart`: validate, then mint an ephemeral session off [scope] and reply
     * [ServerMessage.VoiceSession] (or [ServerMessage.VoiceError]). Non-suspending — both
     * servers call this from their WS receive loop. The caller records `msg.activeTabId` on
     * its connection and passes it back as `defaultTabId` on subsequent tool calls.
     */
    fun handleStart(msg: ClientMessage.VoiceStart, canControl: Boolean, reply: (ServerMessage) -> Unit) {
        if (!canControl) {
            reply(ServerMessage.VoiceError(code = "not_controller"))
            return
        }
        val s = settings()
        if (!s.voiceCallEnabled) {
            reply(ServerMessage.VoiceError(code = "disabled"))
            return
        }
        val key = loadKey()
        if (key == null) {
            reply(ServerMessage.VoiceError(code = "no_key"))
            return
        }
        scope.launch {
            val tools = executor.tools()
            val result = broker.mint(
                apiKey = key,
                model = s.voiceCallModel,
                voice = s.voiceCallVoice,
                instructions = buildInstructions(msg.activeTabId, tools),
                tools = VoiceToolCatalog.openAiToolsJson(tools),
            )
            when (result) {
                is VoiceSessionBroker.MintResult.Ok -> reply(
                    ServerMessage.VoiceSession(
                        clientSecret = result.clientSecret,
                        model = s.voiceCallModel,
                        expiresAtMs = result.expiresAtMs,
                    )
                )
                is VoiceSessionBroker.MintResult.Unauthorized ->
                    reply(ServerMessage.VoiceError(code = "unauthorized"))
                is VoiceSessionBroker.MintResult.Failed ->
                    reply(ServerMessage.VoiceError(code = "mint_failed", message = result.message))
            }
        }
    }

    /**
     * `voiceToolCall`: validate + execute off [scope], reply [ServerMessage.VoiceToolResult]
     * echoing the call id. Failures come back as `isError=true` with an `{"error":…}` payload so
     * the agent can recover verbally instead of the call dying.
     */
    fun handleToolCall(
        msg: ClientMessage.VoiceToolCall,
        canControl: Boolean,
        defaultTabId: String?,
        reply: (ServerMessage) -> Unit,
    ) {
        val def = executor.tools().firstOrNull { it.name == msg.name }
        if (def == null) {
            reply(toolError(msg.callId, "Unknown tool: ${msg.name}"))
            return
        }
        if (def.write && !canControl) {
            reply(toolError(msg.callId, "The caller does not have control of this session"))
            return
        }
        if (inFlightToolCalls.get() >= MAX_IN_FLIGHT_TOOL_CALLS) {
            reply(toolError(msg.callId, "Too many tool calls in flight; wait for one to finish"))
            return
        }
        val args = runCatching { json.parseToJsonElement(msg.argsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        inFlightToolCalls.incrementAndGet()
        scope.launch {
            try {
                val resultJson = executor.execute(def.name, args, defaultTabId)
                reply(ServerMessage.VoiceToolResult(callId = msg.callId, resultJson = resultJson))
            } catch (e: VoiceToolException) {
                reply(toolError(msg.callId, e.message ?: "Tool call rejected"))
            } catch (e: Exception) {
                log.warn("Voice tool {} failed: {}", def.name, e.javaClass.simpleName)
                reply(toolError(msg.callId, "Tool failed: ${e.javaClass.simpleName}"))
            } finally {
                inFlightToolCalls.decrementAndGet()
            }
        }
    }

    private fun toolError(callId: String, message: String): ServerMessage.VoiceToolResult =
        ServerMessage.VoiceToolResult(
            callId = callId,
            resultJson = buildJsonObject { put("error", message) }.toString(),
            isError = true,
        )

    private fun buildInstructions(activeTabId: String?, tools: List<VoiceToolDef>): String {
        val names = tools.map { it.name }.toSet()
        val snapshot = runCatching { executor.contextSnapshot(activeTabId) }.getOrDefault("")
        return buildString {
            appendLine("You are Boss, a voice assistant inside a shared BossTerm terminal session.")
            appendLine("A remote user is watching this terminal in a browser and talking to you.")
            appendLine()
            sessionName()?.let { appendLine("Session: $it") }
            appendLine("Session snapshot (may be stale — use tools for fresh data):")
            appendLine(snapshot.ifBlank { "- (no tabs visible)" })
            appendLine("Default all tool calls to the tab the user is viewing (omit tab_id).")
            appendLine()
            appendLine("Rules:")
            appendLine("- Inspect before you answer: read_scrollback" +
                    (if ("search_output" in names) " / search_output" else "") +
                    (if ("get_last_command" in names) " / get_last_command" else "") + ".")
            if ("run_command" in names) {
                appendLine("- Run shell commands with run_command; use send_input only for interactive " +
                        "programs (TUIs, prompts); send_signal ctrl_c to interrupt.")
            } else {
                appendLine("- Run shell commands by typing them with send_input (include a trailing \\n " +
                        "to submit), then read_scrollback to see the result; send_signal ctrl_c to interrupt.")
            }
            appendLine("- Say briefly what you are about to do before a slow tool call, and summarize " +
                    "results conversationally — never read raw terminal output verbatim.")
            appendLine("- For destructive commands (rm, kill, force-push, reset --hard), say the exact " +
                    "command and get verbal confirmation first.")
            append("- Keep replies short. This is a voice conversation.")
        }
    }

    private companion object {
        const val MAX_IN_FLIGHT_TOOL_CALLS = 4
        val json = Json { ignoreUnknownKeys = true }
    }
}
