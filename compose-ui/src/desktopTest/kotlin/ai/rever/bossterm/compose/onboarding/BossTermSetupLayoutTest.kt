package ai.rever.bossterm.compose.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class BossTermSetupLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("condition was not met within ${timeoutMs}ms")
            Thread.sleep(25)
        }
    }

    @Test
    fun `authorization can be deferred to the setup terminal`() {
        renderFixture(requiresAuthorization = true)

        rule.onNodeWithText("Optional password").assertIsDisplayed()
        rule.onNodeWithText("the setup terminal will prompt", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("setup-primary-action").assertIsEnabled()
    }

    @Test
    fun `live supervised progress keeps Ask Fluck action in the fixed footer`() {
        if (TargetOs.current().isWindows) return
        BossTermSetupController.clearFinished()
        val supervisor = object : BossTermSetupSupervisor {
            override suspend fun start(sessionId: String, tasks: List<SetupTaskState>) = true
        }
        try {
            assertTrue(BossTermSetupController.startTerminalTaskForTest(
                command = "printf 'WAITING_FOR_FLUCK\\n'; sleep 60",
                supervisor = supervisor,
            ))
            waitUntil { BossTermSetupController.canAskFluckToDebugAndFix() }
            rule.setContent {
                val state by BossTermSetupController.state.collectAsState()
                Surface(color = ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor) {
                    Box(Modifier.size(820.dp, 692.dp).testTag("setup-layout-fixture")) {
                        SetupProgress(state, true, false, "", {}, {}, {}, {})
                    }
                }
            }
            rule.onNodeWithTag("setup-ask-fluck-debug").assertIsDisplayed()
            rule.onNodeWithText("Ask Fluck to debug and fix").assertIsDisplayed()
            rule.onNodeWithText("Continue in background").assertIsDisplayed()
            writeFixtureScreenshot("/tmp/bossterm-setup-820x692-progress-fluck-ready.png")
        } finally {
            BossTermSetupController.terminalState.sendCtrlC()
            waitUntil { BossTermSetupController.state.value.finished }
            BossTermSetupController.clearFinished()
            BossTermSetupController.terminalState.dispose()
        }
    }

    @Test
    fun `failed progress keeps Fluck and retry actions inside 820 by 692 footer`() {
        rule.setContent {
            Surface(color = ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor) {
                Box(Modifier.size(820.dp, 692.dp).testTag("setup-layout-fixture")) {
                    SetupProgress(
                        state = BossTermSetupState(
                            sessionId = "failed-fixture",
                            tasks = listOf(SetupTaskState("failed", "Install package", "fixture", SetupTaskStatus.NEEDS_ATTENTION)),
                            finished = true,
                            failureMessage = "Setup needs attention",
                            supervisedByFluck = true,
                            supervisionChecked = true,
                        ),
                        canBackground = true,
                        canRetry = true,
                        adminPassword = "",
                        onAdminPasswordChange = {}, onBackground = {}, onRetry = {}, onDone = {},
                    )
                }
            }
        }
        val root = rule.onNodeWithTag("setup-layout-fixture").getUnclippedBoundsInRoot()
        val fluckBounds = rule.onNodeWithTag("setup-ask-fluck-debug").assertIsDisplayed().getUnclippedBoundsInRoot()
        val retryBounds = rule.onNodeWithText("Try again").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(fluckBounds.bottom <= root.bottom, "Fluck action escaped the fixed footer")
        assertTrue(retryBounds.bottom <= root.bottom, "Try again escaped the fixed footer")
    }

    @Test
    fun `820 by 692 shows preview controls version control and the default AI grid`() {
        renderFixture()

        val rootBounds = rule.onNodeWithTag("setup-layout-fixture").getUnclippedBoundsInRoot()
        val primary = rule.onNodeWithTag("setup-primary-action").assertIsDisplayed()
        assertTrue(primary.getUnclippedBoundsInRoot().bottom <= rootBounds.bottom, "primary action escaped viewport")
        rule.onNodeWithTag("setup-options-scrollbar").assertIsDisplayed()
        rule.onNodeWithTag("setup-basic-choices").assertIsDisplayed()
        rule.onNodeWithTag("prompt-preview").assertIsDisplayed()
        rule.onNodeWithTag("version-control-options").assertIsDisplayed()
        rule.onNodeWithTag("ai-agents-section").assertIsDisplayed()
        onboardingAiTools().forEach { tool ->
            val label = tool.displayName + if (installedFixture().isAiInstalled(tool.id)) " · installed" else ""
            rule.onNodeWithText(label).assertIsDisplayed()
        }
        val scrollBounds = rule.onNodeWithTag("setup-options-scroll").getUnclippedBoundsInRoot()
        val aiBounds = rule.onNodeWithTag("ai-agents-section").getUnclippedBoundsInRoot()
        val supervisionBounds = rule.onNodeWithTag("setup-supervision-note").assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(aiBounds.bottom <= scrollBounds.bottom, "default AI grid is clipped at the bottom")
        assertTrue(supervisionBounds.bottom <= scrollBounds.bottom, "Fluck status requires scrolling at normal size")

        val packageBounds = rule.onNodeWithText("Package manager").getUnclippedBoundsInRoot()
        val shellBounds = rule.onNodeWithText("Default shell").getUnclippedBoundsInRoot()
        val promptBounds = rule.onNodeWithText("Shell customization").getUnclippedBoundsInRoot()
        assertTrue(packageBounds.right <= shellBounds.left, "package manager and shell cards overlap")
        assertTrue(shellBounds.right <= promptBounds.left, "shell and customization cards overlap")
        writeFixtureScreenshot("/tmp/bossterm-setup-820x692-preview-first.png")

        assertWholeSupervisionCardVisible()
        rule.onNodeWithTag("setup-primary-action").assertIsDisplayed()
        writeFixtureScreenshot("/tmp/bossterm-setup-820x692-preview-first-bottom.png")
    }

    @Test
    fun `large fonts keep AI heading readable and actions visible`() {
        renderFixture(fontScale = 1.5f)

        val aiHeader = rule.onNodeWithText("AI agents").performScrollTo().assertIsDisplayed()
        val aiBounds = aiHeader.getUnclippedBoundsInRoot()
        assertTrue(aiBounds.right - aiBounds.left > 60.dp, "large-font AI header collapsed")
        val tools = onboardingAiTools()
        val cells = tools.map { tool ->
            val cell = rule.onNodeWithTag("ai-agent-${tool.id}").getUnclippedBoundsInRoot()
            val label = tool.displayName + if (installedFixture().isAiInstalled(tool.id)) " · installed" else ""
            val labelBounds = rule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue(labelBounds.left >= cell.left && labelBounds.right <= cell.right, "${tool.displayName} label escaped its cell")
            assertTrue(labelBounds.top >= cell.top && labelBounds.bottom <= cell.bottom, "${tool.displayName} label escaped vertically")
            cell
        }
        cells.zipWithNext().forEach { (left, right) ->
            val sharesRow = left.top < right.bottom && right.top < left.bottom
            if (sharesRow) assertTrue(left.right <= right.left, "adjacent AI cells overlap at 150% font scale")
        }
        assertWholeSupervisionCardVisible()
        rule.onNodeWithTag("setup-primary-action").assertIsDisplayed()
        writeFixtureScreenshot("/tmp/bossterm-setup-820x692-preview-first-font150-bottom.png")
    }

    @Test
    fun `shell and prompt menus update preview and reset an unsupported customization`() {
        lateinit var shell: MutableState<ShellChoice>
        lateinit var prompt: MutableState<ShellCustomizationChoice>
        renderFixture(
            shellState = { mutableStateOf(ShellChoice.ZSH).also { shell = it } },
            promptState = { mutableStateOf(ShellCustomizationChoice.KEEP_EXISTING).also { prompt = it } },
        )

        rule.onNodeWithText("Keep existing").performClick()
        rule.onNodeWithText("Oh My Zsh").performClick()
        rule.onNodeWithText(promptPreviewText(ShellChoice.ZSH, ShellCustomizationChoice.OH_MY_ZSH)).assertIsDisplayed()

        rule.onNodeWithText("Zsh · /bin/zsh").performClick()
        rule.onNodeWithText("Bash · /bin/bash").performClick()
        rule.onNodeWithText("Starship · recommended").assertIsDisplayed()
        rule.onNodeWithText(promptPreviewText(ShellChoice.BASH, ShellCustomizationChoice.STARSHIP)).assertIsDisplayed()

        // Keep the pure mapping exhaustive so a newly added choice cannot silently reuse stale preview text.
        ShellCustomizationChoice.entries.forEach { choice ->
            assertTrue(promptPreviewText(shell.value, choice).isNotBlank())
        }
    }

    @Test
    fun `progress keeps interactive terminal and actions visible before twelve task details expand`() {
        val tasks = (1..12).map { index ->
            SetupTaskState(
                id = "fixture-$index",
                title = "Fixture setup step $index",
                detail = "Safe fixture detail $index",
                status = if (index == 1) SetupTaskStatus.RUNNING else SetupTaskStatus.PENDING,
            )
        }
        try {
            rule.setContent {
                Surface(color = ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor) {
                    Box(Modifier.size(820.dp, 692.dp).testTag("setup-layout-fixture")) {
                        SetupProgress(
                            state = BossTermSetupState(
                                sessionId = "layout-fixture",
                                tasks = tasks,
                                supervisionChecked = true,
                                supervisedByFluck = true,
                                agentDebugAwaitingVerification = true,
                            ),
                            canBackground = true,
                            canRetry = false,
                            adminPassword = "",
                            onAdminPasswordChange = {},
                            onBackground = {},
                            onRetry = {},
                            onDone = {},
                        )
                    }
                }
            }

            val rootBounds = rule.onNodeWithTag("setup-layout-fixture").getUnclippedBoundsInRoot()
            val terminalBounds = rule.onNodeWithTag("setup-interactive-terminal").assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            val fluckBounds = rule.onNodeWithTag("setup-fluck-status").assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            assertTrue(fluckBounds.bottom <= terminalBounds.top, "Fluck status must remain visible above the terminal")
            assertTrue(terminalBounds.top >= rootBounds.top && terminalBounds.bottom <= rootBounds.bottom)
            rule.onNodeWithText("Continue in background").assertIsDisplayed()
            rule.onNodeWithTag("setup-ask-fluck-debug").assertIsDisplayed()
            rule.onNodeWithText("Resume and verify").assertIsDisplayed()
            rule.onNodeWithTag("setup-task-details").assertDoesNotExist()
            rule.waitUntil(10_000) { BossTermSetupController.terminalState.isConnected }
            BossTermSetupController.terminalState.write("printf 'Interactive terminal ready\\n'\r")
            Thread.sleep(300)
            rule.waitForIdle()
            writeFixtureScreenshot("/tmp/bossterm-setup-820x692-progress-fluck-awaiting.png")

            rule.onNodeWithTag("setup-task-details-toggle").performClick()
            rule.onNodeWithTag("setup-task-details").assertExists()
            rule.onNodeWithText("Fixture setup step 12").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Continue in background").assertIsDisplayed()
            writeFixtureScreenshot("/tmp/bossterm-setup-820x692-progress-expanded.png")
        } finally {
            BossTermSetupController.terminalState.dispose()
        }
    }

    @Test
    fun `verified success is clear with many completed tasks and Done invokes completion`() {
        var done = false
        renderSuccessFixture { done = true }

        rule.onNodeWithTag("setup-success").assertIsDisplayed()
        rule.onNodeWithText("BOSS Term is ready").assertIsDisplayed()
        rule.onNodeWithText("12 setup steps completed.").assertIsDisplayed()
        rule.onNodeWithText("Completed setup").assertIsDisplayed()
        rule.onNodeWithTag("setup-success-done").assertIsDisplayed()
        writeFixtureScreenshot("/tmp/bossterm-setup-success-820x692.png")
        rule.onNodeWithTag("setup-success-done").performClick()
        rule.runOnIdle { assertTrue(done, "Done must invoke the completion callback") }
    }

    @Test
    fun `verified success remains readable at 150 percent font scale`() {
        renderSuccessFixture(fontScale = 1.5f)

        rule.onNodeWithText("BOSS Term is ready").assertIsDisplayed()
        rule.onNodeWithTag("setup-success-done").assertIsDisplayed()
        rule.onNodeWithText("Completed setup").assertIsDisplayed()
        rule.onNodeWithTag("setup-success-summary").assertIsDisplayed()
        rule.onNodeWithTag("setup-success-scrollbar").assertIsDisplayed()
        writeFixtureScreenshot("/tmp/bossterm-setup-success-820x692-font150.png")
        rule.onNodeWithText("Completed setup step 12").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("setup-success-done").assertIsDisplayed()
        writeFixtureScreenshot("/tmp/bossterm-setup-success-820x692-font150-bottom.png")
    }

    private fun renderFixture(
        fontScale: Float = 1f,
        requiresAuthorization: Boolean = false,
        shellState: () -> MutableState<ShellChoice> = { mutableStateOf(ShellChoice.ZSH) },
        promptState: () -> MutableState<ShellCustomizationChoice> = {
            mutableStateOf(ShellCustomizationChoice.KEEP_EXISTING)
        },
    ) {
        rule.setContent {
            val density = LocalDensity.current
            val shell = remember { shellState() }
            val prompt = remember { promptState() }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Surface(color = ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor) {
                    Box(Modifier.size(820.dp, 692.dp).testTag("setup-layout-fixture")) {
                        SetupConfirmation(
                            packageManager = PackageManagerChoice.AUTO,
                            shell = shell.value,
                            prompt = prompt.value,
                            installed = installedFixture(),
                            installGit = true,
                            installGitHubCli = true,
                            authenticateGitHub = false,
                            aiAssistants = emptySet(),
                            adminPassword = "",
                            requiresAuthorization = requiresAuthorization,
                            fluckBridgeAvailable = true,
                            onShellChange = { selected ->
                                shell.value = selected
                                if (prompt.value !in availablePromptChoices(selected)) {
                                    prompt.value = if (
                                        ShellCustomizationChoice.STARSHIP in availablePromptChoices(selected)
                                    ) ShellCustomizationChoice.STARSHIP else ShellCustomizationChoice.NONE
                                }
                            },
                            onPackageManagerChange = {},
                            onPromptChange = { prompt.value = it },
                            onInstallGitChange = {},
                            onInstallGitHubCliChange = {},
                            onAuthenticateGitHubChange = {},
                            onAiAssistantsChange = {},
                            onAdminPasswordChange = {},
                            onNotNow = {},
                            onStart = {},
                        )
                    }
                }
            }
        }
    }

    private fun installedFixture(): InstalledTools = InstalledTools(
        zsh = true,
        homebrew = true,
        git = true,
        gh = true,
        aiAssistants = onboardingAiTools().associate { it.id to (it.displayName != "Grok Build") },
    )

    private fun renderSuccessFixture(fontScale: Float = 1f, onDone: () -> Unit = {}) {
        val tasks = (1..12).map { index ->
            SetupTaskState(
                id = "success-$index",
                title = "Completed setup step $index",
                detail = "Verified safely",
                status = SetupTaskStatus.COMPLETE,
            )
        }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Surface(color = ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor) {
                    Box(Modifier.size(820.dp, 692.dp).testTag("setup-layout-fixture")) {
                        SetupSuccess(
                            state = BossTermSetupState(
                                sessionId = "success-fixture",
                                tasks = tasks,
                                finished = true,
                                supervisionChecked = true,
                                supervisedByFluck = true,
                            ),
                            onDone = onDone,
                        )
                    }
                }
            }
        }
    }

    private fun assertWholeSupervisionCardVisible() {
        val scrollBounds = rule.onNodeWithTag("setup-options-scroll").getUnclippedBoundsInRoot()
        val note = rule.onNodeWithTag("setup-supervision-note").performScrollTo().assertIsDisplayed()
        val noteBounds = note.getUnclippedBoundsInRoot()
        assertTrue(noteBounds.top >= scrollBounds.top, "supervision card is clipped above the scroll viewport")
        assertTrue(noteBounds.bottom <= scrollBounds.bottom, "supervision card is clipped behind the fixed footer")
    }

    private fun writeFixtureScreenshot(path: String) {
        val bitmap = rule.onRoot().captureToImage().asSkiaBitmap()
        val bytes = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)?.bytes
        requireNotNull(bytes) { "Failed to encode setup fixture screenshot" }
        Files.write(Path.of(path), bytes)
    }
}
