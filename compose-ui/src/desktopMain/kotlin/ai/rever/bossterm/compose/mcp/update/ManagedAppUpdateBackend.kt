package ai.rever.bossterm.compose.mcp.update

import ai.rever.bossterm.compose.update.UpdateManager
import ai.rever.bossterm.compose.update.UpdateResult
import ai.rever.bossterm.compose.update.UpdateState

internal class ManagedAppUpdateBackend(
    private val manager: UpdateManager = UpdateManager.instance,
) : AppUpdateBackend {
    override fun snapshot(): AppUpdateSnapshot {
        val state = manager.updateState.value
        val ready = state as? UpdateState.ReadyToInstall
        val info = manager.updateInfo.value
        return AppUpdateSnapshot(
            app = "BossTerm",
            currentVersion = manager.getCurrentVersion().toString(),
            state = stateName(state),
            latestVersion = info?.latestVersion?.toString(),
            progress = (state as? UpdateState.Downloading)?.progress,
            error = (state as? UpdateState.Error)?.message,
            installVersion = if (ready != null) info?.latestVersion?.toString() else null,
        )
    }

    override suspend fun perform(action: String, version: String?) {
        when (action) {
            "check" -> requireSuccess(manager.checkForUpdates())
            "download" -> {
                val available = manager.updateState.value as? UpdateState.UpdateAvailable
                checkNotNull(available) { "Available update changed; check status again" }
                requireSuccess(manager.downloadUpdate(available.updateInfo))
            }
            "install" -> {
                val ready = manager.updateState.value as? UpdateState.ReadyToInstall
                check(ready != null && manager.updateInfo.value?.latestVersion?.toString() == version) {
                    "Staged version changed; check status again"
                }
                check(manager.installUpdate(ready)) {
                    (manager.updateState.value as? UpdateState.Error)?.message ?: "Update was not installed"
                }
            }
        }
    }

    private fun requireSuccess(result: UpdateResult) {
        check(result !is UpdateResult.Error) { (result as UpdateResult.Error).message }
    }
}

private fun stateName(state: UpdateState): String =
    when (state) {
        UpdateState.Idle -> "idle"
        UpdateState.CheckingForUpdates -> "checking"
        UpdateState.UpToDate -> "up_to_date"
        is UpdateState.UpdateAvailable -> "available"
        is UpdateState.Downloading -> "downloading"
        is UpdateState.ReadyToInstall -> "ready_to_install"
        is UpdateState.InstallOnNextRestart -> "install_on_next_restart"
        UpdateState.Installing -> "installing"
        UpdateState.RestartRequired -> "restart_required"
        is UpdateState.Error -> "error"
    }
