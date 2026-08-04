package ai.rever.bossterm.compose.voice

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Where Boss Calling's OpenAI key comes from: an embedder-supplied source if one is registered,
 * otherwise `~/.bossterm/voice.json`.
 *
 * BossTerm ships as a standalone app as well as a library, so the file stays the fallback and the
 * settings UI that writes it stays exactly as it was. The override exists for an embedder that
 * already asks its users for AI credentials somewhere else and should not ask twice: BOSS keeps
 * them in the secret-manager plugin's `Settings → AI Providers`, and `terminal-tab` registers a
 * source that reads the user's **OpenAI** provider from there.
 *
 * BossTerm cannot depend on `boss-plugin-api` to do that itself — hence a plain lambda.
 *
 * ## Registration
 *
 * ```kotlin
 * VoiceKeySource.setEmbedderSource { context.llmProvider?.configuredProviders()
 *     ?.firstOrNull { it.providerId == "OPENAI" }?.apiKey }
 * ```
 *
 * Process-wide rather than a `TabbedTerminal` parameter because the key is read on three
 * unrelated paths — the in-app call ([HostVoiceCallController]), the share server's ephemeral
 * mint ([VoiceCallService] → [VoiceSessionBroker]), and share advertisement — and threading a
 * composable parameter to all of them would put the seam in the wrong place. Pass null to
 * deregister, which an embedder must do when it unloads.
 *
 * ## What this deliberately does not cover
 *
 * - **The Realtime model stays BossTerm's** (`TerminalSettings.voiceCallModel`). An
 *   `LlmConfig.modelId` is a *chat* model; Realtime needs a `gpt-realtime-*`. Only the credential
 *   is shared.
 * - **An OpenAI key specifically.** Realtime is OpenAI's; an embedder must not hand over whatever
 *   provider happens to be active, or a `sk-ant-…` key ends up at `api.openai.com`.
 * - **The daemon.** [ai.rever.bossterm.compose.daemon.DaemonShareServer] runs headless with no
 *   embedder in the process and keeps reading `voice.json` directly.
 * - **Change notification.** [VoiceAgentStorage.keyStamp] detects an edit to the file; an embedder
 *   source has no equivalent, so a key that appears in the embedder is noticed the next time
 *   something asks rather than pushed. Presence checks call [resolve] per read for that reason.
 */
object VoiceKeySource {
    private val log = LoggerFactory.getLogger(VoiceKeySource::class.java)

    @Volatile
    private var embedder: (() -> String?)? = null

    /** Register (or, with null, remove) the embedder's key source. */
    fun setEmbedderSource(source: (() -> String?)?) {
        embedder = source
        log.info("Boss Calling key source: {}", if (source == null) "voice.json" else "embedder")
    }

    /**
     * The key to use, embedder first, or null when neither source has one.
     *
     * The embedder's lambda reaches into host code — a plugin classloader in BOSS's case — so a
     * throw here must not take a voice call or a share advertisement down with it; it falls
     * through to the file instead. Logged without the throwable: nothing in this path should be
     * able to print a credential.
     */
    internal fun resolve(file: File = VoiceAgentStorage.defaultFile()): String? {
        val fromEmbedder = try {
            embedder?.invoke()
        } catch (t: Throwable) {
            log.warn("Embedder key source failed ({}); falling back to voice.json", t::class.simpleName)
            null
        }
        return fromEmbedder?.takeIf { it.isNotBlank() }
            ?: VoiceAgentStorage.load(file)?.openaiApiKey?.takeIf { it.isNotBlank() }
    }

    /**
     * Whether the *embedder* can supply a key, ignoring the file.
     *
     * Separate from [resolve] because the share server's presence check is already a careful
     * two-source affair (an in-process flow plus a stamp-cached disk read) and this is a third
     * source to OR in, not a replacement for either.
     */
    internal fun embedderKeyPresent(): Boolean =
        try {
            !embedder?.invoke().isNullOrBlank()
        } catch (t: Throwable) {
            log.warn("Embedder key source failed ({}) during presence check", t::class.simpleName)
            false
        }
}
