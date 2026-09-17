package ai.rever.bossterm.compose.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.*

/**
 * Receiver for tools invoked locally by Boss Calling, without an MCP client or transport.
 * Client-directed operations fail explicitly instead of inventing a remote client's response.
 */
internal object InProcessClientConnection : ClientConnection {
    override val sessionId: String = "bossterm-in-process"

    private fun unavailable(): Nothing =
        error("Client-directed MCP operations are unavailable for in-process tool calls")

    override suspend fun notification(notification: ServerNotification, relatedRequestId: RequestId?) = unavailable()

    override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult = unavailable()

    override suspend fun createMessage(request: CreateMessageRequest, options: RequestOptions?): CreateMessageResult =
        unavailable()

    override suspend fun listRoots(request: ListRootsRequest, options: RequestOptions?): ListRootsResult = unavailable()

    override suspend fun createElicitation(
        message: String,
        requestedSchema: ElicitRequestParams.RequestedSchema,
        options: RequestOptions?,
    ): ElicitResult = unavailable()

    override suspend fun createElicitation(
        message: String,
        elicitationId: String,
        url: String,
        options: RequestOptions?,
    ): ElicitResult = unavailable()

    override suspend fun createElicitation(request: ElicitRequest, options: RequestOptions?): ElicitResult = unavailable()

    override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) = unavailable()

    override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) = unavailable()

    override suspend fun sendResourceListChanged() = unavailable()

    override suspend fun sendToolListChanged() = unavailable()

    override suspend fun sendPromptListChanged() = unavailable()

    override suspend fun sendElicitationComplete(notification: ElicitationCompleteNotification) = unavailable()
}
