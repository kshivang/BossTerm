package ai.rever.bossterm.compose.update

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Pushes BossTerm release notifications via Supabase Realtime so the app learns
 * about new versions instantly instead of polling. Subscribes to `postgres_changes`
 * on the shared `app_releases` table (filtered client-side by app id) and invokes
 * [onReleaseChanged] on each INSERT/UPDATE and on every (re)connect (catch-up).
 *
 * Dedicated, isolated Realtime client with exponential-backoff reconnect. Disabled
 * unless a Supabase anon key is configured (see [UpdateSourceConfig]).
 */
class AppUpdateRealtimeService(
    private val supabaseUrl: String = UpdateSourceConfig.supabaseUrl,
    private val anonKey: String = UpdateSourceConfig.anonKey,
    private val appId: String = UpdateSourceConfig.appId
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Touched from start()/stop() and the subscribe + triggerCheck coroutines.
    @Volatile private var client: SupabaseClient? = null
    @Volatile private var channel: RealtimeChannel? = null

    /**
     * Invoked when a release row for [appId] changes, and once on every successful
     * (re)connect. Wire this to UpdateManager.checkForUpdates().
     */
    var onReleaseChanged: (suspend () -> Unit)? = null

    companion object {
        val instance: AppUpdateRealtimeService by lazy { AppUpdateRealtimeService() }
    }

    fun start() {
        if (!UpdateSourceConfig.supabaseEnabled) {
            println("[update] realtime disabled (no Supabase anon key or source=github)")
            return
        }
        if (client != null) return
        try {
            val fullUrl = if (supabaseUrl.startsWith("http://") || supabaseUrl.startsWith("https://")) {
                supabaseUrl
            } else {
                "https://$supabaseUrl"
            }
            println("[update] starting realtime push: $fullUrl")
            client = createSupabaseClient(supabaseUrl = fullUrl, supabaseKey = anonKey) {
                install(Realtime) {
                    heartbeatInterval = 30.seconds
                    reconnectDelay = 7.seconds
                }
            }
            subscribe()
        } catch (e: Exception) {
            println("[update] failed to start realtime: ${e.message}")
        }
    }

    private fun subscribe() {
        scope.launch {
            var backoffMs = 5_000L
            val maxBackoffMs = 60_000L

            while (isActive) {
                try {
                    val c = client ?: return@launch

                    val ch = c.channel("app-releases-changes")
                    channel = ch
                    val changeFlow = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = "app_releases"
                    }

                    ch.subscribe()
                    backoffMs = 5_000L
                    println("[update] subscribed to app_releases changes")

                    // Catch-up for releases published while disconnected/closed.
                    triggerCheck()

                    changeFlow.collect { action ->
                        when (action) {
                            is PostgresAction.Insert -> handleRecord(action.record["app"], action.record["version"])
                            is PostgresAction.Update -> handleRecord(action.record["app"], action.record["version"])
                            else -> {}
                        }
                    }
                    println("[update] app_releases subscription closed; will resubscribe")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("[update] app_releases subscription lost: ${e.message}")
                }
                // Normal close OR error: unsubscribe the old channel (don't leak it) and
                // back off before reconnecting so a repeatedly-completing flow can't busy-spin.
                try { channel?.unsubscribe() } catch (_: Exception) {}
                channel = null
                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
        }
    }

    private fun handleRecord(appElement: Any?, versionElement: Any?) {
        val app = appElement?.toString()?.removeSurrounding("\"")
        if (app != null && app != appId) return  // shared table: ignore other apps
        val version = versionElement?.toString()?.removeSurrounding("\"") ?: ""
        println("[update] release change received: $version")
        triggerCheck()
    }

    private fun triggerCheck() {
        scope.launch {
            try {
                onReleaseChanged?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[update] error running update check from realtime event: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                channel?.unsubscribe()
                client?.close()
            } catch (e: Exception) {
                println("[update] error stopping realtime: ${e.message}")
            } finally {
                channel = null
                client = null
            }
        }
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
