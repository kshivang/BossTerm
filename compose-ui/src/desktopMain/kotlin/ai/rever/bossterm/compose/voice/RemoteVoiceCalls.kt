package ai.rever.bossterm.compose.voice

import ai.rever.bossterm.compose.notification.NotificationService
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * How many voice calls REMOTE viewers currently hold on this host, and the one place that says so.
 *
 * Two reasons this exists rather than each share notifying for itself:
 *
 *  - **The toast is not enough.** `NotificationService.showNotification` returns after a log line on
 *    everything except macOS, and the in-app Call pill is driven by [HostVoiceCall] — it never lights
 *    up for a viewer's call. So on Linux and Windows, someone with a control-share link could start a
 *    call able to run commands here and produce no user-visible signal at all. The comments around
 *    this notification describe it as doing security work, which makes that gap load-bearing.
 *    [active] is platform-independent, and a persistent indicator is a better fit than a transient
 *    toast even where toasts work.
 *  - The message copy was duplicated byte-for-byte in both share servers.
 *
 * Counted, not a boolean: a share can host several calls, and the host should see that.
 */
internal object RemoteVoiceCalls {

    private val log = LoggerFactory.getLogger(RemoteVoiceCalls::class.java)

    /**
     * Its own scope, on IO: a blocking subprocess must not ride a share's receive loop, and it must
     * not take a Dispatchers.Default worker either — those are sized for CPU work.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("boss-voice-notify"))

    private val _active = MutableStateFlow(0)

    /** Live remote calls across every share this process hosts. */
    val active: StateFlow<Int> = _active.asStateFlow()

    /**
     * Record a remote call starting or ending, and tell the host.
     *
     * [sessionName] names the share in the notification. The pairing ("ended" always follows
     * "started") is maintained by [VoiceCallService], which only reports an end for a call it
     * announced — so the count cannot drift downward past zero, but it is clamped anyway rather than
     * trusting a caller this indicator is a security signal for.
     */
    fun onActivity(sessionName: String?, started: Boolean) {
        // The count updates SYNCHRONOUSLY — it is the security-relevant half, it is just a StateFlow
        // write, and it must be true even if the toast fails.
        _active.update { current -> if (started) current + 1 else maxOf(0, current - 1) }

        // The toast does not. On macOS showNotification spawns `osascript` and blocks on waitFor(),
        // and the callers here are not background work: closeCall() runs from the share's WebSocket
        // receive loop (a viewer's voiceEnd, a viewer dropping), and flushExpiredAnnouncements() can
        // fire from any voiceToolCall that happens to sweep an expired call. A slow or hung osascript
        // there stalls every inbound frame for that viewer — keystrokes, resize, focus — and parks a
        // Ktor worker. That is the same shape as the dispatcher starvation VoiceAudioIo's KDoc cites.
        val label = sessionName?.takeIf { it.isNotBlank() } ?: "a shared session"
        val message = if (started) {
            "A viewer started a voice call on \"$label\" - the agent can read and run commands here"
        } else {
            "The voice call on \"$label\" ended"
        }
        scope.launch {
            runCatching {
                NotificationService.showNotification(
                    title = "BossTerm - Boss Calling",
                    message = message,
                    withSound = started,
                )
            }.onFailure { log.warn("Voice call notification failed: {}", it.javaClass.simpleName) }
        }
    }

    /** For tests: no share is live between them, so the count must not leak across. */
    internal fun resetForTest() = _active.update { 0 }
}
