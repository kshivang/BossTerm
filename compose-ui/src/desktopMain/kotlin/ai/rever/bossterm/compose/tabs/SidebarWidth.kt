package ai.rever.bossterm.compose.tabs

/** Keep terminal space available without discarding the user's preferred expanded width. */
fun constrainedSidebarWidth(preferred: Float, windowWidth: Float, dragging: Boolean = false): Float {
    val available = windowWidth.coerceAtLeast(0f)
    val maximum = (available - 320f).coerceAtLeast(200f).coerceAtMost(available)
    val minimum = (if (dragging) 44f else 200f).coerceAtMost(maximum)
    return preferred.coerceIn(minimum, maximum)
}
