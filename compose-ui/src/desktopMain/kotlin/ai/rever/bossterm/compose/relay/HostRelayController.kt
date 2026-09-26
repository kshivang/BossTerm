package ai.rever.bossterm.compose.relay

import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.share.AccountSessionSource
import ai.rever.bossterm.compose.share.SessionShareManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/** One opt-in relay connection for all shares; logout and plugin disposal synchronously revoke it. */
internal object HostRelayController {
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var active: RelayHostSession? = null
    @Volatile private var offer: Pair<String, String>? = null // owner and fragment

    @Synchronized fun start() {
        if (scope != null) return
        val config = RelayConfig.current() ?: return
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = ownerScope
        ownerScope.launch {
            combine(AccountSessionSource.state, SessionShareManager.allSharedTabIds) { account, tabs ->
                (account as? AccountState.SignedIn)?.userId?.takeIf { tabs.isNotEmpty() }
            }.distinctUntilChanged().collectLatest { userId ->
                synchronized(this@HostRelayController) {
                    if (scope !== ownerScope) return@collectLatest
                    offer = null
                }
                if (userId == null) return@collectLatest
                val room = UUID.randomUUID().toString()
                while (isActive) {
                    lateinit var session: RelayHostSession
                    session = RelayHostSession(config.endpoint, room, userId) { ready ->
                        synchronized(this@HostRelayController) {
                            // A cancelled generation may finish after stop/start created a new session.
                            if (scope === ownerScope && active === session) {
                                offer = if (ready && currentOwner() == userId)
                                    userId to config.fragment(room) else null
                            }
                        }
                    }
                    val accepted = synchronized(this@HostRelayController) {
                        if (scope !== ownerScope || currentOwner() != userId) false
                        else { active = session; true }
                    }
                    if (!accepted) { session.close(); return@collectLatest }
                    try { session.run() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep the selected transport; retry with a fresh one-use ticket. */ }
                    finally {
                        session.close()
                        synchronized(this@HostRelayController) {
                            if (active === session) { active = null; offer = null }
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
        return current.second.takeIf {
            scope != null && active != null && currentOwner() == current.first && SessionShareManager.allSharedTabIds.value.isNotEmpty()
        } ?: ""
    }

    @Synchronized fun stop() {
        val previousScope = scope
        scope = null
        offer = null
        active?.close(); active = null
        previousScope?.cancel()
    }
}
