package ai.rever.bossterm.compose.onboarding

import ai.rever.bossterm.compose.ai.AIAssistantDefinition
import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.EmbeddableTerminal
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.Success
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextOnAccent
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.theme.BossUiTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/** The compact, confirmation-first BOSS Term setup shown by both the app and Terminal Tab. */
@Composable
fun OnboardingWizard(
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    settingsManager: SettingsManager,
    supervisor: BossTermSetupSupervisor? = null,
    canRunInBackground: Boolean = supervisor != null,
) {
    val setupState by BossTermSetupController.state.collectAsState()
    var installed by remember { mutableStateOf<InstalledTools?>(null) }
    var packageManager by remember { mutableStateOf(PackageManagerChoice.AUTO) }
    var shell by remember { mutableStateOf(detectedShellChoice()) }
    var prompt by remember { mutableStateOf(defaultPromptChoice(shell)) }
    var installGit by remember { mutableStateOf(true) }
    var installGitHubCli by remember { mutableStateOf(true) }
    var authenticateGitHub by remember { mutableStateOf(false) }
    var aiAssistants by remember { mutableStateOf(AIAssistants.DEFAULT_ONBOARDING_SELECTION) }
    var adminPassword by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        installed = detectInstalledTools().also { tools ->
            aiAssistants += tools.aiAssistants.filterValues { it }.keys
            prompt = defaultPromptChoice(shell)
        }
    }

    DisposableEffect(setupState.sessionId, canRunInBackground) {
        val observedSessionId = setupState.sessionId
        onDispose {
            if (
                canRunInBackground &&
                observedSessionId != null &&
                BossTermSetupController.state.value.sessionId == observedSessionId
            ) {
                BossTermSetupController.sendToBackground()
            }
        }
    }

    fun finishWithoutSetup() {
        settingsManager.updateSetting { copy(onboardingCompleted = true) }
        onDismiss()
    }

    fun closeRequest() {
        when {
            setupState.sessionId == null -> finishWithoutSetup()
            isVerifiedSetupSuccess(setupState) -> {
                BossTermSetupController.clearFinished()
                onComplete()
            }
            isFinishedSetupFailure(setupState) -> onDismiss()
            canRunInBackground -> {
                BossTermSetupController.sendToBackground()
                onDismiss()
            }
        }
    }

    DialogWindow(
        onCloseRequest = ::closeRequest,
        title = "BOSS Term Setup",
        resizable = false,
        state = rememberDialogState(size = DpSize(820.dp, 720.dp)),
    ) {
        Surface(Modifier.fillMaxSize(), color = BackgroundColor) {
            if (setupState.awaitingGitHubAuthentication) {
                GhAuthStep(
                    onComplete = {
                        BossTermSetupController.finishInteractiveGitHubAuth(completed = true)
                    },
                    onSkip = {
                        BossTermSetupController.finishInteractiveGitHubAuth(completed = false)
                    },
                )
            } else if (isVerifiedSetupSuccess(setupState)) {
                SetupSuccess(
                    state = setupState,
                    onDone = {
                        BossTermSetupController.clearFinished()
                        onComplete()
                    },
                )
            } else if (setupState.sessionId != null) {
                SetupProgress(
                    state = setupState,
                    canBackground = canRunInBackground,
                    canRetry = true,
                    adminPassword = adminPassword,
                    onAdminPasswordChange = { adminPassword = it },
                    onBackground = {
                        BossTermSetupController.sendToBackground()
                        onDismiss()
                    },
                    onRetry = { BossTermSetupController.retry(adminPassword) },
                    onDone = {
                        BossTermSetupController.clearFinished()
                        onComplete()
                    },
                )
            } else if (installed == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentColor, strokeWidth = 2.dp)
                        Spacer(Modifier.height(14.dp))
                        Text("Checking this computer…", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            } else {
                val selections =
                    OnboardingSelections(
                        packageManager = packageManager,
                        shell = shell,
                        shellCustomization = prompt,
                        installGit = installGit,
                        installGitHubCLI = installGitHubCli,
                        authenticateGitHub = authenticateGitHub,
                        aiAssistants = aiAssistants,
                    )
                SetupConfirmation(
                    packageManager = packageManager,
                    shell = shell,
                    prompt = prompt,
                    installed = requireNotNull(installed),
                    installGit = installGit,
                    installGitHubCli = installGitHubCli,
                    authenticateGitHub = authenticateGitHub,
                    aiAssistants = aiAssistants,
                    adminPassword = adminPassword,
                    requiresAuthorization =
                        BossTermSetupController.requiresAdminAuthorization(
                            selections,
                            requireNotNull(installed),
                        ),
                    fluckBridgeAvailable = supervisor != null,
                    onShellChange = {
                        shell = it
                        if (prompt !in availablePromptChoices(it)) {
                            prompt =
                                if (ShellCustomizationChoice.STARSHIP in availablePromptChoices(it)) {
                                    ShellCustomizationChoice.STARSHIP
                                } else {
                                    ShellCustomizationChoice.NONE
                                }
                        }
                    },
                    onPackageManagerChange = { packageManager = it },
                    onPromptChange = { prompt = it },
                    onInstallGitChange = { installGit = it },
                    onInstallGitHubCliChange = {
                        installGitHubCli = it
                        if (!it && !requireNotNull(installed).gh) authenticateGitHub = false
                    },
                    onAuthenticateGitHubChange = {
                        authenticateGitHub = it
                        if (it) installGitHubCli = true
                    },
                    onAiAssistantsChange = { aiAssistants = it },
                    onAdminPasswordChange = { adminPassword = it },
                    onNotNow = ::finishWithoutSetup,
                    onStart = {
                        BossTermSetupController.start(
                            selections = selections,
                            installed = requireNotNull(installed),
                            settingsManager = settingsManager,
                            supervisor = supervisor,
                            adminPassword = adminPassword,
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun SetupSuccess(state: BossTermSetupState, onDone: () -> Unit) {
    val completedScroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().testTag("setup-success").padding(horizontal = 56.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(Success.copy(alpha = 0.14f))
                .border(1.dp, Success.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("✓", color = Success, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(16.dp))
        Text("BOSS Term is ready", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("${state.completedTaskCount} setup steps completed.", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp))
                .background(SurfaceColor).border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                .testTag("setup-success-summary"),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(completedScroll).padding(start = 16.dp, top = 16.dp, end = 28.dp, bottom = 16.dp),
            ) {
                Text("Completed setup", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                state.tasks.forEach { task ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✓", color = Success, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(9.dp))
                        Text(task.title, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("Done", color = Success, fontSize = 10.sp)
                    }
                }
                if (state.supervisionChecked && state.supervisedByFluck) {
                    Spacer(Modifier.height(8.dp))
                    Text("Setup was supervised by Fluck.", color = TextSecondary, fontSize = 11.sp)
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(completedScroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 5.dp)
                    .testTag("setup-success-scrollbar"),
                style = defaultScrollbarStyle().copy(
                    unhoverColor = TextSecondary.copy(alpha = 0.65f),
                    hoverColor = AccentColor,
                ),
            )
        }
        Spacer(Modifier.height(18.dp))
        if (completedScroll.canScrollForward || completedScroll.canScrollBackward) {
            Text(
                if (completedScroll.canScrollForward) "More completed steps below  ↓" else "More completed steps above  ↑",
                color = TextSecondary,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text("You're all set to use BOSS Term.", color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.width(220.dp).testTag("setup-success-done"),
            colors = ButtonDefaults.buttonColors(backgroundColor = AccentColor, contentColor = TextOnAccent),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) { Text("Done", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
internal fun SetupConfirmation(
    packageManager: PackageManagerChoice,
    shell: ShellChoice,
    prompt: ShellCustomizationChoice,
    installed: InstalledTools,
    installGit: Boolean,
    installGitHubCli: Boolean,
    authenticateGitHub: Boolean,
    aiAssistants: Set<String>,
    adminPassword: String,
    requiresAuthorization: Boolean,
    fluckBridgeAvailable: Boolean,
    onShellChange: (ShellChoice) -> Unit,
    onPackageManagerChange: (PackageManagerChoice) -> Unit,
    onPromptChange: (ShellCustomizationChoice) -> Unit,
    onInstallGitChange: (Boolean) -> Unit,
    onInstallGitHubCliChange: (Boolean) -> Unit,
    onAuthenticateGitHubChange: (Boolean) -> Unit,
    onAiAssistantsChange: (Set<String>) -> Unit,
    onAdminPasswordChange: (String) -> Unit,
    onNotNow: () -> Unit,
    onStart: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 14.dp)) {
            Text(
                "BOSS TERM SETUP",
                color = AccentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Confirm your terminal setup",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.8).sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Review the practical defaults selected for this computer.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 15.sp,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp)) {
            Column(
                Modifier.fillMaxSize().testTag("setup-options-scroll").verticalScroll(scroll)
                    .padding(start = 8.dp, end = 18.dp, bottom = 18.dp),
            ) {
                TerminalPreview(shell, prompt, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                EssentialChoices(
                    packageManager = packageManager,
                    shell = shell,
                    prompt = prompt,
                    installed = installed,
                    onPackageManagerChange = onPackageManagerChange,
                    onShellChange = onShellChange,
                    onPromptChange = onPromptChange,
                )
                Spacer(Modifier.height(8.dp))

                if (requiresAuthorization) {
                    SetupOption(
                        title = "Administrator authorization",
                        description = "Optional preauthorization; leave blank and the setup terminal will prompt when needed",
                    ) {
                        OutlinedTextField(
                            value = adminPassword,
                            onValueChange = onAdminPasswordChange,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text("Optional password", color = TextMuted, fontSize = 11.sp) },
                            modifier = Modifier.width(220.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                VersionControlOptions(
                    installed = installed,
                    installGit = installGit,
                    installGitHubCli = installGitHubCli,
                    authenticateGitHub = authenticateGitHub,
                    onInstallGitChange = onInstallGitChange,
                    onInstallGitHubCliChange = onInstallGitHubCliChange,
                    onAuthenticateGitHubChange = onAuthenticateGitHubChange,
                )
                Spacer(Modifier.height(8.dp))
                AiAgentGrid(
                    installed = installed,
                    selected = aiAssistants,
                    onSelectedChange = onAiAssistantsChange,
                )

                Spacer(Modifier.height(8.dp))
                SupervisionNote(fluckBridgeAvailable)
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("setup-options-scrollbar"),
                style = defaultScrollbarStyle().copy(
                    unhoverColor = TextSecondary.copy(alpha = 0.65f),
                    hoverColor = AccentColor,
                ),
            )
        }
        Row(
            Modifier.fillMaxWidth().border(1.dp, BorderColor).padding(horizontal = 40.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onNotNow) { Text("Not now", color = TextSecondary) }
            Spacer(Modifier.weight(1f))
            if (scroll.canScrollForward) {
                Text("More options below  ↓", color = TextSecondary, fontSize = 11.sp)
                Spacer(Modifier.width(18.dp))
            }
            Button(
                onClick = onStart,
                enabled = true,
                colors = ButtonDefaults.buttonColors(backgroundColor = AccentColor, contentColor = TextOnAccent),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                modifier = Modifier.testTag("setup-primary-action"),
            ) {
                Text("Set up BOSS Term", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EssentialChoices(
    packageManager: PackageManagerChoice,
    shell: ShellChoice,
    prompt: ShellCustomizationChoice,
    installed: InstalledTools,
    onPackageManagerChange: (PackageManagerChoice) -> Unit,
    onShellChange: (ShellChoice) -> Unit,
    onPromptChange: (ShellCustomizationChoice) -> Unit,
) {
    @Composable
    fun FieldSet(fieldModifier: Modifier, spacer: @Composable () -> Unit) {
        PackageManagerOption(packageManager, installed, onPackageManagerChange, fieldModifier)
        spacer()
        CompactChoiceField("Default shell", "System default", fieldModifier) {
            ChoiceMenu(
                selected = shell.displayName + shellPath(shell),
                choices = availableShellChoices().map { it to (it.displayName + shellPath(it)) },
                onSelect = onShellChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        spacer()
        CompactChoiceField("Shell customization", "Prompt or framework", fieldModifier) {
            ChoiceMenu(
                selected = promptLabel(prompt),
                choices = availablePromptChoices(shell).map { it to promptLabel(it) },
                onSelect = onPromptChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().testTag("setup-basic-choices")) {
        val useSingleColumn = maxWidth < 680.dp || LocalDensity.current.fontScale > 1.2f
        if (useSingleColumn) {
            Column { FieldSet(Modifier.fillMaxWidth()) { Spacer(Modifier.height(8.dp)) } }
        } else {
            Row { FieldSet(Modifier.weight(1f)) { Spacer(Modifier.width(8.dp)) } }
        }
    }
}

@Composable
private fun PackageManagerOption(
    selected: PackageManagerChoice,
    installed: InstalledTools,
    onSelect: (PackageManagerChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail =
        when {
            ShellCustomizationUtils.isMacOS() ->
                if (installed.homebrew) "Homebrew installed" else "Install Homebrew if needed"
            ShellCustomizationUtils.isWindows() && installed.winget ->
                "winget is installed and preferred"
            ShellCustomizationUtils.isWindows() && installed.chocolatey ->
                "Chocolatey is installed"
            ShellCustomizationUtils.isWindows() ->
                "Install winget or Chocolatey before selected tools"
            else -> "BOSS Term can use apt, dnf, or pacman"
        }
    CompactChoiceField(title = "Package manager", description = detail, modifier = modifier) {
        ChoiceMenu(
            selected = packageManagerLabel(selected),
            choices = availablePackageManagerChoices().map { it to packageManagerLabel(it) },
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun packageManagerLabel(choice: PackageManagerChoice): String =
    if (choice == PackageManagerChoice.AUTO) "Recommended (auto)" else choice.displayName

private fun availablePackageManagerChoices(): List<PackageManagerChoice> =
    when {
        ShellCustomizationUtils.isMacOS() ->
            listOf(PackageManagerChoice.AUTO, PackageManagerChoice.HOMEBREW, PackageManagerChoice.NONE)
        ShellCustomizationUtils.isWindows() ->
            listOf(
                PackageManagerChoice.AUTO,
                PackageManagerChoice.WINGET,
                PackageManagerChoice.CHOCOLATEY,
                PackageManagerChoice.NONE,
            )
        else -> listOf(PackageManagerChoice.AUTO, PackageManagerChoice.NONE)
    }

internal fun onboardingAiTools(): List<AIAssistantDefinition> =
    AIAssistants.AI_ASSISTANTS_OSS_FIRST + AIAssistants.LOCAL_MODEL_RUNTIMES

@Composable
private fun VersionControlOptions(
    installed: InstalledTools,
    installGit: Boolean,
    installGitHubCli: Boolean,
    authenticateGitHub: Boolean,
    onInstallGitChange: (Boolean) -> Unit,
    onInstallGitHubCliChange: (Boolean) -> Unit,
    onAuthenticateGitHubChange: (Boolean) -> Unit,
) {
    SetupSection(
        title = "Version control",
        description = "Verify or install command-line tools",
        modifier = Modifier.testTag("version-control-options"),
    ) {
        Row(Modifier.fillMaxWidth()) {
            ToolChoice(
                label = if (installed.git) "Git · installed" else "Git",
                checked = installGit,
                onCheckedChange = onInstallGitChange,
                modifier = Modifier.weight(1f),
            )
            ToolChoice(
                label = if (installed.gh) "GitHub CLI · installed" else "GitHub CLI",
                checked = installGitHubCli,
                onCheckedChange = onInstallGitHubCliChange,
                modifier = Modifier.weight(1f),
            )
            if (installGitHubCli || installed.gh) {
                ToolChoice(
                    label = "Sign in after setup",
                    checked = authenticateGitHub,
                    onCheckedChange = onAuthenticateGitHubChange,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AiAgentGrid(
    installed: InstalledTools,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
) {
    SetupSection(
        title = "AI agents",
        description = "Select coding agents and local model runtimes",
        modifier = Modifier.testTag("ai-agents-section"),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 650.dp || LocalDensity.current.fontScale > 1.2f) 2 else 3
            Column {
                onboardingAiTools().chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { assistant ->
                            val isInstalled = installed.isAiInstalled(assistant.id)
                            val checked = isInstalled || assistant.id in selected
                            AiToolChoice(
                                assistant = assistant,
                                checked = checked,
                                installed = isInstalled,
                                onCheckedChange = { value ->
                                    if (!isInstalled) {
                                        onSelectedChange(if (value) selected + assistant.id else selected - assistant.id)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiToolChoice(
    assistant: AIAssistantDefinition,
    checked: Boolean,
    installed: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .heightIn(min = 36.dp)
            .testTag("ai-agent-${assistant.id}")
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !installed) { onCheckedChange(!checked) }
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentColor,
                    uncheckedColor = TextSecondary,
                ),
            )
        }
        Text(
            assistant.displayName + if (installed) " · installed" else "",
            color = TextPrimary,
            fontSize = 12.sp,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ToolChoice(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = AccentColor, uncheckedColor = TextSecondary),
            )
        }
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
internal fun SetupProgress(
    state: BossTermSetupState,
    canBackground: Boolean,
    canRetry: Boolean,
    adminPassword: String,
    onAdminPasswordChange: (String) -> Unit,
    onBackground: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val scroll = rememberScrollState()
    var showTaskDetails by remember(state.sessionId) { mutableStateOf(false) }
    val failed = state.failureMessage != null
    val activeTask = state.activeTask
    val title = when {
        failed -> "Setup needs your attention"
        state.finished -> "BOSS Term is ready"
        state.tasks.any { it.status == SetupTaskStatus.REPAIRING } -> "Fluck Agent is repairing setup"
        else -> "Setting up BOSS Term"
    }
    val subtitle = when {
        failed -> state.failureMessage.orEmpty()
        state.finished -> "Your terminal preferences and command-line tools are ready."
        activeTask != null -> activeTask.title
        else -> "Preparing the installation…"
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 40.dp, end = 32.dp, top = 24.dp)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp, bottom = 18.dp),
            ) {
        Text(
            "BOSS TERM SETUP",
            color = AccentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        Text(subtitle, color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = state.progress,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = if (failed) Color(0xFFE39A42) else AccentColor,
            backgroundColor = BorderColor,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${state.completedTaskCount} of ${state.tasks.size} steps complete",
            color = TextMuted,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        CompactFluckProgressStatus(state)
        state.agentDebugError?.let { error ->
            Text(
                error,
                color = Color(0xFFE39A42),
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().testTag("setup-fluck-debug-error").padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(320.dp).testTag("setup-interactive-terminal")
                .clip(RoundedCornerShape(9.dp))
                .background(Color.Black.copy(alpha = 0.28f))
                .border(1.dp, BorderColor, RoundedCornerShape(9.dp)),
        ) {
            EmbeddableTerminal(
                state = BossTermSetupController.terminalState,
                command = if (TargetOs.current().isWindows) "powershell.exe" else "/bin/bash",
                environment = BossTermSetupController.terminalEnvironment(),
                onOutput = BossTermSetupController::terminalOutput,
                onReady = BossTermSetupController::terminalReady,
                settingsOverride = TerminalSettingsOverride(fontSize = 12f),
                autoFocus = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                activeTask?.let { "Current: ${it.title}" } ?: "Installation steps",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { showTaskDetails = !showTaskDetails },
                modifier = Modifier.testTag("setup-task-details-toggle"),
            ) {
                Text(
                    if (showTaskDetails) "Hide ${state.tasks.size} steps" else "View all ${state.tasks.size} steps",
                    color = AccentColor,
                )
            }
        }
        if (showTaskDetails) {
            Column(Modifier.testTag("setup-task-details")) {
                state.tasks.forEach { task ->
                    SetupTaskRow(task)
                    Spacer(Modifier.height(7.dp))
                }
                AgentStatusNote(state)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (failed && !TargetOs.current().isWindows) {
            OutlinedTextField(
                value = adminPassword,
                onValueChange = onAdminPasswordChange,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Administrator password", color = TextMuted, fontSize = 10.sp) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = defaultScrollbarStyle().copy(
                    unhoverColor = TextSecondary.copy(alpha = 0.65f),
                    hoverColor = AccentColor,
                ),
            )
        }
        Row(
            Modifier.fillMaxWidth().border(1.dp, BorderColor).padding(horizontal = 40.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canBackground && (state.isRunning || failed)) {
                TextButton(onClick = onBackground) {
                    Text(
                        if (failed) "Keep in bottom bar" else "Continue in background",
                        color = TextSecondary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (
                state.isRunning || failed || state.agentDebugRequestInFlight || state.agentDebugActive ||
                state.agentDebugAwaitingVerification
            ) {
                AskFluckDebugButton(state)
                Spacer(Modifier.width(10.dp))
            }
            when {
                failed -> Button(
                    onClick = onRetry,
                    enabled = canRetry && !state.agentDebugRequestInFlight && !state.agentDebugActive &&
                        !state.agentDebugAwaitingVerification,
                    colors = ButtonDefaults.buttonColors(backgroundColor = AccentColor, contentColor = TextOnAccent),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("Try again", fontWeight = FontWeight.SemiBold) }
                state.finished -> Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(backgroundColor = AccentColor, contentColor = TextOnAccent),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("Done", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun AskFluckDebugButton(state: BossTermSetupState) {
    val canAsk = BossTermSetupController.canAskFluckToDebugAndFix()
    val canResume = state.agentDebugAwaitingVerification
    val label = when {
        state.agentDebugRequestInFlight -> "Sending terminal to Fluck…"
        state.agentDebugActive -> "Fluck is debugging…"
        canResume -> "Resume and verify"
        !state.supervisionChecked -> "Connecting to Fluck…"
        !state.supervisedByFluck -> "Fluck unavailable"
        state.setupTerminalId == null -> "Connecting terminal…"
        else -> "Ask Fluck to debug and fix"
    }
    TextButton(
        onClick = {
            if (canResume) {
                BossTermSetupController.resumeAndReverify()
            } else {
                BossTermSetupController.askFluckToDebugAndFix()
            }
        },
        enabled = canAsk || canResume,
        modifier = Modifier.testTag("setup-ask-fluck-debug")
            .border(
                1.dp,
                AccentColor.copy(alpha = if (canAsk || canResume) 0.55f else 0.18f),
                RoundedCornerShape(7.dp),
            ),
    ) {
        Text(
            label,
            color = if (canAsk || canResume) AccentColor else TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CompactFluckProgressStatus(state: BossTermSetupState) {
    val text = when {
        !state.supervisionChecked -> "Checking for Fluck Agent…"
        state.agentDebugRequestInFlight -> "Preparing a secure terminal handoff to Fluck…"
        state.agentDebugActive -> "Fluck is debugging this setup terminal"
        state.agentDebugAwaitingVerification -> "Fluck finished · resume to verify before setup continues"
        state.tasks.any { it.status == SetupTaskStatus.REPAIRING } ->
            "Fluck is repairing ${state.activeTask?.title ?: "this step"}"
        state.failureMessage != null && state.supervisedByFluck -> "Fluck could not safely recover this step"
        state.failureMessage != null -> "Fluck unavailable · this step needs your attention"
        state.supervisedByFluck && state.isRunning ->
            "Fluck is watching ${state.activeTask?.title ?: "setup"}"
        state.supervisedByFluck && state.finished -> "Setup was supervised by Fluck"
        else -> "Fluck supervision unavailable · setup continues normally"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp).testTag("setup-fluck-status")
            .background(AccentColor.copy(alpha = 0.05f), RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ƒ", color = AccentColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = TextSecondary, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun SetupTaskRow(task: SetupTaskState) {
    val color = when (task.status) {
        SetupTaskStatus.COMPLETE -> Success
        SetupTaskStatus.RUNNING, SetupTaskStatus.REPAIRING -> AccentColor
        SetupTaskStatus.NEEDS_ATTENTION -> Color(0xFFE39A42)
        SetupTaskStatus.PENDING -> TextMuted
    }
    val mark = when (task.status) {
        SetupTaskStatus.COMPLETE -> "✓"
        SetupTaskStatus.RUNNING -> "•"
        SetupTaskStatus.REPAIRING -> "↻"
        SetupTaskStatus.NEEDS_ATTENTION -> "!"
        SetupTaskStatus.PENDING -> ""
    }
    Row(
        Modifier.fillMaxWidth().border(1.dp, BorderColor, RoundedCornerShape(8.dp)).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Text(mark, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(task.detail, color = TextMuted, fontSize = 10.sp)
        }
        Text(taskStatusLabel(task.status), color = color, fontSize = 10.sp)
    }
}

private fun taskStatusLabel(status: SetupTaskStatus): String = when (status) {
    SetupTaskStatus.PENDING -> "Waiting"
    SetupTaskStatus.RUNNING -> "Installing"
    SetupTaskStatus.REPAIRING -> "Repairing"
    SetupTaskStatus.COMPLETE -> "Done"
    SetupTaskStatus.NEEDS_ATTENTION -> "Needs attention"
}

@Composable
private fun AgentStatusNote(state: BossTermSetupState) {
    val (title, detail) = when {
        !state.supervisionChecked -> "Checking for Fluck Agent" to "Setup is connecting to the agent before changes begin."
        state.supervisedByFluck -> "Fluck Agent is supervising" to "It is watching each step and can diagnose and retry safe repairs."
        else -> "Running without AI supervision" to "You can still follow every step here and retry if something needs attention."
    }
    Row(
        Modifier.fillMaxWidth().background(AccentColor.copy(alpha = 0.04f), RoundedCornerShape(9.dp))
            .border(1.dp, AccentColor.copy(alpha = 0.18f), RoundedCornerShape(9.dp)).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).background(AccentColor.copy(alpha = 0.11f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("ƒ", color = AccentColor, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(title, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun TerminalPreview(shell: ShellChoice, prompt: ShellCustomizationChoice, modifier: Modifier = Modifier) {
    val theme = BossUiTheme.current
    Column(
        modifier.heightIn(min = 60.dp).testTag("prompt-preview").clip(RoundedCornerShape(9.dp)).background(theme.ink)
            .border(1.dp, BorderColor, RoundedCornerShape(9.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            if (prompt == ShellCustomizationChoice.KEEP_EXISTING) {
                "Current prompt · ${shell.displayName}"
            } else {
                "Example · ${prompt.displayName} on ${shell.displayName}"
            },
            color = theme.muted,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildAnnotatedString {
                promptPreviewSegments(shell, prompt).forEachIndexed { index, segment ->
                    withStyle(SpanStyle(color = if (index % 2 == 0) AccentColor else theme.chalk)) {
                        append(segment)
                    }
                }
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )
    }
}

internal fun promptPreviewText(shell: ShellChoice, prompt: ShellCustomizationChoice): String =
    promptPreviewSegments(shell, prompt).joinToString("")

private fun promptPreviewSegments(shell: ShellChoice, prompt: ShellCustomizationChoice): List<String> =
    when (prompt) {
        ShellCustomizationChoice.STARSHIP -> listOf("~/project ", "on main ", "via kotlin ❯")
        ShellCustomizationChoice.OH_MY_ZSH -> listOf("➜  ", "project ", "git:(main)")
        ShellCustomizationChoice.PREZTO -> listOf("project ", "❯ ", "git status")
        ShellCustomizationChoice.OH_MY_POSH -> listOf("[ project ] ", "main ", ">")
        ShellCustomizationChoice.NONE ->
            listOf(
                when (shell) {
                    ShellChoice.POWERSHELL -> "PS> "
                    ShellChoice.CMD -> "C:\\> "
                    ShellChoice.FISH -> "> "
                    else -> "$ "
                },
                "git status",
            )
        ShellCustomizationChoice.KEEP_EXISTING -> listOf("Your current prompt is unchanged")
    }

@Composable
private fun SetupOption(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().border(1.dp, BorderColor, RoundedCornerShape(9.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(description, color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(24.dp))
        trailing()
    }
}

@Composable
private fun CompactChoiceField(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    Column(modifier.border(1.dp, BorderColor, RoundedCornerShape(9.dp)).padding(12.dp)) {
        Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        control()
    }
}

@Composable
private fun SetupSection(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().border(1.dp, BorderColor, RoundedCornerShape(9.dp)).padding(10.dp)) {
        Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(description, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun <T> ChoiceMenu(
    selected: String,
    choices: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier.width(220.dp),
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier.clip(RoundedCornerShape(7.dp)).background(SurfaceColor)
                .border(1.dp, BorderColor, RoundedCornerShape(7.dp)).clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("⌄", color = TextMuted, fontSize = 12.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(SurfaceColor),
        ) {
            choices.forEach { (value, label) ->
                DropdownMenuItem(onClick = { onSelect(value); expanded = false }) {
                    Text(label, color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SupervisionNote(fluckBridgeAvailable: Boolean) {
    Row(
        Modifier.testTag("setup-supervision-note")
            .fillMaxWidth().background(AccentColor.copy(alpha = 0.04f), RoundedCornerShape(9.dp))
            .border(1.dp, AccentColor.copy(alpha = 0.18f), RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).background(AccentColor.copy(alpha = 0.11f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(if (fluckBridgeAvailable) "ƒ" else "✓", color = AccentColor, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(11.dp))
        Text(
            if (fluckBridgeAvailable) {
                "Fluck Agent can supervise tasks and retry safe repairs; authorization stays with you."
            } else {
                "Live progress and command output remain visible throughout setup."
            },
            color = TextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

private fun detectedShellChoice(): ShellChoice {
    val shell = System.getenv("SHELL").orEmpty().substringAfterLast('/').lowercase()
    return when (shell) {
        "bash" -> ShellChoice.BASH
        "fish" -> ShellChoice.FISH
        "powershell", "pwsh" -> ShellChoice.POWERSHELL
        "cmd", "cmd.exe" -> ShellChoice.CMD
        else -> if (TargetOs.current().isWindows) ShellChoice.POWERSHELL else ShellChoice.ZSH
    }
}

private fun availableShellChoices(): List<ShellChoice> = if (TargetOs.current().isWindows) {
    listOf(ShellChoice.POWERSHELL, ShellChoice.CMD)
} else {
    listOf(ShellChoice.ZSH, ShellChoice.BASH, ShellChoice.FISH)
}

internal fun defaultPromptChoice(
    shell: ShellChoice,
    targetOs: TargetOs = TargetOs.current(),
): ShellCustomizationChoice =
    if (ShellCustomizationChoice.STARSHIP in availablePromptChoices(shell, targetOs)) {
        ShellCustomizationChoice.STARSHIP
    } else {
        ShellCustomizationChoice.NONE
    }

internal fun isVerifiedSetupSuccess(state: BossTermSetupState): Boolean =
    state.finished && state.failureMessage == null && !state.awaitingGitHubAuthentication

internal fun isFinishedSetupFailure(state: BossTermSetupState): Boolean =
    state.finished && state.failureMessage != null && !state.isRunning

internal fun availablePromptChoices(
    shell: ShellChoice,
    targetOs: TargetOs = TargetOs.current(),
): List<ShellCustomizationChoice> =
    when {
        targetOs.isWindows ->
            if (shell == ShellChoice.CMD) {
                listOf(ShellCustomizationChoice.NONE, ShellCustomizationChoice.KEEP_EXISTING)
            } else {
                listOf(
                    ShellCustomizationChoice.OH_MY_POSH,
                    ShellCustomizationChoice.STARSHIP,
                    ShellCustomizationChoice.NONE,
                    ShellCustomizationChoice.KEEP_EXISTING,
                )
            }
        shell == ShellChoice.ZSH ->
            listOf(
                ShellCustomizationChoice.STARSHIP,
                ShellCustomizationChoice.OH_MY_ZSH,
                ShellCustomizationChoice.PREZTO,
                ShellCustomizationChoice.NONE,
                ShellCustomizationChoice.KEEP_EXISTING,
            )
        else ->
            listOf(
                ShellCustomizationChoice.STARSHIP,
                ShellCustomizationChoice.NONE,
                ShellCustomizationChoice.KEEP_EXISTING,
            )
    }

private fun promptLabel(prompt: ShellCustomizationChoice): String = when (prompt) {
    ShellCustomizationChoice.STARSHIP -> "Starship · recommended"
    ShellCustomizationChoice.KEEP_EXISTING -> "Keep existing"
    else -> prompt.displayName
}

private fun shellPath(shell: ShellChoice): String = shell.command.takeIf { it.isNotBlank() }?.let { " · /bin/$it" }.orEmpty()
