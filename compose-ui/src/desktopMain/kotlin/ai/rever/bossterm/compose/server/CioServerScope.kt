package ai.rever.bossterm.compose.server

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.net.BindException

/**
 * CIO also rethrows bind failures from its root launch coroutine, even when start()'s
 * caller catches them to retry another port. Keep that duplicate report out of the
 * host's uncaught-exception/crash handler. start() still throws, so callers retain
 * their normal fallback and failure handling. Other failures remain uncaught.
 *
 * No parent Job: each engine owns its lifecycle, just as with Ktor's GlobalScope
 * factory. A failed engine must not cancel a manager or another server's scope.
 */
internal val cioServerScope: CoroutineScope = object : CoroutineScope {
    override val coroutineContext = CoroutineExceptionHandler { _, failure ->
        val bind = generateSequence(failure) { it.cause }
            .filterIsInstance<BindException>()
            .firstOrNull()
        if (bind != null) {
            LoggerFactory.getLogger("BossTermCioServer").warn("CIO bind failed: {}", bind.message)
        } else {
            val thread = Thread.currentThread()
            thread.uncaughtExceptionHandler.uncaughtException(thread, failure)
        }
    }
}
