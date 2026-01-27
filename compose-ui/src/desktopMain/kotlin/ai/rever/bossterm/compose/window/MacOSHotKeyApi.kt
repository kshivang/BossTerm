package ai.rever.bossterm.compose.window

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Callback interface for Carbon Event Handler.
 * This is called when a hotkey event is received.
 */
interface EventHandlerUPP : Callback {
    /**
     * @param nextHandler Reference to the next handler in the chain
     * @param event The event being handled
     * @param userData User data passed when installing the handler
     * @return OSStatus (0 = noErr)
     */
    fun invoke(nextHandler: Pointer?, event: Pointer?, userData: Pointer?): Int
}

/**
 * JNA interface for macOS Carbon global hotkey APIs.
 * Uses Carbon's RegisterEventHotKey for system-wide hotkeys.
 */
interface MacOSHotKeyApi : Library {
    companion object {
        val INSTANCE: MacOSHotKeyApi? = try {
            Native.load("Carbon", MacOSHotKeyApi::class.java)
        } catch (e: Throwable) {
            null
        }

        // OSStatus codes
        const val noErr = 0
        const val eventNotHandledErr = -9874

        // Event class and kind for hotkey events
        const val kEventClassKeyboard = 0x6B657962  // 'keyb'
        const val kEventHotKeyPressed = 5

        // Event parameter names (for GetEventParameter)
        const val kEventParamDirectObject = 0x2D2D2D2D  // '----'

        // Type codes
        const val typeEventHotKeyID = 0x686B6964  // 'hkid'

        // Modifier key masks (Carbon)
        const val cmdKey = 0x0100      // Command
        const val shiftKey = 0x0200   // Shift
        const val optionKey = 0x0800  // Option/Alt
        const val controlKey = 0x1000 // Control

        // Virtual key codes (macOS)
        const val kVK_ANSI_Grave = 0x32
        const val kVK_Space = 0x31
        const val kVK_Escape = 0x35
        const val kVK_Tab = 0x30
        const val kVK_Return = 0x24
        const val kVK_ANSI_A = 0x00
        const val kVK_ANSI_B = 0x0B
        const val kVK_ANSI_C = 0x08
        const val kVK_ANSI_D = 0x02
        const val kVK_ANSI_E = 0x0E
        const val kVK_ANSI_F = 0x03
        const val kVK_ANSI_G = 0x05
        const val kVK_ANSI_H = 0x04
        const val kVK_ANSI_I = 0x22
        const val kVK_ANSI_J = 0x26
        const val kVK_ANSI_K = 0x28
        const val kVK_ANSI_L = 0x25
        const val kVK_ANSI_M = 0x2E
        const val kVK_ANSI_N = 0x2D
        const val kVK_ANSI_O = 0x1F
        const val kVK_ANSI_P = 0x23
        const val kVK_ANSI_Q = 0x0C
        const val kVK_ANSI_R = 0x0F
        const val kVK_ANSI_S = 0x01
        const val kVK_ANSI_T = 0x11
        const val kVK_ANSI_U = 0x20
        const val kVK_ANSI_V = 0x09
        const val kVK_ANSI_W = 0x0D
        const val kVK_ANSI_X = 0x07
        const val kVK_ANSI_Y = 0x10
        const val kVK_ANSI_Z = 0x06
        const val kVK_ANSI_0 = 0x1D
        const val kVK_ANSI_1 = 0x12
        const val kVK_ANSI_2 = 0x13
        const val kVK_ANSI_3 = 0x14
        const val kVK_ANSI_4 = 0x15
        const val kVK_ANSI_5 = 0x17
        const val kVK_ANSI_6 = 0x16
        const val kVK_ANSI_7 = 0x1A
        const val kVK_ANSI_8 = 0x1C
        const val kVK_ANSI_9 = 0x19
        const val kVK_F1 = 0x7A
        const val kVK_F2 = 0x78
        const val kVK_F3 = 0x63
        const val kVK_F4 = 0x76
        const val kVK_F5 = 0x60
        const val kVK_F6 = 0x61
        const val kVK_F7 = 0x62
        const val kVK_F8 = 0x64
        const val kVK_F9 = 0x65
        const val kVK_F10 = 0x6D
        const val kVK_F11 = 0x67
        const val kVK_F12 = 0x6F

        /**
         * Convert HotKeyConfig key to macOS virtual key code.
         */
        fun keyToVirtualKeyCode(key: String): Int {
            return when (key.uppercase()) {
                "GRAVE", "`" -> kVK_ANSI_Grave
                "SPACE" -> kVK_Space
                "ESCAPE", "ESC" -> kVK_Escape
                "TAB" -> kVK_Tab
                "ENTER", "RETURN" -> kVK_Return
                "A" -> kVK_ANSI_A
                "B" -> kVK_ANSI_B
                "C" -> kVK_ANSI_C
                "D" -> kVK_ANSI_D
                "E" -> kVK_ANSI_E
                "F" -> kVK_ANSI_F
                "G" -> kVK_ANSI_G
                "H" -> kVK_ANSI_H
                "I" -> kVK_ANSI_I
                "J" -> kVK_ANSI_J
                "K" -> kVK_ANSI_K
                "L" -> kVK_ANSI_L
                "M" -> kVK_ANSI_M
                "N" -> kVK_ANSI_N
                "O" -> kVK_ANSI_O
                "P" -> kVK_ANSI_P
                "Q" -> kVK_ANSI_Q
                "R" -> kVK_ANSI_R
                "S" -> kVK_ANSI_S
                "T" -> kVK_ANSI_T
                "U" -> kVK_ANSI_U
                "V" -> kVK_ANSI_V
                "W" -> kVK_ANSI_W
                "X" -> kVK_ANSI_X
                "Y" -> kVK_ANSI_Y
                "Z" -> kVK_ANSI_Z
                "0" -> kVK_ANSI_0
                "1" -> kVK_ANSI_1
                "2" -> kVK_ANSI_2
                "3" -> kVK_ANSI_3
                "4" -> kVK_ANSI_4
                "5" -> kVK_ANSI_5
                "6" -> kVK_ANSI_6
                "7" -> kVK_ANSI_7
                "8" -> kVK_ANSI_8
                "9" -> kVK_ANSI_9
                "F1" -> kVK_F1
                "F2" -> kVK_F2
                "F3" -> kVK_F3
                "F4" -> kVK_F4
                "F5" -> kVK_F5
                "F6" -> kVK_F6
                "F7" -> kVK_F7
                "F8" -> kVK_F8
                "F9" -> kVK_F9
                "F10" -> kVK_F10
                "F11" -> kVK_F11
                "F12" -> kVK_F12
                else -> kVK_ANSI_Grave
            }
        }

        /**
         * Convert HotKeyConfig modifiers to Carbon modifier mask.
         */
        fun configToModifiers(config: HotKeyConfig): Int {
            var mods = 0
            if (config.ctrl) mods = mods or controlKey
            if (config.alt) mods = mods or optionKey
            if (config.shift) mods = mods or shiftKey
            if (config.win) mods = mods or cmdKey  // Map Win to Cmd on macOS
            return mods
        }
    }

    /**
     * Get the application event target.
     * Note: For truly global hotkeys, use GetEventDispatcherTarget() instead.
     */
    fun GetApplicationEventTarget(): Pointer?

    /**
     * Get the event dispatcher target.
     * This is the key to making hotkeys work globally (even when app is not focused).
     * iTerm2 uses this for their global hotkey implementation.
     */
    fun GetEventDispatcherTarget(): Pointer?

    /**
     * Install an event handler.
     * @param target The event target (from GetApplicationEventTarget)
     * @param handler The callback function that handles events
     * @param numTypes Number of event types in typeList
     * @param typeList Array of EventTypeSpec structures
     * @param userData User data passed to the handler
     * @param outRef Receives the handler reference
     * @return OSStatus (0 = noErr)
     */
    fun InstallEventHandler(
        target: Pointer?,
        handler: EventHandlerUPP?,
        numTypes: Int,
        typeList: Pointer?,
        userData: Pointer?,
        outRef: PointerByReference?
    ): Int

    /**
     * Remove an event handler.
     */
    fun RemoveEventHandler(handlerRef: Pointer?): Int

    /**
     * Register a global hotkey.
     */
    fun RegisterEventHotKey(
        keyCode: Int,
        modifiers: Int,
        hotKeyID: EventHotKeyID.ByReference?,
        target: Pointer?,
        options: Int,
        outRef: PointerByReference?
    ): Int

    /**
     * Unregister a global hotkey.
     */
    fun UnregisterEventHotKey(hotKeyRef: Pointer?): Int

    /**
     * Run the application event loop (blocks).
     */
    fun RunApplicationEventLoop()

    /**
     * Quit the application event loop.
     */
    fun QuitApplicationEventLoop()

    /**
     * Get event parameter.
     * Used to extract the hotkey ID from a hotkey event.
     */
    fun GetEventParameter(
        event: Pointer?,
        name: Int,
        type: Int,
        outType: IntByReference?,
        bufferSize: Int,
        outSize: IntByReference?,
        outData: Pointer?
    ): Int
}

/**
 * JNA interface for CoreFoundation run loop functions.
 * Used for processing events without blocking.
 */
interface CoreFoundationApi : Library {
    companion object {
        val INSTANCE: CoreFoundationApi? = try {
            Native.load("CoreFoundation", CoreFoundationApi::class.java)
        } catch (e: Throwable) {
            null
        }

        // Run loop result codes
        const val kCFRunLoopRunFinished = 1
        const val kCFRunLoopRunStopped = 2
        const val kCFRunLoopRunTimedOut = 3
        const val kCFRunLoopRunHandledSource = 4
    }

    /**
     * Get the current thread's run loop.
     */
    fun CFRunLoopGetCurrent(): Pointer?

    /**
     * Get the main run loop.
     */
    fun CFRunLoopGetMain(): Pointer?

    /**
     * Run the run loop for a specified duration.
     * @param mode The run loop mode (use kCFRunLoopDefaultMode)
     * @param seconds How long to run (0 = don't wait, just check)
     * @param returnAfterSourceHandled Return after handling one source
     * @return Result code
     */
    fun CFRunLoopRunInMode(mode: Pointer?, seconds: Double, returnAfterSourceHandled: Boolean): Int

    /**
     * Stop the run loop.
     */
    fun CFRunLoopStop(runLoop: Pointer?)

    /**
     * Create a CFString from a C string.
     */
    fun CFStringCreateWithCString(allocator: Pointer?, cStr: String?, encoding: Int): Pointer?

    /**
     * Release a CoreFoundation object.
     */
    fun CFRelease(cf: Pointer?)
}

/**
 * EventHotKeyID structure for Carbon API.
 */
@Structure.FieldOrder("signature", "id")
open class EventHotKeyID : Structure() {
    @JvmField var signature: Int = 0
    @JvmField var id: Int = 0

    class ByReference : EventHotKeyID(), Structure.ByReference
    class ByValue : EventHotKeyID(), Structure.ByValue
}

/**
 * EventTypeSpec structure for Carbon API.
 */
@Structure.FieldOrder("eventClass", "eventKind")
open class EventTypeSpec : Structure() {
    @JvmField var eventClass: Int = 0
    @JvmField var eventKind: Int = 0

    class ByReference : EventTypeSpec(), Structure.ByReference
}
