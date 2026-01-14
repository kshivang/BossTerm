package ai.rever.bossterm.compose.wizard

import androidx.compose.runtime.*

/**
 * Manages wizard navigation and shared state.
 *
 * @param S Type of shared wizard state/selections
 * @param steps List of all wizard steps (filtered by platform at runtime)
 * @param initialState Initial shared state
 */
class WizardState<S>(
    private val steps: List<WizardStep<S>>,
    initialState: S
) {
    /** Current step ID */
    var currentStepId by mutableStateOf(activeSteps.firstOrNull()?.id ?: "")
        private set

    /** Shared wizard state */
    var state by mutableStateOf(initialState)
        private set

    /** Whether an async operation (like installation) is in progress */
    var isProcessing by mutableStateOf(false)
        private set

    /** Error message if any */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Derived properties

    /** Steps filtered by platform (actually available on this system) */
    val activeSteps: List<WizardStep<S>>
        get() = steps.filter { it.platformFilter.matches() }

    /** Visible steps for the step indicator (subset of activeSteps with isVisible=true) */
    val visibleSteps: List<WizardStep<S>>
        get() = activeSteps.filter { it.isVisible }

    /** Current step object */
    val currentStep: WizardStep<S>?
        get() = activeSteps.find { it.id == currentStepId }

    /** Index of current step in visible steps (for step indicator) */
    val currentVisibleIndex: Int
        get() = visibleSteps.indexOfFirst { it.id == currentStepId }

    /** Index of current step in active steps (for navigation) */
    private val currentActiveIndex: Int
        get() = activeSteps.indexOfFirst { it.id == currentStepId }

    /** Whether user can go back */
    val canGoBack: Boolean
        get() = currentActiveIndex > 0 && !isProcessing

    /** Whether user can proceed to next step */
    val canGoNext: Boolean
        get() = currentStep?.canProceed?.invoke(state) == true && !isProcessing

    /** Whether this is the first step */
    val isFirstStep: Boolean
        get() = currentActiveIndex == 0

    /** Whether this is the last step */
    val isLastStep: Boolean
        get() = currentActiveIndex == activeSteps.lastIndex

    // Navigation methods

    /**
     * Move to the next step.
     * Does nothing if already at last step or canGoNext is false.
     */
    fun next() {
        if (!canGoNext) return
        val nextIndex = currentActiveIndex + 1
        if (nextIndex < activeSteps.size) {
            currentStepId = activeSteps[nextIndex].id
        }
    }

    /**
     * Move to the previous step.
     * Does nothing if already at first step.
     */
    fun back() {
        if (!canGoBack) return
        val prevIndex = currentActiveIndex - 1
        if (prevIndex >= 0) {
            currentStepId = activeSteps[prevIndex].id
        }
    }

    /**
     * Jump to a specific step by ID.
     * Does nothing if step doesn't exist or is filtered out.
     */
    fun goToStep(stepId: String) {
        if (activeSteps.any { it.id == stepId }) {
            currentStepId = stepId
        }
    }

    /**
     * Update the shared state.
     * @param update Lambda that receives current state and returns new state
     */
    fun updateState(update: S.() -> S) {
        state = state.update()
    }

    /**
     * Mark as processing (blocks navigation).
     */
    fun markProcessing() {
        isProcessing = true
    }

    /**
     * Mark as not processing (allows navigation).
     */
    fun markComplete() {
        isProcessing = false
    }

    /**
     * Show an error message.
     */
    fun showError(message: String?) {
        errorMessage = message
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        errorMessage = null
    }
}

/**
 * Create and remember a WizardState.
 *
 * @param steps List of wizard steps
 * @param initialState Initial shared state
 * @return Remembered WizardState instance
 */
@Composable
fun <S> rememberWizardState(
    steps: List<WizardStep<S>>,
    initialState: S
): WizardState<S> {
    return remember(steps) { WizardState(steps, initialState) }
}
