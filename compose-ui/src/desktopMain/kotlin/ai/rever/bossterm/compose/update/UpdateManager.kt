package ai.rever.bossterm.compose.update

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    object Installing : UpdateState()
    object RestartRequired : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * Central update manager that handles periodic update checks and state management.
 */
class UpdateManager internal constructor(private val operations: UpdateOperations?) {
    constructor() : this(null)

    internal val updateService = DesktopUpdateService()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _lastCheckTime = MutableStateFlow<Long?>(null)
    val lastCheckTime: StateFlow<Long?> = _lastCheckTime.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private var periodicCheckJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        val instance = UpdateManager()
    }

    /**
     * Start periodic update checks.
     */
    fun startPeriodicChecks() {
        if (!UpdateSettings.autoCheckEnabled) return

        periodicCheckJob?.cancel()
        periodicCheckJob = scope.launch {
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
            runCheck()
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
                    UpdateResult.UpdateAvailable(updateInfo)
                }
                else -> {
                    _updateState.value = UpdateState.UpToDate
                    UpdateResult.NoUpdateAvailable
                }
            }
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
        downloadAvailableUpdate(updateInfo)
    }

    private suspend fun downloadAvailableUpdate(updateInfo: UpdateInfo): UpdateResult {
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
        else installStagedUpdate(expected.downloadPath)
    }

    private suspend fun installStagedUpdate(downloadPath: String): Boolean {
        return try {
            val success = operations?.install?.invoke(downloadPath) ?: updateService.installUpdate(downloadPath)
            if (success) {
                _updateState.value = UpdateState.RestartRequired
            } else {
                _updateState.value = UpdateState.Error("Installation failed")
            }
            success
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
        if (state !is UpdateState.Downloading && state != UpdateState.Installing && state != UpdateState.RestartRequired) {
            _updateState.compareAndSet(state, UpdateState.Idle)
        }
    }

    private fun updateInProgress(): Boolean = when (_updateState.value) {
        is UpdateState.Downloading, is UpdateState.ReadyToInstall,
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
)
