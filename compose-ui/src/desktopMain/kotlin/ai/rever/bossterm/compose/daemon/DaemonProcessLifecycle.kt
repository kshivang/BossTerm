package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import org.slf4j.LoggerFactory

/** Separate the daemon from a GUI launched in a shell's foreground process group. */
object DaemonProcessLifecycle {
    private interface Posix : Library {
        fun getpid(): Int
        fun getsid(pid: Int): Int
        fun setsid(): Int
        fun setpgid(pid: Int, pgid: Int): Int
    }

    fun detachFromParentTerminal() {
        if (ShellCustomizationUtils.isWindows()) return
        val log = LoggerFactory.getLogger(DaemonProcessLifecycle::class.java)
        runCatching {
            val posix = Native.load(Platform.C_LIBRARY_NAME, Posix::class.java)
            // Service managers may already have made us a session leader.
            if (posix.getsid(0) == posix.getpid()) return
            if (posix.setsid() >= 0) {
                log.debug("Daemon detached from parent terminal")
            } else if (posix.setpgid(0, 0) != 0) {
                log.warn("Could not isolate daemon process group: errno={}", Native.getLastError())
            }
        }.onFailure { log.warn("Could not detach daemon from parent terminal: {}", it.message) }
    }
}
