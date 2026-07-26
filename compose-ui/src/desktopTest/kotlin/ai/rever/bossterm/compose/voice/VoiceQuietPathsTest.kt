package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.share.CallSegmentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Paths that fail QUIETLY, so a mistake in them has no symptom until someone checks by hand:
 * the stamp cache that carries cross-process revocation, the pill's ordered state mapping, and the
 * mint-response parsers.
 */
class VoiceQuietPathsTest {

    // ---- StampCachedValue: the only way a GUI toggle or a cleared key reaches a daemon share ----

    @Test
    fun `the cache re-reads when the stamp moves`() {
        var stamp = 1L
        var reads = 0
        val cache = StampCachedValue(stamp = { stamp }, read = { reads++; "v$stamp" }, trustTicks = 100)

        assertEquals("v1", cache.get())
        assertEquals("v1", cache.get(), "unchanged stamp → cached")
        assertEquals(1, reads)

        stamp = 2
        assertEquals("v2", cache.get(), "moved stamp → re-read")
        assertEquals(2, reads)
    }

    /**
     * mtime+size is collision-prone by construction — two booleans flipped in one write whose lengths
     * cancel out, inside one second, look unchanged. This is the escape hatch, and without it
     * revocation silently doesn't revoke.
     */
    @Test
    fun `an unchanged stamp is still re-read once trust runs out`() {
        var reads = 0
        val cache = StampCachedValue(stamp = { 7L }, read = { reads++; reads }, trustTicks = 3)

        assertEquals(1, cache.get()) // first read
        cache.get(); cache.get()     // ticks 1, 2 — still cached
        assertEquals(1, reads, "trust not yet exhausted")
        assertEquals(2, cache.get(), "third unchanged read forces a refresh")
    }

    @Test
    fun `a moved stamp does not also consume a tick`() {
        // A file that changes on every read must never hit the forced refresh it doesn't need:
        // if the stamp check consumed a tick, the two conditions would interfere.
        var stamp = 0L
        var reads = 0
        val cache = StampCachedValue(stamp = { ++stamp }, read = { reads++; stamp }, trustTicks = 2)
        repeat(6) { cache.get() }
        assertEquals(6, reads, "one read per change, no extra forced refreshes")
    }

    @Test
    fun `a failing read is retried rather than cached as absent`() {
        var fail = true
        val cache = StampCachedValue<String>(stamp = { 1L }, read = { if (fail) null else "ok" }, trustTicks = 100)
        assertEquals(null, cache.get(), "unreadable file → no value")
        fail = false
        assertEquals("ok", cache.get(), "a null is not cached, so recovery is possible")
    }

    // ---- segmentState: eleven ordered branches, and the ordering is load-bearing ----

    private fun state(
        phase: HostCallPhase = HostCallPhase.Idle,
        speaking: Boolean = false,
        working: Boolean = false,
    ) = HostCallState(phase = phase, speaking = speaking, working = working)

    @Test
    fun `the pill reflects the call before anything else`() {
        assertEquals(
            CallSegmentState.Connecting,
            state(HostCallPhase.Connecting).segmentState(pillEnabled = true, keyPresent = true),
        )
        assertEquals(
            CallSegmentState.Working,
            state(HostCallPhase.Live, working = true).segmentState(pillEnabled = true, keyPresent = true),
        )
        assertEquals(
            CallSegmentState.Speaking,
            state(HostCallPhase.Live, speaking = true).segmentState(pillEnabled = true, keyPresent = true),
        )
        // Working outranks speaking: the agent can be mid-sentence while a tool runs, and "what is it
        // doing" is the more useful answer.
        assertEquals(
            CallSegmentState.Working,
            state(HostCallPhase.Live, speaking = true, working = true)
                .segmentState(pillEnabled = true, keyPresent = true),
        )
    }

    @Test
    fun `a live call stays visible even with the indicator turned off`() {
        // Otherwise ending the call would become unreachable.
        assertEquals(
            CallSegmentState.Live,
            state(HostCallPhase.Live).segmentState(pillEnabled = false, keyPresent = true),
        )
        assertEquals(
            CallSegmentState.Connecting,
            state(HostCallPhase.Connecting).segmentState(pillEnabled = false, keyPresent = true),
        )
    }

    @Test
    fun `disabling the feature hides a stale failure`() {
        val failed = HostCallState(phase = HostCallPhase.Error, error = "boom")
        assertEquals(CallSegmentState.Failed, failed.segmentState(pillEnabled = true, keyPresent = true))
        assertEquals(
            CallSegmentState.Hidden,
            failed.segmentState(pillEnabled = false, keyPresent = true),
            "turning it off must not leave a 'failed' pill with nothing behind it",
        )
    }

    @Test
    fun `an idle host shows ready or needs-key, and nothing when disabled`() {
        assertEquals(CallSegmentState.Ready, state().segmentState(pillEnabled = true, keyPresent = true))
        assertEquals(CallSegmentState.NeedsKey, state().segmentState(pillEnabled = true, keyPresent = false))
        assertEquals(CallSegmentState.Hidden, state().segmentState(pillEnabled = false, keyPresent = true))
    }

    // ---- mint parsing: the GA shape, the beta fallback, and error extraction ----

    @Test
    fun `the GA response shape is parsed`() {
        val r = VoiceSessionBroker().parseMint("""{"value":"ek_ga","expires_at":123}""")
        assertIs<VoiceSessionBroker.MintResult.Ok>(r)
        assertEquals("ek_ga", r.clientSecret)
    }

    @Test
    fun `the older beta shape still works`() {
        val r = VoiceSessionBroker().parseMint("""{"client_secret":{"value":"ek_beta"}}""")
        assertIs<VoiceSessionBroker.MintResult.Ok>(r)
        assertEquals("ek_beta", r.clientSecret)
    }

    @Test
    fun `a response with no secret is a failure, not an empty success`() {
        assertIs<VoiceSessionBroker.MintResult.Failed>(VoiceSessionBroker().parseMint("""{"ok":true}"""))
        assertIs<VoiceSessionBroker.MintResult.Failed>(VoiceSessionBroker().parseMint("not json"))
        // A non-string `value` must not be coerced into a secret.
        assertIs<VoiceSessionBroker.MintResult.Failed>(VoiceSessionBroker().parseMint("""{"value":42}"""))
    }

    @Test
    fun `error detail prefers OpenAI's message and never returns blank`() {
        val broker = VoiceSessionBroker()
        assertEquals(
            "Project is not usable",
            broker.errorDetail("""{"error":{"message":"Project is not usable","type":"server_error"}}"""),
        )
        assertEquals("(empty body)", broker.errorDetail(""))
        // A non-envelope body is clipped rather than dropped, so the log still says something.
        assertTrue(broker.errorDetail("<html>gateway timeout</html>").contains("gateway timeout"))
    }
}
