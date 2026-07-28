package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject

/**
 * One tool an embedder contributes to the in-app voice agent.
 *
 * The first five fields are deliberately the same shape as
 * [ai.rever.bossterm.compose.mcp.BossTermMcpServer.RegisteredToolInfo], which is what BossTerm's own
 * voice surface is built from — because the point of both is the same: the description and the
 * schema come from wherever the tool is actually *defined*, so the voice agent and the tool's real
 * caller can never describe it differently. An embedder is expected to project this straight off its
 * own registry (for BossConsole, the `McpToolDefinition`s its plugins register). Restating them by
 * hand would recreate exactly the drift [VoiceToolCatalog] has had to be corrected for.
 *
 * The last two fields are what [ai.rever.bossterm.compose.mcp.BossTermMcpServer.RegisteredToolInfo]
 * has no answer for, because they are questions only the tool's author can settle:
 *
 *  - [sensitive] — this tool returns secret material. A voice call runs everything it touches
 *    through a third-party realtime API and then says something about it out loud in a room, so this
 *    is not a permissions question that a role or a confirmation can answer: it is exfiltration.
 *    Such a tool is never advertised and never executed. [VoiceToolPolicy] also catches the obvious
 *    names on its own, but a source that KNOWS should say so rather than rely on a pattern.
 *  - [irreversible] — this tool destroys something with no undo. Gated by [VoiceConfirmationGate];
 *    see [VoiceToolPolicy] for why [write] is not a strong enough notion for voice.
 *
 * Both flags are one-way: they can only ever *add* protection. Leaving them false does not remove a
 * classification [VoiceToolPolicy] made on its own.
 */
data class ExternalVoiceTool(
    /** The name the source will be called back with. Need not be a legal OpenAI function name. */
    val name: String,
    /** Shown to the model verbatim. Should describe the tool, not the voice surface. */
    val description: String,
    /** The `properties` object of the tool's JSON schema — the model's parameter list. */
    val properties: JsonObject,
    /** Names from [properties] the tool requires. */
    val required: List<String> = emptyList(),
    /** Mutates something. Mirrors BossTerm's own read/write split. */
    val write: Boolean = false,
    /** Destroys something unrecoverably — confirmation-gated. See [VoiceToolPolicy]. */
    val irreversible: Boolean = false,
    /**
     * Returns secret material: withheld from the advertised surface, and refused by name if the
     * model asks for it anyway. See [VoiceToolPolicy].
     *
     * "Withheld and refused" rather than "never executed", because the honest claim is the narrower
     * one: a tool that becomes sensitive while enumeration is failing stays reachable through the
     * last good surface until an enumeration succeeds. Narrow, and the alternative — treating a
     * failed enumeration as an empty surface — costs the whole external toolset over one blip.
     */
    val sensitive: Boolean = false,
)

/**
 * An embedder's own tool surface, offered to the **in-app** voice agent alongside BossTerm's own.
 *
 * Passed as `voiceToolSource` to [ai.rever.bossterm.compose.TabbedTerminal]; null (the default)
 * leaves the standalone app exactly as it was. Shares are untouched either way — the share viewer
 * keeps [VoiceToolCatalog], where the declared-parameter allowlist is doing real work against a
 * guest on a link. This surface's caller is the machine's owner.
 *
 * BossTerm's agent can reach thirteen tools, all of them about terminals, because that is all
 * BossTerm has. An embedder generally has more: BossConsole's `terminal-tab` plugin hosts a `boss`
 * MCP server whose surface — measured against a live instance — is 106 tools covering git, the
 * browser, plugins, secrets, RPA and the rest. Those are the tools the person talking to Boss
 * actually wants, and there is no reason for BossTerm to know any of their names.
 *
 * ```kotlin
 * TabbedTerminal(
 *     onExit = { … },
 *     voiceToolSource = object : VoiceToolSource {
 *         override fun tools() = registry.tools.value.map { it.asVoiceTool() }   // fresh each call
 *         override suspend fun call(name: String, args: JsonObject) = registry.invoke(name, "$args")
 *         override val policy = VoiceToolPolicy(
 *             excludeExtra = { it.requiredPermissions.any { p -> p.startsWith("secrets.") } },
 *             irreversibleExtra = { it.name in setOf("rpa_run", "evolver_evolve") },
 *         )
 *     },
 * )
 * ```
 *
 * ### Why one interface rather than a list plus a provider lambda
 *
 * The house pattern for embedder-contributed content is a value parameter with a provider lambda
 * beside it (`contextMenuItems` / `contextMenuItemsProvider`). The provider half is exactly right
 * here and is kept: [tools] is invoked afresh on every enumeration, which is what makes hot reload
 * work without BossTerm inventing cache invalidation. The value half is not, and the two halves of
 * *this* seam are not separable:
 *
 *  - A tool list without an invoker is a lie told to the model, and an invoker without a list is
 *    unreachable code. Split into two nullable parameters, three of the four combinations are
 *    illegal and BossTerm has to define behaviour for each.
 *  - Invocation has to be called back with the name the *source* used, not the name the model used,
 *    because names get rewritten to satisfy OpenAI's grammar and to keep an embedder's tool from
 *    shadowing a BossTerm one. That reverse mapping is an invariant between the two halves, so they
 *    belong to one object.
 *  - The exclusion rules ([policy]) are about this source's tools specifically. Hanging them off a
 *    third parameter would let a caller supply tools with no policy, which is precisely the
 *    combination that must not be expressible.
 *
 * ### Contract
 *
 * - **[tools] is re-read, never snapshotted.** Return the live set; do not cache on your side.
 * - **[tools] must be quick and must not block.** It runs with a deadline
 *   ([CompositeVoiceToolExecutor.DEFAULT_ENUMERATE_TIMEOUT_MS]); overrunning it costs the whole
 *   external surface for that enumeration.
 * - **Neither method may take the call down.** Throws, hangs and garbage are contained — see
 *   [CompositeVoiceToolExecutor] — but a source that misbehaves loses tools, so don't rely on it.
 * - **[call] receives the source's OWN [ExternalVoiceTool.name]**, never the advertised one.
 * - **[call] returns a JSON string.** Over 40 KiB is clamped ([clampToolResult]); blank becomes `{}`.
 * - **A tool has about 100 seconds**, and cannot currently ask for more.
 *   [VoiceToolTimeouts.execMs] gives every name but BossTerm's own `run_command` the same budget,
 *   and the call's watchdog fires at 120s, so a source whose work runs for minutes — an RPA run, an
 *   agentic evolve — is answered with "that tool did not answer in time" while it carries on in the
 *   background. A per-tool `timeoutMs` was considered and deliberately not added: clamped under the
 *   watchdog it would buy about nineteen seconds, which does not help the tools that motivate it,
 *   and the version that would help requires the watchdog to take its deadline from the tool as
 *   well. That is a change to the call's own timeout ladder rather than to this seam. Until then,
 *   a long-running tool should return promptly with a handle and let the agent poll.
 * - **[ai.rever.bossterm.compose.mcp.BossTermMcpConfig.allowWriteTools] does not reach this
 *   surface.** It withholds BossTerm's *own* write tools; an [ExternalVoiceTool] with `write = true`
 *   is advertised regardless. That is deliberate — the source owns its tools and their policy, and
 *   BossTerm cannot know whether an embedder's read-only MCP endpoint was meant to constrain the
 *   embedder's own product — but it surprises anyone who set `allowWriteTools = false` expecting a
 *   read-only voice surface. If that is what you want, filter [tools] yourself.
 */
interface VoiceToolSource {

    /**
     * Prefix applied to this source's tool names when [VoiceToolPolicy.onNameCollision] is
     * [VoiceToolCollisionPolicy.PrefixExternal]. Ignored under the default policy, which drops
     * colliding tools instead — see [VoiceToolCollisionPolicy] for the measured trade.
     */
    val namePrefix: String get() = ""

    /**
     * The safety rules for this source's tools. The default catches secret-looking and
     * destructive-looking names on its own; supply one to widen it with what only the embedder can
     * know. See [VoiceToolPolicy] — the rules are additive and cannot be narrowed.
     */
    val policy: VoiceToolPolicy get() = DEFAULT_POLICY

    /**
     * The tools available *right now*.
     *
     * The provider lambda of the context-menu pattern, expressed as a method: called when the
     * session's tool list is built and again before every tool call, so a plugin that registers or
     * unregisters mid-call is reflected without BossTerm holding — or having to invalidate — a
     * cache of its own.
     */
    fun tools(): List<ExternalVoiceTool>

    /**
     * Run [name] (one of this source's own names) with [args] and return its JSON result.
     *
     * [args] has already been filtered to the keys the tool declared in
     * [ExternalVoiceTool.properties], so a parameter the source did not advertise can never reach it
     * — the same allowlist BossTerm applies to its own tools.
     */
    suspend fun call(name: String, args: JsonObject): String
}

/** Shared so the default [VoiceToolSource.policy] getter doesn't allocate one per enumeration. */
private val DEFAULT_POLICY = VoiceToolPolicy()
