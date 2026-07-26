package ai.rever.bossterm.compose.voice

/**
 * A value re-read only when a cheap stamp changes, with a periodic forced refresh.
 *
 * Extracted because it carries load-bearing logic on a quiet path: it is the ONLY way a GUI-side
 * settings change or a cleared key reaches a daemon-hosted share (the daemon is a separate process
 * and `SettingsManager` has no reload-on-change). Get the refresh condition wrong and revocation
 * silently doesn't revoke, leaving the call-duration ceiling as the real bound — a failure with no
 * symptom until someone checks whether the kill switch worked.
 *
 * The stamp is mtime+size, which is collision-prone by construction: two booleans flipped in one
 * write whose lengths cancel out, inside one second, look unchanged. [trustTicks] is the escape
 * hatch — after that many unchanged reads it re-reads anyway.
 */
internal class StampCachedValue<T>(
    private val stamp: () -> Long,
    private val read: () -> T?,
    private val trustTicks: Int = VoiceAgentStorage.STAMP_TRUST_TICKS,
) {
    private var lastStamp: Long = Long.MIN_VALUE
    private var cached: T? = null
    private var ticks: Int = 0

    /**
     * Whether [read] has ever been attempted for [lastStamp] — distinct from `cached != null`.
     *
     * Using nullity for both meant a persistently unreadable file re-read on EVERY get(), because
     * "the read returned null" was indistinguishable from "nothing cached yet". On the daemon that
     * is a full read+parse attempt per call on the socket's receive loop, on exactly the path this
     * class exists to make cheap.
     */
    private var loaded: Boolean = false

    /** The cached value, refreshed when the stamp moved, nothing is cached yet, or trust ran out. */
    @Synchronized
    fun get(): T? {
        val now = stamp()
        // Short-circuit order matters: a moved stamp must not also consume a tick, or a file that
        // changes every read would never hit the forced refresh it doesn't need.
        if (now != lastStamp || !loaded || ++ticks >= trustTicks) {
            lastStamp = now
            ticks = 0
            loaded = true
            cached = read()
        }
        return cached
    }
}
