package ai.rever.bossterm.compose.mcp

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Hook signature for embedders that want to register additional MCP tools
 * on top of the built-in seven. Called once per [Server] after the built-in
 * tools are registered. The embedder owns the names of any tools added here;
 * [BossTermMcpConfig.toolNamePrefix] is NOT applied to them.
 */
typealias ServerToolRegistrar = (Server) -> Unit

/**
 * Configuration object that other applications embedding BossTerm pass when
 * constructing [BossTermMcpManager]. Lets the embedder brand the MCP server,
 * namespace tools, lock out write tools, set first-launch defaults, add
 * custom tools, and control whether the MCP entry appears in the in-app
 * Settings UI.
 *
 * All fields have defaults that match what plain `bossterm-app` ships, so
 * callers that don't care about embedding can pass `BossTermMcpConfig()` (or
 * omit the constructor argument).
 *
 * Example embedder wiring (in your `fun main()`):
 *
 * ```kotlin
 * val mcpConfig = BossTermMcpConfig(
 *     serverName = "bossconsole",
 *     serverVersion = "0.4.2",
 *     toolNamePrefix = "bossconsole_",
 *     allowWriteTools = false,                   // read-only MCP
 *     defaultPort = 7878,
 *     defaultEnabled = true,
 *     additionalTools = { server ->
 *         server.addTool(name = "show_panel", description = "...", inputSchema = ...) { ... }
 *     }
 * )
 * val mcpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
 * val mcpManager = BossTermMcpManager(
 *     registry = McpTerminalRegistry,
 *     settingsManager = SettingsManager.instance,
 *     parentScope = mcpScope,
 *     config = mcpConfig
 * )
 * mcpManager.start()
 *
 * application {
 *     CompositionLocalProvider(LocalBossTermMcpConfig provides mcpConfig) {
 *         // your TabbedTerminal / SettingsWindow tree
 *     }
 * }
 * ```
 */
data class BossTermMcpConfig(
    /** Reported as `Implementation.name`. MCP clients see this string. */
    val serverName: String = "bossterm",
    /** Reported as `Implementation.version`. */
    val serverVersion: String = "1.0",
    /**
     * Optional prefix applied to every built-in tool name. For example,
     * `"bossconsole_"` yields tools named `bossconsole_list_tabs`,
     * `bossconsole_read_scrollback`, etc. Empty string disables prefixing.
     */
    val toolNamePrefix: String = "",
    /**
     * When `false`, the write tools (`send_input`, `send_signal`) are not
     * registered. Use this for embedders that want LLMs to observe but not
     * drive their shells.
     */
    val allowWriteTools: Boolean = true,
    /**
     * First-launch port written to settings when the embedder has never
     * been initialized on this machine (controlled via the
     * `mcpConfigured` settings flag). After that the user's setting wins.
     */
    val defaultPort: Int = 7676,
    /**
     * First-launch enabled state written to settings when the embedder has
     * never been initialized on this machine. After that the user's setting
     * wins. Default `false` for opt-in safety in plain bossterm-app.
     */
    val defaultEnabled: Boolean = false,
    /**
     * When `false`, the MCP entry is hidden from the Settings side rail and
     * any host that respects this flag (e.g. the bossterm-app Tools menu).
     * The status indicator in the tab bar is still driven by user settings
     * — toggle that off via [mcpShowStatusIndicator] in TerminalSettings.
     */
    val showInSettingsUi: Boolean = true,
    /**
     * Embedder hook called after the built-in tools are registered. Lets the
     * embedder add app-specific tools (`server.addTool(...)`). Defaults to a
     * no-op.
     */
    val additionalTools: ServerToolRegistrar = {},
    /**
     * Per-tool description overrides for built-in BossTerm MCP tools. Keys are
     * unprefixed built-in names (e.g. `"list_tabs"`, `"send_input"`); values
     * replace the default description string advertised to MCP clients.
     *
     * Useful when the embedder wants to clarify behavior in their host app —
     * e.g. "lists tabs in `MyIDE`'s integrated terminal" rather than the
     * generic default. Unknown keys are silently ignored; tool names that
     * aren't overridden keep their built-in description. Empty map means
     * "no overrides".
     */
    val customToolDescriptions: Map<String, String> = emptyMap()
)

/**
 * Composition local exposing the embedder's [BossTermMcpConfig] to the
 * settings UI so it can adapt its labels and visibility without each
 * composable threading the config through its parameters.
 *
 * `null` means no embedder provided one. The MCP settings section renders a
 * "how to configure" note in that case so users understand why toggling has
 * no effect.
 */
val LocalBossTermMcpConfig: ProvidableCompositionLocal<BossTermMcpConfig?> =
    compositionLocalOf { null }
