package ai.rever.bossterm.compose.daemon

import org.slf4j.LoggerFactory

/**
 * Dispatches authenticated control verbs (everything past HELLO/PING) against a [SessionHost].
 * Extracted from [DaemonMain] so it can be unit-tested in-process without spawning a JVM or a
 * socket. Returns the single response line (`OK [json]` / `ERR <message>`).
 */
class DaemonControlHandler(
    private val sessionHost: SessionHost,
    private val version: String,
    private val protocolVersion: Int,
    private val uptimeMs: () -> Long,
    private val mcpPort: () -> Int?,
    private val onShutdown: (killSessions: Boolean) -> Unit,
) {
    private val log = LoggerFactory.getLogger(DaemonControlHandler::class.java)
    private val json = DaemonProtocol.json

    fun handle(verb: String, arg: String): String { return try {
        when (verb) {
            DaemonProtocol.STATUS -> ok(
                json.encodeToString(
                    DaemonProtocol.Status.serializer(),
                    DaemonProtocol.Status(
                        pid = ProcessHandle.current().pid(),
                        version = version,
                        protocolVersion = protocolVersion,
                        uptimeMs = uptimeMs(),
                        sessionCount = sessionHost.count(),
                        mcpPort = mcpPort(),
                    )
                )
            )

            DaemonProtocol.OPEN_SESSION -> {
                val req = if (arg.isBlank()) DaemonProtocol.OpenSessionRequest()
                else json.decodeFromString(DaemonProtocol.OpenSessionRequest.serializer(), arg)
                val id = sessionHost.openSession(req.cwd, req.command, req.arguments, req.cols, req.rows)
                ok(json.encodeToString(DaemonProtocol.OpenSessionResponse.serializer(), DaemonProtocol.OpenSessionResponse(id)))
            }

            DaemonProtocol.LIST_SESSIONS -> ok(
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(SessionHost.SessionInfo.serializer()),
                    sessionHost.list()
                )
            )

            DaemonProtocol.CLOSE_SESSION -> {
                if (arg.isBlank()) return err("CLOSE_SESSION requires a session id")
                sessionHost.closeSession(arg.trim())
                ok()
            }

            DaemonProtocol.WRITE_INPUT -> {
                val req = json.decodeFromString(DaemonProtocol.WriteInputRequest.serializer(), arg)
                val core = sessionHost.get(req.id) ?: return err("no such session ${req.id}")
                core.writeInput(req.text)
                ok()
            }

            DaemonProtocol.RESIZE_SESSION -> {
                val req = json.decodeFromString(DaemonProtocol.ResizeRequest.serializer(), arg)
                val core = sessionHost.get(req.id) ?: return err("no such session ${req.id}")
                core.resize(req.cols, req.rows)
                ok()
            }

            DaemonProtocol.SHUTDOWN -> {
                val req = if (arg.isBlank()) DaemonProtocol.ShutdownRequest()
                else json.decodeFromString(DaemonProtocol.ShutdownRequest.serializer(), arg)
                onShutdown(req.killSessions)
                ok("stopping")
            }

            else -> err("unknown verb $verb")
        }
    } catch (e: Exception) {
        log.warn("verb {} failed: {}", verb, e.message)
        err(e.message ?: "error")
    } }

    private fun ok(payload: String = ""): String = if (payload.isEmpty()) "OK" else "OK $payload"
    private fun err(message: String): String = "ERR $message"
}
