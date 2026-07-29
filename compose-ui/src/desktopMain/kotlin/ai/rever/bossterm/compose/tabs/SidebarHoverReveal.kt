package ai.rever.bossterm.compose.tabs

/**
 * Hover-reveal for the collapsed vertical [TabBar]: when the bar is down to its slim
 * icon rail — either forced by a narrow window ([TabBarAutoCollapseWidth]) or collapsed
 * with the chevron — resting the pointer on the rail slides the full bar in as an
 * overlay drawer, which retracts once the pointer leaves. Gated by the
 * `tabBarHoverExpand` setting (on by default).
 *
 * The timing lives in a `LaunchedEffect` in `TabbedTerminal`; the decision itself is
 * kept pure here so it can be unit-tested.
 */

/** Pointer rest time before the collapsed rail reveals the full bar. */
internal const val SidebarRevealOpenDelayMs = 150L

/**
 * Grace period after the pointer leaves before the reveal retracts. Must exceed
 * [SidebarRevealOpenDelayMs]: it covers the rail → drawer handoff (the drawer slides
 * over the rail, so hover moves between two different nodes) and brief excursions
 * past the drawer's edge.
 */
internal const val SidebarRevealCloseDelayMs = 250L

/**
 * Whether the hover drawer should be revealed.
 *
 * @param enabled the `tabBarHoverExpand` setting.
 * @param railShown true while the slim rail (not the full bar) is what's in the layout.
 * @param pointerOnRail pointer is over the rail.
 * @param pointerOnDrawer pointer is over the revealed drawer — true during the handoff,
 *   and what keeps the drawer open while the user reaches for a tab chip.
 */
internal fun hoverRevealTarget(
    enabled: Boolean,
    railShown: Boolean,
    pointerOnRail: Boolean,
    pointerOnDrawer: Boolean
): Boolean = enabled && railShown && (pointerOnRail || pointerOnDrawer)
