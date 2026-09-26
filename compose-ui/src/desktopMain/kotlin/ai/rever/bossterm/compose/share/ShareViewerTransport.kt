package ai.rever.bossterm.compose.share

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable

/** The legacy socket and a relay peer share the same E2E handshake, approvals and role checks. */
internal interface ShareViewerTransport : CoroutineScope {
    val incoming: ReceiveChannel<Frame>
    suspend fun send(frame: Frame)
    suspend fun close(reason: CloseReason)
    suspend fun snapshot(frame: Frame, pane: String, epoch: String, sequence: Long) = send(frame)
}

@Serializable
internal data class RelayPrivateEnvelope(val sequence: Long, val payload: String)

internal interface RelayViewerLifecycle {
    suspend fun admitted(
        share: MirrorShare,
        viewer: ViewerConnection,
        sendPrivate: suspend (payload: String, snapshot: RelaySnapshotBoundary?) -> Unit,
    )
    fun layoutChanged(share: MirrorShare)
    fun graphicsResync(pane: String, relaySequence: Long? = null) {}
    fun disconnected()
}

internal data class RelaySnapshotBoundary(val pane: String, val epoch: String, val sequence: Long)
