package ai.rever.bossterm.compose.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateMcpOwnershipTest {
    private val info = UpdateInfo(true, Version(1, 0, 0), Version(2, 0, 0), "notes")

    @Test
    fun `version bound install refuses replacement at the same path`() = runTest {
        var installs = 0
        val manager = UpdateManager(UpdateOperations({ info }, { _, _ -> "/staged/update.dmg" }, { installs++; true }))
        try {
            manager.checkForUpdates()
            manager.downloadUpdate(info)
            val approved = assertIs<UpdateState.ReadyToInstall>(manager.updateState.value)
            manager.resetState()
            manager.downloadUpdate(info.copy(latestVersion = Version(3, 0, 0)))
            assertFalse(manager.installUpdate(approved))
            assertEquals(0, installs)
            assertIs<UpdateState.ReadyToInstall>(manager.updateState.value)
        } finally { manager.cleanup() }
    }

    @Test
    fun `periodic check cannot replace a staged download`() = runTest {
        var checks = 0
        val manager = UpdateManager(UpdateOperations({ checks++; info }, { _, _ -> "/staged/update.dmg" }, { true }))
        try {
            manager.checkForUpdates()
            manager.downloadUpdate(info)
            manager.checkForUpdates()
            assertIs<UpdateState.ReadyToInstall>(manager.updateState.value)
            assertEquals(1, checks)
        } finally { manager.cleanup() }
    }

    @Test
    fun `simultaneous install calls invoke installer only once`() = runTest {
        val finish = CompletableDeferred<Unit>()
        var installs = 0
        val manager = UpdateManager(UpdateOperations({ info }, { _, _ -> "/staged/update.dmg" }, {
            installs++
            finish.await()
            true
        }))
        try {
            manager.checkForUpdates()
            manager.downloadUpdate(info)
            assertFalse(manager.installUpdate("/arbitrary/file.dmg"))
            val first = async { manager.installUpdate("/staged/update.dmg") }
            runCurrent()
            assertFalse(manager.installUpdate("/staged/update.dmg"))
            manager.resetState()
            assertIs<UpdateState.Installing>(manager.updateState.value)
            finish.complete(Unit)
            assertTrue(first.await())
            assertEquals(1, installs)
            assertIs<UpdateState.RestartRequired>(manager.updateState.value)
        } finally { manager.cleanup() }
    }
}
