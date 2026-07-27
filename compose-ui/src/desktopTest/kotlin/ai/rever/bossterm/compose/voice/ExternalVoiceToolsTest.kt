package ai.rever.bossterm.compose.voice

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Merging an embedder's tools into BossTerm's own: what the agent is told exists, and where a call
 * to each name lands.
 *
 * The measured case this is written against: BossConsole's `boss` MCP server offers 106 tools, and
 * 13 of them are BossTerm's own re-exported by the `terminal-tab` plugin. Name collision is
 * therefore the normal case here, not an edge one, and "the base implementation wins" is the rule
 * everything else hangs off.
 */
class ExternalVoiceToolsTest {

    private val noArgs = JsonObject(emptyMap())

    @Test
    fun `both surfaces are advertised, once each`() {
        val base = FakeBaseExecutor(listOf("run_command", "read_scrollback"))
        val source = FakeToolSource(listOf(externalTool("git_status"), externalTool("browser_navigate")))

        val names = composite(base, source).tools().map { it.name }

        assertEquals(listOf("run_command", "read_scrollback", "git_status", "browser_navigate"), names)
        assertEquals(names.size, names.toSet().size, "duplicate advertised name: $names")
    }

    /**
     * Pins the EXACT advertised list, not a count.
     *
     * Counting `run_command` and finding one was the assertion this test used to make, and it was
     * satisfied by a surface that also advertised `run_command_2` — a second route to the external
     * implementation, past the scope check, under a name the agent's instructions never mention.
     * The count was true and the surface was wrong.
     */
    @Test
    fun `a colliding external tool is dropped and the BossTerm one keeps the name`() {
        val base = FakeBaseExecutor(listOf("run_command", "read_scrollback"))
        val source = FakeToolSource(listOf(externalTool("run_command"), externalTool("git_status")))
        val exec = composite(base, source)

        assertEquals(listOf("run_command", "read_scrollback", "git_status"), exec.tools().map { it.name })

        // Not merely deduped in the list — the call has to land on BossTerm's implementation, the
        // one with the scope check and the focused-pane logic in front of it.
        runBlocking { exec.execute("run_command", buildJsonObject { put("script", "ls") }, null) }
        assertEquals(1, base.executed.size, "run_command must reach the base executor")
        assertTrue(source.calls.isEmpty(), "the external run_command must not have been called")

        // And the dropped one is refused by name rather than reported as unknown.
        val failure = assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("run_command_2", noArgs, null) }
        }
        assertTrue(failure.message!!.contains("not available"), failure.message!!)
    }

    /**
     * The measured case: 13 of the 106 tools on BossConsole's surface are BossTerm's own, re-exported
     * by terminal-tab. Every one of them must lose its slot, not take a suffixed one — including the
     * ceiling arithmetic, since duplicates that survive push real tools off the end.
     */
    @Test
    fun `every collision is dropped, and the freed slots go to real tools`() {
        val baseNames = listOf("run_command", "read_scrollback", "list_tabs", "send_input")
        val base = FakeBaseExecutor(baseNames)
        val source = FakeToolSource(
            list = baseNames.map { externalTool(it) } + (1..3).map { externalTool("git_$it") },
            policy = VoiceToolPolicy(maxExternalTools = 3),
        )

        val names = composite(base, source).tools().map { it.name }

        assertEquals(baseNames + listOf("git_1", "git_2", "git_3"), names)
        assertTrue(names.none { it.endsWith("_2") && it != "git_2" }, names.toString())
    }

    @Test
    fun `a name nothing legal can be made from is dropped, and saying it is refused`() {
        val source = FakeToolSource(listOf(externalTool("!!!"), externalTool("git_status")))
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        assertEquals(listOf("git_status"), exec.tools().map { it.name })
        val failure = assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("!!!", noArgs, null) }
        }
        assertTrue(failure.message!!.contains("not available"), failure.message!!)
        assertTrue(source.calls.isEmpty())
    }

    /**
     * A source listing one tool twice gets it advertised once — not once plus a `_2` clone that
     * calls back with the same source name, which is what a count-based assertion here used to let
     * through.
     */
    @Test
    fun `a source that lists one tool twice gets it advertised once`() {
        val source = FakeToolSource(listOf(externalTool("git_status"), externalTool("git_status")))
        assertEquals(
            listOf("git_status"),
            composite(FakeBaseExecutor(emptyList()), source).tools().map { it.name },
        )
    }

    /** But two genuinely different tools whose names only sanitise alike keep both. */
    @Test
    fun `distinct tools that sanitise to the same name are disambiguated`() {
        val source = FakeToolSource(listOf(externalTool("git.status"), externalTool("git status")))
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        val names = exec.tools().map { it.name }
        assertEquals(listOf("git_status", "git_status_2"), names)

        runBlocking { exec.execute("git_status_2", noArgs, null) }
        assertEquals("git status", source.calls.single().first, "the suffixed one is the second tool")
    }

    @Test
    fun `prefixing keeps both, and results route back to the source's own name`() {
        val base = FakeBaseExecutor(listOf("run_command"))
        val source = FakeToolSource(
            list = listOf(externalTool("run_command"), externalTool("git_status")),
            namePrefix = "boss_",
            policy = VoiceToolPolicy(onNameCollision = VoiceToolCollisionPolicy.PrefixExternal),
        )
        val exec = composite(base, source)

        val names = exec.tools().map { it.name }
        assertEquals(listOf("run_command", "boss_run_command", "boss_git_status"), names)

        runBlocking { exec.execute("boss_run_command", buildJsonObject { put("arg", "x") }, null) }
        assertEquals("run_command", source.calls.single().first, "the source gets ITS name back")
        assertTrue(base.executed.isEmpty())
    }

    /** OpenAI rejects the whole session — every tool, not just the bad one — over an illegal name. */
    @Test
    fun `illegal and overlong names are made legal and stay reversible`() {
        val ugly = "git status/now!"
        val long = "plugin_" + "x".repeat(200)
        val base = FakeBaseExecutor(emptyList())
        val source = FakeToolSource(listOf(externalTool(ugly), externalTool(long)))
        val exec = composite(base, source)

        val names = exec.tools().map { it.name }
        assertEquals(2, names.size)
        for (n in names) {
            assertTrue(VoiceToolNaming.LEGAL.matches(n), "illegal function name: $n")
        }

        runBlocking { exec.execute(names[0], noArgs, null) }
        runBlocking { exec.execute(names[1], noArgs, null) }
        assertEquals(listOf(ugly, long), source.calls.map { it.first })
    }

    /** Two long names sharing a prefix must not collapse onto one advertised tool. */
    @Test
    fun `overlong names that share a prefix stay distinct`() {
        val a = "a".repeat(70) + "_one"
        val b = "a".repeat(70) + "_two"
        val exec = composite(FakeBaseExecutor(emptyList()), FakeToolSource(listOf(externalTool(a), externalTool(b))))

        val names = exec.tools().map { it.name }
        assertEquals(2, names.size, "one of them was swallowed: $names")
        assertEquals(2, names.toSet().size, "both advertised under the same name: $names")
    }

    /**
     * Volume. The whole `boss` surface is 39,785 bytes of session config against BossTerm's 12,056,
     * and that rides every turn — so the ceiling exists, but nothing may vanish quietly.
     */
    @Test
    fun `the ceiling bounds the surface and refuses what it dropped`() {
        val base = FakeBaseExecutor(listOf("run_command"))
        val many = (1..40).map { externalTool("tool_$it") }
        val source = FakeToolSource(many, policy = VoiceToolPolicy(maxExternalTools = 5))
        val exec = composite(base, source)

        val names = exec.tools().map { it.name }
        assertEquals(6, names.size, names.toString())
        assertEquals("tool_5", names.last(), "the ceiling keeps the source's declared order")

        val failure = assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("tool_6", noArgs, null) }
        }
        assertTrue(failure.message!!.contains("not available"), failure.message!!)
        assertTrue(source.calls.isEmpty())
    }

    /**
     * Hot reload is a normal event in BossConsole, so the list is re-read per call rather than
     * captured when the call started.
     */
    @Test
    fun `a tool registered after the call started is callable`() {
        var enumerations = 0
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            enumerate = { enumerations++ },
        )
        val exec = composite(FakeBaseExecutor(), source)

        assertEquals(listOf("git_status"), exec.tools().filter { it.name !in setOf("run_command", "read_scrollback") }.map { it.name })
        assertFailsWith<VoiceToolException> { runBlocking { exec.execute("git_stage", noArgs, null) } }

        // A plugin loads mid-call.
        source.list = listOf(externalTool("git_status"), externalTool("git_stage"))

        assertTrue("git_stage" in exec.tools().map { it.name })
        runBlocking { exec.execute("git_stage", noArgs, null) }
        assertEquals("git_stage", source.calls.single().first)
        assertTrue(enumerations > 1, "the source must be re-read, not snapshotted")
    }

    /** ...and the other direction: an unloaded plugin's tool stops working immediately. */
    @Test
    fun `a tool unregistered mid-call stops being callable`() {
        val source = FakeToolSource(listOf(externalTool("git_status")))
        val exec = composite(FakeBaseExecutor(), source)
        runBlocking { exec.execute("git_status", noArgs, null) }

        source.list = emptyList()

        assertFalse("git_status" in exec.tools().map { it.name })
        assertFailsWith<VoiceToolException> { runBlocking { exec.execute("git_status", noArgs, null) } }
        assertEquals(1, source.calls.size)
    }

    /** The same allowlist BossTerm applies to its own tools: only declared keys reach the source. */
    @Test
    fun `arguments outside the declared schema never reach the source`() {
        val source = FakeToolSource(listOf(externalTool("git_status", properties = props("path"))))
        val exec = composite(FakeBaseExecutor(), source)

        runBlocking {
            exec.execute(
                "git_status",
                buildJsonObject {
                    put("path", "docs")
                    put("force", true)
                    put("voice_confirm", "made-up")
                },
                null,
            )
        }
        assertEquals(setOf("path"), source.calls.single().second.keys)
    }

    @Test
    fun `the schema and description the agent sees are the source's own`() {
        val source = FakeToolSource(
            listOf(
                externalTool(
                    "git_stage",
                    description = "Stage paths for commit.",
                    properties = props("paths"),
                    required = listOf("paths"),
                    write = true,
                )
            )
        )
        val def = composite(FakeBaseExecutor(), source).tools().first { it.name == "git_stage" }

        assertEquals("Stage paths for commit.", def.description)
        assertTrue(def.write)
        assertEquals("object", def.parameters["type"]?.jsonPrimitive?.content)
        assertNotNull(def.parameters["properties"]?.jsonObject?.get("paths"))
        assertEquals("""["paths"]""", def.parameters["required"].toString())
    }

    @Test
    fun `context snapshot and disposal pass through to BossTerm's executor`() {
        val base = FakeBaseExecutor()
        val exec = composite(base, FakeToolSource(emptyList()))
        assertEquals("base snapshot", exec.contextSnapshot(null))
        exec.dispose()
        assertTrue(base.disposed)
    }
}
