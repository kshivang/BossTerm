package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager
import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.auth.SupabaseAuthConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime

/**
 * A live session another BossTerm signed into the same account is sharing. What the
 * live-sessions web page lists, seen from inside the desktop app so it can connect natively
 * through the "Add remote" client instead of a browser.
 */
data class AccountSession(
    val shareId: String,
    val deviceName: String,
    val sessionName: String?,
    /** ShareScope name: TAB, WINDOW or ALL. */
    val scope: String,
    /** The account link: auto-admitted, control-capable, `#k=` secret included. */
    val controlUrl: String,
    val secure: Boolean,
    val e2eCode: String?,
    val appVersion: String?,
    val lastSeen: Instant?,
)

/**
 * The account's live-session registry as seen from this device, minus this device's own shares.
 *
 * Polls PostgREST every [pollMs] while signed in, with the account's own JWT so RLS scopes the
 * rows to this user; an explicit [refresh] is cheap and is what the Remote Sessions window
 * calls when it opens. Signed out -> empty and no traffic. Realtime push is the obvious next
 * step (the table is already in the `supabase_realtime` publication); polling is the catch-up
 * path it would need anyway.
 *
 * Own shares are excluded by token: a row whose link `?t=` this process is serving is ours
 * (see [SessionShareManager.ownsToken]) - connecting to it would mirror a session into itself.
 */
class AccountSessionDirectory(
    private val accountState: StateFlow<AccountState>,
    private val accessToken: suspend () -> String?,
    private val ownsToken: (String) -> Boolean,
    private val restBaseUrl: String,
    private val anonKey: String,
    private val pollMs: Long = DEFAULT_POLL_MS,
    private val liveWindowSeconds: Long = LIVE_WINDOW_SECONDS,
    private val http: HttpClient = defaultHttp(),
) {
    private val log = LoggerFactory.getLogger(AccountSessionDirectory::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val mutex = Mutex()

    private val _sessions = MutableStateFlow<List<AccountSession>>(emptyList())
    /** Other devices' live sessions, newest heartbeat first. Empty while signed out. */
    val sessions: StateFlow<List<AccountSession>> = _sessions.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Human-readable reason the last refresh failed, or null. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Serializable
    private data class Row(
        val share_id: String,
        val device_name: String,
        val session_name: String? = null,
        val scope: String = "TAB",
        val control_url: String,
        val secure: Boolean = false,
        val e2e_code: String? = null,
        val app_version: String? = null,
        val last_seen_at: String? = null,
    )

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job = scope.launch {
            launch { accountState.collect { refresh() } }
            while (isActive) {
                delay(pollMs)
                refresh()
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        scope.cancel()
    }

    /** Fetch now. Safe to call from the UI (suspends on IO); serialised so bursts coalesce. */
    suspend fun refresh() = mutex.withLock {
        if (accountState.value !is AccountState.SignedIn) {
            _sessions.value = emptyList()
            _lastError.value = null
            return@withLock
        }
        val token = accessToken() ?: run {
            _sessions.value = emptyList()
            return@withLock
        }
        val since = Instant.now().minusSeconds(liveWindowSeconds).toString()
        try {
            val resp = http.get(
                "$restBaseUrl/terminal_sessions?select=share_id,device_name,session_name,scope,control_url,secure,e2e_code,app_version,last_seen_at" +
                    "&last_seen_at=gt.$since&order=last_seen_at.desc&limit=100",
            ) {
                header("apikey", anonKey)
                header("Authorization", "Bearer $token")
                header("Accept", "application/json")
            }
            if (resp.status.value !in 200..299) {
                _lastError.value = "Registry answered HTTP ${resp.status.value}"
                log.warn("Account session directory refresh rejected: HTTP {}", resp.status.value)
                return@withLock
            }
            val rows = json.decodeFromString<List<Row>>(resp.bodyAsText())
            _sessions.value = rows
                .filter { row -> tokenOf(row.control_url)?.let(ownsToken) != true }
                .map { it.toSession() }
            _lastError.value = null
        } catch (e: Exception) {
            _lastError.value = "Registry unreachable"
            log.warn("Account session directory refresh failed: {}", e.message)
        }
    }

    private fun Row.toSession() = AccountSession(
        shareId = share_id,
        deviceName = device_name,
        sessionName = session_name,
        scope = scope,
        controlUrl = control_url,
        secure = secure,
        e2eCode = e2e_code,
        appVersion = app_version,
        lastSeen = last_seen_at?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() },
    )

    companion object {
        const val DEFAULT_POLL_MS = 15_000L
        /** Mirrors the edge function's freshness window. */
        const val LIVE_WINDOW_SECONDS = 90L

        /** The `t` query parameter of a share link, or null. */
        fun tokenOf(link: String): String? = runCatching {
            URI(link).rawQuery?.split('&')?.firstOrNull { it.startsWith("t=") }?.substringAfter("t=")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun defaultHttp() = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 5_000
            }
        }

        val Default: AccountSessionDirectory by lazy {
            AccountSessionDirectory(
                accountState = BossAccountManager.state,
                accessToken = { BossAccountManager.accessToken() },
                ownsToken = SessionShareManager::ownsToken,
                restBaseUrl = "${SupabaseAuthConfig.url}/rest/v1",
                anonKey = SupabaseAuthConfig.anonKey,
            )
        }
    }
}
