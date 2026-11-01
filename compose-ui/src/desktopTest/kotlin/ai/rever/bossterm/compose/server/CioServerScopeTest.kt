package ai.rever.bossterm.compose.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.runBlocking
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CioServerScopeTest {
    @Test
    fun `occupied port still fails startup and a fresh engine can bind`() {
        ServerSocket().use { occupied ->
            occupied.bind(InetSocketAddress("127.0.0.1", 0))
            val server = cioServerScope.embeddedServer(CIO, host = "127.0.0.1", port = occupied.localPort) {}
            try {
                val failure = assertFailsWith<Exception> { server.start(wait = false) }
                assertTrue(generateSequence(failure as Throwable) { it.cause }.any { it is BindException })
            } finally {
                server.stop(0, 1000)
            }
        }
        val server = cioServerScope.embeddedServer(CIO, host = "127.0.0.1", port = 0) {}
        try {
            server.start(wait = false)
            runBlocking { assertTrue(server.engine.resolvedConnectors().single().port > 0) }
        } finally {
            server.stop(0, 1000)
        }
    }

    @Test
    fun `bind reports stay local while unexpected errors reach uncaught handler`() {
        val thread = Thread.currentThread()
        val original = thread.uncaughtExceptionHandler
        val reports = mutableListOf<Throwable>()
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> reports.add(failure) }
        try {
            val handler = assertNotNull(cioServerScope.coroutineContext[CoroutineExceptionHandler])
            handler.handleException(cioServerScope.coroutineContext, BindException("Address already in use"))
            handler.handleException(cioServerScope.coroutineContext, IllegalStateException(BindException("busy")))
            assertTrue(reports.isEmpty())
            val unexpected = IllegalStateException("unexpected")
            handler.handleException(cioServerScope.coroutineContext, unexpected)
            assertTrue(reports.single() === unexpected)
        } finally {
            thread.uncaughtExceptionHandler = original
        }
    }
}
