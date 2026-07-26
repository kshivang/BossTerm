package ai.rever.bossterm.compose.voice

/**
 * How an EMBEDDER shapes the voice agent's instructions.
 *
 * BossTerm ships as a library as well as an app, and the two want different things here. The app's
 * agent is talking about "your terminal"; an embedder like BossConsole is putting the same agent
 * inside a product with its own vocabulary, its own conventions, and its own idea of what the user
 * is likely doing — none of which the terminal can infer.
 *
 * Published the same way [ai.rever.bossterm.compose.mcp.McpTerminalRegistry.mcpConfig] is: set it
 * once at startup, before any call is placed. Both call surfaces read it, so an embedder configures
 * the in-app pill and the share viewer together.
 *
 * Three levels, cheapest first:
 *  - do nothing — the built-in rules describe the tool surface accurately on their own;
 *  - [extraInstructions] — append product context to the built-in rules (the common case);
 *  - [rulesOverride] — replace the rules wholesale, for an embedder that wants full control.
 *
 * The USER's own additions live in `TerminalSettings.voiceAgentExtraInstructions` and are appended
 * after the embedder's, so a person can always add something the product did not anticipate.
 */
object VoiceAgentCustomization {

    /**
     * Product context appended to the built-in rules.
     *
     * Describe what this machine or product is FOR — "this is a Kubernetes admin console, prefer
     * kubectl over raw API calls" — rather than restating what the tools do; the agent already has
     * the tool schemas and the rules that go with them.
     */
    @Volatile
    var extraInstructions: String? = null

    /**
     * Replace the built-in rules entirely.
     *
     * Receives the tool names actually advertised on this surface (which varies: the daemon has no
     * GUI-only tools, and an embedder with `allowWriteTools = false` has no write tools at all) and
     * the confirmation wording for the surface. An override that names a tool the surface does not
     * have will send the agent after something that cannot be called, so branch on `names` the way
     * [voiceAgentRules] does.
     *
     * [extraInstructions] and the user's own are still appended, so an override does not silently
     * discard what a person typed into Settings.
     */
    @Volatile
    var rulesOverride: ((names: Set<String>, confirmationWording: String) -> String)? = null

    /** For tests: an embedder's customization must not leak between them. */
    internal fun resetForTest() {
        extraInstructions = null
        rulesOverride = null
    }
}
