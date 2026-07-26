package ai.rever.bossterm.compose.voice

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The host's only PLATFORM-INDEPENDENT signal that a remote viewer's agent is on this machine.
 *
 * `NotificationService.showNotification` returns after a log line on everything but macOS, and the
 * in-app Call pill is driven by HostVoiceCall — it never lights up for a viewer's call. So on Linux
 * and Windows this count is the whole signal, which makes it worth asserting rather than assuming.
 */
class RemoteVoiceCallsTest {

    @BeforeTest
    fun reset() = RemoteVoiceCalls.resetForTest()

    @AfterTest
    fun clear() = RemoteVoiceCalls.resetForTest()

    @Test
    fun `the count follows calls starting and ending`() {
        assertEquals(0, RemoteVoiceCalls.active.value)
        RemoteVoiceCalls.onActivity("session-a", started = true)
        assertEquals(1, RemoteVoiceCalls.active.value)
        // Several calls can be live on one host — the indicator says how many, not merely "some".
        RemoteVoiceCalls.onActivity("session-b", started = true)
        assertEquals(2, RemoteVoiceCalls.active.value)
        RemoteVoiceCalls.onActivity("session-a", started = false)
        assertEquals(1, RemoteVoiceCalls.active.value)
        RemoteVoiceCalls.onActivity("session-b", started = false)
        assertEquals(0, RemoteVoiceCalls.active.value)
    }

    /**
     * VoiceCallService only reports an end for a call it announced, so this should not be reachable
     * — but a security indicator that can be driven NEGATIVE would then read "no calls" while one is
     * live, so it is clamped rather than trusted.
     */
    @Test
    fun `an unpaired end cannot drive the count below zero`() {
        RemoteVoiceCalls.onActivity("session-a", started = false)
        assertEquals(0, RemoteVoiceCalls.active.value)

        RemoteVoiceCalls.onActivity("session-a", started = true)
        RemoteVoiceCalls.onActivity("session-a", started = false)
        RemoteVoiceCalls.onActivity("session-a", started = false)
        assertEquals(0, RemoteVoiceCalls.active.value)

        // And a real call after that still registers, rather than starting from a negative debt.
        RemoteVoiceCalls.onActivity("session-a", started = true)
        assertEquals(1, RemoteVoiceCalls.active.value)
    }

    /**
     * The caller is a share's WebSocket receive loop — closeCall() runs there when a viewer sends
     * voiceEnd or drops. On macOS the notification spawns `osascript` and blocks on waitFor(), so
     * doing it inline stalled every inbound frame for that viewer (keystrokes, resize, focus) and
     * parked a Ktor worker behind a subprocess.
     */
    @Test
    fun `recording a call never blocks the caller`() {
        val elapsed = mutableListOf<Long>()
        repeat(6) { i ->
            val began = System.nanoTime()
            RemoteVoiceCalls.onActivity("session-$i", started = i % 2 == 0)
            elapsed.add((System.nanoTime() - began) / 1_000_000)
        }
        // Generous: a StateFlow write is microseconds, and a spawned osascript is tens of ms at best.
        // The point is that nothing here waits on a subprocess.
        assertTrue(elapsed.all { it < 150 }, "onActivity must return immediately; saw ${elapsed}ms")
    }

    @Test
    fun `the count is correct the instant the call returns`() {
        // The indicator is a security signal, so it cannot lag behind the event it reports even by
        // one dispatch — only the toast is allowed to be asynchronous.
        RemoteVoiceCalls.onActivity("s", started = true)
        assertEquals(1, RemoteVoiceCalls.active.value)
        RemoteVoiceCalls.onActivity("s", started = false)
        assertEquals(0, RemoteVoiceCalls.active.value)
    }

    @Test
    fun `a nameless share still notifies`() {
        // The daemon can host a share with no display name; the message must not read "null".
        RemoteVoiceCalls.onActivity(null, started = true)
        RemoteVoiceCalls.onActivity("", started = true)
        assertEquals(2, RemoteVoiceCalls.active.value)
    }
}
