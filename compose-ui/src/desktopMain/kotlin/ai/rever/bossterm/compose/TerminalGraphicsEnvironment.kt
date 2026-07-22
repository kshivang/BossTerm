package ai.rever.bossterm.compose

/**
 * Advertise the graphics transport BossTerm implements to child processes.
 *
 * Codex and Yazi use KITTY_WINDOW_ID as a graphics capability probe, so BossTerm
 * exposes it while deliberately keeping TERM=xterm-256color. The authoritative
 * capability check remains a Kitty `a=q` APC query. This variable does not imply
 * support for Kitty remote control, kittens, shared-memory transport, animation,
 * or other Kitty-only terminal features; unsupported graphics operations return
 * protocol errors where a response was requested.
 */
internal fun MutableMap<String, String>.putBossTermGraphicsEnvironment(windowIdentity: Any) {
    put("KITTY_WINDOW_ID", Integer.toUnsignedString(windowIdentity.hashCode()))
}
