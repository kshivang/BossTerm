package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Doubles for the embedder seam.
 *
 * Deliberately no MCP server and no live terminal: everything these fixtures stand in for is either
 * an interface BossTerm defines ([VoiceToolExecutor], [VoiceToolSource]) or a pure merge, and the
 * paths worth pinning here — exclusion, renaming, the confirmation interlock, what a hung source
 * does — are the ones a real server would only make slower and less deterministic to reach.
 */

/** A JSON schema `properties` object with one string property per name. */
internal fun props(vararg names: String): JsonObject = buildJsonObject {
    names.forEach { putJsonObject(it) { put("type", "string") } }
}

internal fun externalTool(
    name: String,
    description: String = "does $name",
    properties: JsonObject = props("arg"),
    required: List<String> = emptyList(),
    write: Boolean = false,
    irreversible: Boolean = false,
    sensitive: Boolean = false,
) = ExternalVoiceTool(name, description, properties, required, write, irreversible, sensitive)

/**
 * BossTerm's half of the surface.
 *
 * The default is deliberately NON-EMPTY and deliberately overlaps the names tests give their fake
 * sources. Almost every tier-1 and ceiling test used to pass `emptyList()`, and that is precisely
 * what hid the bug where an excluded or dropped external name made BossTerm's own tool of that name
 * refuse for the rest of the call: with no base tools there was nothing to shadow. A base that
 * overlaps by default checks the invariant everywhere by construction instead of in the one test
 * that thought to look.
 */
internal class FakeBaseExecutor(
    private val names: List<String> = listOf("run_command", "read_scrollback"),
    private val result: String = """{"from":"base"}""",
) : VoiceToolExecutor {

    val executed = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    var disposed = false
        private set

    override fun tools(): List<VoiceToolDef> = names.map {
        VoiceToolDef(
            name = it,
            description = "base $it",
            parameters = buildJsonObject { put("type", "object") },
            write = false,
        )
    }

    override fun contextSnapshot(defaultTabId: String?): String = "base snapshot"

    override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
        if (name !in names) throw VoiceToolException("Unknown tool: $name")
        executed += name to args
        return result
    }

    override fun dispose() {
        disposed = true
    }
}

/** The embedder's half. [list] is `var` so a test can register a tool mid-call. */
internal class FakeToolSource(
    @Volatile var list: List<ExternalVoiceTool>,
    override val namePrefix: String = "",
    override val policy: VoiceToolPolicy = VoiceToolPolicy(),
    private val enumerate: (() -> Unit)? = null,
    private val onCall: suspend (String, JsonObject) -> String = { _, _ -> """{"from":"source"}""" },
) : VoiceToolSource {

    val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    override fun tools(): List<ExternalVoiceTool> {
        enumerate?.invoke()
        return list
    }

    override suspend fun call(name: String, args: JsonObject): String {
        calls += name to args
        return onCall(name, args)
    }
}

/** The executor under test, with test-sized deadlines. */
internal fun composite(
    base: VoiceToolExecutor,
    source: VoiceToolSource,
    gate: VoiceConfirmationGate = VoiceConfirmationGate(),
    enumerateTimeoutMs: Long = 1_000L,
    callTimeoutMs: Long = 1_000L,
    /**
     * The executor's clock. Pass a scripted one to make a budget test deterministic: a test
     * that spends budget with a real `delay` is asserting on the CI runner's scheduler, not
     * on the executor.
     */
    nowMs: () -> Long = { System.currentTimeMillis() },
) = CompositeVoiceToolExecutor(
    base = base,
    source = source,
    confirmations = gate,
    enumerateTimeoutMs = enumerateTimeoutMs,
    callTimeoutMs = { callTimeoutMs },
    nowMs = nowMs,
)
