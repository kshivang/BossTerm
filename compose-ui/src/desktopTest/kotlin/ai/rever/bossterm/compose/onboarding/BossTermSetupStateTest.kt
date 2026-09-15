package ai.rever.bossterm.compose.onboarding

import ai.rever.bossterm.compose.ai.AIAssistants
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame

class BossTermSetupStateTest {
    @Test
    fun `default prompt is Starship when supported even if an existing prompt was detected`() {
        assertEquals(ShellCustomizationChoice.STARSHIP, defaultPromptChoice(ShellChoice.ZSH, TargetOs.MAC))
        assertEquals(ShellCustomizationChoice.STARSHIP, defaultPromptChoice(ShellChoice.BASH, TargetOs.LINUX))
        assertEquals(ShellCustomizationChoice.NONE, defaultPromptChoice(ShellChoice.CMD, TargetOs.WINDOWS))
    }

    @Test
    fun `success requires verified finish without failure or pending GitHub authentication`() {
        assertTrue(isVerifiedSetupSuccess(BossTermSetupState(sessionId = "ok", finished = true)))
        assertFalse(
            isVerifiedSetupSuccess(
                BossTermSetupState(sessionId = "failed", finished = true, failureMessage = "verification failed"),
            ),
        )
        assertFalse(
            isVerifiedSetupSuccess(
                BossTermSetupState(sessionId = "auth", finished = true, awaitingGitHubAuthentication = true),
            ),
        )
        assertFalse(isVerifiedSetupSuccess(BossTermSetupState(sessionId = "running", finished = false)))
    }

    @Test
    fun `finished failure can close without clearing retained retry state`() {
        assertTrue(
            isFinishedSetupFailure(
                BossTermSetupState(sessionId = "failed", finished = true, failureMessage = "verification failed"),
            ),
        )
        assertFalse(isFinishedSetupFailure(BossTermSetupState(sessionId = "running")))
        assertFalse(isFinishedSetupFailure(BossTermSetupState(sessionId = "ok", finished = true)))
    }

    @Test
    fun `headless controller terminal reads input and completes while backgrounded`() {
        if (TargetOs.current().isWindows) return
        BossTermSetupController.clearFinished()
        assertTrue(
            BossTermSetupController.startTerminalTaskForTest(
                "printf 'READY_FOR_INPUT\\n'; read value; printf 'input:%s\\n' \"\$value\"",
            ),
        )
        val terminal = BossTermSetupController.terminalState
        BossTermSetupController.sendToBackground()
        try {
            waitUntil { terminal.isConnected && BossTermSetupController.state.value.isRunning }
            val session = terminal.session
            waitUntil { "READY_FOR_INPUT" in BossTermSetupController.terminalCapturedOutputForTest() }
            terminal.write("hello-from-test\r")
            waitUntil { BossTermSetupController.state.value.finished }

            val result = BossTermSetupController.state.value
            assertTrue(result.isBackgrounded)
            assertEquals(SetupTaskStatus.COMPLETE, result.tasks.single().status)
            assertTrue("input:hello-from-test" in BossTermSetupController.terminalCapturedOutputForTest())
            assertSame(session, terminal.session, "background execution must finish on its original PTY")
        } finally {
            BossTermSetupController.clearFinished()
        }
    }

    @Test
    fun `active task hands the real PTY to Fluck then explicitly verifies and reaches auth`() {
        if (TargetOs.current().isWindows) return
        BossTermSetupController.clearFinished()
        val fixed = Files.createTempFile("bossterm-agent-fix-", ".marker").toFile().apply { delete() }
        val requestId = AtomicReference<String>()
        val supervisor = object : BossTermSetupSupervisor {
            override suspend fun start(sessionId: String, tasks: List<SetupTaskState>): Boolean = true

            override suspend fun debugAndFix(
                request: SetupDebugRequest,
                onAccepted: () -> Unit,
            ): SetupDebugResult {
                requestId.set(request.requestId)
                onAccepted()
                assertFalse(
                    BossTermSetupController.sendSetupTerminalInput(
                        request.terminalId,
                        "stale-token",
                        "touch '${fixed.absolutePath}'\r".toByteArray(),
                    ),
                )
                assertTrue(
                    BossTermSetupController.sendSetupTerminalInput(
                        request.terminalId,
                        request.requestId,
                        "touch '${fixed.absolutePath}'; printf 'AGENT_FIX_DONE\\n'\r".toByteArray(),
                    ),
                )
                waitUntil {
                    BossTermSetupController.setupTerminalScrollback(request.terminalId)
                        ?.lines?.any { "AGENT_FIX_DONE" in it } == true
                }
                return SetupDebugResult(completed = true)
            }
        }
        try {
            assertTrue(
                BossTermSetupController.startTerminalTaskForTest(
                    command = "printf 'ACTIVE_TASK\\n'; sleep 60",
                    verificationCommand = "test -f '${fixed.absolutePath}'",
                    supervisor = supervisor,
                    authenticateGitHub = true,
                ),
            )
            waitUntil { "ACTIVE_TASK" in BossTermSetupController.terminalCapturedOutputForTest() }
            waitUntil { BossTermSetupController.canAskFluckToDebugAndFix() }
            assertTrue(BossTermSetupController.askFluckToDebugAndFix())
            waitUntil { BossTermSetupController.state.value.agentDebugAwaitingVerification }
            val terminalId = requireNotNull(BossTermSetupController.state.value.setupTerminalId)
            assertFalse(
                BossTermSetupController.sendSetupTerminalInput(
                    terminalId,
                    requireNotNull(requestId.get()),
                    "echo too-late\r".toByteArray(),
                ),
                "agent token must be revoked before verification",
            )
            assertTrue(BossTermSetupController.resumeAndReverify())
            waitUntil { BossTermSetupController.state.value.awaitingGitHubAuthentication }
            assertEquals(SetupTaskStatus.COMPLETE, BossTermSetupController.state.value.tasks.single().status)
            assertTrue(BossTermSetupController.finishInteractiveGitHubAuth(completed = false))
            assertTrue(BossTermSetupController.state.value.finished)
        } finally {
            BossTermSetupController.terminalState.sendCtrlC()
            BossTermSetupController.terminalState.dispose()
            fixed.delete()
            BossTermSetupController.clearFinished()
        }
    }

    @Test
    fun `handoff refuses a PTY that cannot reach an interrupt boundary`() {
        if (TargetOs.current().isWindows) return
        BossTermSetupController.clearFinished()
        val invoked = AtomicBoolean(false)
        val supervisor = object : BossTermSetupSupervisor {
            override suspend fun start(sessionId: String, tasks: List<SetupTaskState>): Boolean = true

            override suspend fun debugAndFix(
                request: SetupDebugRequest,
                onAccepted: () -> Unit,
            ): SetupDebugResult {
                invoked.set(true)
                return SetupDebugResult(true)
            }
        }
        try {
            assertTrue(
                BossTermSetupController.startTerminalTaskForTest(
                    command = "trap '' INT; printf 'IGNORING_INTERRUPT\\n'; sleep 30",
                    supervisor = supervisor,
                ),
            )
            waitUntil { "IGNORING_INTERRUPT" in BossTermSetupController.terminalCapturedOutputForTest() }
            waitUntil { BossTermSetupController.canAskFluckToDebugAndFix() }
            assertTrue(BossTermSetupController.askFluckToDebugAndFix())
            waitUntil(timeoutMs = 10_000) { BossTermSetupController.state.value.agentDebugError != null }
            assertFalse(invoked.get(), "Fluck must never receive a terminal that is still busy")
            assertTrue(BossTermSetupController.state.value.agentDebugError!!.contains("Could not pause"))
            waitUntil { BossTermSetupController.state.value.finished }
        } finally {
            BossTermSetupController.terminalState.sendCtrlC()
            BossTermSetupController.terminalState.dispose()
            BossTermSetupController.clearFinished()
        }
    }

    @Test
    fun `Ask during an automatic retry is consumed and resumes through verification`() {
        if (TargetOs.current().isWindows) return
        BossTermSetupController.clearFinished()
        val directory = Files.createTempDirectory("bossterm-repair-handoff-").toFile()
        val attempt = directory.resolve("attempt")
        val fixed = directory.resolve("fixed")
        val supervisor = object : BossTermSetupSupervisor {
            override suspend fun start(sessionId: String, tasks: List<SetupTaskState>): Boolean = true
            override suspend fun repair(failure: SetupFailure): SetupRepair = SetupRepair.RETRY
            override suspend fun debugAndFix(
                request: SetupDebugRequest,
                onAccepted: () -> Unit,
            ): SetupDebugResult {
                onAccepted()
                assertTrue(
                    BossTermSetupController.sendSetupTerminalInput(
                        request.terminalId,
                        request.requestId,
                        "touch '${fixed.absolutePath}'; printf 'RETRY_FIXED\\n'\r".toByteArray(),
                    ),
                )
                waitUntil {
                    BossTermSetupController.setupTerminalScrollback(request.terminalId)
                        ?.lines?.any { "RETRY_FIXED" in it } == true
                }
                return SetupDebugResult(true)
            }
        }
        try {
            assertTrue(
                BossTermSetupController.startTerminalTaskForTest(
                    command = "if [ ! -f '${attempt.absolutePath}' ]; then touch '${attempt.absolutePath}'; exit 9; fi; " +
                        "printf 'RETRY_ACTIVE\\n'; sleep 60",
                    verificationCommand = "test -f '${fixed.absolutePath}'",
                    supervisor = supervisor,
                ),
            )
            waitUntil { "RETRY_ACTIVE" in BossTermSetupController.terminalCapturedOutputForTest() }
            waitUntil { BossTermSetupController.canAskFluckToDebugAndFix() }
            assertTrue(BossTermSetupController.askFluckToDebugAndFix())
            waitUntil { BossTermSetupController.state.value.agentDebugAwaitingVerification }
            assertTrue(BossTermSetupController.resumeAndReverify())
            waitUntil { BossTermSetupController.state.value.finished }
            assertEquals(SetupTaskStatus.COMPLETE, BossTermSetupController.state.value.tasks.single().status)
        } finally {
            BossTermSetupController.terminalState.sendCtrlC()
            BossTermSetupController.terminalState.dispose()
            directory.deleteRecursively()
            BossTermSetupController.clearFinished()
        }
    }

    @Test
    fun `progress follows completed tasks and preserves an active repair`() {
        val state = BossTermSetupState(
            sessionId = "setup-1",
            tasks = listOf(
                task("preferences", SetupTaskStatus.COMPLETE),
                task("prompt", SetupTaskStatus.REPAIRING),
                task("git", SetupTaskStatus.PENDING),
                task("github-cli", SetupTaskStatus.PENDING),
            ),
            supervisedByFluck = true,
            supervisionChecked = true,
            isBackgrounded = true,
        )

        assertTrue(state.isRunning)
        assertTrue(state.isBackgrounded)
        assertEquals(1, state.completedTaskCount)
        assertEquals(0.25f, state.progress)
        assertEquals("prompt", state.activeTask?.id)
    }

    @Test
    fun `finished failure is no longer running`() {
        val state = BossTermSetupState(
            sessionId = "setup-2",
            tasks = listOf(task("git", SetupTaskStatus.NEEDS_ATTENTION)),
            finished = true,
            failureMessage = "Git needs attention",
        )

        assertFalse(state.isRunning)
        assertEquals(0f, state.progress)
        assertEquals(null, state.activeTask)
    }

    @Test
    fun `zsh exposes both framework customizations while fish does not`() {
        assertTrue(ShellCustomizationChoice.OH_MY_ZSH in availablePromptChoices(ShellChoice.ZSH))
        assertTrue(ShellCustomizationChoice.PREZTO in availablePromptChoices(ShellChoice.ZSH))
        assertFalse(ShellCustomizationChoice.OH_MY_ZSH in availablePromptChoices(ShellChoice.FISH))
        assertFalse(ShellCustomizationChoice.PREZTO in availablePromptChoices(ShellChoice.FISH))
    }

    @Test
    fun `command prompt does not offer PowerShell prompt integrations`() {
        val choices = availablePromptChoices(ShellChoice.CMD, TargetOs.WINDOWS)
        assertFalse(ShellCustomizationChoice.STARSHIP in choices)
        assertFalse(ShellCustomizationChoice.OH_MY_POSH in choices)
    }

    @Test
    fun `compact setup lists every registered onboarding AI tool`() {
        val tools = onboardingAiTools()

        assertTrue(tools.isNotEmpty())
        assertEquals(tools.size, tools.map { it.id }.distinct().size)
    }

    @Test
    fun `setup plans every selected AI assistant and verifies its executable`() {
        val selected = AIAssistants.BUILTIN.take(2)
        val plans = BossTermSetupController.buildTaskPlan(
            OnboardingSelections(
                shell = ShellChoice.KEEP_CURRENT,
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = selected.map { it.id }.toSet(),
            ),
            InstalledTools(aiAssistants = selected.associate { it.id to false }),
        )

        val aiPlans = plans.filter { it.state.id.startsWith("ai-") }
        assertEquals(selected.map { "ai-${it.id}" }.toSet(), aiPlans.map { it.state.id }.toSet())
        assertTrue(aiPlans.all { it.verificationCommand != null })
    }

    @Test
    fun `verification is platform-specific and blank commands need none`() {
        assertEquals(null, BossTermSetupController.executableVerification("", TargetOs.MAC))
        val unixVerification = BossTermSetupController.executableVerification("git", TargetOs.LINUX)
        val windowsVerification = BossTermSetupController.executableVerification("gh", TargetOs.WINDOWS)
        assertTrue(unixVerification!!.contains("command -v 'git'"))
        assertTrue(windowsVerification!!.contains("Get-Command 'gh'"))
    }

    @Test
    fun `an explicit package manager is a real setup task`() {
        val plans = BossTermSetupController.buildTaskPlan(
            OnboardingSelections(
                packageManager = PackageManagerChoice.HOMEBREW,
                shell = ShellChoice.KEEP_CURRENT,
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = false,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(homebrew = false),
        )

        assertEquals("package-manager", plans.first().state.id)
        assertEquals("Install Homebrew", plans.first().state.detail)
        assertTrue(plans.first().verificationCommand.orEmpty().contains("brew"))
    }

    @Test
    fun `verification rejects an executable whose version smoke test fails`() {
        withFakeExecutable(exitCode = 7) { executableDir ->
            val command = requireNotNull(BossTermSetupController.executableVerification("fake-tool", TargetOs.LINUX))
            assertEquals(7, runVerification(command, executableDir))
        }
    }

    @Test
    fun `verification accepts an executable whose version smoke test passes`() {
        withFakeExecutable(exitCode = 0) { executableDir ->
            val command = requireNotNull(BossTermSetupController.executableVerification("fake-tool", TargetOs.LINUX))
            assertEquals(0, runVerification(command, executableDir))
        }
    }

    @Test
    fun `prompt directory without shell activation is rejected`() {
        val fakeHome = Files.createTempDirectory("bossterm-prompt-verification-").toFile()
        try {
            fakeHome.resolve(".oh-my-zsh").mkdirs()
            val verification = requireNotNull(
                BossTermSetupController.promptVerification(
                    ShellCustomizationChoice.OH_MY_ZSH,
                    ShellChoice.ZSH,
                    TargetOs.LINUX,
                ),
            )
            assertTrue(runVerification(verification, home = fakeHome) != 0)
            fakeHome.resolve(".zshrc").writeText("source \$HOME/.oh-my-zsh/oh-my-zsh.sh\n")
            assertEquals(0, runVerification(verification, home = fakeHome))
        } finally {
            fakeHome.deleteRecursively()
        }
    }

    @Test
    fun `prompt activation cannot hide a failing executable smoke test`() {
        val fakeHome = Files.createTempDirectory("bossterm-prompt-home-").toFile()
        val executableDir = Files.createTempDirectory("bossterm-prompt-bin-").toFile()
        try {
            fakeHome.resolve(".zshrc").writeText("eval \"\$(starship init zsh)\"\n")
            executableDir.resolve("starship").apply {
                writeText("#!/bin/bash\nexit 9\n")
                setExecutable(true)
            }
            val verification = requireNotNull(
                BossTermSetupController.promptVerification(
                    ShellCustomizationChoice.STARSHIP,
                    ShellChoice.ZSH,
                    TargetOs.LINUX,
                ),
            )
            assertEquals(9, runVerification(verification, executableDir, fakeHome))
        } finally {
            fakeHome.deleteRecursively()
            executableDir.deleteRecursively()
        }
    }

    @Test
    fun `explicit Chocolatey selection overrides detected Winget for installs`() {
        val plans = BossTermSetupController.buildTaskPlan(
            OnboardingSelections(
                packageManager = PackageManagerChoice.CHOCOLATEY,
                shell = ShellChoice.KEEP_CURRENT,
                shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
                installGit = true,
                installGitHubCLI = false,
                aiAssistants = emptySet(),
            ),
            InstalledTools(winget = true, chocolatey = true, git = false),
            TargetOs.WINDOWS,
        )

        val gitCommand = plans.single { it.state.id == "git" }.command
        assertTrue("choco install git" in gitCommand)
        assertFalse("winget install Git.Git" in gitCommand)
    }

    private fun withFakeExecutable(exitCode: Int, assertion: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("bossterm-verifier-").toFile()
        try {
            directory.resolve("fake-tool").apply {
                writeText("#!/bin/bash\nexit $exitCode\n")
                setExecutable(true)
            }
            assertion(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("condition was not met within ${timeoutMs}ms")
            Thread.sleep(25)
        }
    }

    private fun runVerification(
        script: String,
        executableDir: java.io.File? = null,
        home: java.io.File? = null,
    ): Int {
        val scriptFile = Files.createTempFile("bossterm-verification-", ".sh").toFile()
        return try {
            scriptFile.writeText(script)
            ProcessBuilder("/bin/bash", scriptFile.absolutePath).apply {
                executableDir?.let { environment()["PATH"] = "${it.absolutePath}:/usr/bin:/bin" }
                home?.let { environment()["HOME"] = it.absolutePath }
            }.start().waitFor()
        } finally {
            scriptFile.delete()
        }
    }

    private fun task(id: String, status: SetupTaskStatus) = SetupTaskState(
        id = id,
        title = id,
        detail = id,
        status = status,
    )
}
