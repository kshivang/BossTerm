package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager
import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.mcp.McpTerminalRegistry
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Attaches the account's OTHER live sessions to this BossTerm as remote tabs, automatically.
 *
 * Two devices signed into one BOSS account each auto-share (AccountAutoShare) and each publish
 * to the registry (AccountSessionPublisher). [AccountSessionDirectory] already lists the other
 * devices; this component does what a user would otherwise do by hand in the Remote Sessions
 * window: connect each of them through the native client, using the account link, which the
 * host auto-admits (no approval prompt on the other machine).
 *
 * Rules:
 *   - Only while signed in and [TerminalSettings.autoConnectAccountSessions] is on.
 *   - Only the PRIMARY window connects (McpTerminalRegistry.primaryState()), so a session shows
 *     up once, not once per window.
 *   - A session already connected in that window (same link token) is left alone, whether the
 *     user or this component connected it.
 *   - Only the other device's ACCOUNT share (scope ALL) is attached. Its hand-made TAB/WINDOW
 *     shares are also in the registry, but they show the same terminals again; those stay a
 *     choice in the Remote Sessions window.
 *   - A session the user DISCONNECTS by hand is not re-attached under the same token, even if
 *     its row blips out of the directory (heartbeat lag, token refresh) and returns; it comes
 *     back only as a new share (new token), or after sign-out/toggle-off. Otherwise
 *     "Disconnect" would be undone within one tick.
 *   - When the primary window changes (the old one closed), what it had attached is forgotten
 *     without counting as a hand disconnect, so the new primary attaches afresh.
 *   - Signing out or switching the setting off disconnects the sessions this component
 *     attached and nothing else.
 *
 * The manager's [connect] refuses this instance's own links, so a device never mirrors itself
 * even though the directory already filters those out.
 */
class AccountAutoRemote(
    private val accountState: StateFlow<AccountState>,
    private val settings: StateFlow<TerminalSettings>,
    private val directory: StateFlow<List<AccountSession>>,
    /** Link tokens currently connected in the primary window, or null when there is no window yet. */
    private val connectedTokens: suspend () -> Set<String>?,
    /** Identity of the primary window the tokens belong to; a change resets [attached]. */
    private val windowKey: () -> Any? = { null },
    /** Connect a link in the primary window; false when refused (own link, no window). */
    private val connect: suspend (url: String, deviceName: String) -> Boolean,
    /** Disconnect the remote session with this link token in the primary window. */
    private val disconnect: suspend (token: String) -> Unit,
    private val deviceName: () -> String,
    private val pollMs: Long = DEFAULT_POLL_MS,
) {
    private val log = LoggerFactory.getLogger(AccountAutoRemote::class.java)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val mutex = Mutex()

    /** Tokens this component connected (still believed attached). */
    private val attached = LinkedHashSet<String>()
    /** Tokens the user disconnected by hand; skipped until sign-out/toggle-off. */
    private val dismissed = LinkedHashSet<String>()
    private var lastWindowKey: Any? = null
    private var lastUserId: String? = null

    /** Test/inspection view of what this component attached. */
    val attachedTokens: Set<String> get() = synchronized(attached) { attached.toSet() }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        job = scope.launch {
            val trigger = combine(accountState, settings, directory) { acct, s, dir ->
                Triple((acct as? AccountState.SignedIn)?.userId, s.autoConnectAccountSessions, dir.map { it.controlUrl })
            }.distinctUntilChanged()
            launch { trigger.collect { reconcile() } }
            while (isActive) {
                delay(pollMs)
                reconcile()
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel(); job = null
        scope.cancel()
    }

    /** Await account-owned connection cleanup before accepting a new host identity. */
    suspend fun resetAccount() = mutex.withLock {
        for (token in attached.toList()) disconnect(token)
        synchronized(attached) { attached.clear() }
        dismissed.clear()
        lastUserId = null
    }

    private suspend fun reconcile() = mutex.withLock {
        val userId = (accountState.value as? AccountState.SignedIn)?.userId
        if (lastUserId != userId) {
            for (t in attached.toList()) disconnect(t)
            synchronized(attached) { attached.clear() }
            dismissed.clear()
            lastUserId = userId
        }
        val wanted = userId != null && settings.value.autoConnectAccountSessions
        val connected = connectedTokens()
        if (!wanted) {
            if (attached.isNotEmpty()) {
                log.info("Auto-remote: conditions no longer hold; detaching {} account session(s)", attached.size)
                for (t in attached.toList()) runCatching { disconnect(t) }
                synchronized(attached) { attached.clear() }
            }
            dismissed.clear()
            return@withLock
        }
        if (connected == null) return@withLock // no window yet; the poll will retry
        val key = windowKey()
        if (key != lastWindowKey) {
            // A different primary window: its sessions were never ours to judge.
            lastWindowKey = key
            synchronized(attached) { attached.clear() }
        }

        val listed = directory.value
            .filter { it.scope == "ALL" }
            .mapNotNull { s -> AccountSessionDirectory.tokenOf(s.controlUrl)?.let { it to s } }.toMap()

        // Sessions we attached that are no longer connected: either the host ended them (row
        // gone too, forget them) or the user disconnected by hand (row still listed: remember
        // not to re-attach that token).
        for (t in attached.toList()) {
            if (t in connected) continue
            synchronized(attached) { attached.remove(t) }
            if (t in listed) dismissed.add(t)
        }

        for ((token, s) in listed) {
            if (token in connected || token in dismissed) continue
            val ok = runCatching { connect(s.controlUrl, deviceName()) }.getOrDefault(false)
            if (ok) {
                synchronized(attached) { attached.add(token) }
                log.info("Auto-remote: attached {} ({})", s.deviceName, s.scope)
            }
        }
    }

    companion object {
        const val DEFAULT_POLL_MS = 5_000L

        private fun primaryManager() = McpTerminalRegistry.primaryState()?.remoteSessions

        val Default: AccountAutoRemote by lazy {
            AccountAutoRemote(
                accountState = AccountSessionSource.state,
                settings = SettingsManager.instance.settings,
                directory = AccountSessionDirectory.Default.sessions,
                connectedTokens = {
                    withContext(Dispatchers.Main) {
                        primaryManager()?.sessions?.mapNotNull { AccountSessionDirectory.tokenOf(it.link) }?.toSet()
                    }
                },
                windowKey = { McpTerminalRegistry.primaryState() },
                // RemoteSessionManager.connect is UI-thread code (Compose state lists).
                connect = { url, name -> withContext(Dispatchers.Main) { primaryManager()?.connect(url, name) != null } },
                disconnect = { token ->
                    withContext(Dispatchers.Main) {
                        primaryManager()?.let { m ->
                            m.sessions.firstOrNull { AccountSessionDirectory.tokenOf(it.link) == token }?.let { m.disconnect(it) }
                        }
                    }
                },
                deviceName = {
                    (System.getProperty("user.name")?.takeIf { it.isNotBlank() }?.let { "$it (BossTerm)" }) ?: "BossTerm"
                },
            )
        }
    }
}
