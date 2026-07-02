package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ai.rever.bossterm.compose.mcp.BossTermMcpServer
import ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig
import ai.rever.bossterm.compose.mcp.McpAttachResult
import ai.rever.bossterm.compose.mcp.McpAttachTarget
import ai.rever.bossterm.compose.mcp.McpCliAttacher
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.Success
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextOnAccent
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.Warning
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.SettingsDropdown
import ai.rever.bossterm.compose.settings.components.SettingsNumberInput
import ai.rever.bossterm.compose.settings.components.SettingsSection
import ai.rever.bossterm.compose.settings.components.SettingsToggle

/**
 * MCP server settings: toggle the in-process Model Context Protocol server
 * and pick a localhost port. Endpoint is always `http://127.0.0.1:<port>`
 * over SSE; the server only binds when enabled. Off by default so the port
 * isn't opened until the user opts in.
 *
 * When an embedder has provided a [ai.rever.bossterm.compose.mcp.BossTermMcpConfig]
 * via [LocalBossTermMcpConfig], the inline endpoint note surfaces the configured
 * server name. When the composition local is null (no manager was constructed
 * by the host application), the section renders a short note pointing at the
 * docs so users understand why toggling has no effect.
 */
@Composable
fun McpSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cfg = LocalBossTermMcpConfig.current

    Column(modifier = modifier) {

        // No-manager banner: shown when the embedder hasn't wired
        // BossTermMcpManager in their fun main(). Toggles below still render
        // so users see what would be available — the manager is what actually
        // binds the port.
        if (cfg == null) {
            ConfigurationBanner()
            Spacer(modifier = Modifier.height(16.dp))
        }

        SettingsSection(title = "BossTerm MCP Server") {
            SettingsToggle(
                label = "Enable BossTerm MCP Server",
                checked = settings.mcpEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(mcpEnabled = it)) },
                description = "Bind an in-process Model Context Protocol server on " +
                        "127.0.0.1 so external tools (e.g. AI clients) can read scrollback, " +
                        "search output, and drive your tabs. Toggling takes effect immediately."
            )

            SettingsNumberInput(
                label = "Port",
                value = settings.mcpPort,
                onValueChange = { onSettingsChange(settings.copy(mcpPort = it)) },
                range = 1..65535,
                description = "Localhost port the server listens on. Default 7676. " +
                        "Changing while enabled will rebind to the new port.",
                enabled = settings.mcpEnabled
            )

            SettingsToggle(
                label = "Show Status Indicator in Tab Bar",
                checked = settings.mcpShowStatusIndicator,
                onCheckedChange = { onSettingsChange(settings.copy(mcpShowStatusIndicator = it)) },
                description = "Display a small green dot in the tab bar while the BossTerm MCP server " +
                        "is running. Click the dot to jump to this settings page.",
                enabled = settings.mcpEnabled
            )

            ai.rever.bossterm.compose.settings.components.SettingsSlider(
                label = "Default Split Size for `run_in_panel` / `run_command`",
                value = settings.mcpDefaultSplitRatio,
                onValueChange = { onSettingsChange(settings.copy(mcpDefaultSplitRatio = it)) },
                onValueChangeFinished = onSettingsSave,
                // Range matches run_in_panel's per-call split_ratio clamp
                // (0.05..0.95) so any value an agent might send is also
                // representable in the UI. Step size 0.05.
                valueRange = 0.05f..0.95f,
                steps = 17, // 0.05, 0.10, ..., 0.95 = 19 stops, 17 internal steps
                valueDisplay = { "${(it * 100).toInt()}%" },
                description = "When an MCP agent opens a split without specifying split_ratio, " +
                        "the new pane gets this fraction of the parent's size. Smaller values " +
                        "(~30%) keep the agent's main pane visible; larger values give the " +
                        "script more real estate.",
                enabled = settings.mcpEnabled
            )

            SettingsDropdown(
                label = "Default Panel Mode for `run_command`",
                options = listOf("horizontal_split", "vertical_split", "new_tab"),
                selectedOption = settings.mcpRunCommandDefaultPanel,
                onOptionSelected = {
                    onSettingsChange(settings.copy(mcpRunCommandDefaultPanel = it))
                },
                description = "Where `run_command` creates its scratch pane on the first call " +
                        "in a tab. Subsequent calls reuse that pane regardless of this setting. " +
                        "`horizontal_split` puts a strip below the agent's pane; `vertical_split` " +
                        "puts it beside; `new_tab` opens a fresh tab.",
                enabled = settings.mcpEnabled
            )

            SettingsNumberInput(
                label = "Default `run_command` Timeout (ms)",
                value = settings.mcpRunCommandDefaultTimeoutMs,
                onValueChange = {
                    onSettingsChange(settings.copy(mcpRunCommandDefaultTimeoutMs = it))
                },
                range = 100..600_000,
                description = "Hard timeout `run_command` uses when the caller doesn't pass " +
                        "`timeout_ms` explicitly. 120000 = 2 minutes (default). Range " +
                        "100..600000. Per-call values from the agent still override this.",
                enabled = settings.mcpEnabled
            )

            SettingsToggle(
                label = "Use `run_command` as AI clients' default shell",
                checked = settings.mcpRunCommandPreferredShell,
                onCheckedChange = {
                    onSettingsChange(settings.copy(mcpRunCommandPreferredShell = it))
                },
                description = "Off by default: `run_command` is available, but the agent uses " +
                        "it only when you explicitly ask (e.g. \"split and run X\"). Turn on to " +
                        "prefer `run_command` over the client's own built-in shell for " +
                        "everything, so commands run in a visible BossTerm pane. The MCP " +
                        "initialize instructions carry this as a soft nudge (applies on the " +
                        "next client connect). If you've installed the Claude Code PreToolUse " +
                        "hook (docs/mcp-server.md), this also writes the ~/.bossterm/mcp.port " +
                        "marker live, so the hook enforces it instantly — toggling flips Bash " +
                        "routing on/off per command with no Claude restart.",
                enabled = settings.mcpEnabled
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        ExposedToolsSection(
            settings = settings,
            onSettingsChange = onSettingsChange,
            allowWriteTools = cfg?.allowWriteTools != false
        )

        Spacer(modifier = Modifier.height(16.dp))

        AttachToCliSection(
            port = settings.mcpPort,
            enabled = settings.mcpEnabled,
            serverName = cfg?.serverName ?: "bossterm"
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Inline endpoint + security note. Includes the embedder's configured
        // server name when available so users know how the server identifies
        // itself to clients.
        val serverIdLine = cfg?.let { "Server identifier: ${it.serverName} v${it.serverVersion}\n" } ?: ""
        Text(
            text = serverIdLine +
                    "Endpoint when enabled: http://127.0.0.1:${settings.mcpPort}\n" +
                    "Server binds loopback only and rejects non-loopback Host headers (403). " +
                    "Tools include read access (scrollback, search, last command)" +
                    (if (cfg?.allowWriteTools != false) " and write access (send_input, send_signal)" else "") +
                    ". Any process running as your user can reach the endpoint while it is enabled.",
            color = TextMuted,
            fontSize = 12.sp
        )
    }
}

/**
 * Per-tool toggles that control which built-in BossTerm MCP tools are exposed
 * to clients. Reads/writes the same `disabledMcpTools` setting that the
 * `manage_tools` MCP tool edits, so toggling here is identical to a remote
 * agent calling `manage_tools` with `disable` / `enable`. Changes apply live
 * (no server restart needed).
 *
 * Write tools (send_input, send_signal, run_in_panel) only render when the
 * embedder's config allows write tools at all — otherwise they're never
 * registered regardless of this toggle, so showing them would mislead.
 */
@Composable
private fun ExposedToolsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    allowWriteTools: Boolean
) {
    val disabled = settings.disabledMcpTools

    fun setEnabled(toolName: String, enable: Boolean) {
        val next = disabled.toMutableSet()
        if (enable) next.remove(toolName) else next.add(toolName)
        onSettingsChange(settings.copy(disabledMcpTools = next))
    }

    SettingsSection(title = "Exposed Tools") {
        Text(
            text = "Pick which built-in BossTerm MCP tools clients can call. Toggling here " +
                    "is equivalent to calling the `manage_tools` MCP tool — both update the " +
                    "same setting and apply live without restarting the server. The " +
                    "`manage_tools` tool itself is always exposed so disabling everything " +
                    "leaves a way back.",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        ToolGroupLabel("Read tools")
        BossTermMcpServer.BUILT_IN_READ_TOOLS.forEach { name ->
            SettingsToggle(
                label = name,
                checked = name !in disabled,
                onCheckedChange = { setEnabled(name, it) },
                description = toolDescription(name),
                enabled = settings.mcpEnabled
            )
        }

        if (allowWriteTools) {
            Spacer(modifier = Modifier.height(8.dp))
            ToolGroupLabel("Write tools")
            BossTermMcpServer.BUILT_IN_WRITE_TOOLS.forEach { name ->
                // Reserved tools (only manage_tools today) can never be disabled;
                // it isn't in this list, so every write tool is togglable.
                val reserved = name in BossTermMcpServer.UNDISABLABLE_TOOLS
                SettingsToggle(
                    label = name,
                    checked = name !in disabled,
                    onCheckedChange = { setEnabled(name, it) },
                    description = toolDescription(name),
                    enabled = settings.mcpEnabled && !reserved
                )
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Write tools are disabled by this build's configuration (allowWriteTools=false).",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            // Always-exposed meta-tool isn't in BUILT_IN_READ_TOOLS or
            // BUILT_IN_WRITE_TOOLS, so the loops above never render it.
            // Surface its existence here so users know it's there.
            text = "Plus the always-exposed meta-tool `manage_tools` — lets clients " +
                    "enable/disable the tools above at runtime. It cannot be hidden from " +
                    "this surface.",
            color = TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ToolGroupLabel(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

/** Short, user-facing one-liner for each tool. Kept terse — the MCP tool descriptions
 *  in BossTermMcpServer carry the full schema details for clients. */
private fun toolDescription(name: String): String = when (name) {
    "list_tabs" -> "Enumerate every open terminal tab across all windows."
    "get_active_tab" -> "Return the active tab of the primary window."
    "read_scrollback" -> "Read the last N lines from a tab/pane's buffer."
    "search_output" -> "Regex-search a tab/pane's scrollback for matches."
    "get_last_command" -> "Return the most recently completed OSC 133 command."
    "read_debug_console" -> "Read recent entries from a tab's debug-data buffer."
    "send_input" -> "Write raw text (including newlines) to a tab/pane's stdin."
    "send_signal" -> "Send ctrl_c / ctrl_d / ctrl_z to a tab/pane."
    "run_in_panel" -> "Open a new tab or split pane and run a script in it (fire-and-forget)."
    "run_command" ->
        "Run a shell command in a visible pane and return its output + exit code. " +
            "Available for explicit use by default; see 'Use run_command as default " +
            "shell' above to make AI clients prefer it over their built-in shell."
    "show_image" -> "Display an image (file or base64) inline in a terminal pane."
    else -> name
}

/**
 * One-click attach buttons for the four AI CLIs that ship with BossTerm's
 * AI Assistants menu. Each button tries the CLI's native `mcp add`
 * subcommand; on failure (binary missing, command not supported, non-zero
 * exit) the relevant config snippet is copied to the clipboard.
 *
 * Buttons disable themselves when MCP is off — attaching is pointless
 * before the server is bound.
 */
@Composable
private fun AttachToCliSection(
    port: Int,
    enabled: Boolean,
    serverName: String
) {
    val scope = rememberCoroutineScope()
    // Per-target status so clicking different buttons in sequence preserves
    // each line — without this every click clobbers the others.
    val lastResults = remember { mutableStateMapOf<McpAttachTarget, McpAttachResult>() }
    var inFlight by remember { mutableStateOf<McpAttachTarget?>(null) }
    // Process-wide attached set, shared with the right-click menus so the
    // Settings panel agrees with what the indicator shows.
    val attached by ai.rever.bossterm.compose.mcp.McpTerminalRegistry
        .attachedTargets.collectAsState()

    SettingsSection(title = "Attach to AI CLI") {
        Text(
            text = "Register this BossTerm MCP endpoint with your favorite AI CLI. " +
                    "Each button removes any existing entry of the same name, then runs " +
                    "the CLI's `mcp add` command — so repeated clicks are safe. " +
                    "If the CLI isn't installed (or the command fails), the right " +
                    "config snippet is copied to your clipboard.",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            McpAttachTarget.entries.forEach { target ->
                val isAttached = target in attached
                Button(
                    onClick = {
                        inFlight = target
                        lastResults.remove(target)
                        scope.launch {
                            try {
                                val result = McpCliAttacher.attach(target, serverName, port)
                                if (result is McpAttachResult.Success) {
                                    ai.rever.bossterm.compose.mcp.McpTerminalRegistry
                                        .markAttached(target)
                                }
                                lastResults[target] = result
                            } finally {
                                inFlight = null
                            }
                        }
                    },
                    enabled = enabled && inFlight == null,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = AccentColor,
                        contentColor = TextOnAccent,
                        disabledBackgroundColor = BorderColor,
                        disabledContentColor = TextMuted
                    )
                ) {
                    val label = when {
                        inFlight == target -> "Attaching…"
                        isAttached -> "✓ ${target.displayName} (re-attach)"
                        else -> "Attach ${target.displayName}"
                    }
                    Text(text = label, fontSize = 12.sp)
                }
            }
        }

        // Per-target status. Two stacked lines per target when relevant:
        //   - Persistent attached state from the process-wide registry
        //     (survives the transient lastResults clear).
        //   - Last-attempt result with detail / clipboard fallback reason.
        McpAttachTarget.entries.forEach { target ->
            val isAttached = target in attached
            val result = lastResults[target]
            if (!isAttached && result == null) return@forEach

            if (isAttached) {
                Text(
                    text = "✓ ${target.displayName} is currently attached",
                    color = Success,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (result != null) {
                val (label, color) = when (result) {
                    is McpAttachResult.Success ->
                        "Last attempt: ✓ ${result.target.displayName} attached" +
                            (if (result.detail.isNotEmpty()) " — ${result.detail}" else "") to
                            Success
                    is McpAttachResult.CopiedToClipboard ->
                        "Last attempt: ${result.target.displayName}: ${result.reason}" to Warning
                }
                Text(
                    text = label,
                    color = color,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (!enabled) {
            Text(
                text = "Turn on the BossTerm MCP server above to enable these buttons.",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * Banner rendered inside [McpSettingsSection] when no [BossTermMcpConfig] is
 * provided by the host. Explains the embedding contract in a single short
 * code snippet so a developer running someone else's app knows where to look.
 */
@Composable
private fun ConfigurationBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Warning.copy(alpha = 0.2f)) // soft amber tint
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "BossTerm MCP is not wired up in this build.",
            color = TextPrimary,
            fontSize = 13.sp
        )
        Text(
            text = "The settings below persist, but no Ktor server is bound because the host " +
                    "application has not constructed a BossTermMcpManager. To enable it, call " +
                    "the snippet below in your fun main():",
            color = TextMuted,
            fontSize = 12.sp
        )
        Text(
            text = "val mcpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)\n" +
                    "val mcpConfig = BossTermMcpConfig(serverName = \"yourapp\")\n" +
                    "val mcpManager = BossTermMcpManager(\n" +
                    "    registry = McpTerminalRegistry,\n" +
                    "    settingsManager = SettingsManager.instance,\n" +
                    "    parentScope = mcpScope,\n" +
                    "    config = mcpConfig\n" +
                    ")\n" +
                    "mcpManager.start()\n" +
                    "application {\n" +
                    "    CompositionLocalProvider(LocalBossTermMcpConfig provides mcpConfig) {\n" +
                    "        // your TabbedTerminal tree\n" +
                    "    }\n" +
                    "}",
            color = TextPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
