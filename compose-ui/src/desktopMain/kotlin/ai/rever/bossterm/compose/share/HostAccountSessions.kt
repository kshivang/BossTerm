package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager
import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Host-owned identity and registry transport. No host session credentials cross this boundary.
 * Install before creating terminals or starting any of the account services. Implementations must
 * reject operations when [userId] no longer matches their authenticated user, including at the server.
 */
interface HostAccountSessions {
    val state: StateFlow<AccountState>
    suspend fun upsert(userId: String, rowJson: String): Boolean
    suspend fun delete(userId: String, shareId: String): Boolean
    suspend fun list(userId: String, since: String): String
}

/** Optional relay admission. A ticket is room-scoped and single-use, never a host login token. */
interface HostTerminalRelay {
    suspend fun relayTicket(userId: String, roomId: String, role: String): String
}

/** Optional account settings capability. Host access tokens never leave the provider. */
interface HostTerminalPreferences {
    suspend fun preferences(userId: String): String
    suspend fun settingsHandoff(userId: String): String
}

/** Shared by account services and UI, while standalone BossTerm retains its own login. */
object AccountSessionSource {
    private val hostState = MutableStateFlow<AccountState>(AccountState.SignedOut)
    private var identityJob: Job? = null
    @Volatile private var delegate: HostAccountSessions? = null
    @Volatile private var embedded = false

    // Stable facade: default services can survive a plugin disable/re-register in one classloader.
    private val facade = object : HostAccountSessions, HostTerminalPreferences, HostTerminalRelay {
        override val state = hostState.asStateFlow()
        override suspend fun relayTicket(userId: String, roomId: String, role: String) =
            checkNotNull(delegate as? HostTerminalRelay) { "Host relay unavailable" }.relayTicket(userId, roomId, role)
        override suspend fun preferences(userId: String) =
            checkNotNull(delegate as? HostTerminalPreferences) { "Host settings unavailable" }.preferences(userId)
        override suspend fun settingsHandoff(userId: String) =
            checkNotNull(delegate as? HostTerminalPreferences) { "Host settings unavailable" }.settingsHandoff(userId)
        override suspend fun upsert(userId: String, rowJson: String) = delegate?.upsert(userId, rowJson) ?: false
        override suspend fun delete(userId: String, shareId: String) = delegate?.delete(userId, shareId) ?: false
        override suspend fun list(userId: String, since: String) =
            checkNotNull(delegate) { "Host account unavailable" }.list(userId, since)
    }

    val host: HostAccountSessions? get() = if (embedded) facade else null
    val state: StateFlow<AccountState> get() = host?.state ?: BossAccountManager.state
    val signInHint: String get() = if (embedded) "Sign in to BossConsole" else "Sign in (menu > Sign In...)"

    @Synchronized
    fun install(host: HostAccountSessions, scope: CoroutineScope) {
        identityJob?.cancel()
        embedded = true
        delegate = host
        identityJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            host.state.collect { refreshHostIdentity() }
        }
    }

    /** Flush a host transition before revoking links or disconnecting account-owned viewers. */
    @Synchronized
    fun refreshHostIdentity() {
        hostState.value = delegate?.state?.value ?: AccountState.SignedOut
    }

    /** Never restore a standalone login after an embedded host is disposed. */
    @Synchronized
    fun disconnect() {
        embedded = true
        identityJob?.cancel()
        identityJob = null
        delegate = null
        hostState.value = AccountState.SignedOut
    }
}
