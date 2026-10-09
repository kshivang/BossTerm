package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.session.TerminalSessionStack
import ai.rever.bossterm.compose.settings.TerminalSettings
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** The daemon owns a shared terminal engine through a headless display adapter. */
class TerminalSessionCore(
    val id: String = UUID.randomUUID().toString(),
    settings: TerminalSettings,
    workingDir: String?,
    command: String? = null,
    arguments: List<String> = emptyList(),
    initialCols: Int = 80,
    initialRows: Int = 24,
    platformServices: PlatformServices = getPlatformServices(),
    colorSettingsProvider: () -> TerminalSettings = { settings },
    initialCommand: String? = null,
    environmentOverrides: Map<String, String> = emptyMap(),
) {
    sealed class State {
        object Initializing : State()
        object Connected : State()
        data class Error(val message: String) : State()
        object Exited : State()
    }
    private val _state = MutableStateFlow<State>(State.Initializing)
    val state = _state.asStateFlow()
    val display = HeadlessTerminalDisplay(
        initialCols.coerceIn(1, 2000), initialRows.coerceIn(1, 2000),
        ai.rever.bossterm.core.Color(settings.defaultForegroundColor.toArgb()),
        ai.rever.bossterm.core.Color(settings.defaultBackgroundColor.toArgb()),
        colorSettingsProvider = colorSettingsProvider,
    )
    val engine = TerminalSessionEngine(
        id = id, settings = settings, workingDir = workingDir,
        command = command, arguments = arguments, platformServices = platformServices,
        initialCommand = initialCommand,
        environmentOverrides = environmentOverrides,
        stack = TerminalSessionStack.create(settings, display, initialCols = initialCols, initialRows = initialRows),
        onStateChanged = { value ->
            _state.value = when (value) {
                TerminalSessionEngine.State.Initializing -> State.Initializing
                TerminalSessionEngine.State.Connected -> State.Connected
                is TerminalSessionEngine.State.Error -> State.Error(value.message)
                TerminalSessionEngine.State.Exited -> State.Exited
            }
        },
    )
    val textBuffer get() = engine.textBuffer
    val terminal get() = engine.terminal
    val dataStream get() = engine.dataStream
    val emulator get() = engine.emulator
    val workingDirectory get() = engine.workingDirectory
    val windowTitle get() = engine.windowTitle
    val iconTitle get() = engine.iconTitle
    val processHandle get() = engine.processHandle
    var onExit: (() -> Unit)?
        get() = engine.onExit
        set(value) { engine.onExit = value }

    fun start() = engine.start()
    fun writeInput(text: String) = engine.writeInput(text)
    fun writeBytes(bytes: ByteArray) = engine.writeBytes(bytes)
    fun resize(cols: Int, rows: Int) = engine.resize(cols, rows)
    fun isAlive() = engine.isAlive()
    fun close(): Thread? = engine.close()
    fun addRawOutputListener(listener: (String) -> Unit) = engine.addRawOutputListener(listener)
    fun removeRawOutputListener(listener: (String) -> Unit) = engine.removeRawOutputListener(listener)
    fun attachOutputListener(listener: (String) -> Unit, snapshot: () -> Unit) = engine.attachOutputListener(listener, snapshot)
    fun addResizeListener(listener: (Int, Int) -> Unit) = engine.addResizeListener(listener)
    fun removeResizeListener(listener: (Int, Int) -> Unit) = engine.removeResizeListener(listener)
}
