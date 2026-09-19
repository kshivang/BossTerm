package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.share.ClientMessage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class RemoteFileStoreTest {
    private fun withStore(writable: Boolean = true, block: (Path, RemoteFileStore) -> Unit): Unit {
        val root = Files.createTempDirectory("remote-files-test")
        return try { RemoteFileStore(root, writable).use { block(root, it) } }
        finally { root.toFile().deleteRecursively() }
    }
    private fun request(op: String, path: String = "", id: String? = null, offset: Long = 0,
                        size: Long = 0, bytes: ByteArray = byteArrayOf(), overwrite: Boolean = false, hash: String? = null) =
        ClientMessage.FilesRequest("test", op, path, id, offset, size, Base64.getEncoder().encodeToString(bytes), overwrite, hash)
    private fun hash(bytes: ByteArray) = RemoteFileStore.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test fun `paths cannot escape approved folder`() = withStore { _, store ->
        for (path in listOf("../", "../secret", "/etc", "dir/../secret", "C:/Windows", "dir\\secret", "dir//file", ".", "\u0000")) {
            assertFails { store.execute(request("list", path)) }
        }
    }
    @Test fun `symlink directories and files cannot be accessed`() = withStore { root, store ->
        val outside = Files.createTempDirectory("outside")
        try {
            Files.writeString(outside.resolve("secret"), "private")
            Files.createSymbolicLink(root.resolve("link"), outside)
            Files.createSymbolicLink(root.resolve("file"), outside.resolve("secret"))
            assertTrue(store.execute(request("list")).entries.isEmpty())
            assertFails { store.execute(request("download", "file")) }
            assertFails { store.execute(request("download", "link/secret")) }
            assertFails { store.execute(request("upload", "link/new")) }
            assertFails { store.execute(request("upload", "file", overwrite = true)) }
            assertEquals("private", Files.readString(outside.resolve("secret")))
        } finally { outside.toFile().deleteRecursively() }
    }
    @Test fun `read only denies writes but allows downloads`() = withStore(false) { root, store ->
        Files.writeString(root.resolve("a"), "hello")
        assertFails { store.execute(request("upload", "new")) }
        val begin = store.execute(request("download", "a"))
        val chunk = store.execute(request("read", id = begin.transferId))
        assertEquals("hello", String(Base64.getDecoder().decode(chunk.data)))
        assertTrue(chunk.done)
        assertEquals(hash("hello".toByteArray()), chunk.sha256)
    }
    @Test fun `upload is hidden until verified and replaces only with permission`() = withStore { root, store ->
        Files.writeString(root.resolve("a"), "old")
        assertFails { store.execute(request("upload", "a", size = 3)) }
        val id = store.execute(request("upload", "a", size = 3, overwrite = true)).transferId
        store.execute(request("write", id = id, bytes = "new".toByteArray()))
        assertEquals("old", Files.readString(root.resolve("a")))
        assertEquals(listOf("a"), store.execute(request("list")).entries.map { it.name })
        store.execute(request("finish", id = id, hash = hash("new".toByteArray())))
        assertEquals("new", Files.readString(root.resolve("a")))
    }
    @Test fun `destination created during upload is not overwritten`() = withStore { root, store ->
        val id = store.execute(request("upload", "a")).transferId
        Files.writeString(root.resolve("a"), "keep")
        assertFails { store.execute(request("finish", id = id, hash = hash(byteArrayOf()))) }
        assertEquals("keep", Files.readString(root.resolve("a")))
    }
    @Test fun `concurrent no replace publication preserves the winning file`() {
        val root = Files.createTempDirectory("publish-race")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(30) {
                Files.writeString(root.resolve("first"), "first")
                Files.writeString(root.resolve("second"), "second")
                val start = java.util.concurrent.CountDownLatch(1)
                val results = listOf("first", "second").map { name ->
                    pool.submit<Boolean> {
                        FileDirectory.open(root).use { dir ->
                            start.await()
                            try { dir.move(Path.of(name), Path.of("destination"), false); true }
                            catch (_: java.nio.file.FileAlreadyExistsException) { false }
                        }
                    }
                }
                start.countDown()
                val winners = results.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(1, winners.count { it })
                val winner = if (winners[0]) "first" else "second"
                val loser = if (winners[0]) "second" else "first"
                assertEquals(winner, Files.readString(root.resolve("destination")))
                assertEquals(loser, Files.readString(root.resolve(loser)))
                Files.delete(root.resolve("destination"))
            }
            Files.writeString(root.resolve("destination"), "keep")
            FileDirectory.open(root).use { dir ->
                assertFails { dir.move(Path.of("missing"), Path.of("destination"), false) }
            }
            assertEquals("keep", Files.readString(root.resolve("destination")))
        } finally { pool.shutdownNow(); root.toFile().deleteRecursively() }
    }
    @Test fun `invalid offsets oversized chunks and incorrect hashes fail`() = withStore { root, store ->
        val id = store.execute(request("upload", "a", size = 1)).transferId
        assertFails { store.execute(request("write", id = id, offset = 1, bytes = byteArrayOf(1))) }
        assertFails { store.execute(request("write", id = id, bytes = ByteArray(RemoteFileStore.CHUNK_SIZE + 1))) }
        assertFails { store.execute(request("write", id = id, bytes = byteArrayOf(1, 2))) }
        store.execute(request("write", id = id, bytes = byteArrayOf(1)))
        assertFails { store.execute(request("finish", id = id, hash = "wrong")) }
        assertFalse(Files.exists(root.resolve("a")))
    }
    @Test fun `cancel and close remove partial uploads`() {
        val root = Files.createTempDirectory("cancel")
        try {
            val store = RemoteFileStore(root, true)
            val id = store.execute(request("upload", "a", size = 5)).transferId
            store.execute(request("cancel", id = id))
            assertEquals(0L, Files.list(root).use { it.count() })
            store.execute(request("upload", "b", size = 5))
            store.close()
            assertEquals(0L, Files.list(root).use { it.count() })
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `zero byte unicode file roundtrip`() = withStore { root, store ->
        val name = "empty résumé 猫.txt"
        val id = store.execute(request("upload", name)).transferId
        store.execute(request("finish", id = id, hash = hash(byteArrayOf())))
        assertEquals(0L, Files.size(root.resolve(name)))
        val download = store.execute(request("download", name))
        assertTrue(store.execute(request("read", id = download.transferId)).done)
    }
    @Test fun `directory listing is bounded and paginated`() = withStore { root, store ->
        repeat(205) { Files.createFile(root.resolve("file-%03d".format(it))) }
        Files.createDirectory(root.resolve("folder"))
        val first = store.execute(request("list"))
        assertEquals(100, first.entries.size)
        assertTrue(first.entries.first().directory)
        assertEquals(100, first.next)
        val second = store.execute(request("list", offset = 100))
        val last = store.execute(request("list", offset = 200))
        assertEquals(206, (first.entries + second.entries + last.entries).map { it.name }.toSet().size)
        assertNull(last.next)
    }
    @Test fun `second transfer and wrong transfer id are rejected`() = withStore { _, store ->
        store.execute(request("upload", "a"))
        assertFails { store.execute(request("upload", "b")) }
        assertFails { store.execute(request("write", id = "other")) }
        assertFails { store.execute(request("upload", "b", size = RemoteFileStore.MAX_FILE_SIZE + 1)) }
    }
}
