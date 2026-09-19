package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.settings.SettingsManager
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/** Discover only installed binaries; never install a tool or write config for an absent one. */
object McpAutoAttachment {
    suspend fun installedTargets(): Set<McpAttachTarget> = withContext(Dispatchers.IO) {
        McpAttachTarget.entries.map { target ->
            async {
                val command = AIAssistants.findById(target.assistantId)?.command
                val binary = command?.let(CliBinaryResolver::resolve)?.let(::File)
                target.takeIf { binary != null && binary.isFile && binary.canExecute() }
            }
        }.awaitAll().filterNotNull().toSet()
    }

    private val log = LoggerFactory.getLogger(McpAutoAttachment::class.java)

    /** Each target fails independently, but disabling the server still cancels the whole batch. */
    internal suspend fun attachTargets(
        targets: Set<McpAttachTarget>,
        serverName: String,
        port: Int,
        stillRunning: () -> Boolean = { true },
        home: File = File(System.getProperty("user.home")),
        probe: suspend (Int) -> String? = McpInstanceProbe::liveServerName,
        attach: suspend (McpAttachTarget) -> McpAttachResult = {
            McpCliAttacher.attach(it, serverName, port, quiet = true)
        },
        onSuccess: (McpAttachTarget) -> Unit
    ) = coroutineScope {
        val completed = targets.map { target -> async<McpAttachTarget?>(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                if (!stillRunning()) return@async null
                val registration = McpRegistrationScanner.automaticRegistration(target, serverName, home)
                if (!registration.canAttach) {
                    log.info("Skipping automatic attachment for {}: existing registration is not managed by BossTerm", target.displayName)
                    return@async null
                }
                val registered = registration.port
                val owner = registered?.takeIf { it != port }?.let { probe(it) }
                if (!McpInstanceProbe.shouldRewrite(registered, port, serverName, owner)) return@async null
                currentCoroutineContext().ensureActive()
                if (!stillRunning()) return@async null
                val result = attach(target)
                currentCoroutineContext().ensureActive()
                if (result is McpAttachResult.Success && stillRunning()) target
                else {
                    if (result is McpAttachResult.CopiedToClipboard)
                        log.warn("Automatic attachment failed for {}: {}", target.displayName, result.reason)
                    null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.warn("Skipping automatic attachment for {}: {}", target.displayName, error.message)
                null
            }
        } }.awaitAll()
        // Registry updates are read/modify/write operations; publish successes serially.
        for (target in completed.filterNotNull()) {
            currentCoroutineContext().ensureActive()
            if (stillRunning()) onSuccess(target)
        }
    }

    /** The daemon has no GUI manager, but owns the same supported CLI registrations. */
    suspend fun attachInstalledToDaemon(port: Int, stillRunning: () -> Boolean) {
        if (!SettingsManager.instance.settings.value.mcpAutoAttachInstalled) return
        attachTargets(installedTargets(), "bossterm", port, stillRunning, onSuccess = { target ->
            SettingsManager.instance.updateSetting { copy(mcpAttachedTo = mcpAttachedTo + target.persistenceKey) }
        })
    }
}
