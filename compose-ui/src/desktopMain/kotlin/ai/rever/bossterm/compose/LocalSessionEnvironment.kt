package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.osc.OpenTargetToken
import ai.rever.bossterm.compose.settings.TerminalSettings

/** GUI host context injected into the shared engine; explicit pre-connect values win. */
internal fun localSessionEnvironment(settings: TerminalSettings, overrides: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
    put("BOSSTERM_OPEN_TOKEN", OpenTargetToken.value)
    put("BOSS_MCP_SERVER", McpTerminalRegistry.mcpServerName)
    put(McpTerminalRegistry.mcpPortEnvVar, (McpTerminalRegistry.runningPort.value ?: settings.mcpPort).toString())
    putAll(overrides)
}
