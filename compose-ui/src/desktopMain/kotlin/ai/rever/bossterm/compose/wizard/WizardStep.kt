package ai.rever.bossterm.compose.wizard

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils

/**
 * Generic wizard step definition with pluggable content.
 *
 * @param S The type of shared wizard state
 * @param id Unique identifier for the step
 * @param displayName Name shown in step indicator
 * @param isVisible Whether step appears in the step indicator (false for transitional steps)
 * @param canSkip Whether user can skip this step entirely
 * @param platformFilter Filter to show/hide step based on platform
 * @param canProceed Lambda to determine if user can proceed from this step
 */
data class WizardStep<S>(
    val id: String,
    val displayName: String,
    val isVisible: Boolean = true,
    val canSkip: Boolean = false,
    val platformFilter: PlatformFilter = PlatformFilter.All,
    val canProceed: (state: S) -> Boolean = { true }
)

/**
 * Platform filter for step visibility.
 * Steps with non-matching filters are skipped automatically.
 */
sealed class PlatformFilter {
    /** Step is shown on all platforms */
    object All : PlatformFilter()

    /** Step is shown only on Windows */
    object Windows : PlatformFilter()

    /** Step is shown only on macOS */
    object MacOS : PlatformFilter()

    /** Step is shown only on Linux */
    object Linux : PlatformFilter()

    /** Step is shown on Unix-like systems (macOS + Linux) */
    object Unix : PlatformFilter()

    /** Step is shown only on non-Windows systems */
    object NonWindows : PlatformFilter()

    /** Custom filter with user-provided predicate */
    data class Custom(val predicate: () -> Boolean) : PlatformFilter()

    /**
     * Check if this filter matches the current platform.
     */
    fun matches(): Boolean = when (this) {
        All -> true
        Windows -> ShellCustomizationUtils.isWindows()
        MacOS -> ShellCustomizationUtils.isMacOS()
        Linux -> ShellCustomizationUtils.isLinux()
        Unix -> !ShellCustomizationUtils.isWindows()
        NonWindows -> !ShellCustomizationUtils.isWindows()
        is Custom -> predicate()
    }
}
