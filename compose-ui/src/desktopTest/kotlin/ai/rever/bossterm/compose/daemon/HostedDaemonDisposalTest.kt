package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.TabbedTerminalState
import ai.rever.bossterm.compose.settings.TerminalSettings
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class HostedDaemonDisposalTest {
    @Test
    fun `last-tab disposal from a bridge callback does not join its own UI job`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val state = TabbedTerminalState(parentScope = scope)
        val disposed = CompletableFuture<Unit>()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/attach") {
                    send(Frame.Text(DaemonAttachProtocol.encodeServer(DaemonAttachProtocol.Server.SessionList(
                        listOf(DaemonAttachProtocol.SessionMeta("session", "shell"))))))
                    send(Frame.Text(DaemonAttachProtocol.encodeServer(DaemonAttachProtocol.Server.GroupList(
                        listOf(GroupView("session", GroupTreeDto.Pane("pane", "session")))))))
                    send(Frame.Text(DaemonAttachProtocol.encodeServer(DaemonAttachProtocol.Server.Closed("session"))))
                    awaitCancellation()
                }
            }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            SwingUtilities.invokeAndWait {
                state.initialize(TerminalSettings.DEFAULT, onLastTabClosed = {
                    state.dispose()
                    disposed.complete(Unit)
                }, isWindowFocused = { true }, parentScope = scope)
                DaemonBridgeCoordinator.registerHosted(state, scope, port, "test")
                DaemonBridgeCoordinator.initializeHosted(state)
            }
            disposed.get(5, TimeUnit.SECONDS)
            // External unload also works on the EDT while cancelled UI continuations drain.
            SwingUtilities.invokeAndWait { HostedDaemonBridges.shutdownForUnload() }
            assertTrue(state.tabs.isEmpty())
        } finally {
            scope.cancel()
            server.stop(0, 500)
        }
    }
}
