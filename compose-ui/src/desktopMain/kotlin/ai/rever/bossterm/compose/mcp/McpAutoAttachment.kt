package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.settings.SettingsManager
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

    /** The daemon has no GUI manager, but owns the same supported CLI registrations. */
    suspend fun attachInstalledToDaemon(port: Int, stillRunning: () -> Boolean) = coroutineScope {
        if (!SettingsManager.instance.settings.value.mcpAutoAttachInstalled) return@coroutineScope
        installedTargets().map { target -> async(Dispatchers.IO) {
            if (!stillRunning()) return@async
            val registered = McpRegistrationScanner.registeredDefaultPort(target, "bossterm")
            val owner = registered?.takeIf { it != port }?.let { McpInstanceProbe.liveServerName(it) }
            if (!McpInstanceProbe.shouldRewrite(registered, port, "bossterm", owner)) return@async
            if (!stillRunning()) return@async
            val result = McpCliAttacher.attach(target, "bossterm", port, quiet = true)
            currentCoroutineContext().ensureActive()
            if (result is McpAttachResult.Success && stillRunning()) {
                SettingsManager.instance.updateSetting { copy(mcpAttachedTo = mcpAttachedTo + target.persistenceKey) }
            }
        } }.awaitAll()
    }
}
