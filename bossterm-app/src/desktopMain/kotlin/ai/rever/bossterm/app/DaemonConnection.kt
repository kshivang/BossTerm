package ai.rever.bossterm.app

import ai.rever.bossterm.compose.daemon.DaemonBridgeCoordinator
import ai.rever.bossterm.compose.daemon.DaemonClient

/** A control connection alone is insufficient: local fallback also needs its own MCP owner. */
internal fun configureDaemonConnection(client: DaemonClient, startLocalMcp: () -> Unit): Boolean {
    if (DaemonBridgeCoordinator.onConnected(client)) return true
    DaemonBridgeCoordinator.markAttachUnavailable()
    startLocalMcp()
    return false
}
