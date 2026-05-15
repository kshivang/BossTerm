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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig
import ai.rever.bossterm.compose.mcp.McpAttachResult
import ai.rever.bossterm.compose.mcp.McpAttachTarget
import ai.rever.bossterm.compose.mcp.McpCliAttacher
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.components.SettingsNumberInput
import ai.rever.bossterm.compose.settings.components.SettingsSection
import ai.rever.bossterm.compose.settings.components.SettingsToggle

/**
 * MCP server settings: toggle the in-process Model Context Protocol server
 * and pick a localhost port. Endpoint is always `http://127.0.0.1:<port>/mcp`
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

        SettingsSection(title = "MCP Server") {
            SettingsToggle(
                label = "Enable MCP Server",
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
                description = "Display a small green dot in the tab bar while the MCP server " +
                        "is running. Click the dot to jump to this settings page.",
                enabled = settings.mcpEnabled
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        AttachToCliSection(
            port = settings.mcpPort,
            enabled = settings.mcpEnabled
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Inline endpoint + security note. Includes the embedder's configured
        // server name when available so users know how the server identifies
        // itself to clients.
        val serverIdLine = cfg?.let { "Server identifier: ${it.serverName} v${it.serverVersion}\n" } ?: ""
        Text(
            text = serverIdLine +
                    "Endpoint when enabled: http://127.0.0.1:${settings.mcpPort}/mcp\n" +
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
    enabled: Boolean
) {
    val scope = rememberCoroutineScope()
    // Last-attempt status per target so each button can show its own
    // success/fallback line without clobbering the others.
    var lastResult by remember { mutableStateOf<McpAttachResult?>(null) }
    var inFlight by remember { mutableStateOf<McpAttachTarget?>(null) }

    SettingsSection(title = "Attach to AI CLI") {
        Text(
            text = "Register this BossTerm MCP endpoint with your favorite AI CLI. " +
                    "Each button shells out to the CLI's `mcp add` command. " +
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
                Button(
                    onClick = {
                        inFlight = target
                        lastResult = null
                        scope.launch {
                            try {
                                lastResult = McpCliAttacher.attach(target, port)
                            } finally {
                                inFlight = null
                            }
                        }
                    },
                    enabled = enabled && inFlight == null,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF2D6CDF),
                        contentColor = Color.White,
                        disabledBackgroundColor = Color(0xFF3A3A3A),
                        disabledContentColor = Color(0xFF888888)
                    )
                ) {
                    Text(
                        text = if (inFlight == target) "Attaching…" else "Attach ${target.displayName}",
                        fontSize = 12.sp
                    )
                }
            }
        }

        lastResult?.let { result ->
            val (label, color) = when (result) {
                is McpAttachResult.Success ->
                    "✓ ${result.target.displayName} attached" +
                        (if (result.detail.isNotEmpty()) " — ${result.detail}" else "") to
                        Color(0xFF4CAF50)
                is McpAttachResult.CopiedToClipboard ->
                    "${result.target.displayName}: ${result.reason}" to Color(0xFFFFC107)
            }
            Text(
                text = label,
                color = color,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (!enabled) {
            Text(
                text = "Turn on the MCP server above to enable these buttons.",
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
            .background(Color(0x33_FF_C1_07)) // soft amber tint
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "MCP is not wired up in this build.",
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
