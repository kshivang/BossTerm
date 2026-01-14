package ai.rever.bossterm.compose.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary

/**
 * Generic wizard dialog with consistent styling.
 *
 * @param S Type of shared wizard state
 * @param state WizardState managing navigation and shared state
 * @param title Dialog window title
 * @param size Dialog size (default 650x500)
 * @param onDismiss Called when dialog is dismissed/cancelled
 * @param onComplete Called when wizard is completed (last step finished)
 * @param showStepIndicator Whether to show step indicator for current step
 * @param primaryButtonText Text for primary button (Next/Install/Finish)
 * @param showBackButton Whether to show back button
 * @param showSkipButton Whether to show skip button on first step
 * @param skipButtonText Text for skip button
 * @param stepContent Composable content for each step
 */
@Composable
fun <S> WizardDialog(
    state: WizardState<S>,
    title: String,
    size: DpSize = DpSize(650.dp, 500.dp),
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    showStepIndicator: (WizardStep<S>) -> Boolean = { it.isVisible },
    primaryButtonText: (WizardStep<S>) -> String = { step ->
        when {
            state.isLastStep -> "Finish"
            else -> "Next"
        }
    },
    showPrimaryButton: (WizardStep<S>) -> Boolean = { true },
    showBackButton: (WizardStep<S>) -> Boolean = { state.canGoBack },
    showSkipButton: Boolean = false,
    skipButtonText: String = "Skip",
    stepContent: @Composable (WizardStep<S>, WizardState<S>) -> Unit
) {
    val primaryButtonFocusRequester = remember { FocusRequester() }
    val currentStep = state.currentStep

    DialogWindow(
        onCloseRequest = onDismiss,
        title = title,
        resizable = false,
        state = rememberDialogState(size = size)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BackgroundColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Step indicator (conditional)
                if (currentStep != null && showStepIndicator(currentStep)) {
                    WizardStepIndicator(
                        currentIndex = state.currentVisibleIndex,
                        steps = state.visibleSteps.map { it.displayName }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Step content
                Box(modifier = Modifier.weight(1f)) {
                    if (currentStep != null) {
                        stepContent(currentStep, state)
                    }
                }

                // Error message
                state.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Navigation buttons
                if (currentStep != null) {
                    WizardNavigationBar(
                        state = state,
                        currentStep = currentStep,
                        primaryButtonText = primaryButtonText(currentStep),
                        showPrimaryButton = showPrimaryButton(currentStep),
                        showBackButton = showBackButton(currentStep),
                        showSkipButton = showSkipButton && state.isFirstStep,
                        skipButtonText = skipButtonText,
                        primaryButtonFocusRequester = primaryButtonFocusRequester,
                        onBack = { state.back() },
                        onNext = {
                            if (state.isLastStep) {
                                onComplete()
                            } else {
                                state.next()
                            }
                        },
                        onSkip = onDismiss
                    )
                }
            }
        }
    }

    // Focus primary button on step change
    LaunchedEffect(state.currentStepId) {
        try {
            primaryButtonFocusRequester.requestFocus()
        } catch (e: Exception) {
            // Ignore focus errors
        }
    }
}

/**
 * Navigation bar with Back, Skip, and Next/Finish buttons.
 */
@Composable
private fun <S> WizardNavigationBar(
    state: WizardState<S>,
    currentStep: WizardStep<S>,
    primaryButtonText: String,
    showPrimaryButton: Boolean,
    showBackButton: Boolean,
    showSkipButton: Boolean,
    skipButtonText: String,
    primaryButtonFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        if (showBackButton) {
            OutlinedButton(
                onClick = onBack,
                enabled = !state.isProcessing,
                colors = ButtonDefaults.outlinedButtonColors(
                    backgroundColor = Color.Transparent,
                    contentColor = TextPrimary
                ),
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Text("Back")
            }
        } else {
            Spacer(Modifier.width(1.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Skip button
            if (showSkipButton) {
                TextButton(
                    onClick = onSkip,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text(skipButtonText)
                }
            }

            // Primary button (can be hidden for steps that handle their own navigation)
            if (showPrimaryButton) {
                Button(
                    onClick = onNext,
                    enabled = state.canGoNext,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = AccentColor,
                        contentColor = Color.White,
                        disabledBackgroundColor = AccentColor.copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.focusRequester(primaryButtonFocusRequester)
                ) {
                    Text(primaryButtonText)
                }
            }
        }
    }
}

/**
 * Simplified wizard dialog using standard Dialog instead of DialogWindow.
 * Use this for smaller wizards or when embedded in existing dialogs.
 */
@Composable
fun <S> WizardDialogCompact(
    state: WizardState<S>,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    showStepIndicator: Boolean = true,
    primaryButtonText: (WizardStep<S>) -> String = { if (state.isLastStep) "Finish" else "Next" },
    stepContent: @Composable (WizardStep<S>, WizardState<S>) -> Unit
) {
    val currentStep = state.currentStep

    Surface(
        modifier = Modifier
            .width(600.dp)
            .heightIn(min = 400.dp, max = 550.dp),
        color = BackgroundColor,
        elevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Step indicator
            if (showStepIndicator && state.visibleSteps.size > 1) {
                WizardStepIndicator(
                    currentIndex = state.currentVisibleIndex,
                    steps = state.visibleSteps.map { it.displayName }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Step content
            Box(modifier = Modifier.weight(1f)) {
                if (currentStep != null) {
                    stepContent(currentStep, state)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (state.canGoBack) {
                    OutlinedButton(
                        onClick = { state.back() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = Color.Transparent,
                            contentColor = TextPrimary
                        ),
                        border = BorderStroke(1.dp, BorderColor)
                    ) {
                        Text("Back")
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Button(
                        onClick = {
                            if (state.isLastStep) onComplete() else state.next()
                        },
                        enabled = state.canGoNext,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = AccentColor,
                            contentColor = Color.White
                        )
                    ) {
                        Text(currentStep?.let { primaryButtonText(it) } ?: "Next")
                    }
                }
            }
        }
    }
}
