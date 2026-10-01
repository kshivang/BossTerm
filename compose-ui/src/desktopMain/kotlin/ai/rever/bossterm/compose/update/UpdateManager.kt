package ai.rever.bossterm.compose.update

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Update state sealed class representing the current update status.
 */
sealed class UpdateState {
    object Idle : UpdateState()
    object CheckingForUpdates : UpdateState()
    object UpToDate : UpdateState()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateState()
    data class Downloading(val progress: Float) : UpdateState()
    data class ReadyToInstall(val downloadPath: String) : UpdateState()
    data class InstallOnNextRestart(val downloadPath: String) : UpdateState()
    object Installing : UpdateState()
    object RestartRequired : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/** Central update manager for update checks, downloads, and installation. */
class UpdateManager internal constructor(
    private val operations: UpdateOperations?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    constructor() : this(null)

    internal val updateService = DesktopUpdateService()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _lastCheckTime = MutableStateFlow<Long?>(null)
    val lastCheckTime: StateFlow<Long?> = _lastCheckTime.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private var periodicCheckJob: Job? = null
    private var automaticUpdateJob: Job? = null
    private val initializationMutex = Mutex()
    private var initialized = false
    private val completedUpdate = MutableStateFlow<Version?>(null)

    /** Only one window claims the startup notification for this installation. */
    fun takeCompletedUpdateNotification(): Version? = completedUpdate.getAndUpdate { null }

    /** Load preferences once across all standalone windows before checking. */
    suspend fun initialize() = initializationMutex.withLock {
        if (initialized) return@withLock
        UpdateSettingsManager.loadSettings()
        completedUpdate.value = withContext(Dispatchers.IO) {
            try {
                UpdateLaunchTracker.forCurrentInstallation().recordLaunch(getCurrentVersion())
            } catch (e: Exception) {
                println("Could not record update launch version: ${e.message}")
                null
            }
        }
        startAutomaticUpdates()
        // Network checks must not delay the notification for an already completed update.
        if (shouldCheckForUpdates()) scope.launch { checkForUpdates() }
        startRealtimePush()
        initialized = true
    }

    companion object {
        val instance = UpdateManager()
    }

    /** Start only from the standalone app; embedded terminals never self-update. */
    fun startAutomaticUpdates() {
        if (automaticUpdateJob != null) return
        automaticUpdateJob = scope.launch {
            UpdateSettings.settings.map { it.autoUpdateEnabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    checkMutex.withLock {
                        if (!UpdateSettings.autoUpdateEnabled) return@withLock
                        when (val state = _updateState.value) {
                            is UpdateState.ReadyToInstall -> installUpdateInternal(state.downloadPath, restartAutomatically = false)
                            is UpdateState.UpdateAvailable -> applyAutomaticUpdate(state.updateInfo)
                            is UpdateState.RestartRequired, is UpdateState.InstallOnNextRestart, is UpdateState.Installing -> Unit
                            else -> runCheck()
                        }
                    }
                    if (UpdateSettings.autoUpdateEnabled) startPeriodicChecks(checkImmediately = false)
                } else {
                    stopPeriodicChecks()
                }
            }
        }
    }

    private suspend fun applyAutomaticUpdate(info: UpdateInfo) {
        if (!UpdateSettings.autoUpdateEnabled) return
        downloadUpdateInternal(info)
        val ready = _updateState.value as? UpdateState.ReadyToInstall ?: return
        // Turning the mode off during a download leaves it ready for manual installation.
        if (UpdateSettings.autoUpdateEnabled) installUpdateInternal(ready.downloadPath, restartAutomatically = false)
    }

    /**
     * Start periodic update checks.
     */
    fun startPeriodicChecks(checkImmediately: Boolean = true) {
        if (!UpdateSettings.autoCheckEnabled) return

        periodicCheckJob?.cancel()
        periodicCheckJob = scope.launch {
            if (!checkImmediately) delay(UpdateSettings.checkIntervalHours * 60 * 60 * 1000)
            while (isActive) {
                try {
                    checkForUpdatesInternal()
                    delay(UpdateSettings.checkIntervalHours * 60 * 60 * 1000)
                } catch (e: Exception) {
                    println("Error in periodic update check: ${e.message}")
                    delay(60 * 60 * 1000) // Retry in 1 hour on error
                }
            }
        }
    }

    /**
     * Stop periodic update checks.
     */
    fun stopPeriodicChecks() {
        periodicCheckJob?.cancel()
        periodicCheckJob = null
    }

    /**
     * Start Supabase Realtime push so a newly published release triggers an update
     * check the moment it lands — no polling. Idempotent; a no-op unless a Supabase
     * anon key is configured (otherwise update checks rely on the GitHub backup).
     * Call once at app startup.
     */
    fun startRealtimePush() {
        AppUpdateRealtimeService.instance.onReleaseChanged = { checkForUpdates() }
        AppUpdateRealtimeService.instance.start()
    }

    /**
     * Manually check for updates.
     */
    suspend fun checkForUpdates(): UpdateResult {
        return checkForUpdatesInternal()
    }

    // Coalesces concurrent checks: the startup check and the Realtime on-connect
    // catch-up fire near-simultaneously, and each Realtime event launches its own.
    private val checkMutex = Mutex()

    private suspend fun checkForUpdatesInternal(): UpdateResult {
        if (!checkMutex.tryLock()) {
            val info = _updateInfo.value
            return if (info != null && info.isNewerVersionAvailable) {
                UpdateResult.UpdateAvailable(info)
            } else {
                UpdateResult.NoUpdateAvailable
            }
        }
        return try {
            when (val state = _updateState.value) {
                is UpdateState.ReadyToInstall -> {
                    if (UpdateSettings.autoUpdateEnabled) installUpdateInternal(state.downloadPath, restartAutomatically = false)
                    _updateInfo.value?.let { UpdateResult.UpdateAvailable(it) } ?: UpdateResult.NoUpdateAvailable
                }
                is UpdateState.RestartRequired, is UpdateState.InstallOnNextRestart, is UpdateState.Installing -> UpdateResult.NoUpdateAvailable
                else -> runCheck()
            }
        } finally {
            checkMutex.unlock()
        }
    }

    private suspend fun runCheck(): UpdateResult {
        if (updateInProgress()) return UpdateResult.NoUpdateAvailable
        return try {
            _updateState.value = UpdateState.CheckingForUpdates
            _lastCheckTime.value = System.currentTimeMillis()

            val updateInfo = operations?.check?.invoke() ?: updateService.checkForUpdates()
            _updateInfo.value = updateInfo

            when {
                updateInfo.isNewerVersionAvailable -> {
                    _updateState.value = UpdateState.UpdateAvailable(updateInfo)
                    applyAutomaticUpdate(updateInfo)
                    UpdateResult.UpdateAvailable(updateInfo)
                }
                else -> {
                    _updateState.value = UpdateState.UpToDate
                    UpdateResult.NoUpdateAvailable
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
            UpdateResult.Error("Failed to check for updates", e)
        }
    }

    /**
     * Download the available update.
     */
    suspend fun downloadUpdate(updateInfo: UpdateInfo): UpdateResult = checkMutex.withLock {
        if (updateInProgress()) return@withLock UpdateResult.Error("An update is already in progress")
        _updateInfo.value = updateInfo
        downloadUpdateInternal(updateInfo)
    }

    private suspend fun downloadUpdateInternal(updateInfo: UpdateInfo): UpdateResult {
        return try {
            _updateState.value = UpdateState.Downloading(0f)

            val download = operations?.download ?: updateService::downloadUpdate
            val downloadPath = download(updateInfo) { progress ->
                _updateState.value = UpdateState.Downloading(progress)
            }

            if (downloadPath != null) {
                _updateState.value = UpdateState.ReadyToInstall(downloadPath)
                UpdateResult.UpdateAvailable(updateInfo)
            } else {
                val errorMsg = "Failed to download update"
                _updateState.value = UpdateState.Error(errorMsg)
                UpdateResult.Error(errorMsg)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "Download failed: ${e.message}"
            _updateState.value = UpdateState.Error(errorMsg)
            UpdateResult.Error(errorMsg, e)
        }
    }

    /**
     * Install the downloaded update.
     */
    suspend fun installUpdate(downloadPath: String): Boolean {
        val expected = _updateState.value as? UpdateState.ReadyToInstall ?: return false
        return if (expected.downloadPath == downloadPath) installUpdate(expected) else false
    }

    /** An approval belongs to this staged artifact, not merely its possibly reusable filename. */
    internal suspend fun installUpdate(expected: UpdateState.ReadyToInstall): Boolean = checkMutex.withLock {
        if (_updateState.value !== expected || !_updateState.compareAndSet(expected, UpdateState.Installing)) false
        else installUpdateInternal(expected.downloadPath)
    }

    private suspend fun installUpdateInternal(downloadPath: String, restartAutomatically: Boolean = true): Boolean {
        return try {
            _updateState.value = UpdateState.Installing

            val install = if (restartAutomatically) operations?.install else operations?.schedule
            val success = install?.invoke(downloadPath) ?: updateService.installUpdate(downloadPath, restartAutomatically)
            if (success) {
                _updateState.value = if (restartAutomatically) UpdateState.RestartRequired
                    else UpdateState.InstallOnNextRestart(downloadPath)
            } else {
                _updateState.value = UpdateState.Error("Installation failed")
            }
            success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("Installation failed: ${e.message}")
            false
        }
    }

    /**
     * Get current application version.
     */
    fun getCurrentVersion(): Version = Version.CURRENT

    /**
     * Check if enough time has passed since last check.
     */
    fun shouldCheckForUpdates(): Boolean {
        if (!UpdateSettings.autoCheckEnabled) return false
        val lastCheck = _lastCheckTime.value ?: return true
        val now = System.currentTimeMillis()
        val hoursSinceLastCheck = (now - lastCheck) / (1000 * 60 * 60)
        return hoursSinceLastCheck >= UpdateSettings.checkIntervalHours
    }

    /**
     * Reset update state to idle.
     */
    fun resetState() {
        val state = _updateState.value
        if (!checkMutex.isLocked && state !is UpdateState.Downloading && state != UpdateState.Installing &&
            state != UpdateState.RestartRequired && state !is UpdateState.InstallOnNextRestart) {
            _updateState.compareAndSet(state, UpdateState.Idle)
        }
    }

    private fun updateInProgress(): Boolean = when (_updateState.value) {
        is UpdateState.Downloading, is UpdateState.ReadyToInstall, is UpdateState.InstallOnNextRestart,
        UpdateState.Installing, UpdateState.RestartRequired -> true
        else -> false
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        stopPeriodicChecks()
        scope.cancel()
    }
}

/** Test seam at the actual updater boundary; production always uses DesktopUpdateService. */
internal class UpdateOperations(
    val check: suspend () -> UpdateInfo,
    val download: suspend (UpdateInfo, (Float) -> Unit) -> String?,
    val install: suspend (String) -> Boolean,
    val schedule: suspend (String) -> Boolean = install,
)
