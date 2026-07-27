package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
     * Tier-1 refusals. Holds BOTH the source's own name and the name the tool *would* have been
     * advertised under, because the model can arrive at either: the advertised one from an earlier
     * enumeration that still offered the tool, the raw one from a sibling tool's description or the
     * terminal itself. Stored at merge time rather than recomputed per lookup — recomputing meant
     * re-deriving the advertised form from the raw name, which is not even possible under
     * [VoiceToolCollisionPolicy.PrefixExternal], where the advertised form carries a prefix the
     * lookup knew nothing about. An excluded tool went unrefused by its prefixed name there.
     */
    val excluded: Set<String>,
    /** Same, for tools left out for collision, ceiling or an unusable name. */
    val dropped: Set<String>,
) {
    val byAdvertisedName: Map<String, AdvertisedExternalTool> = advertised.associateBy { it.advertisedName }

    companion object {
        /** What an enumeration that failed with no usable fallback contributes: nothing. */
        val EMPTY = ExternalSurface(emptyList(), emptySet(), emptySet())
    }

    /** Why [name] is refused, or null if this surface has nothing to say about it. */
    fun refusalFor(name: String): String? = when (name) {
        in excluded ->
            "$name is withheld from the voice agent: it returns secret material, and a voice call " +
                "sends everything it touches through a third-party service."
        in dropped ->
            "$name is not available to the voice agent on this host."
        else -> null
    }
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
private val log = LoggerFactory.getLogger("ai.rever.bossterm.compose.voice.VoiceToolMerge")

internal fun mergeExternalTools(
    baseNames: Set<String>,
    external: List<ExternalVoiceTool>,
    prefix: String,
    policy: VoiceToolPolicy,
): ExternalSurface {
    val advertised = mutableListOf<AdvertisedExternalTool>()
    val excluded = mutableSetOf<String>()
    val dropped = mutableSetOf<String>()
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
    fun refuse(into: MutableSet<String>, raw: String, wanted: String) {
        if (raw !in baseNames) into += raw
        if (wanted.isNotEmpty() && wanted !in baseNames) into += wanted
    }

    val usePrefix = policy.onNameCollision == VoiceToolCollisionPolicy.PrefixExternal &&
        prefix.isNotBlank()
    if (policy.onNameCollision == VoiceToolCollisionPolicy.PrefixExternal && prefix.isBlank()) {
        logOnce(log, "blank-prefix", "Voice tool source asked for prefixing but declared no namePrefix; " +
            "colliding tools will be dropped instead")
    }

    // Computed before the exclusion check, not after, so a refused tool can be refused by the name
    // the model would have seen as well as by its own.
    fun wantedNameFor(tool: ExternalVoiceTool) =
        VoiceToolNaming.advertise(if (usePrefix) prefix + tool.name else tool.name)

    val seenSourceNames = mutableSetOf<String>()

    for (tool in external) {
        val wanted = wantedNameFor(tool)
        if (policy.isExcluded(tool)) {
            refuse(excluded, tool.name, wanted)
            logOnce(log, "excl:${tool.name}", "Voice tool ${tool.name} is withheld from the agent: " +
                "it returns secret material")
            continue
        }
        if (advertised.size >= policy.maxExternalTools) {
            refuse(dropped, tool.name, wanted)
            logOnce(log, "cap:${tool.name}", "Voice tool ${tool.name} dropped: over the " +
                "${policy.maxExternalTools}-tool ceiling for the external surface")
            continue
        }
        if (wanted.isEmpty()) {
            refuse(dropped, tool.name, wanted)
            logOnce(log, "name:${tool.name}", "Voice tool ${tool.name} dropped: no legal function " +
                "name can be made from it")
            continue
        }
        // One tool, listed twice by its own source. Advertising the second as `git_status_2` would
        // offer the model two names for one implementation and call back with the same source name
        // for both — a duplicate, not a second tool.
        if (!seenSourceNames.add(tool.name)) {
            refuse(dropped, tool.name, wanted)
            logOnce(log, "dup:${tool.name}", "Voice tool ${tool.name} dropped: the source listed " +
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
            refuse(dropped, tool.name, wanted)
            logOnce(log, "clash:${tool.name}", "Voice tool ${tool.name} dropped: BossTerm already " +
                "advertises $wanted, and its own implementation is the one the agent is told about")
            continue
        }
        val name = disambiguate(wanted, taken)
        if (name == null) {
            refuse(dropped, tool.name, wanted)
            logOnce(log, "exhausted:${tool.name}", "Voice tool ${tool.name} dropped: $wanted is taken " +
                "and no distinct name was free")
            continue
        }
        // Only the SURPRISING renames. Under PrefixExternal every tool is renamed by design, and a
        // line per tool says nothing about 106 of them; what earns a line is a name that had to be
        // sanitised or disambiguated to fit.
        if (name != (if (usePrefix) prefix + tool.name else tool.name)) {
            logOnce(log, "rename:${tool.name}", "Voice tool ${tool.name} advertised as $name")
        }
        taken += name
        val gated = policy.isIrreversible(tool)
        advertised += AdvertisedExternalTool(
            advertisedName = name,
            source = tool,
            gated = gated,
            // Lazily: execute() runs this whole merge before every tool call and reads only
            // byAdvertisedName/gated/source, so building 64 parameter schemas per call — inside a
            // live call — bought nothing. tools() forces them, which is the one caller that needs them.
            defProvider = { definitionFor(name, tool, gated) },
        )
    }
    return ExternalSurface(advertised, excluded, dropped)
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
private fun definitionFor(name: String, tool: ExternalVoiceTool, gated: Boolean): VoiceToolDef {
    // A source that declares its OWN `voice_confirm` would have it overwritten here, and — because
    // the argument allowlist is the source's declared keys — the host's token would then be
    // forwarded to it, contradicting "the confirmation argument is the host's, not the tool's".
    // Warn rather than drop the tool: the collision is the source's to fix and losing the tool over
    // a parameter name is a worse trade than losing that parameter.
    if (gated && VoiceConfirmationGate.CONFIRM_ARG in tool.properties) {
        logOnce(log, "confirmarg:${tool.name}", "Voice tool ${tool.name} declares a " +
            "${VoiceConfirmationGate.CONFIRM_ARG} parameter; the host's confirmation argument " +
            "takes that name and the tool's own will not be passed through")
    }
    val properties = if (!gated) tool.properties else JsonObject(
        tool.properties + (
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
        // paragraph the model reads once, while this sits on the tool it is about to pick.
        description = if (gated) {
            tool.description.trimEnd().let { if (it.isEmpty()) it else "$it " } +
                "This cannot be undone and requires spoken confirmation."
        } else {
            tool.description
        },
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", properties)
            putJsonArray("required") { tool.required.forEach { add(it) } }
        },
        write = tool.write,
    )
}

/**
 * WARN the first time [key] is seen in this process, DEBUG every time after.
 *
 * Enumeration runs before every tool call, so warning each time would put thirteen identical lines
 * in the log per call. Warning only once, though, quietly weakened the claim this logging exists to
 * make: "every dropped tool is logged by name" was true once per process, and a plugin reload that
 * changed WHICH tools were dropped went unmentioned because the key had been seen before. The
 * downgrade keeps one loud line per distinct cause and leaves the per-call record at a level that
 * can be turned on when someone is actually looking for it.
 */
private val loggedKeys = ConcurrentHashMap.newKeySet<String>()

internal fun logOnce(log: org.slf4j.Logger, key: String, message: String) {
    if (loggedKeys.size > 500) loggedKeys.clear()
    if (loggedKeys.add(key)) log.warn(message) else log.debug(message)
}
