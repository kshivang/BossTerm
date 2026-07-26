package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.daemon.DaemonMcpTools
import ai.rever.bossterm.compose.daemon.SessionHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [VoiceToolExecutor] for daemon (headless) shares. The daemon has no GUI window/tab model, so
 * the surface is the smaller [DaemonMcpTools] set — `list_tabs`/`get_active_tab` (answered from
 * [SessionHost.list], sessions presented as "tabs" so the agent sees one uniform shape),
 * `read_scrollback`, `send_input`, `send_signal`. GUI-only tools (`run_command`,
 * `search_output`, …) simply aren't advertised, and the instructions template only lists
 * advertised tools, so the agent never tries them. Voice arg names (`tab_id`) are mapped onto
 * the daemon's (`session_id`).
 */
internal class DaemonVoiceToolExecutor(
    private val host: SessionHost,
    private val inScopeSessionIds: () -> Set<String>,
    /** Provider, not a value: an ALL-scoped share's fallback session changes as sessions come and go. */
    private val anchorSessionId: () -> String?,
) : VoiceToolExecutor {

    private val tools = DaemonMcpTools(host)

    override fun tools(): List<VoiceToolDef> = VoiceToolCatalog.ALL.filter { !it.guiOnly }

    override fun contextSnapshot(defaultTabId: String?): String {
        val scope = inScopeSessionIds()
        // Same fallback as execute(): a stale id shouldn't silently drop the marker.
        val viewing = defaultTabId?.takeIf { it in scope } ?: anchorSessionId()?.takeIf { it in scope }
        val sb = StringBuilder()
        for (s in host.list()) {
            if (s.id !in scope) continue
            sb.append("- \"").append(s.title).append("\" (tab_id ").append(s.id).append(')')
            s.cwd?.let { sb.append(", cwd ").append(it) }
            if (!s.alive) sb.append(" [exited]")
            if (s.id == viewing) sb.append(" ← the user is viewing this tab")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
        // One tools() call per invocation: it rebuilds the filtered list each time, and this used to
        // resolve the name here and then look the same def up again below.
        val def = tools().firstOrNull { it.name == name }
            ?: throw VoiceToolException("Unknown tool: $name")
        val scope = inScopeSessionIds()
        val viewing = defaultTabId ?: anchorSessionId()

        // A NAMED session is always scope-checked — the lookups here scan all of host.list(), so
        // that check is the boundary. Viewer-reported focus isn't: the session can exit after it was
        // stored, and rejecting a stale id broke every tool including the two that take no argument.
        // Any session id present is scope-checked (the boundary, kept loud); it only becomes the
        // TARGET for tools that advertise the parameter — get_active_tab declares none, so honouring
        // one there made `isActive` describe a session the caller isn't viewing.
        val named = args.stringArg("tab_id")
        if (named != null && named !in scope) {
            throw VoiceToolException("Tab $named is not part of this share")
        }
        val requested = named?.takeIf { "tab_id" in VoiceToolCatalog.declaredParameters(def) }
        val target = requested
            ?: viewing?.takeIf { it in scope }
            ?: anchorSessionId()?.takeIf { it in scope }

        when (name) {
            // Needs no target at all — the agent's first orienting call must never fail just because
            // nothing is focused yet (the GUI executor behaves the same way).
            "list_tabs" -> return listTabsJson(scope, target)
            "get_active_tab" -> return tabInfoJson(
                target ?: throw VoiceToolException("No session available"),
                viewing = target,
            )
        }
        if (target == null) throw VoiceToolException("No session available")

        // Same allowlist as the GUI executor: only keys this tool advertises reach DaemonMcpTools.
        val allowed = VoiceToolCatalog.declaredParameters(def)
        val daemonArgs = buildJsonObject {
            args.forEach { (k, v) -> if (k != "tab_id" && k in allowed) put(k, v) }
            put("session_id", target)
        }
        return when (name) {
            "read_scrollback" -> tools.readScrollback(daemonArgs)
            "send_input" -> tools.sendInput(daemonArgs)
            "send_signal" -> tools.sendSignal(daemonArgs)
            else -> throw VoiceToolException("Tool not available on this host: $name")
        }
    }

    private fun listTabsJson(scope: Set<String>, viewing: String?): String = buildJsonObject {
        put("tabs", buildJsonArray {
            for (s in host.list()) {
                if (s.id !in scope) continue
                add(buildJsonObject {
                    put("id", s.id)
                    put("title", s.title)
                    s.cwd?.let { put("cwd", it) }
                    put("isActive", s.id == viewing)
                })
            }
        })
        viewing?.let { put("viewingTabId", it) }
    }.toString()

    /** [viewing] is the session the caller is looking at, so `isActive` means the same thing here
     *  as in [listTabsJson] and in the GUI executor — it used to be hardcoded true. */
    private fun tabInfoJson(sessionId: String, viewing: String?): String {
        val s = host.list().firstOrNull { it.id == sessionId } ?: return buildJsonObject {
            put("error", "Tab $sessionId is no longer open")
        }.toString()
        return buildJsonObject {
            put("id", s.id)
            put("title", s.title)
            s.cwd?.let { put("cwd", it) }
            put("isActive", s.id == viewing)
        }.toString()
    }

    private fun JsonObject.stringArg(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
