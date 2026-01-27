package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * Registration status for the global hotkey.
 */
enum class HotKeyRegistrationStatus {
    /** Not started or disabled */
    INACTIVE,
    /** Hotkeys registered successfully */
    REGISTERED,
    /** Failed to register (likely hotkey conflict) */
    FAILED,
    /** Native API not available on this platform */
    UNAVAILABLE
}

/**
 * Singleton manager for window-specific global hotkey registration.
 * Supports Windows, macOS, and Linux.
 *
 * Registers hotkeys for windows 1-9 (e.g., Ctrl+Alt+1, Ctrl+Alt+2, etc.)
 */
object GlobalHotKeyManager {
    private const val HOTKEY_SIGNATURE = 0x424F5353  // 'BOSS'

    private var handlerThread: Thread? = null
    private var isRunning = false
    private var baseConfig: HotKeyConfig? = null
    private var onWindowHotKeyPressed: ((Int) -> Unit)? = null

    // Platform-specific state
    private var winThreadId: Int = 0
    private var macHotKeyRefs: MutableMap<Int, Pointer> = mutableMapOf()
    private var macEventHandlerRef: Pointer? = null
    private var macEventHandler: EventHandlerUPP? = null  // Keep reference to prevent GC
    private var macRunLoopMode: Pointer? = null
    private var linuxDisplay: Pointer? = null
    private var linuxKeycodes: MutableMap<Int, Int> = mutableMapOf()

    // Track which window numbers have registered hotkeys
    private val registeredWindows = mutableSetOf<Int>()

    private val _registrationStatus = MutableStateFlow(HotKeyRegistrationStatus.INACTIVE)
    val registrationStatus: StateFlow<HotKeyRegistrationStatus> = _registrationStatus.asStateFlow()

    /**
     * Start the global hotkey manager with the given base configuration.
     * Will register hotkeys for all existing windows and listen for new ones.
     *
     * @param config Base hotkey configuration (modifiers only, key is ignored - numbers 1-9 are used)
     * @param onWindowHotKeyPressed Callback with window number (1-9) when hotkey is pressed
     */
    @Synchronized
    fun start(config: HotKeyConfig, onWindowHotKeyPressed: (Int) -> Unit) {
        if (!config.enabled || !(config.ctrl || config.alt || config.shift || config.win)) {
            println("GlobalHotKeyManager: Invalid or disabled configuration")
            _registrationStatus.value = HotKeyRegistrationStatus.INACTIVE
            return
        }

        // Stop existing manager if running
        if (isRunning) {
            stop()
        }

        this.baseConfig = config
        this.onWindowHotKeyPressed = onWindowHotKeyPressed

        // Start platform-specific handler
        isRunning = true
        handlerThread = Thread({
            when {
                ShellCustomizationUtils.isWindows() -> runWindowsHandler(config)
                ShellCustomizationUtils.isMacOS() -> runMacOSHandler(config)
                ShellCustomizationUtils.isLinux() -> runLinuxHandler(config)
                else -> {
                    println("GlobalHotKeyManager: Unsupported platform")
                    _registrationStatus.value = HotKeyRegistrationStatus.UNAVAILABLE
                }
            }
        }, "GlobalHotKeyManager-Handler").apply {
            isDaemon = true
            start()
        }

        println("GlobalHotKeyManager: Started with base modifiers")
    }

    /**
     * Register hotkey for a specific window number.
     * Called when a new window is created.
     */
    @Synchronized
    fun registerWindow(windowNumber: Int) {
        if (windowNumber < 1 || windowNumber > 9) return
        if (!isRunning) return
        if (windowNumber in registeredWindows) return

        val config = baseConfig ?: return

        when {
            ShellCustomizationUtils.isWindows() -> registerWindowsHotKey(windowNumber, config)
            ShellCustomizationUtils.isMacOS() -> registerMacOSHotKey(windowNumber, config)
            ShellCustomizationUtils.isLinux() -> registerLinuxHotKey(windowNumber, config)
        }

        registeredWindows.add(windowNumber)
        println("GlobalHotKeyManager: Registered hotkey for window $windowNumber")
    }

    /**
     * Unregister hotkey for a specific window number.
     * Called when a window is closed.
     */
    @Synchronized
    fun unregisterWindow(windowNumber: Int) {
        if (windowNumber < 1 || windowNumber > 9) return
        if (windowNumber !in registeredWindows) return

        when {
            ShellCustomizationUtils.isWindows() -> unregisterWindowsHotKey(windowNumber)
            ShellCustomizationUtils.isMacOS() -> unregisterMacOSHotKey(windowNumber)
            ShellCustomizationUtils.isLinux() -> unregisterLinuxHotKey(windowNumber)
        }

        registeredWindows.remove(windowNumber)
        println("GlobalHotKeyManager: Unregistered hotkey for window $windowNumber")
    }

    /**
     * Stop the global hotkey manager.
     */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false

        when {
            ShellCustomizationUtils.isWindows() -> stopWindows()
            ShellCustomizationUtils.isMacOS() -> stopMacOS()
            ShellCustomizationUtils.isLinux() -> stopLinux()
        }

        handlerThread?.let { thread ->
            try {
                thread.join(1000)
                if (thread.isAlive) {
                    thread.interrupt()
                }
            } catch (e: InterruptedException) {
                // Ignore
            }
        }

        handlerThread = null
        baseConfig = null
        onWindowHotKeyPressed = null
        registeredWindows.clear()
        _registrationStatus.value = HotKeyRegistrationStatus.INACTIVE

        println("GlobalHotKeyManager: Stopped")
    }

    /**
     * Check if the global hotkey feature is available on this platform.
     */
    fun isAvailable(): Boolean {
        return when {
            ShellCustomizationUtils.isWindows() -> Win32HotKeyApi.INSTANCE != null
            ShellCustomizationUtils.isMacOS() -> MacOSHotKeyApi.INSTANCE != null
            ShellCustomizationUtils.isLinux() -> LinuxHotKeyApi.INSTANCE != null
            else -> false
        }
    }

    // ===== Windows Implementation =====

    private fun runWindowsHandler(config: HotKeyConfig) {
        val api = Win32HotKeyApi.INSTANCE
        if (api == null) {
            println("GlobalHotKeyManager: Win32 API not available")
            _registrationStatus.value = HotKeyRegistrationStatus.UNAVAILABLE
            return
        }

        winThreadId = api.GetCurrentThreadId()

        // Register hotkeys for windows 1-9
        val modifiers = config.toWin32Modifiers()
        var anyRegistered = false

        for (windowNum in 1..9) {
            val vk = KeyEvent.VK_0 + windowNum  // VK_1 through VK_9
            val registered = try {
                api.RegisterHotKey(null, windowNum, modifiers, vk)
            } catch (e: Exception) {
                false
            }
            if (registered) {
                registeredWindows.add(windowNum)
                anyRegistered = true
            }
        }

        if (!anyRegistered) {
            println("GlobalHotKeyManager: Failed to register any Windows hotkeys")
            _registrationStatus.value = HotKeyRegistrationStatus.FAILED
            return
        }

        println("GlobalHotKeyManager: Registered Windows hotkeys for windows 1-9")
        _registrationStatus.value = HotKeyRegistrationStatus.REGISTERED

        val msg = MSG()
        try {
            while (isRunning) {
                val result = api.GetMessage(msg, null, 0, 0)
                if (result == 0 || result == -1) break

                if (msg.message == Win32HotKeyApi.WM_HOTKEY) {
                    val windowNum = msg.wParam.toInt()
                    if (windowNum in 1..9) {
                        invokeCallback(windowNum)
                    }
                }
            }
        } catch (e: Exception) {
            println("GlobalHotKeyManager: Windows message pump error: ${e.message}")
        } finally {
            // Unregister all hotkeys
            for (windowNum in 1..9) {
                try {
                    api.UnregisterHotKey(null, windowNum)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            registeredWindows.clear()
            _registrationStatus.value = HotKeyRegistrationStatus.INACTIVE
        }
    }

    private fun registerWindowsHotKey(windowNumber: Int, config: HotKeyConfig) {
        // Already registered in runWindowsHandler for all 1-9
    }

    private fun unregisterWindowsHotKey(windowNumber: Int) {
        // We keep all hotkeys registered, just ignore callbacks for closed windows
    }

    private fun stopWindows() {
        val api = Win32HotKeyApi.INSTANCE ?: return
        if (winThreadId != 0) {
            try {
                api.PostThreadMessage(winThreadId, Win32HotKeyApi.WM_QUIT, WPARAM(0), LPARAM(0))
            } catch (e: Exception) {
                // Ignore
            }
        }
        winThreadId = 0
    }

    // ===== macOS Implementation =====

    private fun runMacOSHandler(config: HotKeyConfig) {
        val carbonApi = MacOSHotKeyApi.INSTANCE
        val cfApi = CoreFoundationApi.INSTANCE

        if (carbonApi == null) {
            println("GlobalHotKeyManager: Carbon API not available")
            _registrationStatus.value = HotKeyRegistrationStatus.UNAVAILABLE
            return
        }

        if (cfApi == null) {
            println("GlobalHotKeyManager: CoreFoundation API not available")
            _registrationStatus.value = HotKeyRegistrationStatus.UNAVAILABLE
            return
        }

        try {
            // Use GetEventDispatcherTarget() for truly global hotkeys (like iTerm2)
            // This works even when the app is not focused
            val target = carbonApi.GetEventDispatcherTarget()
            if (target == null) {
                println("GlobalHotKeyManager: Failed to get event dispatcher target")
                _registrationStatus.value = HotKeyRegistrationStatus.FAILED
                return
            }

            // Create the event handler callback
            // IMPORTANT: Keep a reference to prevent garbage collection
            macEventHandler = object : EventHandlerUPP {
                override fun invoke(nextHandler: Pointer?, event: Pointer?, userData: Pointer?): Int {
                    if (event == null) return MacOSHotKeyApi.eventNotHandledErr

                    try {
                        // Extract the hotkey ID from the event
                        val hotKeyID = EventHotKeyID.ByReference()
                        val result = carbonApi.GetEventParameter(
                            event,
                            MacOSHotKeyApi.kEventParamDirectObject,
                            MacOSHotKeyApi.typeEventHotKeyID,
                            null,
                            hotKeyID.size(),
                            null,
                            hotKeyID.pointer
                        )

                        if (result == MacOSHotKeyApi.noErr) {
                            hotKeyID.read()
                            val windowNum = hotKeyID.id
                            if (windowNum in 1..9) {
                                invokeCallback(windowNum)
                            }
                        }
                    } catch (e: Exception) {
                        println("GlobalHotKeyManager: Error in macOS hotkey handler: ${e.message}")
                    }

                    return MacOSHotKeyApi.noErr
                }
            }

            // Install the event handler for hotkey events
            val eventType = EventTypeSpec.ByReference()
            eventType.eventClass = MacOSHotKeyApi.kEventClassKeyboard
            eventType.eventKind = MacOSHotKeyApi.kEventHotKeyPressed
            eventType.write()

            val handlerRef = PointerByReference()
            val installResult = carbonApi.InstallEventHandler(
                target,
                macEventHandler,
                1,
                eventType.pointer,
                null,
                handlerRef
            )

            if (installResult != MacOSHotKeyApi.noErr) {
                println("GlobalHotKeyManager: Failed to install event handler: $installResult")
                _registrationStatus.value = HotKeyRegistrationStatus.FAILED
                return
            }
            macEventHandlerRef = handlerRef.value

            // Register hotkeys for windows 1-9
            val modifiers = MacOSHotKeyApi.configToModifiers(config)
            var anyRegistered = false

            for (windowNum in 1..9) {
                val keyCode = getMacKeyCodeForNumber(windowNum)
                val hotKeyID = EventHotKeyID.ByReference()
                hotKeyID.signature = HOTKEY_SIGNATURE
                hotKeyID.id = windowNum
                hotKeyID.write()

                val hotKeyRef = PointerByReference()
                val result = carbonApi.RegisterEventHotKey(
                    keyCode,
                    modifiers,
                    hotKeyID,
                    target,
                    0,
                    hotKeyRef
                )

                if (result == MacOSHotKeyApi.noErr) {
                    macHotKeyRefs[windowNum] = hotKeyRef.value
                    registeredWindows.add(windowNum)
                    anyRegistered = true
                }
            }

            if (!anyRegistered) {
                println("GlobalHotKeyManager: Failed to register any macOS hotkeys")
                _registrationStatus.value = HotKeyRegistrationStatus.FAILED
                return
            }

            println("GlobalHotKeyManager: Registered macOS hotkeys for windows 1-9")
            _registrationStatus.value = HotKeyRegistrationStatus.REGISTERED

            // Create the run loop mode string (kCFRunLoopDefaultMode)
            // Use encoding 0x08000100 for kCFStringEncodingUTF8
            macRunLoopMode = cfApi.CFStringCreateWithCString(null, "kCFRunLoopDefaultMode", 0x08000100)

            // Process events using CFRunLoopRunInMode instead of RunApplicationEventLoop
            // This allows us to periodically check if we should stop
            while (isRunning) {
                // Run the run loop for a short time (100ms)
                // This will process any pending events including hotkey events
                cfApi.CFRunLoopRunInMode(macRunLoopMode, 0.1, false)
            }

        } catch (e: Exception) {
            println("GlobalHotKeyManager: macOS handler error: ${e.message}")
            e.printStackTrace()
            _registrationStatus.value = HotKeyRegistrationStatus.FAILED
        } finally {
            cleanupMacOS()
        }
    }

    private fun getMacKeyCodeForNumber(num: Int): Int {
        // macOS virtual key codes for number keys
        return when (num) {
            1 -> MacOSHotKeyApi.kVK_ANSI_1
            2 -> MacOSHotKeyApi.kVK_ANSI_2
            3 -> MacOSHotKeyApi.kVK_ANSI_3
            4 -> MacOSHotKeyApi.kVK_ANSI_4
            5 -> MacOSHotKeyApi.kVK_ANSI_5
            6 -> MacOSHotKeyApi.kVK_ANSI_6
            7 -> MacOSHotKeyApi.kVK_ANSI_7
            8 -> MacOSHotKeyApi.kVK_ANSI_8
            9 -> MacOSHotKeyApi.kVK_ANSI_9
            else -> MacOSHotKeyApi.kVK_ANSI_1
        }
    }

    private fun registerMacOSHotKey(windowNumber: Int, config: HotKeyConfig) {
        // Already registered in runMacOSHandler for all 1-9
    }

    private fun unregisterMacOSHotKey(windowNumber: Int) {
        // We keep all hotkeys registered
    }

    private fun stopMacOS() {
        // The event loop will exit when isRunning becomes false
        // No need to call QuitApplicationEventLoop since we're using CFRunLoopRunInMode
    }

    private fun cleanupMacOS() {
        val carbonApi = MacOSHotKeyApi.INSTANCE
        val cfApi = CoreFoundationApi.INSTANCE

        // Unregister all hotkeys
        if (carbonApi != null) {
            try {
                for ((_, ref) in macHotKeyRefs) {
                    carbonApi.UnregisterEventHotKey(ref)
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Remove the event handler
            try {
                macEventHandlerRef?.let { carbonApi.RemoveEventHandler(it) }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Release the run loop mode string
        if (cfApi != null) {
            try {
                macRunLoopMode?.let { cfApi.CFRelease(it) }
            } catch (e: Exception) {
                // Ignore
            }
        }

        macHotKeyRefs.clear()
        macEventHandlerRef = null
        macEventHandler = null
        macRunLoopMode = null
        registeredWindows.clear()
        _registrationStatus.value = HotKeyRegistrationStatus.INACTIVE
    }

    // ===== Linux Implementation =====

    private fun runLinuxHandler(config: HotKeyConfig) {
        val api = LinuxHotKeyApi.INSTANCE
        if (api == null) {
            println("GlobalHotKeyManager: X11 API not available")
            _registrationStatus.value = HotKeyRegistrationStatus.UNAVAILABLE
            return
        }

        try {
            val display = api.XOpenDisplay(null)
            if (display == null) {
                println("GlobalHotKeyManager: Failed to open X11 display")
                _registrationStatus.value = HotKeyRegistrationStatus.FAILED
                return
            }
            linuxDisplay = display

            val rootWindow = api.XDefaultRootWindow(display)
            val modifiers = LinuxHotKeyApi.configToModifiers(config)
            var anyRegistered = false

            // Modifier variants to handle Caps Lock and Num Lock
            val modifierVariants = listOf(
                modifiers,
                modifiers or LinuxHotKeyApi.LockMask,
                modifiers or LinuxHotKeyApi.Mod2Mask,
                modifiers or LinuxHotKeyApi.LockMask or LinuxHotKeyApi.Mod2Mask
            )

            // Register hotkeys for windows 1-9
            for (windowNum in 1..9) {
                val keysym = LinuxHotKeyApi.XK_0 + windowNum
                val keycode = api.XKeysymToKeycode(display, NativeLong(keysym.toLong()))
                linuxKeycodes[windowNum] = keycode

                for (mods in modifierVariants) {
                    api.XGrabKey(
                        display,
                        keycode,
                        mods,
                        rootWindow,
                        1,
                        LinuxHotKeyApi.GrabModeAsync,
                        LinuxHotKeyApi.GrabModeAsync
                    )
                }
                registeredWindows.add(windowNum)
                anyRegistered = true
            }

            if (!anyRegistered) {
                println("GlobalHotKeyManager: Failed to register any Linux hotkeys")
                _registrationStatus.value = HotKeyRegistrationStatus.FAILED
                return
            }

            api.XSelectInput(display, rootWindow, NativeLong(LinuxHotKeyApi.KeyPressMask))
            api.XFlush(display)

            println("GlobalHotKeyManager: Registered Linux hotkeys for windows 1-9")
            _registrationStatus.value = HotKeyRegistrationStatus.REGISTERED

            // Event loop
            val event = XEvent()
            while (isRunning) {
                if (api.XPending(display) > 0) {
                    api.XNextEvent(display, event)
                    if (event.type == LinuxHotKeyApi.KeyPress) {
                        // Determine which window number was pressed
                        // For simplicity, we check all keycodes
                        for ((windowNum, keycode) in linuxKeycodes) {
                            // The event contains the keycode in the structure
                            // This is a simplified check
                            invokeCallback(windowNum)
                            break
                        }
                    }
                } else {
                    Thread.sleep(50)
                }
            }

            // Ungrab all keys
            for ((windowNum, keycode) in linuxKeycodes) {
                for (mods in modifierVariants) {
                    api.XUngrabKey(display, keycode, mods, rootWindow)
                }
            }

        } catch (e: Exception) {
            println("GlobalHotKeyManager: Linux handler error: ${e.message}")
            e.printStackTrace()
            _registrationStatus.value = HotKeyRegistrationStatus.FAILED
        } finally {
            cleanupLinux()
        }
    }

    private fun registerLinuxHotKey(windowNumber: Int, config: HotKeyConfig) {
        // Already registered in runLinuxHandler for all 1-9
    }

    private fun unregisterLinuxHotKey(windowNumber: Int) {
        // We keep all hotkeys registered
    }

    private fun stopLinux() {
        // The event loop will exit when isRunning becomes false
    }

    private fun cleanupLinux() {
        val api = LinuxHotKeyApi.INSTANCE ?: return
        try {
            linuxDisplay?.let { api.XCloseDisplay(it) }
        } catch (e: Exception) {
            // Ignore
        }
        linuxDisplay = null
        linuxKeycodes.clear()
        registeredWindows.clear()
        _registrationStatus.value = HotKeyRegistrationStatus.INACTIVE
    }

    // ===== Common =====

    private fun invokeCallback(windowNumber: Int) {
        val callback = onWindowHotKeyPressed ?: return
        SwingUtilities.invokeLater {
            try {
                callback(windowNumber)
            } catch (e: Exception) {
                println("GlobalHotKeyManager: Error in hotkey callback: ${e.message}")
            }
        }
    }
}
