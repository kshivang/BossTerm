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
 * to their BOSS account, so the live-sessions page always lists this machine without anyone
 * right-clicking Share Tab.
 *
 * Rules, evaluated on every change to the account, the settings or the share set, and every
 * [pollMs] (tabs are not observable as a flow; a window may open after start-up):
 *
 *   - signed in AND [TerminalSettings.publishSessionsToAccount] AND
 *     [TerminalSettings.autoShareToAccount] AND at least one tab exists AND nothing is currently
 *     shared  ->  start an ALL-scope share from the first tab. If session sharing is switched off
 *     it is switched ON (the Share Tab menu does the same; the setting is persisted so the user
 *     sees it in Settings), and a remote mode of "off" becomes "cloudflare", the default and the
 *     only way the page can reach this machine from elsewhere.
 *   - any of the conditions stops holding  ->  the share THIS component started is stopped. A share
 *     the user started by hand is never touched, which is why the started tab id is remembered.
 *
 * A share that ends on its own (its initiating tab closed, the window closed) is simply started
 * again on the next tick while the conditions hold. The publisher then re-registers it under a
 * new share_id, which is correct: it is a new share.
 */
class AccountAutoShare(
    private val accountState: StateFlow<AccountState>,
    private val settings: StateFlow<TerminalSettings>,
    private val sharedTabIds: StateFlow<Set<String>>,
    private val firstTabId: () -> String?,
    private val share: suspend (tabId: String) -> Boolean,
    private val unshare: (tabId: String) -> Unit,
    private val updateSettings: ((TerminalSettings) -> TerminalSettings) -> Unit,
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
        if (!wanted) {
            if (mine != null) {
                log.info("Auto-share: conditions no longer hold; stopping the share this started")
                unshare(mine)
                autoSharedTabId = null
            }
            return@withLock
        }
        // Our share is still running: nothing to do. (It may have ended by itself - the tab or
        // window closed - in which case it is no longer in sharedTabIds and we fall through.)
        if (mine != null && mine in sharedTabIds.value) return@withLock
        autoSharedTabId = null
        // Something is shared already (by the user, or a previous instance): do not stack a second
        // ALL share on top of it. The publisher lists whatever is shared.
        if (sharedTabIds.value.isNotEmpty()) return@withLock
        val tabId = firstTabId() ?: return@withLock // no window yet; the poll will retry
        if (!s.sessionSharingEnabled || s.shareTailscaleMode == "off") {
            log.info(
                "Auto-share: enabling session sharing{} for the signed-in account",
                if (s.shareTailscaleMode == "off") " with the Cloudflare tunnel" else "",
            )
            updateSettings { cur ->
                cur.copy(
                    sessionSharingEnabled = true,
                    shareTailscaleMode = if (cur.shareTailscaleMode == "off") "cloudflare" else cur.shareTailscaleMode,
                )
            }
        }
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
                sharedTabIds = SessionShareManager.sharedTabIds,
                firstTabId = { McpTerminalRegistry.primaryState()?.tabs?.firstOrNull()?.id },
                share = { tabId -> SessionShareManager.share(tabId, ShareScope.ALL) != null },
                unshare = SessionShareManager::unshare,
                updateSettings = { f -> SettingsManager.instance.updateSetting { f(this) } },
            )
        }
    }
}
