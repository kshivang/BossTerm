package ai.rever.bossterm.terminal.model

import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.emulator.mouse.MouseFormat
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode

/**
 * A [TerminalDisplay] that records nothing, for tests that only care about the buffer.
 *
 * Shared because this had been copied privately into four test classes, which means adding a member
 * to [TerminalDisplay] costs four identical edits. Tests needing to observe display calls should
 * still keep their own local stub - this one deliberately has no state to assert on.
 */
internal open class NoopTerminalDisplay : TerminalDisplay {
    override var windowTitle: String? = null
    override var iconTitle: String? = null
    override val selection: TerminalSelection? = null

    override fun setCursor(x: Int, y: Int) = Unit
    override fun setCursorShape(cursorShape: CursorShape?) = Unit
    override fun beep() = Unit
    override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) = Unit
    override fun setCursorVisible(isCursorVisible: Boolean) = Unit
    override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) = Unit
    override fun terminalMouseModeSet(mouseMode: MouseMode) = Unit
    override fun setMouseFormat(mouseFormat: MouseFormat) = Unit
    override fun ambiguousCharsAreDoubleWidth(): Boolean = false
}
