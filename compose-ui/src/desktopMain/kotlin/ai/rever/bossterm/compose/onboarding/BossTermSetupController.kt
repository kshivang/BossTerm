package ai.rever.bossterm.compose.onboarding

import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.EmbeddableTerminalState
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.SettingsLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

enum class SetupTaskStatus { PENDING, RUNNING, REPAIRING, COMPLETE, NEEDS_ATTENTION }

data class SetupTaskState(
    val id: String,
    val title: String,
    val detail: String,
    val status: SetupTaskStatus = SetupTaskStatus.PENDING,
)

data class BossTermSetupState(
    val sessionId: String? = null,
    val tasks: List<SetupTaskState> = emptyList(),
    val supervisedByFluck: Boolean = false,
    val supervisionChecked: Boolean = false,
    val isBackgrounded: Boolean = false,
    val finished: Boolean = false,
    val failureMessage: String? = null,
    val failureOutput: String? = null,
    val outputLines: List<String> = emptyList(),
    val authenticateGitHubAfterSetup: Boolean = false,
    val awaitingGitHubAuthentication: Boolean = false,
    val setupTerminalId: String? = null,
    val agentDebugRequestInFlight: Boolean = false,
    val agentDebugActive: Boolean = false,
    val agentDebugAwaitingVerification: Boolean = false,
    val agentDebugError: String? = null,
) {
    val isRunning: Boolean get() =
        sessionId != null && (
            agentDebugRequestInFlight || agentDebugActive || agentDebugAwaitingVerification ||
                (!finished && failureMessage == null && !awaitingGitHubAuthentication)
            )
    val completedTaskCount: Int get() = tasks.count { it.status == SetupTaskStatus.COMPLETE }
    val progress: Float get() = if (tasks.isEmpty()) 0f else completedTaskCount.toFloat() / tasks.size
    val activeTask: SetupTaskState? get() = tasks.firstOrNull {
        it.status == SetupTaskStatus.RUNNING || it.status == SetupTaskStatus.REPAIRING
    }
}

data class SetupFailure(
    val sessionId: String,
    val taskId: String,
    val taskTitle: String,
    val attempt: Int,
    val exitCode: Int,
    val output: String,
    val platform: TargetOs,
)

enum class SetupRepair { RETRY, REFRESH_PACKAGES_AND_RETRY, STOP }
enum class SetupTerminalActivity { BUSY, IDLE, UNKNOWN, HANDOFF }

data class SetupDebugRequest(
    val requestId: String,
    val sessionId: String,
    val terminalId: String,
    val task: SetupTaskState,
    val output: String,
)

data class SetupDebugResult(val completed: Boolean, val error: String? = null)

/** Optional host bridge. BossTerm itself never depends on, or assumes, Fluck Agent. */
interface BossTermSetupSupervisor {
    suspend fun start(sessionId: String, tasks: List<SetupTaskState>): Boolean
    suspend fun taskChanged(sessionId: String, task: SetupTaskState) = Unit
    suspend fun repair(failure: SetupFailure): SetupRepair = SetupRepair.STOP
    suspend fun finish(sessionId: String, success: Boolean, message: String?) = Unit
    suspend fun debugAndFix(request: SetupDebugRequest, onAccepted: () -> Unit): SetupDebugResult =
        SetupDebugResult(false, "Fluck debugging is unavailable")
}

/**
 * Process-lifetime owner for onboarding installation.
 *
 * The wizard is a separate window. Keeping the Job here lets the user explicitly move an active
 * setup into the host's bottom bar without cancelling it, then reopen the same live progress.
 */
object BossTermSetupController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("bossterm-setup"))
    private val _state = MutableStateFlow(BossTermSetupState())
    val state: StateFlow<BossTermSetupState> = _state.asStateFlow()
    private var retainedRequest: SetupRequest? = null
    val terminalState = EmbeddableTerminalState()
    private val terminalLock = Any()
    private var terminalReady = CompletableDeferred<Unit>()
    private var pendingTerminalCommand: PendingTerminalCommand? = null
    @Volatile
    internal var terminalCommandSubmittedForTest: Boolean = false
        private set
    @Volatile
    private var lastTerminalOutputForTest: String = ""
    @Volatile
    private var handoffRequestedSessionId: String? = null
    @Volatile
    private var activeHandoffRequestId: String? = null
    private var pendingAgentResume: CompletableDeferred<Unit>? = null
    @Volatile
    private var terminalBoundarySafe = true

    fun start(
        selections: OnboardingSelections,
        installed: InstalledTools,
        settingsManager: SettingsManager,
        supervisor: BossTermSetupSupervisor? = null,
        adminPassword: String = "",
    ): Boolean {
        if (_state.value.isRunning || _state.value.awaitingGitHubAuthentication) return false
        if (!supportsSelections(selections, TargetOs.current())) return false

        val sessionId = UUID.randomUUID().toString()
        terminalState.dispose()
        terminalReady = CompletableDeferred()
        synchronized(terminalLock) { pendingTerminalCommand = null }
        terminalCommandSubmittedForTest = false
        lastTerminalOutputForTest = ""
        activeHandoffRequestId = null
        handoffRequestedSessionId = null
        pendingAgentResume = null
        terminalBoundarySafe = true
        retainedRequest = SetupRequest(sessionId, selections, installed, settingsManager, supervisor, adminPassword)
        val plans = buildTaskPlan(selections, installed)
        _state.value = BossTermSetupState(
            sessionId = sessionId,
            tasks = plans.map { it.state },
            authenticateGitHubAfterSetup = selections.authenticateGitHub,
        )
        initializeTerminal(sessionId)
        updateSession(sessionId) { it.copy(setupTerminalId = terminalState.terminalId) }

        scope.launch {
            val supervised = runCatching {
                supervisor?.start(sessionId, plans.map { it.state }) == true
            }.getOrDefault(false)
            updateSession(sessionId) { it.copy(supervisedByFluck = supervised, supervisionChecked = true) }

            var terminalFailure: String? = null
            var terminalOutput: String? = null
            for ((index, plan) in plans.withIndex()) {
                updateTask(index, SetupTaskStatus.RUNNING, supervisor)
                appendOutput("Starting ${plan.state.title}")
                val result = executeTaskWithRepairs(plan, index, sessionId, supervisor, supervised)

                if (result.exitCode != 0) {
                    terminalFailure = "${plan.state.title} needs attention"
                    terminalOutput = result.output.takeLast(MAX_FAILURE_OUTPUT)
                    updateTask(index, SetupTaskStatus.NEEDS_ATTENTION, supervisor)
                    break
                }
                updateTask(index, SetupTaskStatus.COMPLETE, supervisor)
                appendOutput("Completed ${plan.state.title}")
            }

            val success = terminalFailure == null
            if (success && retainedRequest?.sessionId == sessionId) {
                retainedRequest = retainedRequest?.copy(adminPassword = "")
            }
            if (success && selections.authenticateGitHub) {
                updateSession(sessionId) { it.copy(awaitingGitHubAuthentication = true) }
            } else {
                updateSession(sessionId) {
                    it.copy(finished = true, failureMessage = terminalFailure, failureOutput = terminalOutput)
                }
                if (success) runCatching { settingsManager.updateSetting { copy(onboardingCompleted = true) } }
                runCatching { supervisor?.finish(sessionId, success, terminalFailure) }
            }
        }
        return true
    }

    /** Restarts the last failed setup with the exact selections and detection snapshot it used. */
    fun retry(adminPassword: String? = null): Boolean {
        val savedRequest = retainedRequest ?: return false
        if (_state.value.isRunning || _state.value.failureMessage == null) return false
        val request = adminPassword?.let { savedRequest.copy(adminPassword = it) } ?: savedRequest
        return start(
            request.selections,
            request.installed,
            request.settingsManager,
            request.supervisor,
            request.adminPassword,
        )
    }

    /** Completes the user-owned foreground GitHub authentication stage. */
    fun finishInteractiveGitHubAuth(completed: Boolean): Boolean {
        val current = _state.value
        val sessionId = current.sessionId ?: return false
        if (!current.awaitingGitHubAuthentication) return false
        val supervisor = retainedRequest?.takeIf { it.sessionId == sessionId }?.supervisor
        val message = if (completed) null else "GitHub authentication was skipped"
        updateSession(sessionId) { it.copy(awaitingGitHubAuthentication = false, finished = true) }
        scope.launch {
            retainedRequest?.takeIf { it.sessionId == sessionId }?.settingsManager?.let { settings ->
                runCatching { settings.updateSetting { copy(onboardingCompleted = true) } }
            }
            // Authentication is an optional foreground continuation. An explicit skip completes
            // setup successfully while preserving an informational message for the supervisor.
            runCatching { supervisor?.finish(sessionId, true, message) }
        }
        return true
    }

    private suspend fun executeTaskWithRepairs(
        plan: TaskPlan,
        index: Int,
        sessionId: String,
        supervisor: BossTermSetupSupervisor?,
        supervised: Boolean,
    ): CommandResult {
        var result = runTaskWithHandoff(plan, sessionId, supervisor)
        var repairAttempt = 0
        while (result.exitCode != 0 && result.exitCode !in USER_CANCEL_EXIT_CODES &&
            terminalState.isConnected && supervised && repairAttempt < MAX_REPAIR_ATTEMPTS
        ) {
            repairAttempt += 1
            updateTask(index, SetupTaskStatus.REPAIRING, supervisor)
            val decision = runCatching {
                supervisor?.repair(
                    SetupFailure(
                        sessionId,
                        plan.state.id,
                        plan.state.title,
                        repairAttempt,
                        result.exitCode,
                        result.output.takeLast(MAX_FAILURE_OUTPUT),
                        TargetOs.current(),
                    ),
                ) ?: SetupRepair.STOP
            }.getOrDefault(SetupRepair.STOP)
            if (decision == SetupRepair.REFRESH_PACKAGES_AND_RETRY) {
                appendOutput("Fluck Agent is refreshing package information")
                result = runCommand(packageRefreshCommand(TargetOs.current()), sessionId)
                val handedOff = handoffRequestedSessionId == sessionId
                if (handedOff) {
                    result = runAgentHandoff(sessionId, plan, result, supervisor)
                    if (result.exitCode == 0) return result
                }
                if (result.exitCode != 0) {
                    appendOutput("Package information refresh failed")
                    continue
                }
            }
            if (decision == SetupRepair.STOP) {
                appendOutput("Fluck Agent needs your attention before setup can continue")
                break
            }
            appendOutput("Fluck Agent is retrying ${plan.state.title}")
            result = runTaskWithHandoff(plan, sessionId, supervisor)
        }
        return result
    }

    private suspend fun runTaskWithHandoff(
        plan: TaskPlan,
        sessionId: String,
        supervisor: BossTermSetupSupervisor?,
    ): CommandResult {
        val result = runTask(plan, sessionId)
        return if (handoffRequestedSessionId == sessionId) {
            runAgentHandoff(sessionId, plan, result, supervisor)
        } else {
            result
        }
    }

    internal fun requiresAdminAuthorization(
        selections: OnboardingSelections,
        installed: InstalledTools,
        targetOs: TargetOs = TargetOs.current(),
    ): Boolean =
        buildTaskPlan(selections, installed, targetOs).any { "BOSSTERM_SUDO_PWD" in it.command }

    internal fun supportsSelections(selections: OnboardingSelections, targetOs: TargetOs): Boolean {
        val shell = resolveConfiguredShell(selections.shell, targetOs)
        val prompt = selections.shellCustomization
        return when {
            targetOs.isWindows && prompt in setOf(
                ShellCustomizationChoice.OH_MY_ZSH,
                ShellCustomizationChoice.PREZTO,
            ) -> false
            !targetOs.isWindows && prompt == ShellCustomizationChoice.OH_MY_POSH -> false
            prompt in setOf(ShellCustomizationChoice.OH_MY_ZSH, ShellCustomizationChoice.PREZTO) &&
                shell != ShellChoice.ZSH -> false
            shell == ShellChoice.CMD && prompt !in setOf(
                ShellCustomizationChoice.NONE,
                ShellCustomizationChoice.KEEP_EXISTING,
            ) -> false
            else -> true
        }
    }

    fun sendToBackground() {
        _state.update { current ->
            if (current.sessionId != null) current.copy(isBackgrounded = true) else current
        }
    }

    fun bringToForeground() {
        _state.update { it.copy(isBackgrounded = false) }
    }

    fun clearFinished() {
        _state.update { current ->
            if (!current.isRunning && !current.awaitingGitHubAuthentication) {
                if (retainedRequest?.sessionId == current.sessionId) retainedRequest = null
                terminalState.dispose()
                BossTermSetupState()
            } else {
                current
            }
        }
    }

    /** Called by the embedded terminal view. The controller, rather than that view, owns the PTY. */
    fun terminalReady() {
        terminalReady.complete(Unit)
    }

    /** Keeps task completion and captured diagnostics alive while the terminal view is backgrounded. */
    fun terminalOutput(chunk: String) {
        terminalOutput(_state.value.sessionId, chunk)
    }

    private fun terminalOutput(sessionId: String?, chunk: String) {
        if (sessionId == null || _state.value.sessionId != sessionId) return
        val pending = synchronized(terminalLock) { pendingTerminalCommand }
        if (pending != null) {
            synchronized(pending.output) { pending.output.append(chunk) }
            pending.parser.accept(chunk)?.let { exitCode ->
                if (synchronized(terminalLock) {
                        if (pendingTerminalCommand === pending) {
                            pendingTerminalCommand = null
                            true
                        } else false
                    }) {
                    val output = redactSecret(synchronized(pending.output) { pending.output.toString() })
                    lastTerminalOutputForTest = output
                    pending.completion.complete(CommandResult(exitCode, output))
                }
            }
        }
    }

    internal fun terminalEnvironment(): Map<String, String> =
        mapOf("BOSSTERM_SUDO_PWD" to retainedRequest?.adminPassword.orEmpty())

    fun hasSetupTerminal(terminalId: String): Boolean =
        _state.value.setupTerminalId == terminalId && terminalState.terminalId == terminalId && terminalState.isConnected

    fun sendSetupTerminalInput(terminalId: String, requestId: String, bytes: ByteArray): Boolean {
        return synchronized(terminalLock) {
            if (!hasSetupTerminal(terminalId) || !_state.value.agentDebugActive ||
                activeHandoffRequestId != requestId
            ) return@synchronized false
            terminalState.sendInput(bytes)
            true
        }
    }

    fun interruptSetupTerminal(terminalId: String, requestId: String): Boolean {
        return synchronized(terminalLock) {
            if (!hasSetupTerminal(terminalId) || !_state.value.agentDebugActive ||
                activeHandoffRequestId != requestId
            ) return@synchronized false
            terminalState.sendCtrlC()
            true
        }
    }

    fun setupTerminalActivity(terminalId: String): SetupTerminalActivity = when {
        !hasSetupTerminal(terminalId) -> SetupTerminalActivity.UNKNOWN
        _state.value.agentDebugRequestInFlight || _state.value.agentDebugActive -> SetupTerminalActivity.HANDOFF
        synchronized(terminalLock) { pendingTerminalCommand != null } -> SetupTerminalActivity.BUSY
        else -> SetupTerminalActivity.IDLE
    }

    fun canAskFluckToDebugAndFix(): Boolean {
        val current = _state.value
        return current.supervisedByFluck && current.setupTerminalId != null &&
            hasSetupTerminal(current.setupTerminalId) && terminalBoundarySafe &&
            !current.agentDebugRequestInFlight &&
            !current.agentDebugActive && !current.agentDebugAwaitingVerification &&
            (synchronized(terminalLock) { pendingTerminalCommand != null } || current.failureMessage != null)
    }

    /** Requests an exclusive terminal handoff. Active setup work is interrupted at its sentinel boundary. */
    fun askFluckToDebugAndFix(): Boolean {
        if (!canAskFluckToDebugAndFix()) return false
        val current = _state.value
        val sessionId = current.sessionId ?: return false
        synchronized(terminalLock) { activeHandoffRequestId = UUID.randomUUID().toString() }
        handoffRequestedSessionId = sessionId
        updateSession(sessionId) {
            it.copy(agentDebugRequestInFlight = true, agentDebugActive = false, agentDebugError = null)
        }
        if (synchronized(terminalLock) { pendingTerminalCommand != null }) {
            val interrupted = synchronized(terminalLock) { pendingTerminalCommand }
            terminalState.sendCtrlC()
            scope.launch {
                delay(HANDOFF_BOUNDARY_TIMEOUT_MS)
                val stillBusy = synchronized(terminalLock) { pendingTerminalCommand === interrupted }
                if (stillBusy && handoffRequestedSessionId == sessionId) {
                    terminalBoundarySafe = false
                    handoffRequestedSessionId = null
                    synchronized(terminalLock) { activeHandoffRequestId = null }
                    interrupted?.completion?.complete(
                        CommandResult(HANDOFF_FAILED_EXIT_CODE, "Could not pause the active terminal command safely"),
                    )
                    updateSession(sessionId) {
                        it.copy(
                            agentDebugRequestInFlight = false,
                            agentDebugActive = false,
                            agentDebugError = "Could not pause this step safely. Try again after it stops.",
                        )
                    }
                }
            }
        } else if (current.failureMessage != null) {
            resumeFailedTaskWithAgent(sessionId)
        }
        return true
    }

    fun resumeAndReverify(): Boolean {
        val current = _state.value
        if (!current.agentDebugAwaitingVerification) return false
        val signal = synchronized(terminalLock) {
            pendingAgentResume.also { pendingAgentResume = null }
        } ?: return false
        updateSession(requireNotNull(current.sessionId)) {
            it.copy(agentDebugAwaitingVerification = false, agentDebugError = null)
        }
        signal.complete(Unit)
        return true
    }

    private fun resumeFailedTaskWithAgent(sessionId: String) {
        val request = retainedRequest?.takeIf { it.sessionId == sessionId } ?: return
        val plans = buildTaskPlan(request.selections, request.installed)
        val failedIndex = _state.value.tasks.indexOfFirst { it.status == SetupTaskStatus.NEEDS_ATTENTION }
        val plan = plans.getOrNull(failedIndex) ?: return
        scope.launch {
            val result = runAgentHandoff(
                sessionId,
                plan,
                CommandResult(-1, _state.value.failureOutput.orEmpty()),
                request.supervisor,
            )
            var success = result.exitCode == 0
            var failureOutput = result.output
            updateTask(failedIndex, if (success) SetupTaskStatus.COMPLETE else SetupTaskStatus.NEEDS_ATTENTION, request.supervisor)
            if (success) {
                updateSession(sessionId) { it.copy(finished = false, failureMessage = null, failureOutput = null) }
                for (index in (failedIndex + 1) until plans.size) {
                    updateTask(index, SetupTaskStatus.RUNNING, request.supervisor)
                    val remaining = executeTaskWithRepairs(
                        plans[index],
                        index,
                        sessionId,
                        request.supervisor,
                        _state.value.supervisedByFluck,
                    )
                    success = remaining.exitCode == 0
                    failureOutput = remaining.output
                    updateTask(
                        index,
                        if (success) SetupTaskStatus.COMPLETE else SetupTaskStatus.NEEDS_ATTENTION,
                        request.supervisor,
                    )
                    if (!success) break
                }
            }
            if (success && retainedRequest?.sessionId == sessionId) {
                retainedRequest = retainedRequest?.copy(adminPassword = "")
            }
            if (success && request.selections.authenticateGitHub) {
                updateSession(sessionId) {
                    it.copy(finished = false, awaitingGitHubAuthentication = true, failureMessage = null, failureOutput = null)
                }
            } else {
                updateSession(sessionId) {
                    it.copy(
                        finished = true,
                        failureMessage = if (success) null else "Setup still needs attention",
                        failureOutput = if (success) null else failureOutput.takeLast(MAX_FAILURE_OUTPUT),
                    )
                }
                if (success) {
                    runCatching { request.settingsManager.updateSetting { copy(onboardingCompleted = true) } }
                }
                runCatching { request.supervisor?.finish(sessionId, success, if (success) null else "Setup still needs attention") }
            }
        }
    }

    private suspend fun runAgentHandoff(
        sessionId: String,
        plan: TaskPlan,
        failure: CommandResult,
        supervisor: BossTermSetupSupervisor?,
    ): CommandResult {
        val terminalId = terminalState.terminalId
            ?: return CommandResult(-1, "Setup terminal is unavailable")
        handoffRequestedSessionId = null
        val requestId = synchronized(terminalLock) { activeHandoffRequestId }
            ?: return CommandResult(-1, "Fluck handoff request is unavailable")
        val result = runCatching {
            supervisor?.debugAndFix(
                SetupDebugRequest(requestId, sessionId, terminalId, plan.state, failure.output.takeLast(MAX_FAILURE_OUTPUT)),
            ) {
                updateSession(sessionId) {
                    it.copy(agentDebugRequestInFlight = false, agentDebugActive = true, agentDebugError = null)
                }
            } ?: SetupDebugResult(false, "Fluck debugging is unavailable")
        }.getOrElse { SetupDebugResult(false, it.message ?: "Fluck debugging failed") }
        // Revoke tool writes before verification enters the same PTY queue.
        synchronized(terminalLock) { activeHandoffRequestId = null }
        pendingAgentResume = null
        terminalBoundarySafe = true
        updateSession(sessionId) {
            it.copy(
                agentDebugRequestInFlight = false,
                agentDebugActive = false,
                agentDebugError = result.error,
            )
        }
        if (!result.completed) {
            return CommandResult(HANDOFF_FAILED_EXIT_CODE, result.error ?: "Fluck could not complete debugging")
        }
        val resume = CompletableDeferred<Unit>()
        synchronized(terminalLock) { pendingAgentResume = resume }
        updateSession(sessionId) { it.copy(agentDebugAwaitingVerification = true) }
        resume.await()
        val verification = plan.verificationCommand
            ?: return CommandResult(HANDOFF_FAILED_EXIT_CODE, "This setup step has no safe verification command")
        appendOutput("Verifying Fluck's fix for ${plan.state.title}")
        return runCommand(verification, sessionId)
    }

    fun setupTerminalScrollback(
        terminalId: String,
        lines: Int = 200,
    ): EmbeddableTerminalState.Scrollback? = if (hasSetupTerminal(terminalId)) {
        terminalState.readScrollback(lines)?.let { scrollback ->
            scrollback.copy(lines = scrollback.lines.map(::redactSecret))
        }
    } else {
        null
    }

    internal fun terminalCapturedOutputForTest(): String = synchronized(terminalLock) {
        pendingTerminalCommand?.let { pending ->
            redactSecret(synchronized(pending.output) { pending.output.toString() })
        } ?: lastTerminalOutputForTest
    }

    private fun initializeTerminal(sessionId: String) {
        val windows = TargetOs.current().isWindows
        terminalState.initializeSession(
            settings = SettingsLoader.resolveSettings(),
            command = if (windows) "powershell.exe" else "/bin/bash",
            workingDirectory = null,
            environment = terminalEnvironment(),
            initialCommand = null,
            onInitialCommandComplete = null,
            onOutput = { terminalOutput(sessionId, it) },
            onExit = { exitCode -> terminalExited(sessionId, exitCode) },
        )
        scope.launch {
            repeat(TERMINAL_READY_POLL_ATTEMPTS) {
                if (terminalState.isConnected) {
                    terminalReady()
                    return@launch
                }
                delay(TERMINAL_READY_POLL_MS)
            }
        }
    }

    private fun terminalExited(sessionId: String, exitCode: Int) {
        if (_state.value.sessionId != sessionId) return
        val pending = synchronized(terminalLock) {
            pendingTerminalCommand.also { pendingTerminalCommand = null }
        }
        pending?.completion?.complete(
            CommandResult(exitCode.takeIf { it != 0 } ?: -1, "Interactive terminal exited"),
        )
    }

    /** Harmless task-runner seam for PTY lifecycle tests; callers supply the complete test script. */
    internal fun startTerminalTaskForTest(
        command: String,
        verificationCommand: String? = null,
        supervisor: BossTermSetupSupervisor? = null,
        authenticateGitHub: Boolean = false,
    ): Boolean {
        if (_state.value.isRunning) return false
        val sessionId = UUID.randomUUID().toString()
        terminalState.dispose()
        terminalReady = CompletableDeferred()
        synchronized(terminalLock) { pendingTerminalCommand = null }
        terminalCommandSubmittedForTest = false
        lastTerminalOutputForTest = ""
        activeHandoffRequestId = null
        handoffRequestedSessionId = null
        pendingAgentResume = null
        terminalBoundarySafe = true
        val plan = TaskPlan(SetupTaskState("test", "Test terminal task", "Test only"), command, verificationCommand)
        _state.value = BossTermSetupState(
            sessionId = sessionId,
            tasks = listOf(plan.state),
            supervisedByFluck = supervisor != null,
            supervisionChecked = true,
            authenticateGitHubAfterSetup = authenticateGitHub,
        )
        initializeTerminal(sessionId)
        updateSession(sessionId) { it.copy(setupTerminalId = terminalState.terminalId) }
        scope.launch {
            updateTask(0, SetupTaskStatus.RUNNING, null)
            val result = executeTaskWithRepairs(plan, 0, sessionId, supervisor, supervisor != null)
            val success = result.exitCode == 0
            updateTask(0, if (success) SetupTaskStatus.COMPLETE else SetupTaskStatus.NEEDS_ATTENTION, null)
            updateSession(sessionId) {
                it.copy(
                    finished = !success || !authenticateGitHub,
                    awaitingGitHubAuthentication = success && authenticateGitHub,
                    failureMessage = if (success) null else "Test terminal task needs attention",
                    failureOutput = result.output.takeLast(MAX_FAILURE_OUTPUT),
                )
            }
        }
        return true
    }

    private fun redactSecret(text: String): String {
        val secret = retainedRequest?.adminPassword.orEmpty()
        return if (secret.isBlank()) text else text.replace(secret, "[redacted]")
    }

    private suspend fun updateTask(
        index: Int,
        status: SetupTaskStatus,
        supervisor: BossTermSetupSupervisor?,
    ) {
        val sessionId = requireNotNull(_state.value.sessionId)
        var updatedTask: SetupTaskState? = null
        updateSession(sessionId) { current ->
            val tasks = current.tasks.toMutableList()
            tasks[index] = tasks[index].copy(status = status)
            updatedTask = tasks[index]
            current.copy(tasks = tasks)
        }
        updatedTask?.let { task -> runCatching { supervisor?.taskChanged(sessionId, task) } }
    }

    private data class SetupRequest(
        val sessionId: String,
        val selections: OnboardingSelections,
        val installed: InstalledTools,
        val settingsManager: SettingsManager,
        val supervisor: BossTermSetupSupervisor?,
        val adminPassword: String,
    )

    internal data class TaskPlan(
        val state: SetupTaskState,
        val command: String,
        val verificationCommand: String? = null,
    )

    internal fun buildTaskPlan(
        selections: OnboardingSelections,
        installed: InstalledTools,
        targetOs: TargetOs = TargetOs.current(),
    ): List<TaskPlan> {
        val packageManager = resolvePackageManager(selections.packageManager, installed, targetOs)
        val effectiveInstalled = installed.withPackageManager(packageManager)
        val configuredShell = resolveConfiguredShell(selections.shell, targetOs)
        fun command(selection: OnboardingSelections) = buildInstallCommand(selection, effectiveInstalled, targetOs)
        val nothingElse = OnboardingSelections(
            packageManager = PackageManagerChoice.NONE,
            shell = ShellChoice.KEEP_CURRENT,
            shellCustomization = ShellCustomizationChoice.KEEP_EXISTING,
            installGit = false,
            installGitHubCLI = false,
            authenticateGitHub = false,
            aiAssistants = emptySet(),
        )
        return listOfNotNull(
            packageManagerTask(packageManager, installed, targetOs),
            TaskPlan(
                SetupTaskState("preferences", "Save terminal preferences", selections.shell.displayName),
                command(nothingElse.copy(shell = selections.shell)),
                shellVerification(selections.shell, targetOs),
            ),
            TaskPlan(
                SetupTaskState("prompt", "Configure ${selections.shellCustomization.displayName}", "Shell prompt"),
                withShellEnvironment(
                    command(nothingElse.copy(shellCustomization = selections.shellCustomization)) +
                        ensurePromptActivationCommand(selections.shellCustomization, configuredShell, targetOs),
                    configuredShell,
                    targetOs,
                ),
                promptVerification(selections.shellCustomization, configuredShell, targetOs),
            ),
            selections.installGit.takeIf { it }?.let {
                TaskPlan(
                    SetupTaskState("git", "Verify Git", if (installed.git) "Already installed" else "Install Git"),
                    command(nothingElse.copy(installGit = true)),
                    executableVerification("git", targetOs),
                )
            },
            selections.installGitHubCLI.takeIf { it }?.let {
                TaskPlan(
                    SetupTaskState(
                        "github-cli",
                        "Verify GitHub CLI",
                        if (installed.gh) "Already installed" else "Install GitHub CLI",
                    ),
                    command(nothingElse.copy(installGitHubCLI = true)),
                    executableVerification("gh", targetOs),
                )
            },
            *selections.aiAssistants.mapNotNull { id ->
                val assistant = AIAssistants.BUILTIN.firstOrNull { it.id == id } ?: return@mapNotNull null
                TaskPlan(
                    SetupTaskState(
                        "ai-$id",
                        "Verify ${assistant.displayName}",
                        if (installed.isAiInstalled(id)) "Already installed" else "Install ${assistant.displayName}",
                    ),
                    command(nothingElse.copy(aiAssistants = setOf(id))),
                    assistantVerification(
                        assistant.command,
                        assistant.resolvedDetectPaths(System.getProperty("user.home").orEmpty()),
                        targetOs,
                    ),
                )
            }.toTypedArray(),
        )
    }

    private fun resolveConfiguredShell(choice: ShellChoice, targetOs: TargetOs): ShellChoice {
        if (choice != ShellChoice.KEEP_CURRENT) return choice
        val current = System.getenv("SHELL").orEmpty().substringAfterLast('/')
        return ShellChoice.entries.firstOrNull { it.command.substringBefore('.') == current }
            ?: if (targetOs.isWindows) ShellChoice.POWERSHELL else ShellChoice.ZSH
    }

    private fun shellVerification(choice: ShellChoice, targetOs: TargetOs): String? {
        val executable = executableVerification(choice.command, targetOs) ?: return null
        if (targetOs.isWindows || choice == ShellChoice.KEEP_CURRENT) return executable
        val loginShell = if (targetOs.isMac) {
            "dscl . -read /Users/\"\$USER\" UserShell | awk '{print \$2}'"
        } else {
            "{ getent passwd \"\$USER\" 2>/dev/null || grep \"^\$USER:\" /etc/passwd; } | cut -d: -f7"
        }
        return "$executable\nLOGIN_SHELL=\$($loginShell)\n" +
            "test \"\$(basename \"\$LOGIN_SHELL\")\" = \"${choice.command}\"\n"
    }

    private fun withShellEnvironment(script: String, shell: ShellChoice, targetOs: TargetOs): String =
        if (targetOs.isWindows || shell.command.isBlank()) script else "export SHELL=\"\$(command -v ${shell.command})\"\n$script"

    private fun ensurePromptActivationCommand(
        choice: ShellCustomizationChoice,
        shell: ShellChoice,
        targetOs: TargetOs,
    ): String {
        if (targetOs.isWindows) return ""
        val file = when (shell) {
            ShellChoice.ZSH -> "\$HOME/.zshrc"
            ShellChoice.BASH -> "\$HOME/.bashrc"
            ShellChoice.FISH -> "\$HOME/.config/fish/config.fish"
            else -> return ""
        }
        val line = when (choice) {
            ShellCustomizationChoice.STARSHIP -> when (shell) {
                ShellChoice.FISH -> "starship init fish | source"
                else -> "eval \"\$(starship init ${shell.command})\""
            }
            ShellCustomizationChoice.OH_MY_ZSH -> "source \$HOME/.oh-my-zsh/oh-my-zsh.sh"
            ShellCustomizationChoice.PREZTO -> "source \$HOME/.zprezto/init.zsh"
            else -> return ""
        }
        return "\nmkdir -p \"\$(dirname \"$file\")\" && touch \"$file\" && " +
            "grep -Fq '$line' \"$file\" || echo '$line' >> \"$file\"\n"
    }

    private fun resolvePackageManager(
        choice: PackageManagerChoice,
        installed: InstalledTools,
        targetOs: TargetOs,
    ): PackageManagerChoice = when (choice) {
        PackageManagerChoice.AUTO -> when {
            targetOs.isMac -> PackageManagerChoice.HOMEBREW
            installed.winget -> PackageManagerChoice.WINGET
            installed.chocolatey -> PackageManagerChoice.CHOCOLATEY
            targetOs.isWindows -> PackageManagerChoice.WINGET
            else -> PackageManagerChoice.NONE
        }
        else -> choice
    }

    private fun InstalledTools.withPackageManager(choice: PackageManagerChoice): InstalledTools = when (choice) {
        PackageManagerChoice.HOMEBREW -> copy(homebrew = true, winget = false, chocolatey = false)
        PackageManagerChoice.WINGET -> copy(homebrew = false, winget = true, chocolatey = false)
        PackageManagerChoice.CHOCOLATEY -> copy(homebrew = false, winget = false, chocolatey = true)
        PackageManagerChoice.NONE -> copy(homebrew = false, winget = false, chocolatey = false)
        PackageManagerChoice.AUTO -> this
    }

    private fun packageManagerTask(
        choice: PackageManagerChoice,
        installed: InstalledTools,
        targetOs: TargetOs,
    ): TaskPlan? {
        if (choice == PackageManagerChoice.NONE || choice == PackageManagerChoice.AUTO) return null
        val alreadyInstalled = when (choice) {
            PackageManagerChoice.HOMEBREW -> installed.homebrew
            PackageManagerChoice.WINGET -> installed.winget
            PackageManagerChoice.CHOCOLATEY -> installed.chocolatey
            PackageManagerChoice.AUTO, PackageManagerChoice.NONE -> false
        }
        val executable = when (choice) {
            PackageManagerChoice.HOMEBREW -> "brew"
            PackageManagerChoice.WINGET -> "winget"
            PackageManagerChoice.CHOCOLATEY -> "choco"
            PackageManagerChoice.AUTO, PackageManagerChoice.NONE -> return null
        }
        val installCommand = when {
            alreadyInstalled -> executableVerification(executable, targetOs).orEmpty()
            choice == PackageManagerChoice.HOMEBREW && targetOs.isMac ->
                "#!/bin/bash\nif [ -n \"\$BOSSTERM_SUDO_PWD\" ]; then " +
                    "printf '%s\\n' \"\$BOSSTERM_SUDO_PWD\" | sudo -S -v; else sudo -v; fi && " +
                    "/bin/bash -c " +
                    "\"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"\n"
            choice == PackageManagerChoice.CHOCOLATEY && targetOs.isWindows ->
                "Set-ExecutionPolicy Bypass -Scope Process -Force; " +
                    "[System.Net.ServicePointManager]::SecurityProtocol = " +
                    "[System.Net.ServicePointManager]::SecurityProtocol -bor 3072; " +
                    "iex ((New-Object System.Net.WebClient)." +
                    "DownloadString('https://community.chocolatey.org/install.ps1'))"
            choice == PackageManagerChoice.WINGET && targetOs.isWindows -> wingetInstallCommand()
            else -> "Write-Error '${choice.displayName} must be installed by the user'; exit 1"
        }
        return TaskPlan(
            SetupTaskState(
                "package-manager",
                "Verify ${choice.displayName}",
                if (alreadyInstalled) "Already installed" else "Install ${choice.displayName}",
            ),
            installCommand,
            executableVerification(executable, targetOs),
        )
    }

    private fun wingetInstallCommand(): String =
        "\$progressPreference = 'silentlyContinue'; " +
            "\$release = Invoke-RestMethod -Uri 'https://api.github.com/repos/microsoft/winget-cli/releases/latest'; " +
            "\$url = \$release.assets | Where-Object { \$_.name -match '\\.msixbundle\$' } | " +
            "Select-Object -First 1 -ExpandProperty browser_download_url; " +
            "if (-not \$url) { exit 1 }; " +
            "Invoke-WebRequest -Uri \$url -OutFile \"\$env:TEMP\\winget.msixbundle\"; " +
            "Add-AppxPackage -Path \"\$env:TEMP\\winget.msixbundle\""

    private suspend fun runTask(plan: TaskPlan, sessionId: String): CommandResult {
        val installation = runCommand(plan.command, sessionId)
        if (installation.exitCode != 0 || plan.verificationCommand == null) return installation
        appendOutput("Verifying ${plan.state.title}")
        val verification = runCommand(plan.verificationCommand, sessionId)
        return if (verification.exitCode == 0) {
            installation
        } else {
            CommandResult(
                verification.exitCode,
                listOf(installation.output, "Post-install verification failed.", verification.output)
                    .filter { it.isNotBlank() }
                    .joinToString("\n"),
            )
        }
    }

    internal fun executableVerification(command: String, targetOs: TargetOs): String? {
        if (command.isBlank()) return null
        return if (targetOs.isWindows) {
            val smoke = when (command.lowercase()) {
                "cmd.exe", "cmd" -> "cmd.exe /c ver"
                "powershell.exe", "powershell" -> "powershell -NoProfile -Command '\$PSVersionTable.PSVersion'"
                else -> "& '$command' --version"
            }
            "if (-not (Get-Command '$command' -ErrorAction SilentlyContinue)) { exit 1 }; " +
                "$smoke; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }"
        } else {
            "#!/bin/bash\ncommand -v '$command' >/dev/null 2>&1 && '$command' --version >/dev/null 2>&1\n"
        }
    }

    private fun assistantVerification(command: String, paths: List<String>, targetOs: TargetOs): String? {
        if (command.isBlank()) return null
        return if (targetOs.isWindows) {
            val pathBranches = paths.joinToString(" ") { path ->
                val safePath = path.replace("'", "''")
                "elseif (Test-Path '$safePath') { & '$safePath' --version; " +
                    "if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE } }"
            }
            "if (Get-Command '$command' -ErrorAction SilentlyContinue) { " +
                "& '$command' --version; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE } " +
                "} $pathBranches else { exit 1 }"
        } else {
            val pathBranches = paths.joinToString(" ") { path ->
                val safePath = path.replace("'", "'\"'\"'")
                "elif test -x '$safePath'; then '$safePath' --version >/dev/null 2>&1;"
            }
            "#!/bin/bash\nif command -v '$command' >/dev/null 2>&1; then " +
                "'$command' --version >/dev/null 2>&1; $pathBranches else exit 1; fi\n"
        }
    }

    internal fun promptVerification(
        choice: ShellCustomizationChoice,
        shell: ShellChoice,
        targetOs: TargetOs,
    ): String? =
        when (choice) {
            ShellCustomizationChoice.STARSHIP -> promptExecutableVerification("starship", "starship init", shell, targetOs)
            ShellCustomizationChoice.OH_MY_POSH -> promptExecutableVerification("oh-my-posh", "oh-my-posh init", shell, targetOs)
            ShellCustomizationChoice.OH_MY_ZSH -> unixPromptVerification("\$HOME/.oh-my-zsh", "oh-my-zsh.sh")
            ShellCustomizationChoice.PREZTO -> unixPromptVerification("\$HOME/.zprezto", "zprezto")
            ShellCustomizationChoice.NONE, ShellCustomizationChoice.KEEP_EXISTING -> null
        }

    private fun promptExecutableVerification(
        command: String,
        marker: String,
        shell: ShellChoice,
        targetOs: TargetOs,
    ): String {
        val executable = requireNotNull(executableVerification(command, targetOs))
        val config = when (shell) {
            ShellChoice.ZSH -> "\$HOME/.zshrc"
            ShellChoice.BASH -> "\$HOME/.bashrc"
            ShellChoice.FISH -> "\$HOME/.config/fish/config.fish"
            ShellChoice.POWERSHELL, ShellChoice.CMD ->
                "\$env:USERPROFILE/Documents/PowerShell/Microsoft.PowerShell_profile.ps1"
            ShellChoice.KEEP_CURRENT -> "\$HOME/.zshrc"
        }
        return if (targetOs.isWindows) {
            "$executable; if (-not (Select-String -Path $config -Pattern '$marker' -Quiet)) { exit 1 }"
        } else {
            "#!/bin/bash\nset -e\n$executable\ngrep -q '$marker' \"$config\"\n"
        }
    }

    private fun unixPromptVerification(path: String, marker: String): String =
        "#!/bin/bash\ntest -d \"$path\" && grep -q '$marker' \"\$HOME/.zshrc\"\n"

    private data class CommandResult(val exitCode: Int, val output: String)

    private data class PendingTerminalCommand(
        val parser: SetupTerminalSentinelParser,
        val completion: CompletableDeferred<CommandResult>,
        val output: StringBuilder = StringBuilder(),
    )

    private suspend fun runCommand(script: String, sessionId: String): CommandResult {
        if (withTimeoutOrNull(TERMINAL_READY_TIMEOUT_MS) { terminalReady.await() } == null) {
            return CommandResult(-1, "Interactive terminal did not become ready")
        }
        if (_state.value.sessionId != sessionId) return CommandResult(-1, "Setup session changed")
        val windows = TargetOs.current().isWindows
        val file = File.createTempFile("bossterm-setup-", if (windows) ".ps1" else ".sh").apply {
            writeText(script)
            if (!windows) setExecutable(true)
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val completion = CompletableDeferred<CommandResult>()
        val pending = PendingTerminalCommand(SetupTerminalSentinelParser(token), completion)
        try {
            synchronized(terminalLock) {
                check(pendingTerminalCommand == null) { "A setup command is already active" }
                pendingTerminalCommand = pending
            }
            terminalCommandSubmittedForTest = false
            val quotedPath = file.absolutePath.replace("'", if (windows) "''" else "'\"'\"'")
            val submitted = if (windows) {
                "\$global:LASTEXITCODE = 0; & '$quotedPath'; " +
                    "\$__bossExit = if (-not \$?) { 1 } elseif (\$null -eq \$LASTEXITCODE) { 0 } else { \$LASTEXITCODE }; " +
                    "Write-Output \"__BOSS_SETUP_${token}__:\$__bossExit\""
            } else {
                "bash '$quotedPath'; __boss_exit=\$?; " +
                    "printf '\\n__BOSS_SETUP_${token}__:%s\\n' \"\$__boss_exit\""
            }
            terminalState.write("$submitted\r")
            terminalCommandSubmittedForTest = true
            return withTimeoutOrNull(TASK_TIMEOUT_MINUTES * 60_000L) { completion.await() }
                ?: run {
                    synchronized(terminalLock) {
                        if (pendingTerminalCommand === pending) pendingTerminalCommand = null
                    }
                    terminalState.sendCtrlC()
                    CommandResult(TIMEOUT_EXIT_CODE, "Task timed out after $TASK_TIMEOUT_MINUTES minutes")
                }
        } catch (cancelled: CancellationException) {
            synchronized(terminalLock) {
                if (pendingTerminalCommand === pending) pendingTerminalCommand = null
            }
            terminalState.sendCtrlC()
            throw cancelled
        } catch (error: Exception) {
            return CommandResult(-1, error.message ?: error::class.simpleName.orEmpty())
        } finally {
            synchronized(terminalLock) {
                if (pendingTerminalCommand === pending) pendingTerminalCommand = null
            }
            file.delete()
        }
    }

    internal class SetupTerminalSentinelParser(private val token: String) {
        private val marker = "__BOSS_SETUP_${token}__:"
        private val buffered = StringBuilder()

        @Synchronized
        fun accept(chunk: String): Int? {
            buffered.append(ANSI_ESCAPE.replace(chunk, ""))
            val lines = buffered.toString().split('\r', '\n')
            buffered.clear()
            if (!chunk.endsWith('\r') && !chunk.endsWith('\n')) buffered.append(lines.last())
            return lines.dropLast(if (buffered.isEmpty()) 0 else 1)
                .firstNotNullOfOrNull { line ->
                    line.trim().takeIf { it.startsWith(marker) }
                        ?.removePrefix(marker)?.trim()?.takeIf { value -> value.all(Char::isDigit) }
                        ?.toIntOrNull()
                }
        }
    }

    private fun appendOutput(line: String) {
        val printable = line.trim().take(MAX_VISIBLE_LINE_LENGTH)
        if (printable.isEmpty()) return
        _state.update { current ->
            current.copy(outputLines = (current.outputLines + printable).takeLast(MAX_VISIBLE_LINES))
        }
    }

    private inline fun updateSession(
        sessionId: String,
        transform: (BossTermSetupState) -> BossTermSetupState,
    ) {
        _state.update { current -> if (current.sessionId == sessionId) transform(current) else current }
    }

    private fun packageRefreshCommand(os: TargetOs): String = when (os) {
        TargetOs.MAC -> "#!/bin/bash\nset -e\nexport PATH=/opt/homebrew/bin:/usr/local/bin:\$PATH\nbrew update\n"
        TargetOs.LINUX ->
            "#!/bin/bash\nset -e\n" +
                "if command -v apt >/dev/null; then sudo -n apt update; " +
                "elif command -v dnf >/dev/null; then sudo -n dnf makecache; " +
                "elif command -v pacman >/dev/null; then sudo -n pacman -Sy; fi\n"
        TargetOs.WINDOWS -> "winget source update"
    }

    private const val MAX_FAILURE_OUTPUT = 4_000
    private const val MAX_REPAIR_ATTEMPTS = 2
    private const val TERMINAL_READY_TIMEOUT_MS = 30_000L
    private const val TERMINAL_READY_POLL_MS = 25L
    private const val TERMINAL_READY_POLL_ATTEMPTS = 1_200
    private const val TIMEOUT_EXIT_CODE = 124
    private const val HANDOFF_FAILED_EXIT_CODE = 125
    private const val HANDOFF_BOUNDARY_TIMEOUT_MS = 5_000L
    private val USER_CANCEL_EXIT_CODES = setOf(TIMEOUT_EXIT_CODE, HANDOFF_FAILED_EXIT_CODE, 130, 143)
    private const val MAX_VISIBLE_LINES = 80
    private const val MAX_VISIBLE_LINE_LENGTH = 500
    private const val TASK_TIMEOUT_MINUTES = 10L
    private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
}
