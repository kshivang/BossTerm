package ai.rever.bossterm.terminal

import org.slf4j.Logger
import org.slf4j.LoggerFactory


enum class TerminalMode {
    Null,
    CursorKey {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            terminal?.setApplicationArrowKeys(enabled)
        }
    },
    ANSI,
    WideColumn {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            // Skip resizing as it would require to resize parent container.
            // Other terminal emulators (iTerm2, Terminal.app, GNOME Terminal) ignore it too.
            terminal?.clearScreen()
            terminal?.resetScrollRegions()
        }
    },
    CursorVisible {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            terminal?.setCursorVisible(enabled)
        }
    },
    AlternateBuffer {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            terminal?.useAlternateBuffer(enabled)
        }
    },
    SmoothScroll,
    ReverseVideo,
    OriginMode {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
        }
    },
    AutoWrap {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            //we do nothing just switching the mode
        }
    },
    AutoRepeatKeys,
    Interlace,
    Keypad {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            terminal?.setApplicationKeypad(enabled)
        }
    },
    StoreCursor {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            if (enabled) {
                terminal?.saveCursor()
            } else {
                terminal?.restoreCursor()
            }
        }
    },
    AllowWideColumn,
    ReverseWrapAround,
    AutoNewLine {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            terminal?.setAutoNewLine(enabled)
        }
    },
    KeyboardAction,
    InsertMode,
    SendReceive,
    EightBitInput {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            //Interpret "meta" key, sets eighth bit. (enables the eightBitInput resource).
            // http://www.leonerd.org.uk/hacks/hints/xterm-8bit.html
            // https://github.com/microsoft/terminal/issues/6722
            // Not implemented for now. The method is overridden to suppress warnings.
        }
    },

    AltSendsEscape //See section Alt and Meta Keys in http://invisible-island.net/xterm/ctlseqs/ctlseqs.html
    {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            terminal?.setAltSendsEscape(enabled)
        }
    },

    // https://cirw.in/blog/bracketed-paste
    // http://www.xfree86.org/current/ctlseqs.html#Bracketed%20Paste%20Mode
    BracketedPasteMode {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            terminal?.setBracketedPasteMode(enabled)
        }
    },

    // DEC Private Mode 2026 - Synchronized Output / Update Mode
    // Spec: https://github.com/contour-terminal/vt-extensions/blob/master/synchronized-output.md
    // Also: https://gist.github.com/christianparpart/d8a62cc1ab659194337d73e399004036
    // When enabled, terminal buffers all output and suppresses rendering.
    // When disabled, all buffered output is rendered at once (reduces flicker).
    SynchronizedUpdate {
        override fun setEnabled(terminal: Terminal?, enabled: Boolean) {
            terminal?.setSynchronizedUpdate(enabled)
        }
    };

    open fun setEnabled(terminal: Terminal?, enabled: Boolean) {
        LOG.warn("Mode " + name + " is not implemented, setting to " + enabled)
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(TerminalMode::class.java)
    }
}