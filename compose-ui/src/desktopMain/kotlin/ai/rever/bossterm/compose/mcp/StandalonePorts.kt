package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.settings.TerminalSettings

/** Standalone app policy; embedders keep their own settings and ports. */
object StandalonePorts {
    const val MCP = 17760
    const val SHARING = 17770
    const val ATTACH = 17780

    /** Retire the old overlapping fallback ranges, preserving unrelated custom ports. */
    fun migrate(settings: TerminalSettings): TerminalSettings = settings.copy(
        mcpPort = if (settings.mcpPort in 7676..7685) MCP else settings.mcpPort,
        sessionSharingPort = if (settings.sessionSharingPort in 7677..7686) SHARING else settings.sessionSharingPort,
    )
}
