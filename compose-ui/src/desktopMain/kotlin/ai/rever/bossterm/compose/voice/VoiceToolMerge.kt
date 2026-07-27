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
internal data class AdvertisedExternalTool(
    /** What OpenAI is told the tool is called. */
    val advertisedName: String,
    /** The source's own tool, with the source's own name — what [VoiceToolSource.call] gets back. */
    val source: ExternalVoiceTool,
    /** Confirmation-gated ([VoiceToolPolicy.isIrreversible]). */
    val gated: Boolean,
    /** The advertised definition, schema and all. */
    val def: VoiceToolDef,
)

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
    /** Source names refused outright (tier 1). */
    val excluded: Set<String>,
    /** Source names left out for collision, ceiling or an unusable name. */
    val dropped: Set<String>,
) {
    val byAdvertisedName: Map<String, AdvertisedExternalTool> = advertised.associateBy { it.advertisedName }


    /**
     * Why [name] is refused, or null if this surface has nothing to say about it.
     *
     * Matched against the source's own name AND the name it would have been advertised under,
     * because the model can arrive at either: the advertised one from an earlier enumeration that
     * still offered the tool, the raw one from a sibling tool's description or the terminal itself.
     */
    fun refusalFor(name: String): String? = when {
        excluded.any { it == name || VoiceToolNaming.advertise(it) == name } ->
            "$name is withheld from the voice agent: it returns secret material, and a voice call " +
                "sends everything it touches through a third-party service."
        dropped.any { it == name || VoiceToolNaming.advertise(it) == name } ->
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
internal fun mergeExternalTools(
    baseNames: Set<String>,
    external: List<ExternalVoiceTool>,
    prefix: String,
    policy: VoiceToolPolicy,
): ExternalSurface {
    val log = LoggerFactory.getLogger("ai.rever.bossterm.compose.voice.VoiceToolMerge")
    val advertised = mutableListOf<AdvertisedExternalTool>()
    val excluded = mutableSetOf<String>()
    val dropped = mutableSetOf<String>()
    val taken = baseNames.toMutableSet()

    val usePrefix = policy.onNameCollision == VoiceToolCollisionPolicy.PrefixExternal &&
        prefix.isNotBlank()
    if (policy.onNameCollision == VoiceToolCollisionPolicy.PrefixExternal && prefix.isBlank()) {
        logOnce(log, "blank-prefix", "Voice tool source asked for prefixing but declared no namePrefix; " +
            "colliding tools will be dropped instead")
    }

    for (tool in external) {
        if (policy.isExcluded(tool)) {
            excluded += tool.name
            logOnce(log, "excl:${tool.name}", "Voice tool ${tool.name} is withheld from the agent: " +
                "it returns secret material")
            continue
        }
        if (advertised.size >= policy.maxExternalTools) {
            dropped += tool.name
            logOnce(log, "cap:${tool.name}", "Voice tool ${tool.name} dropped: over the " +
                "${policy.maxExternalTools}-tool ceiling for the external surface")
            continue
        }
        val wanted = VoiceToolNaming.advertise(if (usePrefix) prefix + tool.name else tool.name)
        if (wanted.isEmpty()) {
            dropped += tool.name
            logOnce(log, "name:${tool.name}", "Voice tool ${tool.name} dropped: no legal function " +
                "name can be made from it")
            continue
        }
        val name = disambiguate(wanted, taken)
        if (name == null) {
            dropped += tool.name
            logOnce(log, "clash:${tool.name}", "Voice tool ${tool.name} dropped: " +
                "$wanted is already taken" + if (usePrefix) "" else " by BossTerm's own surface")
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
            def = definitionFor(name, tool, gated),
        )
    }
    return ExternalSurface(advertised, excluded, dropped)
}

/**
 * [wanted] if it is free, else `wanted_2`… — and null once even that fails.
 *
 * Under the default policy a base collision is dropped before it gets here; this covers the
 * prefixing mode and the (unlikely, but silent if unhandled) case of two source tools whose names
 * differ only in characters the grammar does not allow.
 */
private fun disambiguate(wanted: String, taken: Set<String>): String? {
    if (wanted !in taken) return wanted
    for (n in 2..9) {
        val candidate = VoiceToolNaming.advertise("${wanted}_$n")
        if (candidate !in taken) return candidate
    }
    return null
}

/** The advertised definition: the source's own schema, plus the confirmation argument when gated. */
private fun definitionFor(name: String, tool: ExternalVoiceTool, gated: Boolean): VoiceToolDef {
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
 * Log [message] the first time [key] is seen.
 *
 * Enumeration runs before every tool call, and a surface with thirteen collisions would otherwise
 * put thirteen identical warnings in the log per call. The set is process-wide and bounded; the
 * point of these lines is that a dropped tool is discoverable at all, not that it is counted.
 */
private val loggedKeys = ConcurrentHashMap.newKeySet<String>()

internal fun logOnce(log: org.slf4j.Logger, key: String, message: String) {
    if (loggedKeys.size > 500) loggedKeys.clear()
    if (loggedKeys.add(key)) log.warn(message)
}
