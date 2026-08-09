package ai.rever.bossterm.compose

/**
 * Which of the three candidate titles the OS window should show.
 *
 * Precedence, highest first: a Rename… custom title, then the app's OSC 2 window title, then the
 * tab's own title. See the call site for why each one is where it is.
 *
 * @param osc2 the OSC 2 window title, where empty means "reset, fall back" rather than "blank" -
 *        TabController clears it at each prompt start so an exited program stops naming the window.
 *        Blank counts as empty for the same reason a blank rename does: a whitespace title would
 *        otherwise win and leave the window looking nameless.
 * @return empty only when every candidate is empty, which the caller suppresses rather than
 *         showing a nameless window.
 */
internal fun resolveWindowTitle(custom: String?, osc2: String, tabTitle: String): String =
    // ifBlank, not just null: both rename paths normalise blank to null today, but a whitespace
    // custom title would otherwise win and blank the window rather than falling through.
    custom?.ifBlank { null } ?: osc2.ifBlank { tabTitle }

/**
 * The title a command-completion notification carries.
 *
 * Same precedence as the window title, so the two agree on which session finished - which is the
 * notification's entire job, since it only fires while the window is UNFOCUSED and several tabs all
 * announcing the app name says nothing.
 *
 * @param tabTitle the tab's resolved title. This is the one that makes the fallback useful: it is
 *        re-asserted at every prompt, mirrors an app's OSC 1, and otherwise reads the cwd - so it
 *        neither goes stale like a raw OSC 1 slot (which nothing resets, and would still say "vim"
 *        long after vim exited) nor collapses to the app name on a shell that sets no title at all.
 * @param fallback used only when there is genuinely nothing to say.
 */
internal fun notificationTitle(
    custom: String?,
    osc2: String,
    tabTitle: String,
    fallback: String,
): String = resolveWindowTitle(custom, osc2, tabTitle).ifBlank { fallback }
