package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager
import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.auth.SupabaseAuthConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener

@Serializable
data class TerminalViewingPreferences(
    val unfocused_mode: String = "batch",
    val unfocused_fps: Int = 4,
    val revision: Long = 0,
) {
    init {
        require(unfocused_mode in setOf("batch", "preview"))
        require(unfocused_fps in 1..30 && revision >= 0)
    }
}

/** Account-scoped cache; failed refreshes retain only this user's last valid preferences. */
class AccountTerminalPreferences(
    private val account: StateFlow<AccountState>,
    private val transport: suspend (userId: String, function: String) -> String,
    private val settingsBaseUrl: String,
    private val pollMs: Long = 60_000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, TerminalViewingPreferences>()
    private val mutablePreferences = MutableStateFlow(TerminalViewingPreferences())
    val preferences = mutablePreferences.asStateFlow()
    private var owner: String? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val focusListener = PropertyChangeListener { event ->
        if (event.newValue != null) scope?.launch { refresh() }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val running = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = running
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("activeWindow", focusListener)
        job = running.launch {
            launch { account.collectLatest { refresh() } }
            while (isActive) { delay(pollMs); refresh() }
        }
    }

    @Synchronized
    fun stop() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removePropertyChangeListener("activeWindow", focusListener)
        scope?.cancel()
        scope = null
        job = null
        mutablePreferences.value = TerminalViewingPreferences()
    }

    suspend fun refresh() = mutex.withLock {
        val identity = account.value as? AccountState.SignedIn
        if (owner != identity?.userId) {
            owner = identity?.userId
            mutablePreferences.value = identity?.let { cache[it.userId] } ?: TerminalViewingPreferences()
        }
        if (identity == null) return@withLock
        try {
            val next = json.decodeFromString<TerminalViewingPreferences>(transport(identity.userId, "get_user_terminal_preferences"))
            if (account.value != identity) return@withLock
            cache[identity.userId] = next
            while (cache.size > 16) cache.remove(cache.keys.first())
            mutablePreferences.value = next
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Old servers and temporarily offline devices retain safe defaults or their own cache.
        }
    }

    suspend fun settingsUrl(): String {
        val identity = account.value as? AccountState.SignedIn ?: error("Sign in to open account settings")
        val response = transport(identity.userId, "mint_user_settings_handoff")
        check(account.value == identity) { "Account changed" }
        val token = json.parseToJsonElement(response).jsonObject["token"]?.jsonPrimitive?.content
        require(token != null && Regex("[A-Za-z0-9_-]{43}").matches(token)) { "Settings unavailable" }
        return "${settingsBaseUrl.trimEnd('/')}?t=$token"
    }

    companion object {
        private val http by lazy {
            HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 8_000; connectTimeoutMillis = 5_000 }
            }
        }
        val Default by lazy {
            AccountTerminalPreferences(AccountSessionSource.state, { userId, function ->
                check((AccountSessionSource.state.value as? AccountState.SignedIn)?.userId == userId)
                val host = AccountSessionSource.host
                val result = if (host != null) {
                    val provider = checkNotNull(host as? HostTerminalPreferences)
                    when (function) {
                        "get_user_terminal_preferences" -> provider.preferences(userId)
                        "mint_user_settings_handoff" -> provider.settingsHandoff(userId)
                        else -> error("Unsupported account operation")
                    }
                } else {
                    val token = checkNotNull(BossAccountManager.accessToken())
                    val response = http.post("${SupabaseAuthConfig.url}/rest/v1/rpc/$function") {
                        header("apikey", SupabaseAuthConfig.anonKey)
                        header("Authorization", "Bearer $token")
                        header("Content-Type", "application/json")
                        setBody(buildJsonObject { put("p_expected_user_id", userId) }.toString())
                    }
                    check(response.status.value in 200..299) { "Account settings unavailable" }
                    response.bodyAsText()
                }
                check((AccountSessionSource.state.value as? AccountState.SignedIn)?.userId == userId)
                result
            }, "${SupabaseAuthConfig.url}/functions/v1/user-settings")
        }
    }
}
