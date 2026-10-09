package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.session.SessionCommandRunner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Daemon topology and scratch-pane ownership; command capture belongs to the shared runner. */
internal class DaemonCommandExecutor(private val host: SessionHost) {
    private data class Target(val core: TerminalSessionCore, val tabId: String, val fresh: Boolean)

    // Resolve/create atomically so pipelined calls cannot create duplicate scratch panes.
    private val resolutionMutex = Mutex()
    private val scratchPanes = mutableMapOf<String, String>()

    suspend fun run(args: JsonObject): String {
        val script = args.string("script") ?: return error("Missing required argument: script")
        if (script.isBlank()) return error("'script' must not be empty or blank")
        for (key in listOf("pane_id", "tab_id", "panel", "working_dir")) {
            val value = args[key]
            if (value != null && value != JsonNull && (value !is JsonPrimitive || !value.isString)) {
                return error("'$key' must be a string")
            }
        }
        val timeoutValue = args["timeout_ms"]
        if (timeoutValue != null && timeoutValue != JsonNull &&
            (timeoutValue !is JsonPrimitive || timeoutValue.isString || timeoutValue.intOrNull == null)) {
            return error("'timeout_ms' must be an integer")
        }
        val ratioValue = args["split_ratio"]
        if (ratioValue != null && ratioValue != JsonNull &&
            (ratioValue !is JsonPrimitive || ratioValue.isString || ratioValue.floatOrNull?.isFinite() != true)) {
            return error("'split_ratio' must be a finite number")
        }
        val panel = (args.string("panel") ?: "reuse").lowercase()
        val validPanels = setOf("reuse", "horizontal_split", "vertical_split", "new_tab")
        if (panel !in validPanels) return error("Unknown panel: '$panel'. Expected reuse, horizontal_split, vertical_split, new_tab")
        val settings = host.currentSettings()
        val timeoutMs = ((timeoutValue as? JsonPrimitive)?.intOrNull ?: settings.mcpRunCommandDefaultTimeoutMs)
            .coerceIn(100, 600_000)
        val ratio = ((ratioValue as? JsonPrimitive)?.floatOrNull ?: settings.mcpDefaultSplitRatio).coerceIn(0.05f, 0.95f)
        val explicitPane = args.string("pane_id")
        val requestedTab = args.string("tab_id")
        var resolutionError: String? = null
        val target = resolutionMutex.withLock {
            scratchPanes.entries.removeAll { host.get(it.value) == null }
            val requestedGroup = requestedTab?.let { tab ->
                if (host.sessionsInGroup(tab).isNotEmpty()) tab else host.groupForSession(tab)
            }
            if (requestedTab != null && requestedGroup == null && host.get(requestedTab) == null) {
                resolutionError = "Unknown tab_id: $requestedTab"
                return@withLock null
            }
            if (explicitPane != null) {
                val core = host.get(explicitPane)
                if (core == null || (requestedTab != null &&
                        if (requestedGroup != null) host.groupForSession(explicitPane) != requestedGroup
                        else explicitPane != requestedTab)) {
                    resolutionError = "Unknown pane_id '$explicitPane'${requestedTab?.let { " in tab '$it'" }.orEmpty()}"
                    return@withLock null
                }
                // Explicit user panes never become cached scratch panes.
                return@withLock Target(core, host.groupForSession(core.id) ?: core.id, false)
            }
            val sourceGroup = requestedGroup ?: if (requestedTab == null) host.listGroups().firstOrNull()?.groupId else null
            val sourceSession = sourceGroup?.let { host.sessionsInGroup(it).firstOrNull() }
                ?: requestedTab ?: host.list().firstOrNull()?.id
            val cacheKey = sourceGroup ?: sourceSession ?: "<default>"
            scratchPanes[cacheKey]?.let { id ->
                host.get(id)?.let { core -> return@withLock Target(core, host.groupForSession(id) ?: id, false) }
            }
            val configuredPanel = settings.mcpRunCommandDefaultPanel.lowercase()
            val effectivePanel = if (panel == "reuse") {
                configuredPanel.takeIf { it in validPanels && it != "reuse" } ?: "horizontal_split"
            } else panel
            val cwd = args.string("working_dir") ?: sourceSession?.let { host.get(it)?.workingDirectory?.value }
                ?: System.getProperty("user.home")
            val paneId: String
            val tabId: String
            if (sourceGroup != null && sourceSession != null && effectivePanel != "new_tab") {
                val orientation = if (effectivePanel == "vertical_split") SplitOrientation.VERTICAL else SplitOrientation.HORIZONTAL
                paneId = host.splitPane(sourceSession, orientation, cwd = cwd, ratio = 1f - ratio)
                    ?: run { resolutionError = "Source tab closed while creating scratch pane"; return@withLock null }
                tabId = sourceGroup
            } else {
                // A headless/flat source has no split tree. Give its scratch pane a new visible tab.
                val opened = host.openWindow(cwd = cwd, initialCommand = null)
                paneId = opened.first
                tabId = opened.second
            }
            val core = host.get(paneId)
                ?: run { resolutionError = "Scratch pane exited during startup"; return@withLock null }
            scratchPanes[cacheKey] = paneId
            scratchPanes[tabId] = paneId
            Target(core, tabId, true)
        } ?: return error(resolutionError ?: "Cannot resolve scratch pane")

        return target.core.mcpCommandMutex.withLock {
            if (host.get(target.core.id) !== target.core) return@withLock error("Pane closed before command submission")
            val started = System.nanoTime()
            val outcome = SessionCommandRunner.run(
                terminal = target.core.terminal,
                textBuffer = target.core.textBuffer,
                writeInput = target.core::writeInput,
                script = script,
                timeoutMs = timeoutMs,
                freshlyCreated = target.fresh,
                shellReadyTimeoutMs = settings.mcpRunCommandShellReadyTimeoutMs.coerceIn(0, 30_000).toLong(),
                maxOutputChars = settings.mcpRunCommandMaxOutputChars.coerceAtLeast(1024),
                isAlive = { target.core.state.value is TerminalSessionCore.State.Initializing || target.core.isAlive() },
            )
            buildJsonObject {
                put("ok", outcome.error == null)
                put("tabId", target.tabId)
                put("paneId", target.core.id)
                put("exitCode", outcome.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                put("durationMs", (System.nanoTime() - started) / 1_000_000)
                put("output", outcome.output)
                put("truncated", outcome.truncated)
                put("error", outcome.error?.let { JsonPrimitive(it) } ?: JsonNull)
            }.toString()
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun error(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()
}
