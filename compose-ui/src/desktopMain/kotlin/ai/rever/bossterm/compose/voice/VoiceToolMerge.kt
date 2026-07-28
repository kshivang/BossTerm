package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** One external tool as the model sees it, plus the mapping back to the source. */
internal class AdvertisedExternalTool(
    /** What OpenAI is told the tool is called. */
    val advertisedName: String,
    /** The source's own tool, with the source's own name — what [VoiceToolSource.call] gets back. */
    val source: ExternalVoiceTool,
    /** Confirmation-gated ([VoiceToolPolicy.isIrreversible]). */
    val gated: Boolean,
    defProvider: () -> VoiceToolDef,
) {
    /** The advertised definition, schema and all. Built on demand — only [tools] needs it. */
    val def: VoiceToolDef by lazy(LazyThreadSafetyMode.PUBLICATION, defProvider)
}

/**
 * The external half of one enumeration: what is advertised, and what is not and why.
 *
 * The two rejection sets are kept — rather than the survivors alone — because refusing a call has to
 * be able to say *which* rejection it was. "Unknown tool" for a hard-excluded `secret_get` reads as
 * a bug in the surface and invites a retry; "this tool is not available to the voice agent" is the
 * answer, and the difference is only expressible if the merge remembers.
 */
internal data class ExternalSurface(
    val advertised: List<AdvertisedExternalTool>,
    /**
     * Refused names → the phrase to say about them.
     *
     * Keyed by BOTH the source's own name and the one the tool would have been advertised under,
     * because the model can arrive at either: the advertised one from an earlier enumeration that
     * still offered the tool, the raw one from a sibling tool's description or the terminal itself.
     * Recorded at merge time rather than recomputed per lookup — recomputing meant re-deriving the
     * advertised form from the raw name, which is not even possible under
     * [VoiceToolCollisionPolicy.PrefixExternal], where that form carries a prefix the lookup knew
     * nothing about, so an excluded tool went unrefused by its prefixed name there.
     *
     * A phrase per name, not one phrase for the whole set: the agent says this out loud, and
     * "it returns secret material" is the wrong thing to say about a tool an embedder's own rule
     * happened to reject.
     */
    val refusals: Map<String, String>,
) {
    val byAdvertisedName: Map<String, AdvertisedExternalTool> = advertised.associateBy { it.advertisedName }

    companion object {
        /** What an enumeration that failed with no usable fallback contributes: nothing. */
        val EMPTY = ExternalSurface(emptyList(), emptyMap())
    }

    /** Why [name] is refused, or null if this surface has nothing to say about it. */
    fun refusalFor(name: String): String? = refusals[name]?.let { "$name $it" }
}

/**
 * OpenAI function names must match `^[a-zA-Z0-9_-]{1,64}$`, and the host has to be able to get from
 * the advertised name back to the source's own — so every transformation here is recorded in
 * [ExternalSurface.byAdvertisedName] rather than recomputed at call time.
 *
 * Nothing on BossConsole's 106-tool surface needs any of this today (the longest name is 25
 * characters and all of them are already legal). It is here because the seam accepts names from an
 * embedder's registry, and "an illegal name is rejected by OpenAI" fails as a rejected *session* —
 * every tool gone, the call dead, and the cause in a Realtime error field rather than anywhere
 * obvious.
 */
internal object VoiceToolNaming {

    const val MAX_NAME_LENGTH = 64

    /** OpenAI's grammar, asserted rather than assumed. */
    val LEGAL = Regex("^[a-zA-Z0-9_-]{1,64}$")

    /**
     * [raw] as a legal function name, or `""` when nothing usable is left.
     *
     * Overlong names keep a prefix of the original plus a hash of the whole thing: truncation alone
     * would map two long names that share a prefix onto one, and the collision loop would then hide
     * a real tool behind a `_2` suffix for no reason.
     */
    fun advertise(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (c in raw) {
                append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_')
            }
        }.trim('_')
        if (cleaned.isEmpty()) return ""
        if (cleaned.length <= MAX_NAME_LENGTH) return cleaned
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
        return cleaned.take(MAX_NAME_LENGTH - 9) + "_" + digest
    }
}

private val log = LoggerFactory.getLogger("ai.rever.bossterm.compose.voice.VoiceToolMerge")

/**
 * Merge an embedder's tools into the surface BossTerm already advertises.
 *
 * Pure, and separate from [CompositeVoiceToolExecutor], because every interesting decision in the
 * feature is here — what is excluded, what is renamed, what is dropped for the ceiling — and none of
 * them should need a live MCP server, a call, or a coroutine to test.
 *
 * @param baseNames the names BossTerm's own executor is advertising this enumeration.
 * @param external the source's live list, in its declared order (which the ceiling respects).
 */
internal fun mergeExternalTools(
    /** Namespaces the log dedupe so two sources in one process do not share slots. */
    sourceKey: String = "",
    baseNames: Set<String>,
    external: List<ExternalVoiceTool>,
    prefix: String,
    policy: VoiceToolPolicy,
): ExternalSurface {
    val advertised = mutableListOf<AdvertisedExternalTool>()
    val refusals = mutableMapOf<String, String>()
    val taken = baseNames.toMutableSet()

    /**
     * Record a rejection, under the source's own name AND the one it would have been advertised
     * under — but NEVER under a name BossTerm itself owns.
     *
     * The exception is the whole point. These sets are what [ExternalSurface.refusalFor] answers
     * from, and [CompositeVoiceToolExecutor.execute] consults it BEFORE falling through to the base
     * executor. So recording a base name here does not merely mis-explain a refusal: it makes
     * BossTerm's own tool uncallable for the rest of the call, while still advertised, because the
     * advertisement comes from `base.tools()` untouched.
     *
     * This was fixed once for the collision branch and left wrong on the other three exits, which is
     * how the ceiling could take out `run_command` purely on plugin registration order, and a
     * throwing `excludeExtra` could refuse all thirteen re-exported BossTerm tools with "it returns
     * secret material". One guard, used by every exit, instead of the same reasoning re-derived four
     * times — it has already been missed twice.
     */
    fun refuse(raw: String, wanted: String, reason: String) {
        if (raw !in baseNames) refusals[raw] = reason
        if (wanted.isNotEmpty() && wanted !in baseNames) refusals[wanted] = reason
    }

    val usePrefix = policy.onNameCollision == VoiceToolCollisionPolicy.PrefixExternal &&
        prefix.isNotBlank()
    if (policy.onNameCollision == VoiceToolCollisionPolicy.PrefixExternal && prefix.isBlank()) {
        logOnce(log, sourceKey + "blank-prefix", "Voice tool source asked for prefixing but declared no namePrefix; " +
            "colliding tools will be dropped instead")
    }

    // Computed before the exclusion check, not after, so a refused tool can be refused by the name
    // the model would have seen as well as by its own.
    fun wantedNameFor(tool: ExternalVoiceTool) =
        VoiceToolNaming.advertise(if (usePrefix) prefix + tool.name else tool.name)

    val seenSourceNames = mutableSetOf<String>()

    for (tool in external) {
        val wanted = wantedNameFor(tool)
        val exclusion = policy.exclusionReason(tool)
        if (exclusion != null) {
            refuse(tool.name, wanted, exclusion)
            logOnce(log, sourceKey + "excl:${tool.name}", "Voice tool ${tool.name} ${exclusion}")
            continue
        }
        if (advertised.size >= policy.maxExternalTools) {
            refuse(tool.name, wanted, VoiceToolPolicy.UNAVAILABLE_REASON)
            logOnce(log, sourceKey + "cap:${tool.name}", "Voice tool ${tool.name} dropped: over the " +
                "${policy.maxExternalTools}-tool ceiling for the external surface")
            continue
        }
        if (wanted.isEmpty()) {
            refuse(tool.name, wanted, VoiceToolPolicy.UNAVAILABLE_REASON)
            logOnce(log, sourceKey + "name:${tool.name}", "Voice tool ${tool.name} dropped: no legal function " +
                "name can be made from it")
            continue
        }
        // One tool, listed twice by its own source. Advertising the second as `git_status_2` would
        // offer the model two names for one implementation and call back with the same source name
        // for both — a duplicate, not a second tool.
        if (!seenSourceNames.add(tool.name)) {
            refuse(tool.name, wanted, VoiceToolPolicy.UNAVAILABLE_REASON)
            logOnce(log, sourceKey + "dup:${tool.name}", "Voice tool ${tool.name} dropped: the source listed " +
                "it more than once")
            continue
        }
        // THE DROP RULE. This is what [VoiceToolCollisionPolicy.DropExternal] means, and it was
        // documented here — in disambiguate's KDoc, no less — before it was written: without it a
        // colliding tool fell through to the suffix loop and was advertised as `run_command_2`. On
        // the surface this was measured against that is not a corner case but thirteen of the
        // hundred and six, each one a second route to the other implementation, past BossTerm's
        // scope check and focused-pane logic, under a name the agent's instructions never mention.
        if (!usePrefix && wanted in baseNames) {
            // refuse() filters both names out here by construction — `wanted` IS a base name — which
            // is exactly the invariant every other exit needs and used to lack.
            refuse(tool.name, wanted, VoiceToolPolicy.UNAVAILABLE_REASON)
            logOnce(log, sourceKey + "clash:${tool.name}", "Voice tool ${tool.name} dropped: BossTerm already " +
                "advertises $wanted, and its own implementation is the one the agent is told about")
            continue
        }
        val name = disambiguate(wanted, taken)
        if (name == null) {
            refuse(tool.name, wanted, VoiceToolPolicy.UNAVAILABLE_REASON)
            logOnce(log, sourceKey + "exhausted:${tool.name}", "Voice tool ${tool.name} dropped: $wanted is taken " +
                "and no distinct name was free")
            continue
        }
        // Only the SURPRISING renames. Under PrefixExternal every tool is renamed by design, and a
        // line per tool says nothing about 106 of them; what earns a line is a name that had to be
        // sanitised or disambiguated to fit.
        if (name != (if (usePrefix) prefix + tool.name else tool.name)) {
            logOnce(log, sourceKey + "rename:${tool.name}", "Voice tool ${tool.name} advertised as $name")
        }
        val schema = sanitisedSchema(sourceKey, tool)
        if (schema == null) {
            refuse(tool.name, wanted, VoiceToolPolicy.UNAVAILABLE_REASON)
            logOnce(log, sourceKey + "schema:${tool.name}", "Voice tool ${tool.name} dropped: none of its " +
                "declared parameters are JSON-schema objects")
            continue
        }
        taken += name
        val gated = policy.isIrreversible(tool)
        val hostApproves = policy.approve != null
        advertised += AdvertisedExternalTool(
            advertisedName = name,
            source = tool,
            gated = gated,
            // Lazily: execute() runs this whole merge before every tool call and reads only
            // byAdvertisedName/gated/source, so building 64 parameter schemas per call — inside a
            // live call — bought nothing. tools() forces them, which is the one caller that needs them.
            defProvider = { definitionFor(sourceKey, name, tool, gated, schema, hostApproves) },
        )
    }
    return ExternalSurface(advertised, refusals)
}

/** A tool's parameter schema, after everything unusable has been taken out of it. */
private class SanitisedSchema(val properties: JsonObject, val required: List<String>)

/**
 * [tool]'s schema reduced to what OpenAI will actually accept, or null if nothing coherent is left.
 *
 * [legalName] makes the argument for why this exists and then only applies it to the NAME: an
 * illegal function name does not fail as one broken tool, the whole `session.update` is rejected,
 * every tool disappears and the call dies with the cause in a Realtime error field. Exactly the same
 * is true of the rest of the tool object, and nothing checked it — [ExternalVoiceTool.properties] is
 * a raw `JsonObject` from embedder code and went straight into `parameters.properties`, so
 * `{"path": "string"}` (a string where a schema object belongs) killed the call by the same route,
 * before the watchdog that would explain it exists.
 *
 * Not hypothetical for the caller this was built for: the worked example casts `.jsonObject` over
 * whatever a plugin happened to register.
 *
 * Each property value must be a JSON object; the rest are dropped by name. `required` is intersected
 * with what survives, because a `required` entry naming a property that is not there is itself a
 * rejected session. A tool that declared parameters and has none left is dropped entirely — its
 * schema no longer describes it — while a tool that declared none in the first place is fine, since
 * a no-argument tool is a legitimate thing to be.
 */
private fun sanitisedSchema(sourceKey: String, tool: ExternalVoiceTool): SanitisedSchema? {
    val usable = tool.properties.filterValues { it is JsonObject }
    if (tool.properties.isNotEmpty() && usable.isEmpty()) return null
    if (usable.size != tool.properties.size) {
        val bad = tool.properties.keys - usable.keys
        logOnce(log, sourceKey + "badprops:${tool.name}", "Voice tool ${tool.name}: parameters $bad are not " +
            "JSON-schema objects and are withheld; OpenAI would reject the whole session for them")
    }
    val required = tool.required.filter { it in usable }
    if (required.size != tool.required.size) {
        logOnce(log, sourceKey + "badrequired:${tool.name}", "Voice tool ${tool.name}: required names " +
            "${tool.required.toSet() - required.toSet()} have no matching parameter and are dropped")
    }
    return SanitisedSchema(JsonObject(usable), required)
}

/**
 * [wanted] if it is free, else `wanted_2`… — and null once even that fails.
 *
 * By the time this runs the two collisions that should NOT be suffixed are already gone: a base
 * collision under the default policy, and a source that listed one tool twice. What is left is a
 * genuine clash between distinct tools — two source names that differ only in characters the
 * grammar disallows (`a.b` and `a b` both sanitise to `a_b`), or a prefixed name meeting a base
 * tool under [VoiceToolCollisionPolicy.PrefixExternal], where the embedder asked for both.
 */
private fun disambiguate(wanted: String, taken: Set<String>): String? {
    if (wanted !in taken) return wanted
    for (n in 2..MAX_DISAMBIGUATION_SUFFIX) {
        val candidate = VoiceToolNaming.advertise("${wanted}_$n")
        if (candidate !in taken) return candidate
    }
    return null
}

/**
 * How far the suffix loop goes before giving up and dropping the tool.
 *
 * Small on purpose. Distinct tools colliding after sanitisation is already unusual; eight of them
 * colliding is a source with a naming problem, and `thing_9` tells the model nothing useful about
 * which of nine near-identical tools it is picking. Dropping and logging beats advertising noise.
 */
private const val MAX_DISAMBIGUATION_SUFFIX = 9

/** The advertised definition: the source's own schema, plus the confirmation argument when gated. */
private fun definitionFor(
    sourceKey: String,
    name: String,
    tool: ExternalVoiceTool,
    gated: Boolean,
    schema: SanitisedSchema,
    hostApproves: Boolean,
): VoiceToolDef {
    // The in-band handshake is only advertised when it is the one that will actually run. With an
    // approver installed, confirmationRefusal short-circuits to the modal and never mints a token —
    // so telling the model about a token protocol it will never see is describing a mechanism that
    // does not exist, on up to sixty-four tools.
    val advertiseToken = gated && !hostApproves
    if (advertiseToken && VoiceConfirmationGate.CONFIRM_ARG in schema.properties) {
        // A source that declares its OWN `voice_confirm` would have it overwritten here, and —
        // because the argument allowlist is the source's declared keys — the host's token would then
        // be forwarded to it, contradicting "the confirmation argument is the host's, not the
        // tool's". Warn rather than drop the tool: the collision is the source's to fix, and losing
        // the tool over a parameter name is a worse trade than losing that parameter.
        logOnce(log, sourceKey + "confirmarg:${tool.name}", "Voice tool ${tool.name} declares a " +
            "${VoiceConfirmationGate.CONFIRM_ARG} parameter; the host's confirmation argument " +
            "takes that name and the tool's own will not be passed through")
    }
    val properties = if (!advertiseToken) schema.properties else JsonObject(
        schema.properties + (
            VoiceConfirmationGate.CONFIRM_ARG to buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Confirmation token. Call this tool without it first; you will be given a " +
                        "token, and you must say out loud exactly what you are about to do and " +
                        "wait for the user to agree before calling again with the token.",
                )
            }
            ),
    )
    return VoiceToolDef(
        name = name,
        // Said in the description rather than only in the Rules block because the Rules are one
        // paragraph the model reads once, while this sits on the tool it is about to pick. The
        // wording follows the mechanism: a token handshake it must drive, or a dialog it will wait on.
        description = buildString {
            append(clampDescription(tool.description).trimEnd())
            if (gated) {
                if (isNotEmpty()) append(' ')
                append(
                    if (hostApproves) "This cannot be undone; the user will be asked to approve it."
                    else "This cannot be undone and requires spoken confirmation."
                )
            }
        },
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", properties)
            putJsonArray("required") { schema.required.forEach { add(it) } }
        },
        write = tool.write,
    )
}

/**
 * Cap on one advertised description.
 *
 * [VoiceToolPolicy.maxExternalTools] bounds the tool COUNT, and the volume measurements behind its
 * default assume descriptions the size BossConsole writes (a few hundred bytes). Nothing bounded the
 * bytes, so a source with 4 KB descriptions ships a quarter of a megabyte of session config on every
 * turn while sitting comfortably under the ceiling — the ceiling would not be bounding what it is
 * documented to bound. Same reasoning and the same shape as [clampToolResult], one layer earlier.
 */
private const val MAX_DESCRIPTION_CHARS = 1_024

private fun clampDescription(description: String): String =
    if (description.length <= MAX_DESCRIPTION_CHARS) description
    else description.take(MAX_DESCRIPTION_CHARS - 1).trimEnd() + "…"

/**
 * WARN the first time [key] is seen, DEBUG every time after.
 *
 * Enumeration runs before every tool call, so warning each time would put thirteen identical lines in
 * the log per call. Warning only once, though, quietly weakened the claim this logging exists to
 * make: "every dropped tool is logged by name" was true once per process, and a plugin reload that
 * changed WHICH tools were dropped went unmentioned because the key had been seen before. The
 * downgrade keeps one loud line per distinct cause and leaves the per-call record at a level that can
 * be turned on when someone is looking for it.
 *
 * A bounded LRU rather than a set with a size check: the set was cleared wholesale on overflow, which
 * re-warned an entire surface in a burst instead of evicting the least recently seen, and the
 * check-then-clear-then-add sequence raced across enumeration-pool threads. Callers namespace their
 * keys, so two sources in one process do not share slots.
 */
private const val MAX_LOGGED_KEYS = 512

private val loggedKeys = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean = size > MAX_LOGGED_KEYS
}

internal fun logOnce(log: org.slf4j.Logger, key: String, message: String) {
    val firstTime = synchronized(loggedKeys) { loggedKeys.put(key, Unit) == null }
    if (firstTime) log.warn(message) else log.debug(message)
}
