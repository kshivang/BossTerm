package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject

/** What to do when an external tool's name is already taken by BossTerm's own surface. */
enum class VoiceToolCollisionPolicy {
    /**
     * Don't advertise the external one (logged). The default, and on the surface this was built for
     * it is also the *common* case: 13 of the 106 tools on BossConsole's `boss` server are BossTerm's
     * own tools re-exported by the `terminal-tab` plugin, so advertising both would spend ~8 KB of
     * session config offering the model two identical-looking ways to do the same thing. Whichever it
     * picked, the BossTerm one is the implementation with the share-scope and focused-pane logic in
     * front of it.
     */
    DropExternal,

    /**
     * Advertise the whole external surface under [VoiceToolSource.namePrefix]. For an embedder whose
     * same-named tools genuinely differ from BossTerm's. Falls back to [DropExternal] when the
     * source's prefix is blank — the one thing that must never happen is an external tool shadowing
     * a BossTerm tool the agent's instructions describe.
     */
    PrefixExternal,
}

/**
 * The safety policy over an embedder's [VoiceToolSource]. Two tiers, and they are not the same kind
 * of judgement.
 *
 * **Tier 1 — hard exclusion ([isExcluded]).** Anything that returns secret material is never
 * advertised and, if the model names it anyway, never executed. This is not a permissions decision:
 * every word of a voice call — the tool result included, since the agent has to say something about
 * it — travels through a third-party realtime API and is spoken aloud in a room. A `secret_get`
 * result reaching that pipeline is exfiltration that no role check, confirmation or audit log
 * undoes. So the tier is absolute rather than gated.
 *
 * **Tier 2 — confirmation ([isIrreversible]).** Destructive operations are advertised, but the first
 * call is refused and answered with a token the agent must redeem after the user has spoken. See
 * [VoiceConfirmationGate].
 *
 * ### Why `write` is not enough for tier 2
 *
 * BossTerm already classifies tools read/write, and both surfaces already act on it. It is the wrong
 * instrument here for three reasons:
 *
 *  1. **It gates the wrong moment.** `write` decides whether a tool is *registered at all* —
 *     `allowWriteTools` at config time, controller-role at share time. Both are answered before the
 *     call starts. On the in-app surface the caller IS the machine's owner, so every write tool is
 *     already allowed and `write` gates precisely nothing.
 *  2. **It does not separate `git_stage` from `git_discard`.** Both mutate. One is undone by
 *     `git_unstage`; the other threw the work away. A single boolean cannot carry that.
 *  3. **Voice is not typing.** A typed command is a human's exact keystrokes. A voice tool call is a
 *     transcription of a noisy room, fed to a model that then *samples* a tool and its arguments.
 *     Two probabilistic steps sit between the user's intent and the call, and neither is visible to
 *     the user before it runs. "Discard" and "this card" are one bad microphone apart. That is a
 *     materially different risk from the same operation typed, and it is why an irreversible tool
 *     needs an interlock rather than a stern sentence in the instructions.
 *
 * ### Extending it
 *
 * Both tiers are **additive only**. [excludeExtra] and [irreversibleExtra] widen what BossTerm
 * catches; nothing narrows it, so an embedder cannot accidentally un-protect a tool by installing a
 * rule of its own. A rule that throws is treated as a match — an embedder classifier that is broken
 * must not be the reason a secret tool gets advertised.
 *
 * The name patterns below are a floor, not a classifier. They catch `secret_get`, `role_delete`,
 * `git_discard`; they cannot catch `rpa_run` or `evolver_evolve`, which are as destructive as
 * anything on the surface and say nothing about it in their names. Those need the source's own
 * [ExternalVoiceTool.irreversible] flag or an [irreversibleExtra] rule — see the worked example in
 * the PR that introduced this file.
 *
 * Deliberately not a `data class`: it holds lambdas, so a generated `equals`/`hashCode` would be
 * identity comparison wearing the costume of value equality, and nothing here needs either.
 */
class VoiceToolPolicy(
    /** Additional hard-exclusion rule. Widens the built-in one; can never narrow it. */
    val excludeExtra: (ExternalVoiceTool) -> Boolean = { false },
    /** Additional confirmation rule. Widens the built-in one; can never narrow it. */
    val irreversibleExtra: (ExternalVoiceTool) -> Boolean = { false },
    /**
     * Ceiling on how many external tools are advertised. See [DEFAULT_MAX_EXTERNAL_TOOLS] for the
     * measurements this is set from. Tools past the ceiling are dropped **in the source's declared
     * order** — so a source that cares should return its important tools first — and every drop is
     * logged by name.
     */
    val maxExternalTools: Int = DEFAULT_MAX_EXTERNAL_TOOLS,
    /** How to handle an external name BossTerm already uses. */
    val onNameCollision: VoiceToolCollisionPolicy = VoiceToolCollisionPolicy.DropExternal,
    /**
     * A host-supplied approval, replacing the in-band two-step for irreversible tools.
     *
     * This is what an embedder with a UI should install: a modal naming the exact operation beats a
     * spoken confirmation the user may mishear as easily as the agent did. Receives the source's own
     * tool name and the filtered arguments; `false` refuses the call. Absent, [VoiceConfirmationGate]
     * handles it in-band. A throw or a hang is a refusal.
     */
    val approve: (suspend (String, JsonObject) -> Boolean)? = null,
) {

    /** Tier 1: never advertised, never executed. */
    fun isExcluded(tool: ExternalVoiceTool): Boolean = exclusionReason(tool) != null

    /**
     * WHY [tool] is excluded, or null if it is not — because the answer is said out loud.
     *
     * A single reason string meant the agent told the user "that returns secret material" about
     * anything the embedder's own rule rejected, and in the fail-closed case (a classifier that
     * throws excludes everything) it said it about the entire surface, on account of an embedder
     * bug. Wrong, and alarming in a specific way that invites the user to go looking for a leak.
     */
    fun exclusionReason(tool: ExternalVoiceTool): String? = when {
        tool.sensitive || returnsSecretMaterial(tool.name) -> SECRET_REASON
        // Fail closed: a classifier that blew up has told us nothing, and "nothing" must not
        // resolve to "advertise the secret tool".
        runCatching { excludeExtra(tool) }.getOrDefault(true) -> HOST_POLICY_REASON
        else -> null
    }

    /** Tier 2: advertised, but confirmation-gated. */
    fun isIrreversible(tool: ExternalVoiceTool): Boolean =
        tool.irreversible ||
            looksIrreversible(tool.name) ||
            runCatching { irreversibleExtra(tool) }.getOrDefault(true)

    companion object {

        /** Said out loud when a tool is withheld for tier 1 proper. */
        const val SECRET_REASON =
            "is withheld from the voice agent: it returns secret material, and a voice call sends " +
                "everything it touches through a third-party service."

        /** Said out loud when the EMBEDDER's own rule withheld it — no claim about why. */
        const val HOST_POLICY_REASON = "is withheld from the voice agent by this host's policy."

        /** Said out loud for a tool left out for collision, ceiling or an unusable name. */
        const val UNAVAILABLE_REASON = "is not available to the voice agent on this host."

        /**
         * Default ceiling on the external surface.
         *
         * Measured against BossConsole's live `boss` MCP server (106 tools) rendered as an OpenAI
         * Realtime `tools` array: the whole thing is 39,785 bytes ≈ 9.9k tokens, against 12,056
         * bytes ≈ 3.0k for BossTerm's own. Naively merged that is 51,840 bytes of session config
         * riding every turn of the conversation; after tier-1 exclusion and collision-dropping it is
         * still 99 tools and 37,915 bytes. At 64 external tools the merged surface is 32,604 bytes ≈
         * 8.2k tokens — high, but the honest cost of the capability, and the number is a knob rather
         * than a decision BossTerm makes for the embedder.
         *
         * Deliberately generous: the failure this bounds is cost and tool-selection accuracy, not
         * safety, and a default that quietly hid a third of the surface would defeat the point of
         * the seam. Everything dropped is logged by name.
         */
        const val DEFAULT_MAX_EXTERNAL_TOOLS = 64

        /**
         * Name segments that mean "this returns secret material".
         *
         * Segment-anchored, so `secret_get`, `my_secret_get`, `secrets_list` and `secret_search` all
         * match while an unrelated `tokenize` does not. Over-matching is the safe direction here: a
         * read tool wrongly excluded is a capability the agent lacks, a secret tool wrongly
         * advertised is a credential read aloud.
         */
        private val SECRET_SEGMENTS = Regex(
            "(^|_)(secret|secrets|credential|credentials|password|passwords|passphrase|" +
                "apikey|api_key|token|tokens|keychain|keyring)(_|\$)",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Name segments that mean "there is no undo".
         *
         * Same anchoring, same asymmetry: a wrongly gated tool costs one spoken confirmation, a
         * wrongly ungated one costs the thing it deleted. An embedder that finds the gate too eager
         * should install [approve] rather than expect to narrow this — a real modal is a better
         * answer than fewer confirmations.
         */
        private val IRREVERSIBLE_SEGMENTS = Regex(
            "(^|_)(delete|destroy|discard|drop|erase|kill|purge|remove|reset|revert|revoke|" +
                "uninstall|wipe|disable|clear)(_|\$)",
            RegexOption.IGNORE_CASE,
        )

        /**
         * [name] reduced to lowercase `_`-separated segments, so the patterns above see the same
         * tool however its author spelled it.
         *
         * Both patterns anchor on `_`, and they ran against the RAW name — which meant they
         * understood exactly one naming convention. `secret_get` was excluded; `secret-get`,
         * `secret.get` and `getSecret` were not. Hyphens and dots are not exotic in a tool registry,
         * and the premise of this whole seam is accepting names from a registry BossTerm has never
         * seen. The `secret.get` case is the one that gives the game away: [VoiceToolNaming.advertise]
         * sanitises it to `secret_get` for advertisement, so the name the model finally sees is the
         * one that would have matched the pattern the tool was checked against a moment earlier.
         *
         * Normalising is a pure widening — it can only ever match more — which is the direction this
         * floor is already committed to: a read tool wrongly excluded costs a capability, a secret
         * tool wrongly advertised costs a credential.
         */
        fun segments(name: String): String = name
            // camelCase and PascalCase boundaries, including the acronym case (`HTTPGet` → `HTTP_Get`).
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_")
            .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), "_")
            // Anything that is not alphanumeric is a separator: `-`, `.`, `/`, `:`, spaces.
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            // Belt-and-braces: both patterns are already IGNORE_CASE, so removing this changes no
            // behaviour and a mutation proving as much survives on purpose. It stays because this
            // function is a general normalisation, and the next caller may not be a case-insensitive
            // regex.
            .lowercase()

        /** True when [name] looks like it hands back a credential, however it is spelled. */
        fun returnsSecretMaterial(name: String): Boolean =
            SECRET_SEGMENTS.containsMatchIn(segments(name))

        /** True when [name] looks like it destroys something with no undo, however it is spelled. */
        fun looksIrreversible(name: String): Boolean =
            IRREVERSIBLE_SEGMENTS.containsMatchIn(segments(name))
    }
}
