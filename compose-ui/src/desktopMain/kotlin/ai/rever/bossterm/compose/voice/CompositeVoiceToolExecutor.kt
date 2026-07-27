package ai.rever.bossterm.compose.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * BossTerm's own voice tools plus an embedder's, as one executor.
 *
 * A decorator rather than a branch inside [GuiVoiceToolExecutor], for two reasons. The share path
 * constructs that class too, and shares must keep the curated catalog — a flag inside it would be
 * one `if` away from leaking a hundred host tools to a guest on a link. And everything this class
 * does (enumerate, merge, gate, isolate) is about the *boundary* to embedder code, which is a
 * different subject from resolving a tab id.
 *
 * ### Dynamic, not snapshotted
 *
 * [VoiceToolSource.tools] is re-read on every enumeration: once when the session is configured, and
 * again at the top of every [execute]. BossConsole's plugins register and unregister at runtime —
 * hot reload is a normal event there, not an exception — so a list captured at call start would go
 * stale within the call.
 *
 * What that does and does not buy, precisely, because the halves differ:
 *
 *  - **Enforcement is live.** A tool the source stops offering mid-call stops being callable at
 *    once; one it starts offering becomes callable at once.
 *  - **Advertisement is not.** The `tools` array reaches OpenAI once, in `session.update`. A tool
 *    added mid-call is therefore callable but unknown to the model, and one removed mid-call is
 *    still on the model's list until it tries it and is told the tool is gone. Re-sending
 *    `session.update` on every change was the alternative and is worse: it is a whole session
 *    reconfiguration mid-conversation, on a surface where a rejected `session.*` parameter is a
 *    fatal error, and it would fire on every plugin reload. Being wrong in the direction of "the
 *    agent hears 'that tool is not available' and moves on" is the cheap failure.
 *
 * ### Isolation
 *
 * The source is embedder code — in BossConsole's case, plugin code behind a hot-swappable
 * classloader — reached from inside a live phone call. It is treated accordingly: enumeration runs
 * on a pool thread with a deadline and falls back to the last good list, and invocation gets the
 * existing [VoiceToolTimeouts] rung.
 *
 * Both catch [Throwable] rather than [Exception], because a hot-reloaded classloader's
 * characteristic failure is a [LinkageError] rather than an exception — but only one of the two
 * needs it. [execute] calls the source directly, so an `Error` lands in that frame; [enumerate]
 * goes through a [java.util.concurrent.Future], which boxes anything the callable threw in an
 * `ExecutionException`. Said here because a mutation test proved it: narrowing the enumeration
 * catch to `Exception` changes nothing and no test can see it, while narrowing the invocation one
 * is caught immediately.
 */
internal class CompositeVoiceToolExecutor(
    private val base: VoiceToolExecutor,
    private val source: VoiceToolSource,
    /** Shared with [HostVoiceCallController], which pumps it — see [VoiceConfirmationGate]. */
    private val confirmations: VoiceConfirmationGate,
    private val enumerateTimeoutMs: Long = DEFAULT_ENUMERATE_TIMEOUT_MS,
    /** The innermost rung of the existing ladder; the controller's watchdog sits outside it. */
    private val callTimeoutMs: (String) -> Long = { VoiceToolTimeouts.execMs(it) },
) : VoiceToolExecutor {

    private val log = LoggerFactory.getLogger(CompositeVoiceToolExecutor::class.java)

    /**
     * The last list the source successfully returned.
     *
     * Used only when an enumeration fails, so a source that blips for one call does not blank its
     * whole surface — the model would then get "unknown tool" for something it can see in its own
     * list. Not a cache: a successful enumeration always replaces it, including a successful empty
     * one (a plugin really being unloaded).
     */
    @Volatile private var lastGood: List<ExternalVoiceTool>? = null

    override fun tools(): List<VoiceToolDef> {
        val baseTools = base.tools()
        return (baseTools + external(baseTools).advertised.map { it.def }).filter { legalName(it.name) }
    }

    /**
     * Last gate before a name reaches OpenAI.
     *
     * An illegal function name does not fail as one broken tool: the whole `session.update` is
     * rejected, so EVERY tool disappears, the call dies, and the cause is a `session.tools[n].name`
     * string in a Realtime error field. Worth a check at the point the array is assembled.
     *
     * The external half is sanitised at the merge and cannot fail this. The BASE half can, today:
     * [ai.rever.bossterm.compose.mcp.BossTermMcpConfig.toolNamePrefix] is embedder input,
     * `BossTermMcpServer.toolName` concatenates it straight onto every built-in name, and nothing
     * validates it — so a prefix with a space in it names all thirteen illegally. Dropping the
     * offenders and keeping the call is strictly better than losing the surface, and it is only
     * half a fix: the standalone path (no [VoiceToolSource], so no decorator) still has the flaw,
     * which belongs with the config rather than here.
     */
    private fun legalName(name: String): Boolean {
        if (VoiceToolNaming.LEGAL.matches(name)) return true
        logOnce(
            log, "illegal:$name",
            "Voice tool \"$name\" is not a legal OpenAI function name and is withheld; the whole " +
                "session would otherwise be rejected. Check the embedder's MCP toolNamePrefix.",
        )
        return false
    }

    override fun contextSnapshot(defaultTabId: String?): String = base.contextSnapshot(defaultTabId)

    override fun dispose() {
        // The source belongs to the embedder and outlives the call; the tokens do not.
        confirmations.clear()
        base.dispose()
    }

    override suspend fun execute(name: String, args: JsonObject, defaultTabId: String?): String {
        val surface = external(base.tools())
        val hit = surface.byAdvertisedName[name]
        if (hit == null) {
            // Refuse by NAME, not merely by absence. A hard-excluded tool must be answered as
            // refused even if the model produces the name from somewhere else — an older session, a
            // guess off a sibling tool, or something it read in the terminal. "Unknown tool" would
            // read as a glitch worth retrying.
            surface.refusalFor(name)?.let {
                log.warn("Voice tool {} refused: {}", name, it)
                throw VoiceToolException(it)
            }
            return base.execute(name, args, defaultTabId)
        }

        // Only the keys the tool declared, exactly as the base executor does with its own schemas.
        // This is also what strips `voice_confirm` before the source ever sees it: the confirmation
        // argument is the host's, not the tool's.
        val declared = hit.source.properties.keys
        val clean = buildJsonObject { args.forEach { (k, v) -> if (k in declared) put(k, v) } }

        if (hit.gated) {
            confirmationRefusal(hit, args, clean)?.let { return it }
        }

        val result: String? = try {
            withTimeoutOrNull(callTimeoutMs(hit.source.name)) {
                // The embedder's call may block; keep that off the Default dispatcher the call's
                // state machine runs on. It also means the timeout above can only fire if the source
                // suspends — a source that blocks outright is caught by the controller's watchdog,
                // which is the rung above this one and answers the model regardless.
                withContext(Dispatchers.IO) { source.call(hit.source.name, clean) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Voice tool source failed on {}: {}", hit.source.name, t.toString())
            return buildJsonObject {
                put("error", "That tool failed: ${t.message ?: t.javaClass.simpleName}")
            }.toString()
        }
        if (result == null) throw VoiceToolException("Tool $name timed out")
        // Clamped here as well as in the controller: the seam is what makes embedder output reach a
        // wire at all, so bounding it is this class's job rather than something it inherits.
        return clampToolResult(name, result.ifBlank { "{}" })
    }

    /**
     * Run the confirmation tier, returning the payload to answer with when the call may NOT proceed.
     *
     * A JSON result rather than an exception: the agent is being asked to do something (say what it
     * is about to do, then call again), and `{"error": …}` invites it to report a failure instead.
     */
    private suspend fun confirmationRefusal(
        hit: AdvertisedExternalTool,
        rawArgs: JsonObject,
        clean: JsonObject,
    ): String? {
        val approve = runCatching { source.policy.approve }.getOrNull()
        if (approve != null) {
            val ok = try {
                withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { approve(hit.source.name, clean) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.warn("Voice tool approval failed for {}: {}", hit.source.name, t.toString())
                false
            }
            // Null (timed out) and false are the same answer, and it is the safe one: an approval
            // nobody gave is not an approval.
            if (ok == true) return null
            return buildJsonObject {
                put("refused", true)
                put("reason", "The user did not approve that.")
            }.toString()
        }
        val token = (rawArgs[VoiceConfirmationGate.CONFIRM_ARG] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        return when (val decision = confirmations.redeem(hit.source.name, clean, token)) {
            is VoiceConfirmationGate.Decision.Allowed -> null
            is VoiceConfirmationGate.Decision.Confirm -> buildJsonObject {
                put("confirmation_required", true)
                put("operation", hit.advertisedName)
                put(VoiceConfirmationGate.CONFIRM_ARG, decision.token)
                put(
                    "instruction",
                    decision.reason + " Say out loud exactly what you are about to do, wait for " +
                        "the user to answer, then call ${hit.advertisedName} again with " +
                        "${VoiceConfirmationGate.CONFIRM_ARG} set to the value above.",
                )
            }.toString()
        }
    }

    /** The external half of the surface for this enumeration. */
    private fun external(baseTools: List<VoiceToolDef>): ExternalSurface = mergeExternalTools(
        baseNames = baseTools.mapTo(mutableSetOf()) { it.name },
        external = enumerate(),
        prefix = runCatching { source.namePrefix }.getOrDefault(""),
        policy = runCatching { source.policy }.getOrDefault(VoiceToolPolicy()),
    )

    /**
     * The source's live tool list, or the last good one if it cannot produce one in time.
     *
     * A [VoiceToolSource] enumerates synchronously — that is the shape an embedder's registry
     * actually has, and making it suspend would push a coroutine boundary into every implementation
     * to buy nothing. Which means a hang has to be handled by not waiting on it, hence the pool: the
     * calling thread gives up at the deadline and the wedged one is left to the JVM. That thread is
     * genuinely leaked until the source returns, which is why the pool is cached and its threads are
     * daemons.
     */
    private fun enumerate(): List<ExternalVoiceTool> {
        val future = try {
            ENUMERATION.submit(Callable { source.tools() })
        } catch (t: Throwable) {
            log.warn("Could not enumerate voice tool source: {}", t.toString())
            return lastGood.orEmpty()
        }
        return try {
            future.get(enumerateTimeoutMs, TimeUnit.MILLISECONDS).also { lastGood = it }
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            future.cancel(true)
            log.warn("Voice tool source enumeration failed: {}", t.toString())
            lastGood.orEmpty()
        }
    }

    internal companion object {

        /**
         * How long an enumeration may take before its result is written off.
         *
         * Generous for what this should be — a read of a registry the embedder already holds in
         * memory — and still short enough that the two places it runs (configuring the session, and
         * the top of each tool call) do not become the reason a call feels slow.
         */
        const val DEFAULT_ENUMERATE_TIMEOUT_MS = 2_000L

        /** A host approval dialog nobody answers must not hold a tool slot for the whole call. */
        const val APPROVAL_TIMEOUT_MS = 120_000L

        /** Daemon threads: one wedged enumeration must not keep the JVM alive at shutdown. */
        private val ENUMERATION = Executors.newCachedThreadPool { r ->
            Thread(r, "voice-tool-source").apply { isDaemon = true }
        }
    }
}
