package ai.rever.bossterm.compose.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticUpdateTest {
    private val savedSettings = UpdateSettings.toData()

    @AfterTest
    fun restoreSettings() {
        UpdateSettings.loadFromData(savedSettings)
    }

    private class FakeUpdates {
        val info = UpdateInfo(true, Version(1, 0, 0), Version(2, 0, 0), "New release")
        var checks = 0
        var downloads = 0
        var installs = 0
        val restartPolicies = mutableListOf<Boolean>()
        val windowlessRestarts = mutableListOf<String>()
        var canPrepareWindowlessRestart = true
        fun operations() = UpdateOperations(::checkForUpdates, ::downloadUpdate,
            { installUpdate(it, true) }, { installUpdate(it, false) },
            prepareWindowlessRestart = { windowlessRestarts += it; canPrepareWindowlessRestart })
        var downloadPath: String? = "/tmp/bossterm-update"
        var installSuccess = true
        var downloadGate: CompletableDeferred<Unit>? = null
        suspend fun checkForUpdates(): UpdateInfo { checks++; return info }
        suspend fun downloadUpdate(updateInfo: UpdateInfo, onProgress: (Float) -> Unit): String? {
            downloads++
            onProgress(0.5f)
            downloadGate?.await()
            return downloadPath
        }
        suspend fun installUpdate(downloadPath: String, restartAutomatically: Boolean): Boolean {
            installs++
            restartPolicies += restartAutomatically
            return installSuccess
        }
    }

    @Test
    fun `automatic installation uses build default and explicit manual preference is preserved`() {
        val settings = Json.decodeFromString<UpdateSettingsData>("""{"autoCheckEnabled":true,"checkIntervalHours":6}""")
        assertEquals(defaultAutoUpdateEnabled(), UpdateSettingsData().autoUpdateEnabled)
        assertEquals(defaultAutoUpdateEnabled(), settings.autoUpdateEnabled)
        val manual = settings.copy(autoUpdateEnabled = false)
        val restored = Json.decodeFromString<UpdateSettingsData>(Json.encodeToString(UpdateSettingsData.serializer(), manual))
        assertEquals(manual, restored)
        assertFalse(restored.autoUpdateEnabled)
    }

    @Test
    fun `debug defaults off and release defaults on while saved choices survive both`() {
        val property = "bossterm.build.type"
        val original = System.getProperty(property)
        try {
            System.setProperty(property, "debug")
            assertFalse(UpdateSettingsData().autoUpdateEnabled)
            assertFalse(Json.decodeFromString<UpdateSettingsData>("{}").autoUpdateEnabled)
            val savedManual = Json.encodeToString(UpdateSettingsData.serializer(), UpdateSettingsData())

            System.setProperty(property, "release")
            assertTrue(UpdateSettingsData().autoUpdateEnabled)
            assertTrue(Json.decodeFromString<UpdateSettingsData>("{}").autoUpdateEnabled)
            assertFalse(Json.decodeFromString<UpdateSettingsData>(savedManual).autoUpdateEnabled)
            val savedAutomatic = Json.encodeToString(UpdateSettingsData.serializer(), UpdateSettingsData())

            System.setProperty(property, "debug")
            assertTrue(Json.decodeFromString<UpdateSettingsData>(savedAutomatic).autoUpdateEnabled)
            System.clearProperty(property)
            assertFalse(UpdateSettingsData().autoUpdateEnabled)
        } finally {
            if (original == null) System.clearProperty(property) else System.setProperty(property, original)
        }
    }

    @Test
    fun `testing flag enables debug automatic updates without changing normal debug default`() {
        assertFalse(defaultAutoUpdateEnabled("debug", false))
        assertTrue(defaultAutoUpdateEnabled("debug", true))
        assertTrue(defaultAutoUpdateEnabled("release", false))
        assertFalse(defaultAutoUpdateEnabled(null, false))
    }

    @Test
    fun `manual mode only announces an available update`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = false))
        val service = FakeUpdates()
        val manager = UpdateManager(service.operations(), backgroundScope)
        manager.checkForUpdates()
        assertIs<UpdateState.UpdateAvailable>(manager.updateState.value)
        assertEquals(0, service.downloads)
        assertEquals(0, service.installs)
    }

    @Test
    fun `automatic check downloads and schedules installation without restarting once`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = true))
        val service = FakeUpdates()
        val manager = UpdateManager(service.operations(), backgroundScope)
        manager.checkForUpdates()
        manager.checkForUpdates()
        assertIs<UpdateState.InstallOnNextRestart>(manager.updateState.value)
        assertEquals(1, service.checks)
        assertEquals(1, service.downloads)
        assertEquals(1, service.installs)
        assertEquals(listOf(false), service.restartPolicies)
    }

    @Test
    fun `enabling mode applies an already available release`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = false))
        val service = FakeUpdates()
        val manager = UpdateManager(service.operations(), backgroundScope)
        manager.startAutomaticUpdates()
        runCurrent()
        manager.checkForUpdates()
        UpdateSettings.autoUpdateEnabled = true
        runCurrent()
        assertIs<UpdateState.InstallOnNextRestart>(manager.updateState.value)
        assertEquals(1, service.checks)
        assertEquals(1, service.installs)
        assertEquals(listOf(false), service.restartPolicies)
    }

    @Test
    fun `failed download never installs and remains retryable`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = true))
        val service = FakeUpdates().apply { downloadPath = null }
        val manager = UpdateManager(service.operations(), backgroundScope)
        manager.checkForUpdates()
        assertIs<UpdateState.Error>(manager.updateState.value)
        assertEquals(0, service.installs)
        service.downloadPath = "/tmp/retry"
        manager.checkForUpdates()
        assertIs<UpdateState.InstallOnNextRestart>(manager.updateState.value)
        assertEquals(1, service.installs)
        assertEquals(listOf(false), service.restartPolicies)
    }

    @Test
    fun `turning mode off during download leaves manual install and coalesces checks`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = true))
        val gate = CompletableDeferred<Unit>()
        val service = FakeUpdates().apply { downloadGate = gate }
        val manager = UpdateManager(service.operations(), backgroundScope)
        val firstCheck = async { manager.checkForUpdates() }
        runCurrent()
        manager.checkForUpdates()
        assertIs<UpdateState.Downloading>(manager.updateState.value)
        assertEquals(1, service.checks)
        UpdateSettings.autoUpdateEnabled = false
        gate.complete(Unit)
        firstCheck.await()
        assertIs<UpdateState.ReadyToInstall>(manager.updateState.value)
        manager.checkForUpdates()
        assertIs<UpdateState.ReadyToInstall>(manager.updateState.value)
        assertEquals(0, service.installs)
    }

    @Test
    fun `explicit manual installation retains the immediate restart policy`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = false))
        val service = FakeUpdates()
        val manager = UpdateManager(service.operations(), backgroundScope)
        manager.checkForUpdates()
        manager.downloadUpdate(service.info)
        val ready = assertIs<UpdateState.ReadyToInstall>(manager.updateState.value)
        manager.installUpdate(ready.downloadPath)
        assertIs<UpdateState.RestartRequired>(manager.updateState.value)
        assertEquals(listOf(true), service.restartPolicies)
    }

    @Test
    fun `closing all windows installs staged update and relaunches without scheduling twice`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = true))
        val service = FakeUpdates()
        val manager = UpdateManager(service.operations(), backgroundScope)
        val windowsOpen = MutableStateFlow(true)
        var quits = 0
        backgroundScope.launch {
            manager.installAutomaticUpdatesWhenWindowless(windowsOpen, { !windowsOpen.value }) { quits++ }
        }
        manager.checkForUpdates()
        runCurrent()
        assertEquals(0, quits)
        windowsOpen.value = false
        runCurrent()
        assertEquals(1, quits)
        assertEquals(listOf("/tmp/bossterm-update"), service.windowlessRestarts)
        assertEquals(listOf(false), service.restartPolicies)
        assertEquals(1, service.installs)
        assertIs<UpdateState.RestartRequired>(manager.updateState.value)
        windowsOpen.value = true
        runCurrent()
        windowsOpen.value = false
        runCurrent()
        assertEquals(1, quits)
    }

    @Test
    fun `download completing without windows also triggers update`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = true))
        val gate = CompletableDeferred<Unit>()
        val service = FakeUpdates().apply { downloadGate = gate }
        val manager = UpdateManager(service.operations(), backgroundScope)
        val windowsOpen = MutableStateFlow(false)
        var quits = 0
        backgroundScope.launch {
            manager.installAutomaticUpdatesWhenWindowless(windowsOpen, { !windowsOpen.value }) { quits++ }
        }
        val check = async { manager.checkForUpdates() }
        runCurrent()
        assertEquals(0, quits)
        gate.complete(Unit)
        check.await()
        runCurrent()
        assertEquals(1, quits)
        assertEquals(1, service.windowlessRestarts.size)
    }

    @Test
    fun `window reopened while staging prevents idle restart`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = true))
        val gate = CompletableDeferred<Unit>()
        val service = FakeUpdates().apply { downloadGate = gate }
        val manager = UpdateManager(service.operations(), backgroundScope)
        var windowsOpen = false
        val check = async { manager.checkForUpdates() }
        runCurrent()
        val restart = async { manager.prepareAutomaticWindowlessRestart { !windowsOpen } }
        runCurrent()
        windowsOpen = true
        gate.complete(Unit)
        check.await()
        assertFalse(restart.await())
        assertEquals(0, service.windowlessRestarts.size)
        assertIs<UpdateState.InstallOnNextRestart>(manager.updateState.value)
    }

    @Test
    fun `disabling automatic updates prevents idle restart and reenabling applies staged update`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = true))
        val service = FakeUpdates()
        val manager = UpdateManager(service.operations(), backgroundScope)
        manager.checkForUpdates()
        UpdateSettings.autoUpdateEnabled = false
        val windowsOpen = MutableStateFlow(false)
        var quits = 0
        backgroundScope.launch {
            manager.installAutomaticUpdatesWhenWindowless(windowsOpen, { !windowsOpen.value }) { quits++ }
        }
        runCurrent()
        assertEquals(0, quits)
        assertFalse(manager.prepareAutomaticWindowlessRestart { true })
        UpdateSettings.autoUpdateEnabled = true
        runCurrent()
        assertEquals(1, quits)
    }

    @Test
    fun `manual download does not trigger a windowless update`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = false))
        val service = FakeUpdates()
        val manager = UpdateManager(service.operations(), backgroundScope)
        manager.checkForUpdates()
        manager.downloadUpdate(service.info)
        val windowsOpen = MutableStateFlow(false)
        var quits = 0
        backgroundScope.launch {
            manager.installAutomaticUpdatesWhenWindowless(windowsOpen, { true }) { quits++ }
        }
        runCurrent()
        assertEquals(0, quits)
        assertEquals(0, service.windowlessRestarts.size)
        assertIs<UpdateState.ReadyToInstall>(manager.updateState.value)
    }

    @Test
    fun `unavailable relaunch helper leaves app running without a retry loop`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = true))
        val service = FakeUpdates().apply { canPrepareWindowlessRestart = false }
        val manager = UpdateManager(service.operations(), backgroundScope)
        val windowsOpen = MutableStateFlow(false)
        var quits = 0
        backgroundScope.launch {
            manager.installAutomaticUpdatesWhenWindowless(windowsOpen, { true }) { quits++ }
        }
        manager.checkForUpdates()
        runCurrent()
        assertEquals(0, quits)
        assertEquals(1, service.windowlessRestarts.size)
        assertIs<UpdateState.InstallOnNextRestart>(manager.updateState.value)
    }

    @Test
    fun `installation error is visible in update state`() = runTest {
        UpdateSettings.loadFromData(UpdateSettingsData(autoUpdateEnabled = true))
        val service = FakeUpdates().apply { installSuccess = false }
        val manager = UpdateManager(service.operations(), backgroundScope)
        manager.checkForUpdates()
        assertIs<UpdateState.Error>(manager.updateState.value)
        assertEquals(1, service.installs)
        assertEquals(listOf(false), service.restartPolicies)
    }
}
