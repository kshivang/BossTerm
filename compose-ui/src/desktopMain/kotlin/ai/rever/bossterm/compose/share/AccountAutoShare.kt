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
import org.slf4j.LoggerFactory

/**
 * Keeps one whole-app share ([ShareScope.ALL]) running while the user is signed in and publishing
 * to their BOSS account, so the live-sessions page always lists this machine.
 *
 * It is a SEPARATE kind of share from the ones the user starts: flagged [MirrorShare.accountManaged],
 * hidden from the tab Share/Stop button, untouched by "Enable Session Sharing" being switched off
 * (the manager's `accountSharingWanted` is a second enable switch), and reached over Cloudflare
 * even when the remote mode is "off". Only the account toggles ([TerminalSettings.autoShareToAccount],
 * [TerminalSettings.publishSessionsToAccount]) and signing out stop it; the Share dialog carries
 * them in its own collapsed section.
 *
 * Evaluated on every change to the account, the settings or the share set, and every [pollMs]
 * (tabs are not observable as a flow; a window may open after start-up). A share that ends on its
 * own (window closed) is started again on the next tick while the conditions hold.
 */
class AccountAutoShare(
    private val accountState: StateFlow<AccountState>,
    private val settings: StateFlow<TerminalSettings>,
    private val sharedTabIds: StateFlow<Set<String>>,
    private val firstTabId: () -> String?,
    private val share: suspend (tabId: String) -> Boolean,
    private val unshare: (tabId: String) -> Unit,
    /** The manager's second enable switch; true while this component wants its share to exist. */
    private val setWanted: (Boolean) -> Unit,
    private val pollMs: Long = DEFAULT_POLL_MS,
) {
    private val log = LoggerFactory.getLogger(AccountAutoShare::class.java)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val mutex = Mutex()

    /** Tab id of the share this component started, or null. Never a user-started share. */
    @Volatile
    var autoSharedTabId: String? = null
        private set

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        job = scope.launch {
            val trigger = combine(accountState, settings, sharedTabIds) { acct, s, shared ->
                Triple(acct is AccountState.SignedIn, s.publishSessionsToAccount && s.autoShareToAccount, shared)
            }.distinctUntilChanged()
            launch { trigger.collect { reconcile() } }
            while (isActive) {
                delay(pollMs)
                reconcile()
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        scope.cancel()
    }

    private suspend fun reconcile() = mutex.withLock {
        val s = settings.value
        val wanted = accountState.value is AccountState.SignedIn && s.publishSessionsToAccount && s.autoShareToAccount
        val mine = autoSharedTabId
        setWanted(wanted)
        if (!wanted) {
            if (mine != null) {
                log.info("Auto-share: conditions no longer hold; stopping the account share")
                unshare(mine)
                autoSharedTabId = null
            }
            return@withLock
        }
        // Our share is still running: nothing to do. (It may have ended by itself - the tab or
        // window closed - in which case it is no longer in sharedTabIds and we fall through.)
        if (mine != null && mine in sharedTabIds.value) return@withLock
        autoSharedTabId = null
        val tabId = firstTabId() ?: return@withLock // no window yet; the poll will retry
        if (share(tabId)) {
            autoSharedTabId = tabId
            log.info("Auto-share: started an all-windows share for the signed-in account")
        } else {
            log.warn("Auto-share: could not start a share (server not bound?); will retry")
        }
    }

    companion object {
        const val DEFAULT_POLL_MS = 5_000L

        val Default: AccountAutoShare by lazy {
            AccountAutoShare(
                accountState = BossAccountManager.state,
                settings = SettingsManager.instance.settings,
                sharedTabIds = SessionShareManager.allSharedTabIds,
                firstTabId = { McpTerminalRegistry.primaryState()?.tabs?.firstOrNull()?.id },
                share = { tabId -> SessionShareManager.share(tabId, ShareScope.ALL, accountManaged = true) != null },
                unshare = { tabId -> SessionShareManager.unshare(tabId, includeAccountManaged = true) },
                setWanted = { SessionShareManager.accountSharingWanted.value = it },
            )
        }
    }
}
