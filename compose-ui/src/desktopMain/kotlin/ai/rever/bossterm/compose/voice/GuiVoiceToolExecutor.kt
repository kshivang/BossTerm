package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.blocks.BlockState
import ai.rever.bossterm.compose.mcp.BossTermMcpConfig
import ai.rever.bossterm.compose.mcp.BossTermMcpServer
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [VoiceToolExecutor] for in-app (GUI) shares: executes the curated voice tools by invoking the
 * GUI MCP tool handlers **in-process** — a private transport-free [BossTermMcpServer.createServer]
 * that exists regardless of whether the user's MCP endpoint is enabled. `createServer()` already
 * honors `disabledMcpTools`, so user tool-disables carry over to voice for free.
 *
 * The server is built from the EMBEDDER's [BossTermMcpConfig] (published on
 * [McpTerminalRegistry.mcpConfig]), not a fabricated one: an app that configured a deliberately
 * read-only MCP surface (`allowWriteTools = false`) must not get run_command / send_input back
 * through voice, and a `toolNamePrefix` has to be applied when resolving handlers or the surface
 * would silently collapse to the two locally-answered tools.
 *
 * The network MCP path resolves a caller's tab from its TCP connection (ProcessAncestry); that is
 * meaningless in-process, so every call gets an explicit `tab_id` — the model's, else
 * [defaultTabId] (the tab the caller is looking at), else [anchorTabId].
 * `list_tabs` / `get_active_tab` are answered directly from [McpTerminalRegistry], filtered to
 * [inScopeTabIds], so the agent never sees tabs outside the share.
 */
/**
 * Whether the agent's command may go to the pane the user is watching.
 *
 * Pure, because every one of these branches is a behavioural decision about typing into someone's
 * live shell and none of them were reachable in a test while this was buried in a registry lookup.
 * The [hasSeenPrompt] one in particular shipped wrong: keying idleness off an empty BLOCK LIST meant
 * the commonest case of all — open a terminal, start a call, ask for a command — always fell back
 * to the scratch split, because a pane where nothing has run yet has no blocks. That is exactly the
 * pane most obviously free to use.
 */
internal fun shouldUseFocusedPane(
    /** False for a share: a remote caller never takes the host's pane. */
    mayUseFocusedPane: Boolean,
    settingEnabled: Boolean,
    /** OSC 133 has reported at least a prompt here, so "busy" is answerable at all. */
    hasSeenPrompt: Boolean,
    lastBlockRunning: Boolean,
): Boolean {
    if (!mayUseFocusedPane) return false
    if (!settingEnabled) return false
    if (!hasSeenPrompt) return false // no shell integration: cannot tell busy from idle
    return !lastBlockRunning
}

internal class GuiVoiceToolExecutor(
    private val inScopeTabIds: () -> Set<String>,
    /**
     * Fallback target when neither the model nor the caller names a tab. A provider, not a value:
     * a share anchors on its own tab, while an in-app host call follows whichever tab is focused.
     */
    private val anchorTabId: () -> String?,
    /**
     * Whether this surface may run commands in the pane the user is LOOKING at (when idle), rather
     * than run_command's scratch split.
     *
     * True for the in-app call, false for a share. The argument for the focused pane is about the
     * machine's OWNER: they asked out loud for something in the terminal in front of them, and
     * watching a split open underneath with the result over there is not what they asked for. That
     * story does not transfer to a guest on a link. The OSC 133 guard only rules out a command
     * already RUNNING — it cannot tell that the host is mid-thought at their own prompt — so a
     * remote caller keeps the scratch pane and stays out of the way.
     */
    private val mayUseFocusedPane: Boolean = false,
    private val registry: McpTerminalRegistry = McpTerminalRegistry,
    private val settings: () -> TerminalSettings = { SettingsManager.instance.settings.value },
) : VoiceToolExecutor {

    private companion object {
        /** Safety net over handler execution — the innermost rung of [VoiceToolTimeouts]. */
        fun execTimeoutMs(tool: String): Long = VoiceToolTimeouts.execMs(tool)

        /** Tools answered directly from the registry (scope-filtered), not via an MCP handler. */
        val LOCAL_TOOLS = setOf("list_tabs", "get_active_tab")
    }

    /**
     * The embedder's config, or a READ-ONLY default when no manager was ever constructed.
     *
     * `BossTermMcpConfig()` defaults `allowWriteTools = true`, which is right for an app that set up
     * MCP and said nothing about writes. It is not right here: a null `mcpConfig` means nobody ever
     * published one — an embedder that deliberately never wanted MCP at all — and "nobody told us"
     * must not resolve the same way as "they told us it is fine". The voice surface is the only
     * caller that can reach this path without the embedder having opted into anything.
     */
    private val mcpConfig: BossTermMcpConfig
        get() = registry.mcpConfig ?: BossTermMcpConfig(allowWriteTools = false)

    // Built lazily so no MCP machinery spins up until the first call actually starts.
    private val wrapper: BossTermMcpServer by lazy { BossTermMcpServer(config = mcpConfig) }
    // createServer()'s return value is deliberately unused: the wrapper owns the map, and every
    // read goes through its locked accessors.
    @Volatile private var built = false
    // includeEmbedderTools = false: see createServer's KDoc. The embedder's CONFIG is inherited (that
    // is the point of using their BossTermMcpConfig); their registration callback is not, because
    // this server is per-share/per-call and its tools are filtered to the voice catalog anyway.
    private val serverInstance: Any by lazy { built = true; wrapper.createServer(includeEmbedderTools = false) }
    @Volatile private var appliedDisabled: Set<String>? = null

    /**
     * The private server with the user's CURRENT tool disables applied.
     *
     * @Synchronized, not merely @Volatile: compare, store and apply were three steps with up to four
     * tool calls running concurrently, so two threads crossing a settings change could leave the
     * server on the OLDER set while the field claimed the newer one — and nothing would re-apply
     * until the next change, leaving a tool the user just disabled callable. That is exactly the
     * invariant this method exists to establish.
     *
     * `createServer()` reads `disabledMcpTools` once, and only [BossTermMcpManager] drives
     * `applyDisabledSet` on its own instance — so this one froze the disables as they stood at the
     * first voice call. That matters because `manage_tools` exists to toggle them at runtime:
     * disabling run_command mid-session left it callable by voice until the app restarted.
     */
    @Synchronized
    private fun server(): BossTermMcpServer {
        serverInstance // force construction on first use
        val disabled = settings().disabledMcpTools.toSet()
        if (disabled != appliedDisabled) {
            appliedDisabled = disabled
            runCatching { wrapper.applyDisabledSet(disabled) }
        }
        return wrapper
    }

    /**
     * The advertised surface AND the disabled set it was computed from, as one consistent pair.
     *
     * Reading them separately let the two disagree: tools() took its own `settings()` snapshot after
     * server() had already applied a different one, so a tool could be filtered against a disabled
     * set the server was not actually on.
     */
    @Synchronized
    private fun surface(): Pair<BossTermMcpServer, Set<String>> = server() to (appliedDisabled ?: emptySet())

    /** The registered handler name for a catalog tool (the embedder may prefix them). */
    private fun handlerName(tool: String): String = mcpConfig.toolNamePrefix + tool

    override fun tools(): List<VoiceToolDef> {
        // Via the wrapper, not the raw SDK map: it is not thread-safe and this runs concurrently
        // with applyDisabledSet and with up to four in-flight tool calls.
        val (wrapper, disabled) = surface()
        val registered = wrapper.toolNames()
        // LOCAL_TOOLS are answered from the registry rather than through a handler, so they skipped
        // the disabled check that the class KDoc claims carries over "for free" — a user who turned
        // list_tabs off via manage_tools still had it callable by voice, still listing every in-scope
        // tab's title and cwd. Read-only and scope-filtered, but the invariant was stated and untrue.
        return VoiceToolCatalog.ALL.filter {
            if (it.name in LOCAL_TOOLS) handlerName(it.name) !in disabled && it.name !in disabled
            else handlerName(it.name) in registered
        }
    }

    /**
     * The focused pane of [tabId], but only when nothing is running in it.
     *
     * "Nothing running" is OSC 133's answer, not a guess: [CommandBlockTracker] opens a block on
     * command start and closes it on exit, so a trailing RUNNING block means the user (or a previous
     * tool call) has something in flight there. Typing into that would interleave with a live
     * program — a build, a REPL, an ssh session, `less` — so those fall back to the scratch pane,
     * which is what this tool did unconditionally before.
     *
     * A pane with no tracker and no blocks returns null too: absent shell integration we cannot tell
     * busy from idle, and run_command needs OSC 133 anyway.
     */
    private fun idleFocusedPaneId(tabId: String): String? {
        val state = registry.findState(tabId) ?: return null
        val session = state.findSession(tabId, paneId = null) ?: return null
        val tracker = session.commandBlockTracker ?: return null
        val blocks = tracker.blocks.value
        val usable = shouldUseFocusedPane(
            mayUseFocusedPane = mayUseFocusedPane,
            settingEnabled = settings().voiceRunInFocusedPane,
            hasSeenPrompt = tracker.hasSeenPrompt,
            lastBlockRunning = blocks.isNotEmpty() && blocks.last().state == BlockState.RUNNING,
        )
        return if (usable) session.id else null
    }

    override fun dispose() {
        // Only if it was ever built: touching the lazy here would construct one just to drop it.
        if (appliedDisabled != null || built) runCatching { wrapper.detachServer() }
    }

    override fun contextSnapshot(defaultTabId: String?): String {
        val scope = inScopeTabIds()
        // Same fallback as execute(): a stale or foreign id would otherwise silently drop the
        // "the user is viewing this tab" marker instead of pointing at the anchor.
        val viewing = defaultTabId?.takeIf { it in scope } ?: anchorTabId()?.takeIf { it in scope }
        // Titles and cwds are TERMINAL-CONTROLLED and this string becomes part of the agent's system
        // instructions — see VoiceContextSnapshot for why that is sanitized rather than trusted.
        val inScope = registry.allTabs().filter { it.id in scope }
        val sb = StringBuilder()
        for (tab in inScope.take(VoiceContextSnapshot.MAX_ENTRIES)) {
            val active = registry.findState(tab.id)?.activeTabId == tab.id
            sb.append("- \"").append(VoiceContextSnapshot.field(tab.title.value))
                .append("\" (tab_id ").append(tab.id).append(')')
            VoiceContextSnapshot.field(tab.workingDirectory.value).takeIf { it.isNotEmpty() }
                ?.let { sb.append(", cwd ").append(it) }
            if (active) sb.append(" [active]")
            if (tab.id == viewing) sb.append(" ← the user is viewing this tab")
            sb.append('\n')
        }
        return VoiceContextSnapshot.withOverflowNote(
            sb.toString().trimEnd(),
            shown = minOf(inScope.size, VoiceContextSnapshot.MAX_ENTRIES),
            total = inScope.size,
        )
    }

    override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
        // ONE rebuild per call. tools() takes toolsLock (via server().toolNames()) and copies the
        // disabled set, and this method used to call it two or three times — plus the service calls
        // it once more just before. Resolving here also refuses a DISABLED local tool for free:
        // tools() filters those out, so it never resolves, which is what the extra check below was
        // doing by hand.
        val def = tools().firstOrNull { it.name == name }
            ?: throw VoiceToolException("Unknown tool: $name")
        val scope = inScopeTabIds()

        // Resolve and scope-check the target BEFORE dispatching anywhere — including the locally
        // answered tools. [defaultTabId] comes from the viewer's own Focus/VoiceStart, and the
        // registry lookups behind get_active_tab span every window on the host, so checking only
        // the MCP path would let a viewer name a foreign tab and read back its title and cwd.
        // A tab the model NAMES is always scope-checked — that's the security boundary. The
        // viewer-reported focus is not a boundary: it was in scope when stored, but the tab can
        // close afterwards, and treating a stale id as a violation used to break every tool
        // including list_tabs/get_active_tab, leaving the agent no way to discover a valid id.
        // Stale focus therefore falls back to the anchor, which is in scope by construction.
        // Any tab_id present is scope-checked — refusing a foreign id loudly is the documented
        // boundary, and staying loud beats silently ignoring it. But it only becomes the TARGET for
        // tools that actually advertise the parameter: get_active_tab declares none, so honouring one
        // there made `isActive` describe a tab the caller isn't viewing.
        val named = args.stringArg("tab_id")
        if (named != null && named !in scope) {
            throw VoiceToolException("Tab ${named.take(120)} is not part of this share")
        }
        val requested = named?.takeIf { "tab_id" in VoiceToolCatalog.declaredParameters(def) }
        val focused = defaultTabId?.takeIf { it in scope }
        val fallback = requested ?: focused ?: anchorTabId()?.takeIf { it in scope }

        // Tab enumeration is answered locally so it can be scope-filtered (the MCP handler
        // enumerates every window on the host), and needs no target at all.
        when (name) {
            "list_tabs" -> return listTabsJson(scope, fallback)
            "get_active_tab" -> return tabInfoJson(
                fallback ?: throw VoiceToolException("No terminal tab is available")
            )
        }

        val targetTabId = fallback ?: throw VoiceToolException("No terminal tab is available")

        // Pass through ONLY the keys this tool advertises. The underlying MCP schemas are wider
        // than the voice catalog — run_command also takes panel/working_dir/split_ratio — and
        // `panel: "new_tab"` would run the command in a tab the share doesn't mirror, undercutting
        // the "targets are limited to the share" guarantee even though a controller could open a tab
        // by other means.
        val allowed = VoiceToolCatalog.declaredParameters(def)
        val effectiveArgs = buildJsonObject {
            args.forEach { (k, v) -> if (k != "tab_id" && k in allowed) put(k, v) }
            put("tab_id", targetTabId)
            // Run in the pane the user is actually looking at, when it is safe to.
            //
            // Left alone, run_command's "reuse" mode creates a dedicated scratch pane on first use
            // (mcpRunCommandDefaultPanel, a horizontal split) so an agent's output can't interleave
            // with what someone is typing. That is right for a background MCP client and wrong for a
            // voice call: asking out loud to "cd into Development/Boss" and watching a split open
            // underneath you, with the cd applied over there, is not what was asked for.
            //
            // The pane id comes from the HOST, never the model — `pane_id` is not in the voice
            // catalog's declared parameters, so this cannot be steered by an injected instruction to
            // point at some other pane. It only ever resolves to the focused pane of a tab already
            // scope-checked above.
            if (def.name == "run_command") {
                idleFocusedPaneId(targetTabId)?.let { put("pane_id", it) }
            }
        }
        val registeredName = handlerName(def.name)
        val handler = server().handlerFor(registeredName)
            ?: throw VoiceToolException("Tool not available on this host: $name")
        // Per-tool, not a flat ceiling: a single 610s net over the 120s read watchdog is how four
        // wedged reads used to hold every in-flight slot for eight minutes after being answered.
        val result = withTimeoutOrNull(execTimeoutMs(def.name)) {
            handler(CallToolRequest(CallToolRequestParams(name = registeredName, arguments = effectiveArgs)))
        } ?: throw VoiceToolException("Tool $name timed out")
        return result.content.filterIsInstance<TextContent>().firstOrNull()?.text ?: "{}"
    }

    private fun listTabsJson(scope: Set<String>, viewingTabId: String?): String = buildJsonObject {
        put("tabs", buildJsonArray {
            for (tab in registry.allTabs()) {
                if (tab.id !in scope) continue
                add(buildJsonObject {
                    put("id", tab.id)
                    put("title", VoiceContextSnapshot.field(tab.title.value))
                    VoiceContextSnapshot.field(tab.workingDirectory.value).takeIf { it.isNotEmpty() }?.let { put("cwd", it) }
                    put("isActive", registry.findState(tab.id)?.activeTabId == tab.id)
                })
            }
        })
        viewingTabId?.let { put("viewingTabId", it) }
    }.toString()

    private fun tabInfoJson(tabId: String): String {
        val tab = registry.findTab(tabId) ?: return buildJsonObject {
            put("error", "Tab $tabId is no longer open")
        }.toString()
        return buildJsonObject {
            put("id", tab.id)
            put("title", VoiceContextSnapshot.field(tab.title.value))
            VoiceContextSnapshot.field(tab.workingDirectory.value).takeIf { it.isNotEmpty() }?.let { put("cwd", it) }
            put("isActive", registry.findState(tab.id)?.activeTabId == tab.id)
        }.toString()
    }

    private fun JsonObject.stringArg(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
