package ai.rever.bossterm.compose.tabs

/** Launch policy supplied by a session owner; terminal display and parsing stay in the controller. */
data class TerminalLaunchRequest(
    val workingDir: String?,
    val command: String?,
    val arguments: List<String>,
    val onProcessExit: (() -> Unit)?,
    val initialCommand: String?,
    val onInitialCommandComplete: ((Boolean, Int) -> Unit)?,
    val tabId: String?,
    val activate: Boolean,
)
