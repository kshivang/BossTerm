package ai.rever.bossterm.app

import ai.rever.bossterm.compose.daemon.BossTermPaths
import ai.rever.bossterm.compose.daemon.DaemonAttachServer
import ai.rever.bossterm.compose.daemon.DaemonControlChannel
import ai.rever.bossterm.compose.daemon.DaemonControlHandler
import ai.rever.bossterm.compose.daemon.DaemonMcpServer
import ai.rever.bossterm.compose.daemon.DaemonShareServer
import ai.rever.bossterm.compose.daemon.DaemonTray
import ai.rever.bossterm.compose.daemon.SessionHost
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.update.Version
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless entry point for the long-lived BossTerm daemon.
 *
 * Owns the authoritative [SessionHost] (terminal sessions live here, not in any GUI window), the
 * loopback [DaemonControlChannel] the GUI uses to discover/authenticate/drive it, the [DaemonMcpServer],
 * the GUI-attach [DaemonAttachServer], and a menu-bar [DaemonTray].
 *
 * Runs as a macOS UIElement agent (menu-bar item, no Dock icon) — see [ai.rever.bossterm.compose.daemon.DaemonLauncher].
 */
private val log = LoggerFactory.getLogger("ai.rever.bossterm.app.DaemonMain")
private val startNanos = System.nanoTime()
private fun uptimeMs(): Long = (System.nanoTime() - startNanos) / 1_000_000

fun main(args: Array<String>) {
    // NOT headless: the daemon shows a menu-bar/tray icon so a background process isn't invisible.
    // On macOS DaemonLauncher passes -Dapple.awt.UIElement=true so it's an agent (no Dock icon).
    val version = Version.CURRENT.toString()
    log.info("BossTerm daemon starting (v{} proto{}) settingsDir={}",
        version, DaemonControlChannel.PROTOCOL_VERSION, BossTermPaths.dir().absolutePath)

    // Single-instance self-guard: if a compatible daemon is already live (it answers PING on the
    // recorded port), don't start a second one that would overwrite daemon.port and orphan the
    // first. Backstops the GUI's FileChannel.tryLock spawn guard under a cold-start race.
    if (liveCompatibleDaemonPresent()) {
        log.info("A compatible BossTerm daemon is already running; exiting (single-instance)")
        return
    }

    val settings = SettingsManager.instance.settings.value
    val sessionHost = SessionHost(settings)

    // Host MCP in the daemon when enabled, so agent access keeps working while the GUI is closed.
    // Same loopback endpoint + mcp.port as the in-process server. Runtime-controllable (held in a
    // ref + guarded by [mcpLock]) so the GUI's MCP settings toggle works LIVE in daemon mode: the GUI
    // sends SetMcpEnabled over the attach socket, which calls [setDaemonMcpEnabled] below.
    val mcpServerRef = AtomicReference<DaemonMcpServer?>(null)
    val mcpLock = Any()
    fun currentMcpPort(): Int? = mcpServerRef.get()?.boundPort
    fun setDaemonMcpEnabled(on: Boolean): Int? = synchronized(mcpLock) {
        val running = mcpServerRef.get()
        if (on) {
            running?.boundPort ?: run {
                val srv = DaemonMcpServer(sessionHost)
                val p = srv.start(SettingsManager.instance.settings.value.mcpPort)
                if (p == null) {
                    log.warn("Daemon MCP server failed to bind; continuing without it")
                    null
                } else {
                    mcpServerRef.set(srv)
                    SettingsManager.instance.updateSetting { copy(mcpEnabled = true) }
                    log.info("Daemon MCP server enabled on 127.0.0.1:{}", p)
                    p
                }
            }
        } else {
            running?.let { runCatching { it.stop() }; mcpServerRef.set(null); log.info("Daemon MCP server disabled") }
            SettingsManager.instance.updateSetting { copy(mcpEnabled = false) }
            null
        }
    }
    if (settings.mcpEnabled) setDaemonMcpEnabled(true)

    // Host session sharing in the daemon, so a share link survives the GUI closing. Always
    // constructed but LAZY — no port is bound until the first share — so the GUI can start a share
    // without a daemon restart even if sharing was off at boot (matches the non-daemon "first share
    // enables it" UX). It reads settings live and the GUI gates the Share affordance on the setting.
    val shareServer = DaemonShareServer(
        host = sessionHost,
        settings = { SettingsManager.instance.settings.value },
        mcpPort = { currentMcpPort() },
    )

    val stopLatch = CountDownLatch(1)
    // Published via AtomicReference: read from the control-request, tray, and shutdown-hook threads.
    val attachServerRef = AtomicReference<DaemonAttachServer?>(null)

    val handler = DaemonControlHandler(
        sessionHost = sessionHost,
        version = version,
        protocolVersion = DaemonControlChannel.PROTOCOL_VERSION,
        uptimeMs = ::uptimeMs,
        mcpPort = { currentMcpPort() },
        attachPort = { attachServerRef.get()?.boundPort?.takeIf { it > 0 } },
        onShutdown = { killSessions ->
            log.info("Daemon SHUTDOWN requested (killSessions={})", killSessions)
            // Trip the latch slightly after this returns so the control handler can flush its
            // "OK stopping" reply before main tears down the socket. Cleanup (incl. sessions) runs
            // in the shared shutdown() below.
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

    // GUI-attach WebSocket (shares the control secret for auth). Always started; also carries the
    // Phase 2 share-management lane (delegates to shareServer; no-op when sharing is disabled).
    attachServerRef.set(DaemonAttachServer(
        sessionHost, control.secretValue, shareServer,
        setMcpEnabled = { on -> setDaemonMcpEnabled(on) },
    ).also {
        if (it.start() < 0) log.warn("Daemon attach server failed to bind; GUI cannot render daemon sessions")
    })

    // Single, idempotent teardown shared by the SHUTDOWN path and the JVM shutdown hook.
    val stopped = AtomicBoolean(false)
    fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        log.info("BossTerm daemon stopping")
        runCatching { DaemonTray.remove() }
        runCatching { control.stop() }
        runCatching { mcpServerRef.get()?.stop() }
        runCatching { shareServer?.stop() } // stop share server + tunnels before sessions die
        runCatching { attachServerRef.get()?.stop() }
        runCatching { sessionHost.shutdownAll() } // joins PTY-kill threads so shells aren't orphaned
    }

    // Menu-bar presence so the background daemon is visible + quittable without the GUI.
    DaemonTray.install(
        version = version,
        sessionCount = { sessionHost.count() },
        // Open BossTerm: if a GUI is attached, ask it to focus; else launch one.
        onOpenApp = {
            if ((attachServerRef.get()?.focusClients() ?: 0) == 0) {
                ai.rever.bossterm.compose.daemon.DaemonLauncher.openGui()
            }
        },
        onQuit = { log.info("Quit requested from menu bar"); stopLatch.countDown() },
    )

    Runtime.getRuntime().addShutdownHook(Thread { shutdown() })

    try {
        stopLatch.await()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    shutdown()
    log.info("BossTerm daemon stopped")
}

/** True if a protocol-compatible daemon is already listening (answers PING on the recorded port). */
private fun liveCompatibleDaemonPresent(): Boolean {
    val ep = DaemonControlChannel.readEndpoint() ?: return false
    if (ep.protocolVersion != DaemonControlChannel.PROTOCOL_VERSION) return false
    return runCatching {
        Socket(InetAddress.getLoopbackAddress(), ep.port).use { s ->
            s.soTimeout = 1500
            OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8).apply { write("${ep.secret} PING\n"); flush() }
            BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine()?.trim() == "PONG"
        }
    }.getOrDefault(false)
}
