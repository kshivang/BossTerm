package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.settings.SettingsManager

/** Isolated terminal surfaces share one daemon-wide sharing server, including Share All Windows. */
class HostedTerminalPool(private val environment: () -> Map<String, String> = { emptyMap() }) : AutoCloseable {
    private val directory = HostedSessionDirectory()
    private val settings = DaemonColorSettings(SettingsManager.instance)
    private val shares = DaemonShareServer(directory, settings::current, mcpPort = { null }, readPersistedSettings = true)
    private val runtimes = linkedMapOf<String, HostedTerminalRuntime>()

    @Synchronized
    fun attach(identity: String): HostedTerminalRuntime.Endpoint {
        require(identity.length in 1..512)
        return runtimes.getOrPut(identity) { HostedTerminalRuntime(environment, shares, directory) }.start()
    }

    @Synchronized
    fun closeSurface(identity: String) {
        val runtime = runtimes[identity] ?: return
        runtime.close()
        runtimes.remove(identity)
    }

    @Synchronized
    override fun close() {
        var failure: Throwable? = null
        runtimes.values.toList().forEach {
            try { it.close() } catch (t: Throwable) { if (failure == null) failure = t else failure!!.addSuppressed(t) }
        }
        try { shares.stop() } catch (t: Throwable) { if (failure == null) failure = t else failure!!.addSuppressed(t) }
        failure?.let { throw it }
        runtimes.clear()
    }
}
