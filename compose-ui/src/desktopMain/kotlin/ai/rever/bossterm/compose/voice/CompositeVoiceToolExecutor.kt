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
    /** Injected so a slow approval can be exercised without one. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : VoiceToolExecutor {

    private val log = LoggerFactory.getLogger(CompositeVoiceToolExecutor::class.java)

    /**
     * The last list the source successfully returned.
     *
     * Used only when an enumeration fails, so a source that blips for one call does not blank its
     * whole surface — the model would then get "unknown tool" for something it can see in its own
     * list. Not a cache: a successful enumeration always replaces it, including a successful empty
     * one (a plugin really being unloaded). Paired with the base names it was merged against, so a
     * stale surface cannot be reused once BossTerm's own set has moved under it.
     */
    @Volatile private var lastGood: Pair<Set<String>, ExternalSurface>? = null

    /** See [externalAdvertisedNames]. */
    @Volatile private var externalNames: Set<String> = emptySet()

    override fun tools(): List<VoiceToolDef> {
        val baseTools = base.tools()
        val ext = external(baseTools)
        externalNames = ext.advertised.mapTo(mutableSetOf()) { it.advertisedName }
        return (baseTools + ext.advertised.map { it.def }).filter { legalName(it.name) }
    }

    /**
     * The advertised names that came from the embedder, as of the last [tools] call.
     *
     * Only this class can answer it: [base] does not know the source exists, and the caller cannot
     * tell an embedder tool from one of BossTerm's own by looking at a name. Cached from [tools]
     * rather than recomputed, because the one caller asks for it immediately after configuring the
     * session and re-enumerating would run the whole merge again for a set already in hand.
     */
    override fun externalAdvertisedNames(): Set<String> = externalNames

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
        // Off the Default dispatcher, for the reason the call below is: [enumerate] BLOCKS, up to
        // enumerateTimeoutMs, and this runs on the coroutine handling the function call — which
        // HostVoiceCall puts on Dispatchers.Default, along with the call's state machine, the drain
        // watcher and the limit ticker. Four concurrent tool calls (MAX_IN_FLIGHT_TOOLS) against a
        // wedged source would otherwise hold four Default threads for two seconds each, which on a
        // small machine is the whole pool. tools() has no such option — it is a non-suspend override
        // — but nothing in a call's hot path goes through it.
        val surface = withContext(Dispatchers.IO) { external(base.tools()) }
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
        // Minus the confirmation argument, always. It is normally absent from the source's schema and
        // so dropped by the allowlist for free — but a source that happens to declare a parameter of
        // that name would otherwise be handed the host's token, and "the confirmation argument is
        // the host's, not the tool's" has to hold whatever the source calls its parameters.
        val declared = hit.source.properties.keys
        val clean = buildJsonObject {
            args.forEach { (k, v) -> if (k in declared && k != VoiceConfirmationGate.CONFIRM_ARG) put(k, v) }
        }

        // ONE budget for the whole gated round-trip. An approval wait beside the tool's own timeout
        // rather than inside it is what made the worst case 220s against a 120s watchdog — see
        // VoiceToolTimeouts.APPROVAL_MS. Whatever the modal spends, the call gets the rest.
        val budgetMs = callTimeoutMs(hit.source.name)
        val startedAtMs = nowMs()
        if (hit.gated) {
            val approvalBudget = minOf(VoiceToolTimeouts.APPROVAL_MS, budgetMs)
            confirmationRefusal(hit, args, clean, approvalBudget)?.let { return it }
        }
        val remainingMs = budgetMs - (nowMs() - startedAtMs)
        if (remainingMs <= 0) throw VoiceToolException("Tool $name timed out")

        val result: String? = try {
            withTimeoutOrNull(remainingMs) {
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
        approvalBudgetMs: Long,
    ): String? {
        // Both on IO, for the reason source.call is: an approver IS a modal dialog, and dialogs
        // block — invokeAndWait, runBlocking, showConfirmDialog — rather than suspend. So
        // withTimeoutOrNull has no suspension point to cancel at, the budget does not bind, and the
        // thread stays pinned for as long as the dialog is up. Four of those on Dispatchers.Default
        // is four of the threads the call's own state machine, drain watcher and limit ticker run
        // on. Off that dispatcher it is a slow tool call; on it, it is the call freezing. (The
        // policy getter goes with it: it is embedder code outside the enumeration deadline.)
        val approve = withContext(Dispatchers.IO) { runCatching { source.policy.approve }.getOrNull() }
        if (approve != null) {
            val ok = try {
                withTimeoutOrNull(approvalBudgetMs) {
                    withContext(Dispatchers.IO) { approve(hit.source.name, clean) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.warn("Voice tool approval failed for {}: {}", hit.source.name, t.toString())
                false
            }
            // Null (timed out) and false are the same answer, and it is the safe one: an approval
            // nobody gave is not an approval.
            if (ok == true) return null
            // A modal that overran its slice loses the round rather than the call: the model is
            // told it was not approved, which is true, and it can ask again.
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


    private fun external(baseTools: List<VoiceToolDef>): ExternalSurface {
        val baseNames = baseTools.mapTo(mutableSetOf()) { it.name }
        return enumerate(baseNames)
    }

    /**
     * The merged external surface, or the last good one if the source cannot produce it in time.
     *
     * The WHOLE merge runs inside the deadline, not just [VoiceToolSource.tools]. Everything here is
     * embedder code — the `namePrefix` and `policy` getters, and `excludeExtra`/`irreversibleExtra`
     * once per tool, up to a hundred times — and `runCatching` answers a predicate that THROWS while
     * doing nothing at all about one that hangs. The isolation argument for a hot-swappable
     * classloader applies to a predicate exactly as much as to `tools()`.
     *
     * Where that mattered most is session config: `sessionUpdate` calls `executor.tools()` on the
     * connect coroutine, with the socket already open and billing and `startLimits()` not yet armed,
     * and that path has a try/catch but no watchdog. A wedged classifier there hung the call at
     * "Calling…" with nothing to end it.
     *
     * A [VoiceToolSource] enumerates synchronously — the shape an embedder's registry actually has —
     * so a hang can only be handled by not waiting on it: the calling thread gives up at the
     * deadline and the wedged one is left to the JVM, which is why the pool is cached and its
     * threads are daemons.
     */
    private fun enumerate(baseNames: Set<String>): ExternalSurface {
        val future = try {
            ENUMERATION.submit(
                Callable {
                    // The getters are still guarded individually, not just by the deadline: one that
                    // THROWS should degrade to the default policy — which still excludes secret
                    // names — rather than cost the whole external surface. The deadline is what
                    // handles the other half, a getter that hangs.
                    mergeExternalTools(
                        baseNames = baseNames,
                        external = source.tools(),
                        prefix = runCatching { source.namePrefix }.getOrDefault(""),
                        policy = runCatching { source.policy }.getOrDefault(VoiceToolPolicy()),
                    )
                }
            )
        } catch (t: Throwable) {
            log.warn("Could not enumerate voice tool source: {}", t.toString())
            return lastGoodFor(baseNames)
        }
        return try {
            future.get(enumerateTimeoutMs, TimeUnit.MILLISECONDS).also {
                lastGood = baseNames to it
            }
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            future.cancel(true)
            log.warn("Voice tool source enumeration failed: {}", t.toString())
            lastGoodFor(baseNames)
        }
    }

    /**
     * The cached surface, but only if it was merged against the SAME base names.
     *
     * A surface is merged relative to what BossTerm advertises, and that moves — `manage_tools` can
     * disable a tool mid-call. Reusing one computed against a different base could let an external
     * tool occupy a name BossTerm has since taken back, which is the one thing the merge exists to
     * prevent. Cheap to be strict: the base set changes rarely, and the alternative to reuse is an
     * empty external surface for one call.
     */
    private fun lastGoodFor(baseNames: Set<String>): ExternalSurface {
        val (names, surface) = lastGood ?: return ExternalSurface.EMPTY
        return if (names == baseNames) surface else ExternalSurface.EMPTY
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

        /**
         * Daemon threads: one wedged enumeration must not keep the JVM alive at shutdown.
         *
         * Cached and unbounded, deliberately. A source that ignores interrupts leaks one thread per
         * enumeration for the life of the process, which is real — but the alternative is worse: a
         * bounded pool turns that leak into a stall, where the first wedged enumeration takes the
         * external surface down with it for every later call. An unbounded pool of daemon threads
         * degrades; a bounded one fails. The cached pool reaps anything idle for a minute, so the
         * leak is exactly as large as the number of enumerations a broken source was asked for.
         */
        private val ENUMERATION = Executors.newCachedThreadPool { r ->
            Thread(r, "voice-tool-source").apply { isDaemon = true }
        }
    }
}
