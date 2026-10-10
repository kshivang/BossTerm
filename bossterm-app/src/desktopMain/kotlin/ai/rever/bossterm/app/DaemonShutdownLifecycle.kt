package ai.rever.bossterm.app

/** Keeps JVM shutdown independent of AWT/AppKit, which may already be waiting for its hooks. */
internal class DaemonShutdownLifecycle(
    private val stopServices: () -> Unit,
    private val removeTray: () -> Unit,
) {
    private val stopLock = Any()
    private var stopped = false

    private fun stopServicesOnce() = synchronized(stopLock) {
        if (!stopped) {
            stopped = true
            stopServices()
        }
    }

    fun shutdown() {
        stopServicesOnce()
        // UI cleanup is outside the service lock: a concurrent JVM hook must be able to finish
        // even if tray disposal is waiting for AppKit. The OS removes the icon on process exit.
        removeTray()
    }

    fun shutdownFromHook() {
        // Do not invoke or wait for AWT here. A native macOS Quit can run System.exit on AppKit,
        // blocking that thread until this hook returns; tray disposal needs that same thread.
        stopServicesOnce()
    }
}
