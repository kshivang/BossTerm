package ai.rever.bossterm.compose.daemon

/** The share server can observe one session host or several isolated hosted terminal surfaces. */
interface ShareSessionDirectory {
    fun ownerOf(id: String): SessionHost?
    fun get(id: String): TerminalSessionCore?
    fun list(): List<SessionHost.SessionInfo>
    fun listGroups(): List<SessionHost.GroupInfo>
    fun closeSession(id: String)
    fun openSharedSession(): String
    fun addChangeListener(listener: () -> Unit)
    fun removeChangeListener(listener: () -> Unit)
}

internal class HostedSessionDirectory : ShareSessionDirectory {
    private val hosts = java.util.concurrent.CopyOnWriteArrayList<SessionHost>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val changed: () -> Unit = { listeners.forEach { it() } }

    fun add(host: SessionHost) { hosts.add(host); host.addChangeListener(changed); changed() }
    fun remove(host: SessionHost) { host.removeChangeListener(changed); hosts.remove(host); changed() }
    override fun ownerOf(id: String) = hosts.firstOrNull { it.get(id) != null }
    override fun get(id: String) = ownerOf(id)?.get(id)
    override fun list() = hosts.flatMap { it.list() }
    override fun listGroups() = hosts.flatMap { it.listGroups() }
    override fun closeSession(id: String) { hosts.firstOrNull { it.get(id) != null }?.closeSession(id) }
    override fun openSharedSession() = checkNotNull(hosts.firstOrNull()) { "No terminal surfaces" }.openSession()
    override fun addChangeListener(listener: () -> Unit) { listeners.add(listener) }
    override fun removeChangeListener(listener: () -> Unit) { listeners.remove(listener) }
}
