package ai.rever.bossterm.compose.daemon

import kotlinx.coroutines.CompletableDeferred

/**
 * A connected browser viewer of a daemon-hosted share. The headless counterpart to
 * [ai.rever.bossterm.compose.share.ViewerConnection]: the share server pushes control frames
 * (pre-encoded JSON) and raw pane-output chunks into [outbox] and the per-connection Ktor coroutine
 * drains them (encoding output to PaneOutput at drain time, encrypting each frame when a cipher was
 * negotiated). The outbox has two lanes (see [FrameOutbox]): control frames (snapshot / layout /
 * presence / ...) are guaranteed, while incremental pane output is char-bounded drop-oldest so a
 * slow viewer never back-pressures the PTY taps — each drop schedules a healing
 * [ai.rever.bossterm.compose.share.ServerMessage.PaneSnapshot] for the affected pane. [canControl]
 * is mutable because an approved mid-session control upgrade flips a live view-only connection
 * without a reconnect.
 */
internal class DaemonShareConnection(
    val id: Int,
    @Volatile var canControl: Boolean,
    /** Device label from the viewer's Hello — shown to attached GUIs in the approval list. */
    val name: String,
) {
    // Tighter budget than the attach outbox: browser viewers ride real (possibly remote) links, and
    // a healing re-snapshot replaces a big stale backlog more cheaply than delivering it.
    val outbox = FrameOutbox(outputCapacityChars = 2 * 1024 * 1024)
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

    /** The control role actually granted at approval — `control && hostChoice` (a downgrade only:
     *  a view-link viewer is never upgraded). Read by serveViewer once [decision] completes true. */
    @Volatile var grantedControl: Boolean = false
}
