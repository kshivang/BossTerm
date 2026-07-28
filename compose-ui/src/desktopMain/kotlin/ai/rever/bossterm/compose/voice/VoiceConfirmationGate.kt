package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The interlock in front of an irreversible tool.
 *
 * The instructions already tell the agent to get spoken confirmation before anything destructive,
 * and that line is worth its tokens — but it is advice, and the model is free to skip it. This is
 * the mechanism version, and it rests on one property the instructions cannot give:
 *
 *   **a confirmation token is only redeemable after the user has spoken.**
 *
 * The first call to a gated tool does not run. It comes back as a [Decision.Confirm] carrying a
 * token, which the agent is told to redeem after saying what it is about to do. Redemption checks
 * four things:
 *
 *  1. the token exists and has not expired,
 *  2. it was issued for *these arguments* — the fingerprint pins tool + args, so a token minted for
 *     `git_discard {path: "docs"}` cannot be spent on `{path: "/"}`,
 *  3. the AGENT has spoken since the token was minted,
 *  4. the USER has spoken since the agent did.
 *
 * (3) and (4) are the ones that matter, and they have to be in that order. Without them the model
 * can call twice in a row inside a single turn and satisfy the protocol without the user ever
 * hearing about it, which is the failure this exists to prevent: the thing being guarded against is
 * a *misheard* instruction, and the only evidence the user meant it is the user answering a
 * question they were asked.
 *
 * "The user has spoken" alone was not enough, and the hole was narrow but real: in a barge-in the
 * user is already talking when the model emits its call, so `speech_stopped` fires *after* the mint
 * from the very utterance that caused it, and the next attempt redeems against an announcement that
 * never happened. Requiring an agent turn in between closes it — an agent turn is the announcement.
 *
 * How weak is "an agent turn"? It can be satisfied by the same response that made the call: a model
 * that emits audio and a function call together finishes that audio after the mint, and that counts.
 * Deliberately — the agent did say something before the user answered, which is the property being
 * checked — but it is *a* turn, not provably *the* announcement. Pinning it to content would mean
 * reading the audio, and the thing that reads the audio is the model whose judgement the gate exists
 * to doubt.
 *
 * Both signals are the Realtime server's own judgement rather than ours:
 * `input_audio_buffer.speech_stopped` for the user, `response.output_audio.done` for the agent.
 * [HostVoiceCallController] pumps both.
 *
 * **What this does NOT do.** It mechanises *occurrence*, not *consent*. It can prove the agent said
 * something and the user answered; it cannot tell "yes" from "no", and it never will from here —
 * the words are in the audio, and the only thing that reads them is the model whose judgement is
 * the reason the gate exists. What it removes is the case where nobody was asked at all. A host
 * that needs actual consent should install [VoiceToolPolicy.approve] and put a button on it.
 *
 * Fails closed. A surface with no speech signal at all — a transport that never reports it — cannot
 * redeem a token, and irreversible tools are simply unavailable there. That is the correct way for
 * this to break.
 *
 * One gate per call: [HostVoiceCall] builds it and hands it to both the controller and the executor,
 * so tokens never survive a hangup.
 */
internal class VoiceConfirmationGate(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * One monotonic clock for both speakers, so their ORDER is answerable and not just their
     * occurrence: redemption needs agent-then-user, and two independent counters could each have
     * advanced without saying which came first.
     */
    private val turnSeq = AtomicLong(0)

    @Volatile private var lastAgentTurn = 0L
    @Volatile private var lastUserTurn = 0L

    private data class Pending(val fingerprint: String, val mintedAtTurn: Long, val expiresAtMs: Long)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** What [redeem] decided. */
    sealed interface Decision {
        /** Run it. */
        data object Allowed : Decision
        /** Don't run it; tell the agent to confirm out loud and call again with [token]. */
        data class Confirm(val token: String, val reason: String) : Decision
    }

    /** The Realtime server says the user just finished speaking. */
    fun userSpoke() {
        lastUserTurn = turnSeq.incrementAndGet()
    }

    /**
     * The agent just finished saying something — its chance to have announced the operation.
     *
     * Keyed off the API having finished SENDING the reply rather than the speaker having finished
     * playing it: the send completes within a second or two of the answer being generated, so a
     * user who interrupts partway through has still been spoken to, and waiting for playback would
     * make a barge-in look like an announcement that never happened.
     */
    fun agentSpoke() {
        lastAgentTurn = turnSeq.incrementAndGet()
    }

    /**
     * Decide whether a gated call may proceed.
     *
     * [token] is whatever the model passed in the `voice_confirm` argument, if anything.
     */
    fun redeem(tool: String, args: JsonObject, token: String?): Decision {
        gcExpired()
        val fingerprint = fingerprint(tool, args)
        if (token.isNullOrBlank()) {
            return Decision.Confirm(mint(fingerprint), "This cannot be undone.")
        }
        val held = pending[token]
        if (held == null || held.fingerprint != fingerprint || nowMs() > held.expiresAtMs) {
            // A stale, fabricated or mismatched token starts the protocol over rather than failing
            // the call: the agent gets a fresh token and one more chance to ask properly. Re-minting
            // is safe precisely because minting grants nothing — the speech check below is the gate.
            return Decision.Confirm(
                mint(fingerprint),
                if (held != null && held.fingerprint != fingerprint) {
                    "That confirmation was for a different operation."
                } else {
                    "That confirmation is no longer valid."
                },
            )
        }
        // Agent first, then user, both after the mint. Anything else is the agent confirming with
        // itself — or, in a barge-in, redeeming against the tail of the utterance that triggered
        // the call. Keep the token alive either way: the announcement may still be coming.
        val announced = lastAgentTurn > held.mintedAtTurn
        if (!announced) {
            return Decision.Confirm(token, "Say what you are about to do first, out loud.")
        }
        if (lastUserTurn <= lastAgentTurn) {
            return Decision.Confirm(token, "Wait for the user to answer, then call again.")
        }
        // Atomically, and this is not fussiness: four tool calls can be in flight at once
        // (MAX_IN_FLIGHT_TOOLS), so a read here and a remove there let two calls with the same tool,
        // arguments and token both pass the checks above before either consumed it — and both run an
        // irreversible operation off one confirmation. remove(key, value) is the compare-and-delete
        // that makes exactly one of them the winner.
        if (!pending.remove(token, held)) {
            return Decision.Confirm(mint(fingerprint), "That confirmation was already used.")
        }
        return Decision.Allowed
    }

    /** Drop every outstanding token — the call is over. */
    fun clear() {
        pending.clear()
    }

    private fun mint(fingerprint: String): String {
        val token = ByteArray(8).also { RANDOM.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        pending[token] = Pending(fingerprint, turnSeq.get(), nowMs() + TOKEN_TTL_MS)
        // Unbounded growth would need a model that asks for hundreds of confirmations in one call,
        // but a per-call map with no ceiling is the kind of thing that only looks fine.
        if (pending.size > MAX_PENDING) {
            // Tie-broken on the turn counter, not just the expiry. Several tokens minted inside one
            // clock tick share an expiry — guaranteed under an injected clock, likely under a real
            // one — and ConcurrentHashMap's iteration order is unspecified, so "oldest" could evict
            // the token this call just minted. It fails closed into a re-mint either way; being
            // exact costs one comparator.
            pending.entries.sortedWith(compareBy({ it.value.expiresAtMs }, { it.value.mintedAtTurn }))
                .take(pending.size - MAX_PENDING)
                .forEach { pending.remove(it.key) }
        }
        return token
    }

    private fun gcExpired() {
        val now = nowMs()
        pending.entries.removeIf { it.value.expiresAtMs < now }
    }

    /**
     * Tool + arguments, canonically ordered.
     *
     * Order-independent because the model re-serializes its own arguments between the two calls and
     * has no obligation to keep the key order it used the first time.
     */
    private fun fingerprint(tool: String, args: JsonObject): String =
        buildString {
            append(tool)
            args.entries.sortedBy { it.key }.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
        }

    internal companion object {
        private val RANDOM = SecureRandom()

        /**
         * How long a token stays good.
         *
         * Long enough for the agent to say a sentence and the user to answer it; short enough that a
         * token minted at the start of a call cannot be spent twenty minutes later against a
         * repository that has moved on.
         */
        const val TOKEN_TTL_MS = 3 * 60 * 1000L

        /** Outstanding tokens kept per call; the oldest go first. */
        const val MAX_PENDING = 32

        /** The argument the model passes a token back in. */
        const val CONFIRM_ARG = "voice_confirm"
    }
}
