package ai.rever.bossterm.compose

/**
 * Advertise the graphics transport BossTerm implements to child processes.
 *
 * Some clients use KITTY_WINDOW_ID as a transport capability probe. BossTerm
 * supports Kitty inline graphics, but it does not claim Kitty remote control or
 * other Kitty-only terminal features.
 */
internal fun MutableMap<String, String>.putBossTermGraphicsEnvironment(windowIdentity: Any) {
    put("KITTY_WINDOW_ID", Integer.toUnsignedString(windowIdentity.hashCode()))
}
