package ai.rever.bossterm.compose.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
     * The deadline has to cover the embedder's POLICY code, not just its tool list.
     *
     * `runCatching` answers a classifier that throws and does nothing for one that hangs, and the
     * hot-swappable-classloader argument applies to a predicate exactly as much as to `tools()`.
     * This is also the worst place for it: `sessionUpdate` calls `tools()` on the connect coroutine
     * with the socket already open and billing and `startLimits()` not yet armed — a try/catch and
     * no watchdog.
     */
    @Test
    fun `a hanging classifier does not hang the call`() {
        val wedged = java.util.concurrent.CountDownLatch(1)
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            policy = VoiceToolPolicy(
                excludeExtra = { wedged.await(30, java.util.concurrent.TimeUnit.SECONDS); false },
            ),
        )
        val exec = composite(FakeBaseExecutor(listOf("run_command")), source, enumerateTimeoutMs = 100L)

        val started = System.currentTimeMillis()
        val names = exec.tools().map { it.name }
        val elapsed = System.currentTimeMillis() - started

        assertEquals(listOf("run_command"), names)
        assertTrue(elapsed < 3_000, "waited ${elapsed}ms on a wedged classifier")
        wedged.countDown()
    }

    /**
     * The cached fallback is only good for the base set it was merged against.
     *
     * A surface is merged relative to what BossTerm advertises, and that moves — `manage_tools` can
     * disable a tool mid-call, and it can come back. Reusing a surface merged against a different
     * base could let an external tool sit on a name BossTerm has since taken back, which is the one
     * thing the merge exists to prevent.
     */
    @Test
    fun `a stale surface is not reused once BossTerm's own tools have changed`() {
        var fail = false
        var baseNames = listOf("run_command")
        val base = object : VoiceToolExecutor {
            override fun tools() = baseNames.map {
                VoiceToolDef(it, "base $it", kotlinx.serialization.json.buildJsonObject { }, write = false)
            }
            override fun contextSnapshot(defaultTabId: String?) = ""
            override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?) = "{}"
        }
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            enumerate = { if (fail) error("transient") },
        )
        val exec = composite(base, source)
        assertEquals(listOf("run_command", "git_status"), exec.tools().map { it.name })

        fail = true
        baseNames = listOf("run_command", "read_scrollback")

        assertEquals(
            listOf("run_command", "read_scrollback"),
            exec.tools().map { it.name },
            "a surface merged against the old base set was reused against a new one",
        )
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

    /**
     * A malformed schema kills the call by the same route an illegal NAME does — OpenAI rejects the
     * whole `session.update`, every tool disappears, and it happens at session config, before the
     * watchdog that would explain it exists. `properties` is a raw JsonObject from embedder code and
     * went into the advertised schema untouched.
     */
    @Test
    fun `a property that is not a schema object is withheld, not advertised`() {
        val source = FakeToolSource(
            listOf(
                externalTool(
                    "git_status",
                    properties = kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive("string"))
                        putJsonObject("depth") { put("type", "integer") }
                    },
                    required = listOf("path", "depth"),
                )
            )
        )
        val def = composite(FakeBaseExecutor(emptyList()), source).tools().single()

        val props = def.parameters["properties"]!!.jsonObject
        assertEquals(setOf("depth"), props.keys, "a non-object property reached the advertised schema")
        // `required` naming a property that is no longer there is itself a rejected session.
        assertEquals("""["depth"]""", def.parameters["required"].toString())
    }

    @Test
    fun `a required name with no matching property is dropped`() {
        val source = FakeToolSource(
            listOf(externalTool("git_status", properties = props("path"), required = listOf("path", "ghost")))
        )
        val def = composite(FakeBaseExecutor(emptyList()), source).tools().single()
        assertEquals("""["path"]""", def.parameters["required"].toString())
    }

    /** Declared parameters, none of them usable: the schema no longer describes the tool. */
    @Test
    fun `a tool whose whole schema is malformed is dropped and refused`() {
        val source = FakeToolSource(
            listOf(
                externalTool(
                    "git_status",
                    properties = kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive("string"))
                    },
                ),
                externalTool("git_log"),
            )
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        assertEquals(listOf("git_log"), exec.tools().map { it.name })
        assertFailsWith<VoiceToolException> { runBlocking { exec.execute("git_status", noArgs, null) } }
    }

    /**
     * A property object whose `type` is not a string is still an invalid schema, and the stakes are
     * the same rejected session — `is JsonObject` alone does not catch it.
     */
    @Test
    fun `a property with a non-string type is withheld`() {
        val source = FakeToolSource(
            listOf(
                externalTool(
                    "git_status",
                    properties = kotlinx.serialization.json.buildJsonObject {
                        putJsonObject("path") { put("type", kotlinx.serialization.json.JsonPrimitive(123)) }
                        putJsonObject("depth") { put("type", "integer") }
                    },
                )
            )
        )
        val def = composite(FakeBaseExecutor(emptyList()), source).tools().single()
        assertEquals(setOf("depth"), def.parameters["properties"]!!.jsonObject.keys)
    }

    /**
     * The tool ceiling bounds COUNT; one plugin projecting every repo or role name into an enum
     * ships a schema orders of magnitude past anything measured while sitting under it.
     */
    @Test
    fun `a tool with an enormous schema is dropped and refused`() {
        val huge = kotlinx.serialization.json.buildJsonObject {
            putJsonObject("repo") {
                put("type", "string")
                put("description", "x".repeat(100_000))
            }
        }
        val source = FakeToolSource(
            listOf(externalTool("git_status", properties = huge), externalTool("git_log"))
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        assertEquals(listOf("git_log"), exec.tools().map { it.name })
        assertFailsWith<VoiceToolException> { runBlocking { exec.execute("git_status", noArgs, null) } }
    }

    /** But a tool that declares no parameters at all is perfectly legitimate. */
    @Test
    fun `a no-argument tool survives`() {
        val source = FakeToolSource(
            listOf(externalTool("git_status", properties = JsonObject(emptyMap())))
        )
        assertEquals(
            listOf("git_status"),
            composite(FakeBaseExecutor(emptyList()), source).tools().map { it.name },
        )
    }

    /**
     * The ceiling bounds tool COUNT, and the byte measurements behind its default assume
     * BossConsole-sized descriptions. Without a per-description cap a source can sit under the
     * ceiling and still ship a quarter of a megabyte of session config every turn.
     */
    @Test
    fun `an enormous description is clamped`() {
        val source = FakeToolSource(
            listOf(externalTool("git_status", description = "x".repeat(50_000)))
        )
        val def = composite(FakeBaseExecutor(emptyList()), source).tools().single()
        assertTrue(def.description.length < 2_000, "description was ${def.description.length} chars")
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
