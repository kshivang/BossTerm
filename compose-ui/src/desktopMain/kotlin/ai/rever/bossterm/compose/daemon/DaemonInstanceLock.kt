package ai.rever.bossterm.compose.daemon

import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Process-lifetime OS file lock (`daemon.instance.lock`) marking "a daemon owns this settings dir".
 *
 * The GUI's spawn guard ([DaemonClient] on `daemon.lock`) only serializes GUI-initiated spawns; a
 * service-manager start (launchd `RunAtLoad`, systemd `enable --now`) bypasses it entirely. And the
 * daemon's own self-guard (probe `daemon.port`, PING) is check-then-act: two daemons cold-starting
 * in the same instant both see "no live daemon" before either has bound its control channel, so
 * both keep running — two menu-bar icons, and the second overwrites `daemon.port`, orphaning the
 * daemon the GUI attached to. This lock is the atomic arbiter across ALL spawn paths: the first
 * daemon to [tryAcquire] wins and holds the lock until the OS releases it at process death (kill
 * -9 included); every later daemon gets `false` and must exit.
 *
 * Deliberately a DIFFERENT file from `daemon.lock`: the GUI holds that one across spawn+await, so
 * a daemon contending on the same file could never start while its own spawner waits for it.
 */
object DaemonInstanceLock {
    // Held for the whole process lifetime. Keep hard refs: a GC'd channel releases the OS lock.
    @Volatile private var held: FileLock? = null
    private var channel: FileChannel? = null

    /** Try to become THE daemon for this settings dir. Idempotent while held. */
    @Synchronized
    fun tryAcquire(): Boolean {
        held?.takeIf { it.isValid }?.let { return true }
        return runCatching {
            val file = BossTermPaths.daemonInstanceLockFile()
            // Owner-only pre-create, same rationale as daemon.lock: don't leave a window where
            // another local user can open()+lock the file and wedge daemon startup.
            BossTermPaths.createOwnerOnly(file)
            val ch = RandomAccessFile(file, "rw").channel
            val lock = ch.tryLock()
            if (lock != null) {
                channel = ch
                held = lock
                true
            } else {
                ch.close()
                false
            }
        }.getOrDefault(false) // OverlappingFileLockException (same-JVM holder) lands here too
    }

    /** Release the lock (tests only — a real daemon holds it until process exit). */
    @Synchronized
    fun release() {
        runCatching { held?.release() }
        runCatching { channel?.close() }
        held = null
        channel = null
    }
}
