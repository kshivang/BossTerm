package ai.rever.bossterm.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonShutdownLifecycleTest {
    @Test
    fun nativeQuitHookStopsServicesWithoutTouchingTray() {
        val events = mutableListOf<String>()
        val lifecycle = DaemonShutdownLifecycle(
            stopServices = { events.add("services stopped") },
            removeTray = { error("AppKit is waiting for this hook; AWT must not be called") },
        )

        lifecycle.shutdownFromHook()
        lifecycle.shutdownFromHook()

        assertEquals(listOf("services stopped"), events)
    }

    @Test
    fun normalShutdownStopsServicesBeforeRemovingTray() {
        val events = mutableListOf<String>()
        val lifecycle = DaemonShutdownLifecycle(
            stopServices = { events.add("services stopped") },
            removeTray = { events.add("tray removed") },
        )

        lifecycle.shutdown()
        lifecycle.shutdownFromHook()

        assertEquals(listOf("services stopped", "tray removed"), events)
    }

    @Test
    fun hookCanFinishWhileNormalShutdownIsBlockedInTrayDisposal() {
        val trayEntered = CountDownLatch(1)
        val appKitReleased = CountDownLatch(1)
        val stops = AtomicInteger()
        val lifecycle = DaemonShutdownLifecycle(
            stopServices = { stops.incrementAndGet() },
            removeTray = {
                trayEntered.countDown()
                check(appKitReleased.await(5, TimeUnit.SECONDS))
            },
        )
        val threads = Executors.newFixedThreadPool(2)
        try {
            val normalStop = threads.submit { lifecycle.shutdown() }
            assertTrue(trayEntered.await(5, TimeUnit.SECONDS))

            // AppKit waits for the hook before processing the native call made by tray disposal.
            threads.submit { lifecycle.shutdownFromHook() }.get(2, TimeUnit.SECONDS)
            appKitReleased.countDown()
            normalStop.get(2, TimeUnit.SECONDS)
            assertEquals(1, stops.get())
        } finally {
            appKitReleased.countDown()
            threads.shutdownNow()
        }
    }

    @Test
    fun hookWaitsForConcurrentServiceCleanupToFinish() {
        val cleanupEntered = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val hookEntered = CountDownLatch(1)
        val hookFinished = CountDownLatch(1)
        val stops = AtomicInteger()
        val lifecycle = DaemonShutdownLifecycle(
            stopServices = {
                stops.incrementAndGet()
                cleanupEntered.countDown()
                check(finishCleanup.await(5, TimeUnit.SECONDS))
            },
            removeTray = {},
        )
        val threads = Executors.newFixedThreadPool(2)
        try {
            val normalStop = threads.submit { lifecycle.shutdown() }
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            val hook = threads.submit {
                hookEntered.countDown()
                lifecycle.shutdownFromHook()
                hookFinished.countDown()
            }
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS))
            assertEquals(false, hookFinished.await(100, TimeUnit.MILLISECONDS))

            finishCleanup.countDown()
            normalStop.get(2, TimeUnit.SECONDS)
            hook.get(2, TimeUnit.SECONDS)
            assertEquals(1, stops.get())
        } finally {
            finishCleanup.countDown()
            threads.shutdownNow()
        }
    }
}
