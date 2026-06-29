package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.CompletableDeferred

/**
 * A connected browser viewer of a daemon-hosted share. The headless counterpart to
 * [ai.rever.bossterm.compose.share.ViewerConnection]: the share server pushes pre-encoded JSON
 * frames into [outbox] and the per-connection Ktor coroutine drains them (encrypting each when a
 * cipher was negotiated). The outbox has two lanes (see [FrameOutbox]): control frames (snapshot /
 * layout / presence / ...) are guaranteed, while incremental PaneOutput is bounded + DROP_OLDEST so
 * a slow viewer never back-pressures the PTY taps — it just misses intermediate output, and the next
 * [ai.rever.bossterm.compose.share.ServerMessage.PaneSnapshot] heals it. [canControl] is mutable
 * because an approved mid-session control upgrade flips a live view-only connection without a reconnect.
 */
internal class DaemonShareConnection(
    val id: Int,
    @Volatile var canControl: Boolean,
    /** Device label from the viewer's Hello — shown to attached GUIs in the approval list. */
    val name: String,
) {
    val outbox = FrameOutbox(outputCapacity = 2048)
}

/**
 * A viewer awaiting the host's approve/deny decision (issue #276 approval workflow). The connection
 * coroutine parks on [decision]; an attached GUI resolves it via
 * [DaemonShareServer.approveViewer]/[DaemonShareServer.denyViewer]. [control] records whether the
 * device used the control link, so a single approve can grant typing.
 */
internal class DaemonPendingViewer(
    val token: String,
    val clientId: String,
    val name: String?,
    val control: Boolean,
    /** Remote host/IP of the parked viewer — used to cap pending connections per source. */
    val remoteHost: String = "?",
) {
    /** true = approved (with [control]'s role); false = denied / timed out. */
    val decision = CompletableDeferred<Boolean>()
}
