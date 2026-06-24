package ai.rever.bossterm.app

import ai.rever.bossterm.compose.daemon.BossTermPaths
import ai.rever.bossterm.compose.daemon.DaemonControlChannel
import ai.rever.bossterm.compose.update.Version
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

/**
 * Headless entry point for the long-lived BossTerm daemon.
 *
 * Phase 0: a stub that proves the spawn → discover → handshake spine end to end. It opens the
 * loopback [DaemonControlChannel] (writing `~/.bossterm/daemon.port`), answers HELLO/PING/STATUS,
 * and idles until told to SHUTDOWN. No sessions, sharing, or MCP yet — those move in over the
 * following phases (MCP in Phase 1, sharing in Phase 2, PTYs in Phase 4).
 *
 * Launched by [ai.rever.bossterm.compose.daemon.DaemonLauncher] with `-Djava.awt.headless=true`
 * and the GUI's `-Dbossterm.settings.dir` / version / resources props propagated, so it resolves
 * the same files and reports the same version. Run directly for dev:
 *   ./gradlew :bossterm-app:run -DmainClass=ai.rever.bossterm.app.DaemonMain   (or via DaemonLauncher)
 */
private val log = LoggerFactory.getLogger("ai.rever.bossterm.app.DaemonMain")

fun main(args: Array<String>) {
    // The daemon must never create a UI surface. pty4j, Ktor and the MCP SDK are all AWT-free.
    System.setProperty("java.awt.headless", "true")

    val version = Version.CURRENT.toString()
    log.info("BossTerm daemon starting (v{} proto{}) settingsDir={}",
        version, DaemonControlChannel.PROTOCOL_VERSION, BossTermPaths.dir().absolutePath)

    // Block here until a SHUTDOWN verb (or a signal) releases it, keeping the daemon alive.
    val stopLatch = CountDownLatch(1)

    val control = DaemonControlChannel(
        version = version,
        protocolVersion = DaemonControlChannel.PROTOCOL_VERSION,
    ) { verb, _ ->
        when (verb) {
            "STATUS" -> "OK pid=${ProcessHandle.current().pid()} version=$version uptimeMs=${uptimeMs()}"
            "SHUTDOWN" -> {
                log.info("Daemon received SHUTDOWN; stopping")
                // Release the main thread AFTER replying (the latch trip below runs post-response
                // because dispatch returns the line first, then the caller may close).
                stopLatch.countDown()
                "OK stopping"
            }
            else -> "ERR unknown verb $verb"
        }
    }

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
    log.info("BossTerm daemon stopped")
}

private val startNanos = System.nanoTime()
private fun uptimeMs(): Long = (System.nanoTime() - startNanos) / 1_000_000
