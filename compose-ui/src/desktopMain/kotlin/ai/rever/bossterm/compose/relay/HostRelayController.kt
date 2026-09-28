package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.share.AccountSessionSource
import ai.rever.bossterm.compose.share.SessionShareManager
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal data class RelayHostConfiguration(val userId: String, val relay: RelayConfig)

/** collectLatest waits for the previous session's cancellation/cleanup before replacing it. */
internal suspend fun observeRelayHostConfiguration(
    account: Flow<AccountState>,
    shares: Flow<Set<String>>,
    settings: Flow<TerminalSettings>,
    overrides: () -> RelayConfig.Companion.Overrides = { RelayConfig.overrides() },
    run: suspend (RelayHostConfiguration?) -> Unit,
) {
    combine(account, shares, settings) { identity, tabs, preferences ->
        val owner = (identity as? AccountState.SignedIn)?.userId?.takeIf { tabs.isNotEmpty() }
        val config = RelayConfig.current(preferences, overrides())
        if (owner != null && config != null) RelayHostConfiguration(owner, config) else null
    }.distinctUntilChanged().collectLatest(run)
}

/** One relay connection for all shares; account/settings changes and disposal revoke it. */
internal object HostRelayController {
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var active: RelayHostSession? = null
    private data class Publication(val owner: String, val config: RelayConfig, val fragment: String)
    @Volatile private var offer: Publication? = null
    private val _linkRevision = MutableStateFlow(0L)
    val linkRevision: StateFlow<Long> = _linkRevision.asStateFlow()

    private fun publish(value: Publication?) {
        if (offer != value) {
            offer = value
            _linkRevision.value++
        }
    }
    @Volatile private var configuredSettings: StateFlow<TerminalSettings>? = null

    @Synchronized fun start(settings: StateFlow<TerminalSettings> = SettingsManager.instance.settings) {
        if (scope != null) return
        configuredSettings = settings
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = ownerScope
        ownerScope.launch {
            observeRelayHostConfiguration(AccountSessionSource.state, SessionShareManager.allSharedTabIds, settings) { desired ->
                synchronized(this@HostRelayController) {
                    if (scope !== ownerScope) return@observeRelayHostConfiguration
                    publish(null)
                }
                if (desired == null) return@observeRelayHostConfiguration
                val userId = desired.userId
                val config = desired.relay
                val room = UUID.randomUUID().toString()
                while (isActive) {
                    lateinit var session: RelayHostSession
                    session = RelayHostSession(config.endpoint, room, userId) { ready ->
                        synchronized(this@HostRelayController) {
                            // A cancelled generation may finish after stop/start created a new session.
                            if (scope === ownerScope && active === session) {
                                publish(if (ready && currentOwner() == userId)
                                    Publication(userId, config, config.fragment(room)) else null)
                            }
                        }
                    }
                    val accepted = synchronized(this@HostRelayController) {
                        if (scope !== ownerScope || currentOwner() != userId) false
                        else { active = session; true }
                    }
                    if (!accepted) { session.close(); return@observeRelayHostConfiguration }
                    try { session.run() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep the selected transport; retry with a fresh one-use ticket. */ }
                    finally {
                        session.close()
                        synchronized(this@HostRelayController) {
                            if (active === session) { active = null; publish(null) }
                        }
                    }
                    delay(3_000)
                }
            }
        }
    }

    private fun currentOwner(): String? =
        (AccountSessionSource.state.value as? AccountState.SignedIn)?.userId

    @Synchronized fun fragment(): String {
        val current = offer ?: return ""
        return current.fragment.takeIf {
            scope != null && active != null && currentOwner() == current.owner &&
                configuredSettings?.value?.let { RelayConfig.current(it) } == current.config &&
                SessionShareManager.allSharedTabIds.value.isNotEmpty()
        } ?: ""
    }

    @Synchronized fun stop() {
        val previousScope = scope
        scope = null
        configuredSettings = null
        publish(null)
        active?.close(); active = null
        previousScope?.cancel()
    }
}
