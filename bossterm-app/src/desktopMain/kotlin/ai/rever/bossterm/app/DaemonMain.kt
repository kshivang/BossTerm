package ai.rever.bossterm.app

import ai.rever.bossterm.compose.daemon.BossTermPaths
import ai.rever.bossterm.compose.daemon.DaemonControlChannel
import ai.rever.bossterm.compose.daemon.DaemonControlHandler
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

    val stopLatch = CountDownLatch(1)

    val handler = DaemonControlHandler(
        sessionHost = sessionHost,
        version = version,
        protocolVersion = DaemonControlChannel.PROTOCOL_VERSION,
        uptimeMs = ::uptimeMs,
        mcpPort = { null }, // wired in Phase 1 (MCP) — none yet
        onShutdown = { killSessions ->
            log.info("Daemon SHUTDOWN requested (killSessions={})", killSessions)
            if (killSessions) sessionHost.shutdownAll()
            stopLatch.countDown()
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

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Daemon shutdown hook: closing control channel")
        control.stop()
    })

    try {
        stopLatch.await()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    control.stop()
    sessionHost.shutdownAll()
    log.info("BossTerm daemon stopped")
}
