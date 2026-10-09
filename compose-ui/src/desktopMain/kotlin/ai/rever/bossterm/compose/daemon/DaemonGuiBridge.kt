package ai.rever.bossterm.compose.daemon

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** App-lifetime activation, independent of the window-lifetime terminal mirrors. */
internal class DaemonGuiBridge(
    private val port: Int,
    private val secret: String,
    private val scope: CoroutineScope,
    private val onOpen: () -> Unit,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    fun start(): Job = scope.launch(Dispatchers.IO) {
        val log = LoggerFactory.getLogger(DaemonGuiBridge::class.java)
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            var backoff = 250L
            while (isActive) {
                try {
                    val url = "ws://127.0.0.1:$port/attach?lifecycle=1" +
                        "&pid=${ProcessHandle.current().pid()}&v=${DaemonAttachProtocol.PROTOCOL_VERSION}"
                    client.webSocket(url, request = { header(DaemonAttachProtocol.TOKEN_HEADER, secret) }) {
                        backoff = 250L
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val message = DaemonAttachProtocol.decodeServer(frame.readText())
                            if (message is DaemonAttachProtocol.Server.Focus) withContext(uiDispatcher) { onOpen() }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.debug("GUI activation connection dropped: {}", e.message)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(4000L)
            }
        } finally {
            client.close()
        }
    }
}
