package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.RequestOrigin
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import ai.rever.bossterm.terminal.model.TerminalSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [TerminalDisplay] for the headless daemon — the counterpart to [ai.rever.bossterm.compose.ComposeTerminalDisplay].
 *
 * It holds the same display-relevant state (cursor, titles, grid size, mouse mode, bracketed
 * paste) but as plain fields / `StateFlow`s instead of Compose `State`, and its redraw is a no-op:
 * there is no UI to repaint. Attached GUIs are driven by the raw-output tap on the data stream
 * (the byte stream), not by redraw signals — exactly as remote viewers already are today
 * ([ai.rever.bossterm.compose.share.MirrorShare]). The `StateFlow`s let the daemon relay title /
 * size / cursor to attached clients without touching Compose or `Dispatchers.Main`.
 */
class HeadlessTerminalDisplay(initialCols: Int = 80, initialRows: Int = 24) : TerminalDisplay {

    private val _windowTitle = MutableStateFlow("")
    val windowTitleFlow: StateFlow<String> = _windowTitle.asStateFlow()

    private val _iconTitle = MutableStateFlow("")
    val iconTitleFlow: StateFlow<String> = _iconTitle.asStateFlow()

    private val _termSize = MutableStateFlow(TermSize(initialCols, initialRows))
    val termSizeFlow: StateFlow<TermSize> = _termSize.asStateFlow()

    private val _mouseMode = MutableStateFlow(MouseMode.MOUSE_REPORTING_NONE)
    val mouseModeFlow: StateFlow<MouseMode> = _mouseMode.asStateFlow()

    @Volatile var cursorX: Int = 0; private set
    @Volatile var cursorY: Int = 0; private set
    @Volatile var cursorVisible: Boolean = true; private set
    @Volatile var cursorShape: CursorShape? = null; private set
    @Volatile var bracketedPasteMode: Boolean = false; private set
    @Volatile var usingAlternateBuffer: Boolean = false; private set

    override fun setCursor(x: Int, y: Int) { cursorX = x; cursorY = y }
    override fun setCursorShape(cursorShape: CursorShape?) { this.cursorShape = cursorShape }
    override fun setCursorVisible(isCursorVisible: Boolean) { cursorVisible = isCursorVisible }

    override fun beep() { /* headless: no audible bell */ }

    override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) { /* no UI to scroll */ }

    override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {
        usingAlternateBuffer = useAlternateScreenBuffer
    }

    override var windowTitle: String?
        get() = _windowTitle.value
        set(value) { _windowTitle.value = value ?: "" }

    override var iconTitle: String?
        get() = _iconTitle.value
        set(value) { _iconTitle.value = value ?: "" }

    override val selection: TerminalSelection? get() = null

    override fun terminalMouseModeSet(mouseMode: MouseMode) { _mouseMode.value = mouseMode }
    override fun setMouseFormat(mouseFormat: MouseFormat) { /* daemon does not need the format */ }
    override fun ambiguousCharsAreDoubleWidth(): Boolean = false

    override fun setBracketedPasteMode(bracketedPasteModeEnabled: Boolean) {
        bracketedPasteMode = bracketedPasteModeEnabled
    }

    override fun onResize(newTermSize: TermSize, origin: RequestOrigin) {
        _termSize.value = newTermSize
    }
}
