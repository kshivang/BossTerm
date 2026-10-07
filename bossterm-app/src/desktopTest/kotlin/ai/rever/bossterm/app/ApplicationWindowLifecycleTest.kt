package ai.rever.bossterm.app

import ai.rever.bossterm.compose.window.WindowManager
import androidx.compose.ui.window.WindowState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ApplicationWindowLifecycleTest {
    @BeforeTest
    @AfterTest
    fun clearWindows() {
        WindowManager.windows.toList().forEach { WindowManager.closeWindow(it.id) }
    }

    @Test
    fun closingAllMacWindowsKeepsAppRunningAndDockReopensFreshWindow() {
        var quitCount = 0
        val lifecycle = ApplicationWindowLifecycle(isMacOS = true) { quitCount++ }
        lifecycle.openInitialWindow()
        val first = WindowManager.windows.single()
        val second = WindowManager.createWindow()

        lifecycle.closeWindow(first.id)
        assertEquals(listOf(second), WindowManager.windows)
        lifecycle.closeWindow(second.id)
        assertEquals(0, WindowManager.windows.size)
        assertEquals(0, quitCount)

        lifecycle.reopen()
        val reopened = WindowManager.windows.single()
        assertNotEquals(first.id, reopened.id)
        assertNotEquals(second.id, reopened.id)
        assertEquals(0, quitCount)
    }

    @Test
    fun otherPlatformsQuitOnlyAfterLastWindowCloses() {
        var quitCount = 0
        val lifecycle = ApplicationWindowLifecycle(isMacOS = false) { quitCount++ }
        lifecycle.openInitialWindow()
        val first = WindowManager.windows.single()
        val second = WindowManager.createWindow()

        lifecycle.closeWindow(first.id)
        assertEquals(0, quitCount)
        lifecycle.closeWindow(second.id)
        assertEquals(1, quitCount)
        assertEquals(0, WindowManager.windows.size)
    }

    @Test
    fun windowlessUpdateLaunchWaitsForDockWithoutCreatingTerminal() {
        val lifecycle = ApplicationWindowLifecycle(isMacOS = true) {}
        lifecycle.openInitialWindow(startWithoutWindow = true)
        assertEquals(0, WindowManager.windows.size)

        lifecycle.reopen()
        assertEquals(1, WindowManager.windows.size)
    }

    @Test
    fun repeatedDockRequestsDoNotDuplicateWindows() {
        val lifecycle = ApplicationWindowLifecycle(isMacOS = true) {}
        repeat(3) { lifecycle.reopen() }
        assertEquals(1, WindowManager.windows.size)
    }

    @Test
    fun dockRequestRestoresMinimizedWindowWithoutCreatingAnother() {
        val lifecycle = ApplicationWindowLifecycle(isMacOS = true) {}
        lifecycle.openInitialWindow()
        val window = WindowManager.windows.single()
        val state = WindowState(isMinimized = true)
        window.composeWindowState = state

        lifecycle.reopen()

        assertFalse(state.isMinimized)
        assertEquals(listOf(window), WindowManager.windows)
    }
}
