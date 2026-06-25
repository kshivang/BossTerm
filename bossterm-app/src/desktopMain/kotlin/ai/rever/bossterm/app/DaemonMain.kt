package ai.rever.bossterm.app

import ai.rever.bossterm.compose.daemon.BossTermPaths
import ai.rever.bossterm.compose.daemon.DaemonAttachServer
import ai.rever.bossterm.compose.daemon.DaemonControlChannel
import ai.rever.bossterm.compose.daemon.DaemonControlHandler
import ai.rever.bossterm.compose.daemon.DaemonMcpServer
import ai.rever.bossterm.compose.daemon.SessionHost
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.update.Version
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

/**
 * Headless entry point for the long-lived BossTerm daemon.
 *
 * Owns the authoritative [SessionHost] (terminal sessions live here, not in any GUI window) and the
 * loopback [DaemonControlChannel] the GUI uses to discover, authenticate, and drive it. MCP and
 * session-sharing are layered on in later phases; this already lets the daemon spawn, hold sessions,
 * and survive the GUI closing.
 *
 * Launched by [ai.rever.bossterm.compose.daemon.DaemonLauncher] with `-Djava.awt.headless=true` and
 * the GUI's `-Dbossterm.settings.dir` / version / resources props propagated.
 */
private val log = LoggerFactory.getLogger("ai.rever.bossterm.app.DaemonMain")
private val startNanos = System.nanoTime()
private fun uptimeMs(): Long = (System.nanoTime() - startNanos) / 1_000_000

fun main(args: Array<String>) {
    // The daemon must never create a UI surface. pty4j, Ktor and the MCP SDK are all AWT-free.
    System.setProperty("java.awt.headless", "true")

    val version = Version.CURRENT.toString()
    log.info("BossTerm daemon starting (v{} proto{}) settingsDir={}",
        version, DaemonControlChannel.PROTOCOL_VERSION, BossTermPaths.dir().absolutePath)

    val settings = SettingsManager.instance.settings.value
    val sessionHost = SessionHost(settings)

    // Host MCP in the daemon when enabled, so agent access (read scrollback, run commands,
    // send input) keeps working even while the GUI is closed. Binds the same loopback endpoint
    // + writes ~/.bossterm/mcp.port exactly as the in-process server, so existing CLI/hook config
    // is unchanged.
    val mcpServer = if (settings.mcpEnabled) DaemonMcpServer(sessionHost) else null
    val mcpPort = mcpServer?.start(settings.mcpPort)
    if (mcpServer != null && mcpPort == null) log.warn("Daemon MCP server failed to bind; continuing without it")

    val stopLatch = CountDownLatch(1)

    // Holder so the control handler's STATUS can report the attach port (the attach server is
    // created after the control channel, since it shares the channel's secret).
    var attachServer: DaemonAttachServer? = null

    val handler = DaemonControlHandler(
        sessionHost = sessionHost,
        version = version,
        protocolVersion = DaemonControlChannel.PROTOCOL_VERSION,
        uptimeMs = ::uptimeMs,
        mcpPort = { mcpServer?.boundPort },
        attachPort = { attachServer?.boundPort?.takeIf { it > 0 } },
        onShutdown = { killSessions ->
            log.info("Daemon SHUTDOWN requested (killSessions={})", killSessions)
            if (killSessions) sessionHost.shutdownAll()
            // Trip the latch slightly after this returns so the control handler can flush its
            // "OK stopping" reply before main tears down the (daemon-thread) socket and exits.
            Thread { runCatching { Thread.sleep(200) }; stopLatch.countDown() }
                .apply { isDaemon = true }.start()
        },
    )

    val control = DaemonControlChannel(
        version = version,
        protocolVersion = DaemonControlChannel.PROTOCOL_VERSION,
        onRequest = handler::handle,
    )

    val port = try {
        control.start()
    } catch (e: Exception) {
        log.error("Daemon failed to bind control channel: {}", e.message)
        return
    }
    log.info("Daemon listening on 127.0.0.1:{}", port)

    // GUI-attach WebSocket: lets the GUI render/steer daemon sessions as thin-client tabs.
    // Shares the control secret for auth. Always started (the GUI attaches over it).
    attachServer = DaemonAttachServer(sessionHost, control.secretValue).also {
        if (it.start() < 0) log.warn("Daemon attach server failed to bind; GUI cannot render daemon sessions")
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Daemon shutdown hook: closing control channel + MCP + attach")
        control.stop()
        mcpServer?.stop()
        attachServer?.stop()
    })

    try {
        stopLatch.await()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    control.stop()
    mcpServer?.stop()
    attachServer?.stop()
    sessionHost.shutdownAll()
    log.info("BossTerm daemon stopped")
}
