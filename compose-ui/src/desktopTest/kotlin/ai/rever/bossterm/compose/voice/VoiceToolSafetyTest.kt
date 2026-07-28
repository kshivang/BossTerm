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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The two safety tiers over an embedder's tools.
 *
 * Tier 1 is absolute: a tool that returns secret material is never advertised and never executed,
 * because every word of a voice call goes through a third-party realtime API and is then said out
 * loud. Tier 2 is an interlock, not a rule the model is asked to follow.
 */
class VoiceToolSafetyTest {

    private val noArgs = JsonObject(emptyMap())

    // -------------------------------------------------- the shadowing invariant

    /**
     * A rejected external tool must never make BossTerm's own tool of that name stop working.
     *
     * Every exit is covered here on purpose. It was fixed once, for the collision branch, and left
     * wrong on the other three — and no test noticed because every tier-1 and ceiling test ran
     * against an empty base, where there is nothing to shadow. The ceiling case is the alarming one:
     * it needs no embedder mistake at all, just more than `maxExternalTools` tools with a re-export
     * sitting after the cut, which is decided by plugin registration order.
     */
    @Test
    fun `no rejection path can make BossTerm's own tool refuse`() {
        val rejections = mapOf(
            "tier-1 exclusion" to VoiceToolPolicy(excludeExtra = { it.name == "run_command" }),
            "a throwing classifier" to VoiceToolPolicy(excludeExtra = { error("embedder bug") }),
            "the ceiling" to VoiceToolPolicy(maxExternalTools = 0),
            "an unusable name" to VoiceToolPolicy(),
        )
        for ((why, policy) in rejections) {
            val base = FakeBaseExecutor(listOf("run_command", "read_scrollback"))
            val source = FakeToolSource(
                list = listOf(externalTool("run_command"), externalTool("!!!")),
                policy = policy,
            )
            val exec = composite(base, source)

            assertEquals(
                listOf("run_command", "read_scrollback"),
                exec.tools().map { it.name },
                "$why: BossTerm's own tools must still be advertised",
            )
            assertEquals(
                """{"from":"base"}""",
                runBlocking { exec.execute("run_command", noArgs, null) },
                "$why: run_command must still reach BossTerm's own implementation",
            )
            assertTrue(source.calls.isEmpty(), "$why: the external tool must not have been called")
        }
    }

    /** The same, for a source that lists one tool twice and BossTerm owns that name. */
    @Test
    fun `a duplicated external name does not disable BossTerm's tool either`() {
        val base = FakeBaseExecutor(listOf("run_command"))
        val source = FakeToolSource(listOf(externalTool("run_command"), externalTool("run_command")))
        val exec = composite(base, source)

        assertEquals(listOf("run_command"), exec.tools().map { it.name })
        assertEquals("""{"from":"base"}""", runBlocking { exec.execute("run_command", noArgs, null) })
    }

    // ---------------------------------------------------------------- tier 1

    /** The five named on BossConsole's surface, plus the two the same pattern catches. */
    @Test
    fun `secret tools are absent from the advertised surface`() {
        val secrets = listOf(
            "secret_get", "my_secret_get", "secret_search", "secrets_list", "my_secrets_list",
            "secret_create", "secret_delete",
        )
        val source = FakeToolSource(secrets.map { externalTool(it) } + externalTool("git_status"))

        val names = composite(FakeBaseExecutor(emptyList()), source).tools().map { it.name }

        assertEquals(listOf("git_status"), names, "a secret tool reached the agent")
    }

    @Test
    fun `a secret tool is refused when the model names it anyway`() {
        val source = FakeToolSource(listOf(externalTool("secret_get"), externalTool("git_status")))
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        val failure = assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("secret_get", buildJsonObject { put("name", "OPENAI") }, null) }
        }
        assertTrue(failure.message!!.contains("secret material"), failure.message!!)
        assertTrue(source.calls.isEmpty(), "the source must never have been asked")
    }

    /** The tool's author knows things a name pattern cannot. */
    @Test
    fun `a tool the source flags sensitive is excluded whatever it is called`() {
        val source = FakeToolSource(listOf(externalTool("vault_read", sensitive = true), externalTool("git_status")))
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        assertEquals(listOf("git_status"), exec.tools().map { it.name })
        assertFailsWith<VoiceToolException> { runBlocking { exec.execute("vault_read", noArgs, null) } }
    }

    /**
     * The embedder's rule is what makes this expressible without BossTerm holding a list of
     * BossConsole tool names: `rpa_run` says nothing about itself, and only the host knows.
     */
    @Test
    fun `an embedder rule widens the exclusion`() {
        val source = FakeToolSource(
            list = listOf(externalTool("kubectl_exec"), externalTool("git_status")),
            policy = VoiceToolPolicy(excludeExtra = { it.name.endsWith("_exec") }),
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        assertEquals(listOf("git_status"), exec.tools().map { it.name })
        assertFailsWith<VoiceToolException> { runBlocking { exec.execute("kubectl_exec", noArgs, null) } }
    }

    /** An embedder rule cannot un-exclude what BossTerm caught: the tiers only ever widen. */
    @Test
    fun `an embedder rule cannot re-admit a secret tool`() {
        val source = FakeToolSource(
            list = listOf(externalTool("secret_get")),
            policy = VoiceToolPolicy(excludeExtra = { false }),
        )
        assertTrue(composite(FakeBaseExecutor(emptyList()), source).tools().isEmpty())
    }

    /** A classifier that blew up has told us nothing, and nothing is not permission. */
    @Test
    fun `a throwing exclusion rule fails closed`() {
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            policy = VoiceToolPolicy(excludeExtra = { error("embedder bug") }),
        )
        assertTrue(composite(FakeBaseExecutor(emptyList()), source).tools().isEmpty())
    }

    /**
     * The floor has to understand more than one naming convention, because the whole premise of the
     * seam is names from a registry BossTerm has never seen.
     *
     * The `secret.get` row is the one that gives the game away: the merge sanitises it to
     * `secret_get` for advertisement, so before this the name the model finally saw was exactly the
     * one the pattern would have matched a moment earlier.
     */
    @Test
    fun `secret tools are caught whatever the separator`() {
        val spellings = listOf(
            "secret_get", "secret-get", "secret.get", "getSecret", "get_secret",
            "mySecretGet", "secret/get", "SECRET-GET", "api-key-get", "apiKeyGet",
            // An acronym run before the word: only the acronym-boundary rule reaches this one, since
            // the plain camelCase split leaves `awssecret` as a single segment.
            "AWSSecretGet",
        )
        for (name in spellings) {
            assertTrue(VoiceToolPolicy.returnsSecretMaterial(name), "not caught: $name")
            val source = FakeToolSource(listOf(externalTool(name), externalTool("git_status")))
            val exec = composite(FakeBaseExecutor(emptyList()), source)
            assertEquals(listOf("git_status"), exec.tools().map { it.name }, "advertised: $name")
            // …and refused by the sanitised name it would have been advertised under, too.
            for (asked in setOf(name, VoiceToolNaming.advertise(name))) {
                assertFailsWith<VoiceToolException>("$name via $asked") {
                    runBlocking { exec.execute(asked, noArgs, null) }
                }
            }
            assertTrue(source.calls.isEmpty(), name)
        }
    }

    @Test
    fun `destructive tools are gated whatever the separator`() {
        for (name in listOf("git-discard", "git.discard", "gitDiscard", "role-delete", "deleteUser")) {
            assertTrue(VoiceToolPolicy.looksIrreversible(name), "not gated: $name")
            val source = FakeToolSource(listOf(externalTool(name)))
            val def = composite(FakeBaseExecutor(emptyList()), source).tools().single()
            assertTrue(def.description.contains("cannot be undone"), "$name: ${def.description}")
        }
    }

    /** Normalising must not start matching things that merely contain the letters. */
    @Test
    fun `normalisation does not over-match ordinary names`() {
        for (name in listOf("tokenize", "list_tabs", "run-command", "gitStatus", "discardable_notes")) {
            assertFalse(VoiceToolPolicy.returnsSecretMaterial(name), name)
        }
        for (name in listOf("list_tabs", "runCommand", "git-status", "undelete_all")) {
            assertFalse(VoiceToolPolicy.looksIrreversible(name), name)
        }
    }

    /**
     * The agent says the refusal out loud, so "it returns secret material" must not be what it says
     * about a tool the EMBEDDER's rule rejected — least of all in the fail-closed case, where a
     * classifier that throws rejects the entire surface on account of an embedder bug.
     */
    @Test
    fun `an embedder exclusion is not described as a secret leak`() {
        val source = FakeToolSource(
            list = listOf(externalTool("kubectl_exec")),
            policy = VoiceToolPolicy(excludeExtra = { it.name.endsWith("_exec") }),
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        val failure = assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("kubectl_exec", noArgs, null) }
        }
        assertFalse(failure.message!!.contains("secret material"), failure.message!!)
        assertTrue(failure.message!!.contains("host's policy"), failure.message!!)

        // But a genuinely secret-looking one still says so.
        val secret = FakeToolSource(listOf(externalTool("secret_get")))
        val secretExec = composite(FakeBaseExecutor(emptyList()), secret)
        assertTrue(
            assertFailsWith<VoiceToolException> {
                runBlocking { secretExec.execute("secret_get", noArgs, null) }
            }.message!!.contains("secret material")
        )
    }

    @Test
    fun `the built-in patterns catch what they claim to`() {
        for (name in listOf("secret_get", "my_secret_get", "secrets_list", "api_key_get", "get_password")) {
            assertTrue(VoiceToolPolicy.returnsSecretMaterial(name), name)
        }
        // …and are anchored on segments, so ordinary tools survive.
        for (name in listOf("tokenize", "list_tabs", "run_command", "git_status")) {
            assertFalse(VoiceToolPolicy.returnsSecretMaterial(name), name)
        }
    }

    // ---------------------------------------------------------------- tier 2

    @Test
    fun `destructive tools are advertised, marked, and carry the confirmation argument`() {
        val destructive = listOf("git_discard", "plugin_disable", "role_delete", "user_role_remove")
        val source = FakeToolSource(destructive.map { externalTool(it) })
        val defs = composite(FakeBaseExecutor(emptyList()), source).tools()

        assertEquals(destructive, defs.map { it.name }, "gated is not hidden")
        for (def in defs) {
            assertTrue(def.description.contains("cannot be undone"), def.description)
            val props = def.parameters["properties"]!!.jsonObject
            assertTrue(VoiceConfirmationGate.CONFIRM_ARG in props, "${def.name} has no way to confirm")
        }
    }

    /**
     * The flag, not the name — `rpa_run` is exactly the case the pattern cannot reach, so a source
     * that knows must be able to say so and have it mean something.
     *
     * Written because a mutation survived: deleting `tool.irreversible ||` from the policy broke
     * nothing, since every gated tool in this file until now was gated by its NAME.
     */
    @Test
    fun `a tool the source flags irreversible is gated whatever it is called`() {
        val source = FakeToolSource(listOf(externalTool("rpa_run", irreversible = true)))
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        val def = exec.tools().single()
        assertTrue(def.description.contains("cannot be undone"), def.description)
        assertTrue(VoiceConfirmationGate.CONFIRM_ARG in def.parameters["properties"]!!.jsonObject)

        val answer = parse(runBlocking { exec.execute("rpa_run", noArgs, null) })
        assertEquals(true, answer["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(source.calls.isEmpty(), "an irreversible tool ran without confirmation")
    }

    /** `write` cannot tell these apart, which is the whole argument for a second classification. */
    @Test
    fun `a write tool that is merely a write is not gated`() {
        val source = FakeToolSource(listOf(externalTool("git_stage", write = true)))
        val def = composite(FakeBaseExecutor(emptyList()), source).tools().single()
        assertFalse(def.description.contains("cannot be undone"), def.description)
        assertFalse(VoiceConfirmationGate.CONFIRM_ARG in def.parameters["properties"]!!.jsonObject)
    }

    @Test
    fun `the first call to a destructive tool does not run it`() {
        val source = FakeToolSource(listOf(externalTool("git_discard")))
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        val answer = runBlocking { exec.execute("git_discard", noArgs, null) }.let(::parse)

        assertEquals(true, answer["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(answer[VoiceConfirmationGate.CONFIRM_ARG]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(source.calls.isEmpty(), "it ran anyway")
    }

    /**
     * The property the interlock exists for. Two calls inside one turn is the agent confirming with
     * itself — precisely the failure mode a *misheard* instruction produces, because the only
     * evidence the user meant it is the user answering.
     */
    @Test
    fun `a token cannot be redeemed until the user has spoken`() {
        val gate = VoiceConfirmationGate()
        val source = FakeToolSource(listOf(externalTool("git_discard")))
        val exec = composite(FakeBaseExecutor(emptyList()), source, gate)

        val token = parse(runBlocking { exec.execute("git_discard", noArgs, null) })[
            VoiceConfirmationGate.CONFIRM_ARG
        ]!!.jsonPrimitive.content

        val tooSoon = parse(runBlocking { exec.execute("git_discard", withToken(token), null) })
        assertEquals(true, tooSoon["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(tooSoon["instruction"]!!.jsonPrimitive.content.contains("Say what you are about to do"))
        assertTrue(source.calls.isEmpty())

        gate.agentSpoke()
        val announcedOnly = parse(runBlocking { exec.execute("git_discard", withToken(token), null) })
        assertEquals(true, announcedOnly["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(announcedOnly["instruction"]!!.jsonPrimitive.content.contains("Wait for the user"))
        assertTrue(source.calls.isEmpty(), "announcing to nobody is not confirmation")

        gate.userSpoke()

        val result = runBlocking { exec.execute("git_discard", withToken(token), null) }
        assertEquals("""{"from":"source"}""", result)
        assertEquals("git_discard", source.calls.single().first)
        assertFalse(
            VoiceConfirmationGate.CONFIRM_ARG in source.calls.single().second,
            "the confirmation argument is the host's, not the tool's",
        )
    }

    @Test
    fun `a token is single use`() {
        val gate = VoiceConfirmationGate()
        val source = FakeToolSource(listOf(externalTool("git_discard")))
        val exec = composite(FakeBaseExecutor(emptyList()), source, gate)

        val token = parse(runBlocking { exec.execute("git_discard", noArgs, null) })[
            VoiceConfirmationGate.CONFIRM_ARG
        ]!!.jsonPrimitive.content
        gate.agentSpoke(); gate.userSpoke()
        runBlocking { exec.execute("git_discard", withToken(token), null) }

        val replay = parse(runBlocking { exec.execute("git_discard", withToken(token), null) })
        assertEquals(true, replay["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertNotEquals(token, replay[VoiceConfirmationGate.CONFIRM_ARG]!!.jsonPrimitive.content)
        assertEquals(1, source.calls.size)
    }

    /**
     * Single use has to survive concurrency, because concurrency is the normal case: the controller
     * admits four tool calls at once. Two calls sharing a tool, arguments and token could both pass
     * the checks before either consumed the token, and both run an irreversible operation off one
     * confirmation.
     */
    @Test
    fun `two concurrent redemptions of one token allow exactly one`() {
        // Straight at the gate rather than through the executor: the race is a few instructions
        // wide, so it wants many tightly-synchronised attempts, and driving 200 of those through a
        // full enumeration would load the machine enough to time out unrelated tests in the next
        // class — which it did, the first time this was written.
        repeat(200) { attempt ->
            val gate = VoiceConfirmationGate()
            val args = JsonObject(emptyMap())
            val token = (gate.redeem("git_discard", args, null)
                as VoiceConfirmationGate.Decision.Confirm).token
            gate.agentSpoke()
            gate.userSpoke()

            val barrier = java.util.concurrent.CyclicBarrier(2)
            val allowed = java.util.concurrent.atomic.AtomicInteger()
            val threads = (1..2).map {
                Thread {
                    barrier.await()
                    if (gate.redeem("git_discard", args, token) is VoiceConfirmationGate.Decision.Allowed) {
                        allowed.incrementAndGet()
                    }
                }.apply { start() }
            }
            threads.forEach { it.join(5_000) }

            assertEquals(1, allowed.get(), "attempt $attempt: ${allowed.get()} calls cleared one token")
        }
    }

    /**
     * "The confirmation argument is the host's, not the tool's" has to hold even when the source
     * happens to declare a parameter of that name — otherwise the allowlist, which is the source's
     * own declared keys, hands the host's token straight back to it.
     */
    @Test
    fun `a source declaring its own voice_confirm never receives the host's token`() {
        val gate = VoiceConfirmationGate()
        val source = FakeToolSource(
            listOf(
                externalTool(
                    "git_discard",
                    properties = props("path", VoiceConfirmationGate.CONFIRM_ARG),
                )
            )
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source, gate)

        val args = buildJsonObject { put("path", "docs") }
        val token = parse(runBlocking { exec.execute("git_discard", args, null) })[
            VoiceConfirmationGate.CONFIRM_ARG
        ]!!.jsonPrimitive.content
        gate.agentSpoke(); gate.userSpoke()

        runBlocking {
            exec.execute(
                "git_discard",
                buildJsonObject { put("path", "docs"); put(VoiceConfirmationGate.CONFIRM_ARG, token) },
                null,
            )
        }
        assertEquals(setOf("path"), source.calls.single().second.keys)
    }

    /** Confirming "discard the docs folder" must not authorise discarding the repository. */
    @Test
    fun `a token is bound to the arguments it was minted for`() {
        val gate = VoiceConfirmationGate()
        val source = FakeToolSource(listOf(externalTool("git_discard", properties = props("path"))))
        val exec = composite(FakeBaseExecutor(emptyList()), source, gate)

        val minted = runBlocking { exec.execute("git_discard", buildJsonObject { put("path", "docs") }, null) }
        val token = parse(minted)[VoiceConfirmationGate.CONFIRM_ARG]!!.jsonPrimitive.content
        gate.agentSpoke(); gate.userSpoke()

        val swapped = parse(
            runBlocking {
                exec.execute(
                    "git_discard",
                    buildJsonObject { put("path", "/"); put(VoiceConfirmationGate.CONFIRM_ARG, token) },
                    null,
                )
            }
        )
        assertEquals(true, swapped["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(swapped["instruction"]!!.jsonPrimitive.content.contains("different operation"))
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `a fabricated token does not pass`() {
        val gate = VoiceConfirmationGate()
        val source = FakeToolSource(listOf(externalTool("git_discard")))
        val exec = composite(FakeBaseExecutor(emptyList()), source, gate)
        gate.agentSpoke(); gate.userSpoke()

        val answer = parse(runBlocking { exec.execute("git_discard", withToken("0123456789abcdef"), null) })
        assertEquals(true, answer["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `an expired token does not pass`() {
        var now = 0L
        val gate = VoiceConfirmationGate(nowMs = { now })
        val source = FakeToolSource(listOf(externalTool("git_discard")))
        val exec = composite(FakeBaseExecutor(emptyList()), source, gate)

        val token = parse(runBlocking { exec.execute("git_discard", noArgs, null) })[
            VoiceConfirmationGate.CONFIRM_ARG
        ]!!.jsonPrimitive.content
        gate.agentSpoke(); gate.userSpoke()
        now += VoiceConfirmationGate.TOKEN_TTL_MS + 1

        val answer = parse(runBlocking { exec.execute("git_discard", withToken(token), null) })
        assertEquals(true, answer["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `hanging up clears outstanding confirmations`() {
        val gate = VoiceConfirmationGate()
        val source = FakeToolSource(listOf(externalTool("git_discard")))
        val exec = composite(FakeBaseExecutor(emptyList()), source, gate)

        val token = parse(runBlocking { exec.execute("git_discard", noArgs, null) })[
            VoiceConfirmationGate.CONFIRM_ARG
        ]!!.jsonPrimitive.content
        gate.agentSpoke(); gate.userSpoke()
        exec.dispose()

        val answer = parse(runBlocking { exec.execute("git_discard", withToken(token), null) })
        assertEquals(true, answer["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(source.calls.isEmpty())
    }

    // ------------------------------------------------- tier 2, host approval

    @Test
    fun `a host approver replaces the in-band protocol`() {
        val asked = mutableListOf<String>()
        val source = FakeToolSource(
            list = listOf(externalTool("git_discard")),
            policy = VoiceToolPolicy(approve = { name, _ -> asked += name; true }),
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        // No token, no speech: the host said yes, and that is a better answer than either.
        assertEquals("""{"from":"source"}""", runBlocking { exec.execute("git_discard", noArgs, null) })
        assertEquals(listOf("git_discard"), asked)
    }

    @Test
    fun `a refusing or broken approver stops the call`() {
        val no = FakeToolSource(
            list = listOf(externalTool("git_discard")),
            policy = VoiceToolPolicy(approve = { _, _ -> false }),
        )
        val execNo = composite(FakeBaseExecutor(emptyList()), no)
        assertEquals(true, parse(runBlocking { execNo.execute("git_discard", noArgs, null) })["refused"]
            ?.jsonPrimitive?.content?.toBoolean())
        assertTrue(no.calls.isEmpty())

        val broken = FakeToolSource(
            list = listOf(externalTool("git_discard")),
            policy = VoiceToolPolicy(approve = { _, _ -> error("dialog blew up") }),
        )
        val execBroken = composite(FakeBaseExecutor(emptyList()), broken)
        assertEquals(true, parse(runBlocking { execBroken.execute("git_discard", noArgs, null) })["refused"]
            ?.jsonPrimitive?.content?.toBoolean())
        assertTrue(broken.calls.isEmpty())
    }

    // ------------------------------------------------------------ classifier

    @Test
    fun `the destructive pattern catches the obvious names and admits it cannot catch the rest`() {
        for (name in listOf("git_discard", "plugin_disable", "role_delete", "user_role_remove", "secret_delete")) {
            assertTrue(VoiceToolPolicy.looksIrreversible(name), name)
        }
        // Documented limitation, asserted so it cannot quietly become false: these are as
        // destructive as anything on the surface and their names say nothing. Only the source's own
        // flag or the embedder's rule reaches them.
        for (name in listOf("rpa_run", "evolver_evolve")) {
            assertFalse(VoiceToolPolicy.looksIrreversible(name), "$name is not name-classifiable")
        }
        val source = FakeToolSource(
            list = listOf(externalTool("rpa_run"), externalTool("evolver_evolve")),
            policy = VoiceToolPolicy(irreversibleExtra = { it.name in setOf("rpa_run", "evolver_evolve") }),
        )
        val defs = composite(FakeBaseExecutor(emptyList()), source).tools()
        assertTrue(defs.all { it.description.contains("cannot be undone") }, defs.map { it.description }.toString())
    }

    @Test
    fun `a throwing destructive rule fails closed too`() {
        val source = FakeToolSource(
            list = listOf(externalTool("git_status")),
            policy = VoiceToolPolicy(irreversibleExtra = { error("embedder bug") }),
        )
        val def = composite(FakeBaseExecutor(emptyList()), source).tools().single()
        assertTrue(def.description.contains("cannot be undone"), def.description)
    }

    /**
     * The barge-in hole the agent-turn requirement closes.
     *
     * The user is already talking when the model emits its call, so `speech_stopped` fires after the
     * mint from the very utterance that caused it. "The user has spoken" was literally true and the
     * agent had announced nothing.
     */
    @Test
    fun `the tail of the utterance that triggered the call does not confirm it`() {
        val gate = VoiceConfirmationGate()
        val source = FakeToolSource(listOf(externalTool("git_discard")))
        val exec = composite(FakeBaseExecutor(emptyList()), source, gate)

        val token = parse(runBlocking { exec.execute("git_discard", noArgs, null) })[
            VoiceConfirmationGate.CONFIRM_ARG
        ]!!.jsonPrimitive.content

        // Barge-in: the user's interrupting utterance ends AFTER the token was minted.
        gate.userSpoke()

        val answer = parse(runBlocking { exec.execute("git_discard", withToken(token), null) })
        assertEquals(true, answer["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(source.calls.isEmpty(), "redeemed against an announcement that never happened")
    }

    /** Order, not just occurrence: the user has to answer the agent, not precede it. */
    @Test
    fun `a user turn before the agent's announcement does not count`() {
        val gate = VoiceConfirmationGate()
        val source = FakeToolSource(listOf(externalTool("git_discard")))
        val exec = composite(FakeBaseExecutor(emptyList()), source, gate)

        val token = parse(runBlocking { exec.execute("git_discard", noArgs, null) })[
            VoiceConfirmationGate.CONFIRM_ARG
        ]!!.jsonPrimitive.content
        gate.userSpoke()
        gate.agentSpoke() // agent speaks last: nobody has answered it

        val answer = parse(runBlocking { exec.execute("git_discard", withToken(token), null) })
        assertEquals(true, answer["confirmation_required"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(source.calls.isEmpty())
    }

    /**
     * An approval that outlives its slice is a refusal, and it must happen inside the tool's own
     * budget — a modal waiting *beside* the budget put the worst case past the controller's
     * watchdog, so a user who read the dialog slowly and pressed Approve got the tool cancelled
     * mid-flight while the model was told it had timed out.
     */
    @Test
    fun `a slow approver loses the round instead of overrunning the watchdog`() {
        val source = FakeToolSource(
            list = listOf(externalTool("git_discard")),
            policy = VoiceToolPolicy(approve = { _, _ -> kotlinx.coroutines.delay(60_000); true }),
        )
        // The whole budget is 200ms here; the approval slice is min(APPROVAL_MS, budget).
        val exec = composite(FakeBaseExecutor(emptyList()), source, callTimeoutMs = 200L)

        val started = System.currentTimeMillis()
        val answer = parse(runBlocking { exec.execute("git_discard", noArgs, null) })
        val elapsed = System.currentTimeMillis() - started

        assertEquals(true, answer["refused"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(elapsed < 5_000, "the approval wait was not bounded by the budget (${elapsed}ms)")
        assertTrue(source.calls.isEmpty())
    }

    /**
     * The actual invariant behind the approval fix: approval and the call it authorises come out of
     * ONE budget. Spending 250ms in the modal must leave the tool 150ms of a 400ms budget, not hand
     * it a fresh 400ms on top — which is exactly how the worst case reached 220s against a 120s
     * watchdog.
     */
    @Test
    fun `approval spends the tool's budget rather than adding to it`() {
        val source = FakeToolSource(
            list = listOf(externalTool("git_discard")),
            policy = VoiceToolPolicy(approve = { _, _ -> kotlinx.coroutines.delay(250); true }),
            onCall = { _, _ -> kotlinx.coroutines.delay(250); """{"ok":true}""" },
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source, callTimeoutMs = 400L)

        val failure = assertFailsWith<VoiceToolException> {
            runBlocking { exec.execute("git_discard", noArgs, null) }
        }
        assertTrue(failure.message!!.contains("timed out"), failure.message!!)
    }

    /** The approval rung has to fit inside the tool budget it is a slice of. */
    @Test
    fun `the approval slice is smaller than the budget it comes out of`() {
        for (tool in VoiceToolTimeouts.LADDER_SAMPLES) {
            assertTrue(
                VoiceToolTimeouts.APPROVAL_MS < VoiceToolTimeouts.execMs(tool),
                "approval must leave the tool some of its own budget for $tool",
            )
            assertTrue(
                VoiceToolTimeouts.execMs(tool) < VoiceToolTimeouts.inAppMs(tool),
                "approval + call must still land inside the in-app watchdog for $tool",
            )
        }
    }

    /** Under prefixing, the advertised form carries the prefix — refusal has to know that. */
    @Test
    fun `an excluded tool is refused by its prefixed name too`() {
        val source = FakeToolSource(
            list = listOf(externalTool("secret_get")),
            namePrefix = "boss_",
            policy = VoiceToolPolicy(onNameCollision = VoiceToolCollisionPolicy.PrefixExternal),
        )
        val exec = composite(FakeBaseExecutor(emptyList()), source)

        for (named in listOf("secret_get", "boss_secret_get")) {
            val failure = assertFailsWith<VoiceToolException> {
                runBlocking { exec.execute(named, noArgs, null) }
            }
            assertTrue(failure.message!!.contains("secret material"), "$named: ${failure.message}")
        }
        assertTrue(source.calls.isEmpty())
    }

    private fun withToken(token: String) = buildJsonObject { put(VoiceConfirmationGate.CONFIRM_ARG, token) }

    private fun parse(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
}
