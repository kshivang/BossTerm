package ai.rever.bossterm.compose.share

import ai.rever.bossterm.compose.auth.BossAccountManager
import ai.rever.bossterm.compose.auth.BossAccountManager.AccountState
import ai.rever.bossterm.compose.auth.SupabaseAuthConfig
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.update.Version
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Publishes every active [SessionShareManager] share to the signed-in BOSS account's live-session
 * registry (Supabase `terminal_sessions`), so the owner can open it from any browser through the
 * `live-sessions` edge function after a magic-link sign-in.
 *
 * What leaves the machine: the share LINKS (read-only and account/auto-admit, both carrying the E2E
 * secret in their fragment), device + session name, scope, this app's version. Never terminal
 * content. Rows are owner-only under RLS and written AS THE USER with the account's own JWT; there
 * is no service key anywhere in this app.
 *
 * Model: a periodic reconcile. Every [heartbeatMs], and immediately on any change to the shared
 * tab set, the tunnel URL, the account state or the setting, the publisher upserts one row per
 * share (the server stamps `last_seen_at`, so the upsert IS the heartbeat) and deletes rows for
 * shares that ended. A failed request is logged and retried by the next tick; nothing here ever
 * blocks or fails a share. The edge function treats a row as live for 90 s after its last stamp.
 *
 * Lifecycle: [start] once from main(); [stop] from the shutdown hook deletes this process's rows
 * synchronously (bounded) so a clean quit leaves nothing listed. A sign-out deletes them too, via
 * [BossAccountManager.addSignOutListener], while the token is still valid. A crash leaves rows that
 * age out of the page in 90 s and are swept server-side after 15 min.
 *
 * Testable: the class takes its inputs as flows/lambdas and a base URL; [Default] wires the
 * process singletons.
 */
class AccountSessionPublisher(
    private val sharedTabIds: StateFlow<Set<String>>,
    private val remoteUrl: StateFlow<String?>,
    private val accountState: StateFlow<AccountState>,
    private val enabled: StateFlow<Boolean>,
    private val infoFor: (tabId: String) -> SessionShareManager.ShareInfo?,
    private val sessionNameFor: (tabId: String) -> String?,
    private val deviceName: () -> String,
    private val accessToken: suspend (forceRefresh: Boolean) -> String?,
    /** Stored token without a refresh, for [stop] from the shutdown hook. */
    private val cachedAccessToken: () -> String? = { null },
    private val restBaseUrl: String,
    private val anonKey: String,
    private val appVersion: String,
    private val heartbeatMs: Long = DEFAULT_HEARTBEAT_MS,
    private val http: HttpClient = defaultHttp(),
) {
    private val log = LoggerFactory.getLogger(AccountSessionPublisher::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val mutex = Mutex()

    /** tabId -> (userId, shareId) of rows this process has written and not yet deleted. */
    private val published = LinkedHashMap<String, Pair<String, String>>()

    @Serializable
    private data class Row(
        val user_id: String,
        val share_id: String,
        val device_name: String,
        val session_name: String?,
        val scope: String,
        val view_url: String,
        val control_url: String,
        val secure: Boolean,
        val e2e_code: String?,
        val app_version: String,
    )

    /** Idempotent. Starts the reconcile loop. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        log.info("Live-session publisher started (registry {})", restBaseUrl)
        job = scope.launch {
            val trigger = combine(sharedTabIds, remoteUrl, accountState, enabled) { tabs, url, acct, on ->
                Snapshot(tabs, url, acct as? AccountState.SignedIn, on)
            }.distinctUntilChanged()
            launch { trigger.collect { reconcile(it) } }
            while (isActive) {
                delay(heartbeatMs)
                reconcile(currentSnapshot())
            }
        }
    }

    /**
     * Shutdown-hook teardown: delete every row this process owns, synchronously, bounded to
     * [STOP_TIMEOUT_MS] so a hung network never delays exit. Then cancel the loop.
     */
    fun stop() {
        // Delete FIRST, under the mutex, so an upsert in flight finishes (and is then deleted) rather
        // than being cancelled after the server accepted it and before we recorded it. Cancelling the
        // loop first was measured to leave exactly that row behind.
        // cachedAccessToken: a refresh round-trip cannot fit in the hook's budget.
        runBlocking { withTimeoutOrNull(STOP_TIMEOUT_MS) { deleteAll(tokenOverride = cachedAccessToken()) } }
        job?.cancel(); job = null
        scope.cancel()
    }

    /**
     * Delete the rows this process published (sign-out, toggle off, stop), one DELETE per share so
     * another BossTerm signed into the same account on another machine keeps its rows. The tracked
     * set is accurate because every reconcile runs to completion under [mutex] before this does.
     */
    suspend fun deleteAll(tokenOverride: String? = null) = mutex.withLock { deleteTrackedLocked(tokenOverride) }

    private suspend fun deleteTrackedLocked(tokenOverride: String? = null) {
        if (published.isEmpty()) return
        var token = tokenOverride ?: accessToken(false) ?: return
        for (tabId in published.keys.toList()) {
            val (userId, shareId) = published[tabId]!!
            val used = request(token) { b -> del("$restBaseUrl/terminal_sessions?user_id=eq.$userId&share_id=eq.$shareId", b) }
            if (used != null) { token = used; published.remove(tabId) }
        }
    }

    private data class Snapshot(val tabs: Set<String>, val url: String?, val account: AccountState.SignedIn?, val enabled: Boolean)

    private fun currentSnapshot() =
        Snapshot(sharedTabIds.value, remoteUrl.value, accountState.value as? AccountState.SignedIn, enabled.value)

    private suspend fun reconcile(s: Snapshot) = mutex.withLock {
        val account = s.account
        log.debug("Live-session reconcile: shares={} account={} enabled={} tracked={}", s.tabs.size, account?.userId?.take(8), s.enabled, published.size)
        if (account == null) {
            // Signed out: no network here (accessToken() is null now). The tracked set is left
            // alone on purpose - the sign-out listener runs AFTER the state flip and needs it to
            // know which rows to delete with the still-valid token; stop() needs it the same way.
            return@withLock
        }
        if (!s.enabled) {
            deleteTrackedLocked() // opted out with a live token: take our rows down now
            published.clear()
            return@withLock
        }
        var token = accessToken(false)
        if (token == null) { log.warn("Live-session reconcile: no access token available; skipping"); return@withLock }
        // Rows for shares that ended.
        for (tabId in published.keys.toList()) {
            if (tabId in s.tabs) continue
            val (userId, shareId) = published[tabId]!!
            val used = request(token!!) { b -> del("$restBaseUrl/terminal_sessions?user_id=eq.$userId&share_id=eq.$shareId", b) }
            if (used != null) { token = used; published.remove(tabId) }
        }
        // Upsert (create or heartbeat) every current share.
        for (tabId in s.tabs) {
            val info = infoFor(tabId)
            if (info == null) { log.warn("Live-session reconcile: no ShareInfo for tab {}", tabId); continue }
            val accountUrl = info.accountUrl
            if (accountUrl == null) { log.warn("Live-session reconcile: no account URL for tab {} (no link base yet)", tabId); continue }
            val shareId = shareIdOf(info.token)
            val row = Row(
                user_id = account.userId,
                share_id = shareId,
                device_name = deviceName().take(120),
                session_name = sessionNameFor(tabId)?.take(120),
                scope = info.scope.name,
                view_url = info.url,
                control_url = accountUrl,
                secure = info.secure,
                // The account link has its own secret, so its badge differs from the Share sheet's
                // code; the page must show the one the viewer will actually see.
                e2e_code = e2eCodeOf(accountUrl),
                app_version = appVersion.take(40),
            )
            val wasTracked = tabId in published
            val used = request(token!!) { b -> upsert(json.encodeToString(Row.serializer(), row), b) }
            val ok = used != null
            if (used != null) { token = used; published[tabId] = account.userId to shareId }
            if (ok && !wasTracked) log.info("Live-session published: tab {} share {}", tabId, shareId)
        }
    }

    /**
     * One authenticated PostgREST call with a single refresh-and-retry on 401. Returns the bearer
     * the call succeeded with (so a caller mid-loop keeps using the refreshed one instead of
     * re-refreshing per row), or null on failure. Failures are logged at warn without the URL's
     * query (it names the user id).
     */
    private suspend fun request(token: String, call: suspend (bearer: String) -> HttpResponse): String? {
        var bearer = token
        repeat(2) { attempt ->
            val resp = try {
                call(bearer)
            } catch (e: Exception) {
                log.warn("Live-session registry call failed: {}", e.message)
                return null
            }
            when {
                resp.status.value in 200..299 -> return bearer
                resp.status.value == 401 && attempt == 0 -> {
                    bearer = accessToken(true) ?: return null
                }
                else -> {
                    // PostgREST puts the reason (unknown column, CHECK violation) in the body; the
                    // status alone was measured to be undiagnosable. A constraint failure echoes the
                    // failing row, which carries the links' #k= secrets - redact before logging.
                    val detail = runCatching { resp.bodyAsText().take(600) }.getOrDefault("")
                        .replace(Regex("#k=[^\"\\s,)]+"), "#k=REDACTED").take(300)
                    log.warn("Live-session registry call rejected: HTTP {} {}", resp.status.value, detail)
                    return null
                }
            }
        }
        return null
    }

    private suspend fun del(url: String, bearer: String): HttpResponse = http.delete(url) { auth(bearer) }

    private suspend fun upsert(body: String, bearer: String): HttpResponse =
        http.post("$restBaseUrl/terminal_sessions?on_conflict=user_id,share_id") {
            auth(bearer)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(bearer: String) {
        header("apikey", anonKey)
        header("Authorization", "Bearer $bearer")
    }

    companion object {
        const val DEFAULT_HEARTBEAT_MS = 30_000L
        private const val STOP_TIMEOUT_MS = 3_000L
        /** The page's vanity host (a Cloudflare Worker in front of the edge function). */
        const val LIVE_SESSIONS_ORIGIN = "https://cli.risaboss.com"
        const val LIVE_SESSIONS_PAGE = LIVE_SESSIONS_ORIGIN
        /** The function's own host, still valid; framing is allowed from both during the move. */
        const val LIVE_SESSIONS_LEGACY_ORIGIN = "https://api.risaboss.com"

        /** Fingerprint of a link's `#k=` secret, as the share-viewer's E2E badge shows it. */
        fun e2eCodeOf(url: String): String? {
            val k = url.substringAfter("#k=", "").substringBefore('&').takeIf { it.isNotBlank() } ?: return null
            return runCatching { SessionCrypto.fingerprint(SessionCrypto.decodeSecretB64Url(k)) }.getOrNull()
        }

        /** Stable, non-reversible id for a share: first 16 hex of SHA-256(viewToken). */
        fun shareIdOf(viewToken: String): String =
            MessageDigest.getInstance("SHA-256").digest(viewToken.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        private fun defaultHttp() = HttpClient(CIO) {
            expectSuccess = false
            // Short on purpose: every call runs under [mutex], and the sign-out listener (5s budget)
            // and shutdown hook (3s) wait on that mutex. A slow network must not outlive them.
            install(HttpTimeout) {
                requestTimeoutMillis = 4_000
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 3_000
            }
        }

        /** The process-wide publisher wired to the singletons. Started from main(). */
        val Default: AccountSessionPublisher by lazy {
            AccountSessionPublisher(
                sharedTabIds = SessionShareManager.allSharedTabIds,
                remoteUrl = SessionShareManager.remoteUrlFlow,
                accountState = BossAccountManager.state,
                enabled = SettingsManager.instance.settings.map { it.publishSessionsToAccount }
                    .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, SettingsManager.instance.settings.value.publishSessionsToAccount),
                infoFor = SessionShareManager::infoFor,
                sessionNameFor = SessionShareManager::sessionNameFor,
                deviceName = SessionShareManager::defaultSessionName,
                accessToken = { force -> BossAccountManager.accessToken(force) },
                cachedAccessToken = BossAccountManager::cachedAccessToken,
                restBaseUrl = "${SupabaseAuthConfig.url}/rest/v1",
                anonKey = SupabaseAuthConfig.anonKey,
                appVersion = Version.CURRENT.toString(),
            ).also { publisher ->
                // Delete our rows while the token is still valid; signOut() revokes it right after.
                BossAccountManager.addSignOutListener { stored -> publisher.deleteAll(tokenOverride = stored.accessToken) }
            }
        }
    }
}
