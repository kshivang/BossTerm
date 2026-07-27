package ai.rever.bossterm.compose.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A [VoiceToolSource] is embedder code — in BossConsole's case, plugin code behind a hot-swappable
 * classloader — called from inside a live phone call that owns the microphone and is billing by the
 * minute. Every way it can misbehave has to end with the call still up and the agent still able to
 * use BossTerm's own tools.
 */
class VoiceToolSourceIsolationTest {

    private val noArgs = JsonObject(emptyMap())

    @Test
    fun `a source that throws on enumeration costs only its own tools`() {
        val base = FakeBaseExecutor(listOf("run_command", "read_scrollback"))
        val source = FakeToolSource(list = emptyList(), enumerate = { error("plugin unloaded") })
        val exec = composite(base, source)

        assertEquals(listOf("run_command", "read_scrollback"), exec.tools().map { it.name })
        // And BossTerm's own tools still work through the decorator.
        assertEquals("""{"from":"base"}""", runBlocking { exec.execute("run_command", noArgs, null) })
    }

    /** The characteristic failure of a hot-swapped classloader is not an Exception. */
    @Test
    fun `a source that throws an Error is contained too`() {
        val base = FakeBaseExecutor(listOf("run_command"))
        val source = FakeToolSource(list = emptyList(), enumerate = { throw NoClassDefFoundError("gone") })
        assertEquals(listOf("run_command"), composite(base, source).tools().map { it.name })
    }

    /**
     * The invocation path is where catching [Throwable] is actually load-bearing: the source is
     * called directly, so a [LinkageError] from a swapped classloader lands in that frame rather
     * than boxed in an `ExecutionException` the way the enumeration path's Future would box it.
     */
    @Test
    fun `a tool call that throws an Error is contained too`() {
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            onCall = { _, _ -> throw NoClassDefFoundError("plugin classloader swapped") },
        )
        val exec = composite(FakeBaseExecutor(listOf("run_command")), source)

        val result = runBlocking { exec.execute("git_status", noArgs, null) }
        assertTrue(result.contains("\"error\""), result)
        // …and the rest of the surface is still usable afterwards.
        assertEquals("""{"from":"base"}""", runBlocking { exec.execute("run_command", noArgs, null) })
    }

    /**
     * An illegal function name loses the whole session, not one tool — and BossTerm's own names can
     * be illegal today, because `BossTermMcpConfig.toolNamePrefix` is embedder input that nothing
     * validates before it is concatenated onto every built-in name.
     */
    @Test
    fun `a base tool with an illegal name is withheld rather than killing the session`() {
        val base = FakeBaseExecutor(listOf("run_command", "my host_read_scrollback"))
        val exec = composite(base, FakeToolSource(listOf(externalTool("git_status"))))

        val names = exec.tools().map { it.name }
        assertEquals(listOf("run_command", "git_status"), names)
        for (n in names) assertTrue(VoiceToolNaming.LEGAL.matches(n), "illegal name advertised: $n")
    }

    @Test
    fun `a source that hangs on enumeration does not hang the call`() {
        val base = FakeBaseExecutor(listOf("run_command"))
        val wedged = java.util.concurrent.CountDownLatch(1)
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            enumerate = { wedged.await(30, java.util.concurrent.TimeUnit.SECONDS) },
        )
        val exec = composite(base, source, enumerateTimeoutMs = 100L)

        val started = System.currentTimeMillis()
        val names = exec.tools().map { it.name }
        val elapsed = System.currentTimeMillis() - started

        assertEquals(listOf("run_command"), names)
        assertTrue(elapsed < 3_000, "waited ${elapsed}ms on a wedged source")
        wedged.countDown()
    }

    /**
     * A source that blips once must not blank a surface the model can still see in its own tool
     * list — the model would get "unknown tool" for something it was told exists.
     */
    @Test
    fun `a failed enumeration falls back to the last good list`() {
        var fail = false
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            enumerate = { if (fail) error("transient") },
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        assertEquals(listOf("git_status"), exec.tools().map { it.name })
        fail = true
        assertEquals(listOf("git_status"), exec.tools().map { it.name }, "the blip blanked the surface")
        // Still callable, so the fallback is not a display-only fiction.
        runBlocking { exec.execute("git_status", noArgs, null) }
        assertEquals(1, source.calls.size)
    }

    /** An empty list is an answer (the plugin really unloaded), not a failure to fall back from. */
    @Test
    fun `a successful empty enumeration replaces the last good list`() {
        val source = FakeToolSource(listOf(externalTool("git_status")))
        val exec = composite(FakeBaseExecutor(emptyList()), source)
        assertEquals(1, exec.tools().size)

        source.list = emptyList()
        assertTrue(exec.tools().isEmpty(), "the fallback outlived a real unregistration")
    }

    @Test
    fun `a throwing tool call comes back as an error result, not a thrown call`() {
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            onCall = { _, _ -> error("boom") },
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        val result = runBlocking { exec.execute("git_status", noArgs, null) }
        assertTrue(result.contains("\"error\""), result)
        assertTrue(result.contains("boom"), result)
    }

    @Test
    fun `a hanging tool call is answered by the timeout`() {
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            onCall = { _, _ -> delay(60_000); "never" },
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source, callTimeoutMs = 100L)

        val failure = assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("git_status", noArgs, null) }
        }
        assertTrue(failure.message!!.contains("timed out"), failure.message!!)
    }

    /**
     * The result is a string the model reads; it is not BossTerm's business whether it parses. What
     * IS BossTerm's business is that it crosses the wire at all.
     */
    @Test
    fun `garbage comes back verbatim and blank becomes an empty object`() {
        val garbage = FakeToolSource(listOf(externalTool("a")), onCall = { _, _ -> "not json at all" })
        assertEquals(
            "not json at all",
            runBlocking { composite(FakeBaseExecutor(emptyList()), garbage).execute("a", noArgs, null) },
        )

        val blank = FakeToolSource(listOf(externalTool("a")), onCall = { _, _ -> "   " })
        assertEquals(
            "{}",
            runBlocking { composite(FakeBaseExecutor(emptyList()), blank).execute("a", noArgs, null) },
        )
    }

    /**
     * Clamped at the seam rather than left to the controller: this class is what lets embedder
     * output reach a wire, so bounding it is its job and not something it inherits.
     */
    @Test
    fun `an oversize result is clamped`() {
        val huge = "x".repeat(MAX_TOOL_RESULT_CHARS * 3)
        val source = FakeToolSource(listOf(externalTool("read_everything")), onCall = { _, _ -> huge })
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        val result = runBlocking { exec.execute("read_everything", noArgs, null) }

        assertTrue(result.length < MAX_TOOL_RESULT_CHARS, "clamp did not fire: ${result.length} chars")
        assertTrue(result.contains("\"truncated\":true"), result.take(200))
        assertFalse(result.length == huge.length)
    }

    @Test
    fun `a source that throws when asked for its prefix or policy still works`() {
        val source = object : VoiceToolSource {
            override val namePrefix: String get() = error("bad embedder")
            override val policy: VoiceToolPolicy get() = error("bad embedder")
            override fun tools() = listOf(externalTool("git_status"))
            override suspend fun call(name: String, args: JsonObject) = """{"ok":true}"""
        }
        val exec = composite(FakeBaseExecutor(listOf("run_command")), source)
        assertEquals(listOf("run_command", "git_status"), exec.tools().map { it.name })
    }
}
