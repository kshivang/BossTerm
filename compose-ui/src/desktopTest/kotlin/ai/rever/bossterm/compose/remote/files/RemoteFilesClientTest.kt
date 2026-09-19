package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.share.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class RemoteFilesClientTest {
    @Test fun `negotiation is additive for legacy layouts and hello`() {
        assertFalse((ShareProtocol.decodeServer("""{"t":"layout","tabs":[],"activeTabId":null}""") as ServerMessage.Layout).filesAvailable)
        assertTrue((ShareProtocol.decodeClient("""{"t":"hello"}""") as ClientMessage.Hello).capabilities.isEmpty())
    }
    @Test fun `multi chunk upload and download over encrypted serialized RPC`() = runBlocking<Unit> {
        requireFileHostPlatform()
        val root = Files.createTempDirectory("files-host")
        val local = Files.createTempDirectory("files-client")
        lateinit var client: RemoteFilesClient
        val keys = SessionCrypto.deriveKeys(SessionCrypto.newSessionSecret(), SessionCrypto.randomSalt(), SessionCrypto.randomSalt())
        val clientWriter = SessionCrypto.FrameCipher(keys.kC2s, SessionCrypto.DIR_C2S)
        val hostReader = SessionCrypto.FrameCipher(keys.kC2s, SessionCrypto.DIR_C2S)
        val hostWriter = SessionCrypto.FrameCipher(keys.kS2c, SessionCrypto.DIR_S2C)
        val clientReader = SessionCrypto.FrameCipher(keys.kS2c, SessionCrypto.DIR_S2C)
        val host = HostFileAccess("test", reply = {
            client.receive(ShareProtocol.decodeServer(clientReader.decrypt(hostWriter.encrypt(ShareProtocol.encodeServer(it)))) as ServerMessage.FilesReply); true
        }, canControl = { true }, defaultRoot = { root }, approve = { fail("Controller must not need a second approval") })
        client = RemoteFilesClient { host.handle(ShareProtocol.decodeClient(hostReader.decrypt(clientWriter.encrypt(ShareProtocol.encodeClient(it)))) as ClientMessage.FilesRequest) }
        try {
            client.request("access")
            val bytes = ByteArray(1_000_003) { (it % 251).toByte() }
            val source = Files.write(local.resolve("source"), bytes)
            var progress = 0L
            client.upload(source, "remote", false) { done, _ -> assertTrue(done >= progress); progress = done }
            assertContentEquals(bytes, Files.readAllBytes(root.resolve("remote")))
            val target = local.resolve("result")
            client.download("remote", target, false) { _, _ -> }
            assertContentEquals(bytes, Files.readAllBytes(target))
            assertEquals(bytes.size.toLong(), progress)
        } finally { host.close(); root.toFile().deleteRecursively(); local.toFile().deleteRecursively() }
    }
    @Test fun `denied access never exposes listing`() = runBlocking<Unit> {
        lateinit var client: RemoteFilesClient
        val host = HostFileAccess("test", reply = { client.receive(it); true }, approve = { null })
        client = RemoteFilesClient(host::handle)
        try {
            assertFailsWith<IllegalStateException> { client.request("access") }
            assertFailsWith<IllegalStateException> { client.request("list") }
        } finally { host.close() }
    }
    @Test fun `disconnect fails pending RPC immediately`() = runBlocking<Unit> {
        val sent = CompletableDeferred<Unit>()
        val client = RemoteFilesClient { sent.complete(Unit) }
        val request = async { runCatching { client.request("list") } }
        sent.await(); client.disconnected()
        assertTrue(withTimeout(1000) { request.await() }.isFailure)
    }
    @Test fun `cancelled upload cleans temporary file and preserves destination`() = runBlocking<Unit> {
        requireFileHostPlatform()
        val root = Files.createTempDirectory("files-host")
        val source = Files.createTempFile("files-source", ".bin")
        lateinit var client: RemoteFilesClient
        val host = HostFileAccess("test", reply = { client.receive(it); true }, canControl = { true }, defaultRoot = { root }, approve = { fail("Controller must not need a second approval") })
        client = RemoteFilesClient(host::handle)
        try {
            Files.write(source, ByteArray(100_000))
            client.request("access")
            assertFailsWith<CancellationException> {
                client.upload(source, "partial", false) { done, _ -> if (done > 0) throw CancellationException("cancel") }
            }
            assertEquals(0L, Files.list(root).use { it.count() })
        } finally { host.close(); root.toFile().deleteRecursively(); Files.deleteIfExists(source) }
    }

    @Test fun `closing host cancels pending permission request`() = runBlocking<Unit> {
        val approvalStarted = CompletableDeferred<Unit>()
        val approvalCancelled = CompletableDeferred<Unit>()
        val host = HostFileAccess("test", reply = { fail("Closed host must not reply") }, approve = {
            approvalStarted.complete(Unit)
            try { awaitCancellation() } finally { approvalCancelled.complete(Unit) }
        })
        host.handle(ClientMessage.FilesRequest("request", "access"))
        approvalStarted.await()
        host.close()
        withTimeout(1_000) { approvalCancelled.await() }
    }

    @Test fun `failed integrity check preserves local file and removes staging file`() = runBlocking<Unit> {
        requireFileHostPlatform()
        val root = Files.createTempDirectory("files-host")
        val local = Files.createTempDirectory("files-client")
        lateinit var client: RemoteFilesClient
        val host = HostFileAccess("test", reply = {
            client.receive(if (it.done && it.sha256 != null) it.copy(sha256 = "incorrect") else it); true
        }, approve = { FileAccessApproval.Grant(root) })
        client = RemoteFilesClient(host::handle)
        try {
            Files.writeString(root.resolve("remote"), "new")
            val target = Files.writeString(local.resolve("target"), "original")
            client.request("access")
            assertFailsWith<IllegalStateException> { client.download("remote", target, true) { _, _ -> } }
            assertEquals("original", Files.readString(target))
            assertEquals(1L, Files.list(local).use { it.count() })
        } finally { host.close(); root.toFile().deleteRecursively(); local.toFile().deleteRecursively() }
    }
}
