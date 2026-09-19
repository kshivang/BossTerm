package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.share.ClientMessage
import ai.rever.bossterm.compose.share.ServerMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class HostFileAccessPolicyTest {
    @BeforeTest
    fun requireSupportedHost() = requireFileHostPlatform()

    @Test fun `control grants read write without a second prompt and revocation removes automatic access`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("control-files")
        val control = AtomicBoolean(true)
        val responses = Channel<ServerMessage.FilesReply>(Channel.UNLIMITED)
        val host = HostFileAccess("test", reply = { responses.trySend(it).isSuccess },
            canControl = control::get, defaultRoot = { root }, approve = { fail("Unexpected second permission prompt") })
        suspend fun request(op: String, transfer: String? = null): ServerMessage.FilesReply {
            host.handle(ClientMessage.FilesRequest(op, op, path = if (op == "upload") "partial" else "", transferId = transfer, size = 4))
            return withTimeout(1000) { responses.receive() }
        }
        try {
            val access = request("access")
            assertNull(access.error); assertTrue(access.writable)
            assertEquals(root.toRealPath().toString(), access.root)
            val upload = request("upload")
            assertNotNull(upload.transferId)
            control.set(false)
            assertNotNull(request("finish", upload.transferId).error)
            assertEquals(0L, Files.list(root).use { it.count() })
            assertNotNull(request("list").error)
        } finally { host.close(); root.toFile().deleteRecursively() }
    }

    @Test fun `view permission stays read only then upgrades and downgrades with live control`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("read-files")
        val control = AtomicBoolean(false)
        val responses = Channel<ServerMessage.FilesReply>(Channel.UNLIMITED)
        var prompts = 0
        val host = HostFileAccess("test", reply = { responses.trySend(it).isSuccess }, canControl = control::get,
            defaultRoot = { error("An approved folder must be retained") },
            approve = { prompts++; FileAccessApproval.Grant(root) })
        suspend fun request(op: String): ServerMessage.FilesReply {
            host.handle(ClientMessage.FilesRequest(op, op, path = if (op == "upload") "file" else ""))
            return withTimeout(1000) { responses.receive() }
        }
        try {
            assertFalse(request("access").writable)
            assertNotNull(request("upload").error)
            control.set(true)
            assertTrue(request("access").writable)
            assertEquals(1, prompts)
            control.set(false)
            assertFalse(request("access").writable)
            assertNull(request("list").error)
            assertNotNull(request("upload").error)
            assertEquals(1, prompts)
        } finally { host.close(); root.toFile().deleteRecursively() }
    }
}
