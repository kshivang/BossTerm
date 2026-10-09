package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.SettingsManager
import java.security.SecureRandom

/**
 * Terminal service for a host-owned background process. The host owns process supervision,
 * branding and plugin loading; this adapter owns PTYs, attach transport and sharing.
 * One instance represents one host terminal surface, so unrelated panels never mirror each other.
 */
class HostedTerminalRuntime internal constructor(
    environment: () -> Map<String, String>,
    sharedServer: DaemonShareServer?,
    private val directory: HostedSessionDirectory?,
) : AutoCloseable {
    constructor(environment: () -> Map<String, String> = { emptyMap() }) : this(environment, null, null)

    data class Endpoint(val port: Int, val token: String, val protocol: Int)
    private val settings = DaemonColorSettings(SettingsManager.instance)
    val host = SessionHost(settings.current(), colorSettingsProvider = settings::current,
        settingsProvider = settings::current, environmentProvider = environment)
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val ownsShares = sharedServer == null
    private val shares = sharedServer ?: DaemonShareServer(host, settings = settings::current,
        mcpPort = { null }, readPersistedSettings = true)
    init { directory?.add(host) }
    private val attach = DaemonAttachServer(host, secret, shareServer = shares, activateGui = {})

    fun start(): Endpoint {
        val port = attach.start(0)
        check(port > 0) { "Could not start hosted terminal transport" }
        return Endpoint(port, secret, DaemonAttachProtocol.PROTOCOL_VERSION)
    }

    override fun close() {
        host.beginShutdown(killSessions = true)
        attach.stop()
        if (ownsShares) shares.stop()
        host.shutdownAll()
        host.close()
        directory?.remove(host)
    }
}
