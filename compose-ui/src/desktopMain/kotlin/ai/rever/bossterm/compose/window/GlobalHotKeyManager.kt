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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    // Linux registration success threshold: if less than this fraction succeed,
    // treat as overall failure. 50% threshold chosen because:
    // - Allows 4-5 windows to work even if some hotkeys conflict
    // - Provides clear feedback that something is wrong (not just 1-2 conflicts)
    // - Matches user expectation that "most" should work for success
    private const val LINUX_SUCCESS_THRESHOLD = 0.5

    private var handlerThread: Thread? = null
    @Volatile private var isRunning = false
    private var baseConfig: HotKeyConfig? = null
    private var onWindowHotKeyPressed: ((Int) -> Unit)? = null
    private var initializationLatch: CountDownLatch? = null

    // Platform-specific state
    private var winThreadId: Int = 0
    private val macHotKeyRefs: MutableMap<Int, Pointer> = Collections.synchronizedMap(mutableMapOf())
    private var macEventHandlerRef: Pointer? = null
    private var macEventHandler: EventHandlerUPP? = null  // Keep reference to prevent GC
    private var macRunLoopMode: Pointer? = null
    private var linuxDisplay: Pointer? = null
    private val linuxKeycodes: MutableMap<Int, Int> = Collections.synchronizedMap(mutableMapOf())

    // Track which window numbers have registered hotkeys (accessed from multiple threads)
    private val registeredWindows: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

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
        this.initializationLatch = CountDownLatch(1)

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

        // Wait for platform-specific initialization to complete
        val initialized = initializationLatch?.await(5, TimeUnit.SECONDS) ?: false
        if (!initialized) {
            println("GlobalHotKeyManager: Initialization timeout, cannot register window $windowNumber")
            return
        }

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

        // Save caller's interrupt status to restore it later
        val wasInterrupted = Thread.interrupted()  // Returns and clears interrupt status

        try {
            isRunning = false

            when {
                ShellCustomizationUtils.isWindows() -> stopWindows()
                ShellCustomizationUtils.isMacOS() -> stopMacOS()
                ShellCustomizationUtils.isLinux() -> stopLinux()
            }

            handlerThread?.let { thread ->
                try {
                    // Wait up to 2 seconds for graceful shutdown
                    thread.join(2000)
                    if (thread.isAlive) {
                        // Interrupt if still running
                        thread.interrupt()
                        // Give it another second to cleanup after interrupt
                        thread.join(1000)
                    }
                } catch (e: InterruptedException) {
                    // Handler thread join was interrupted - restore status and continue cleanup
                    Thread.currentThread().interrupt()
                }
            }

            handlerThread = null
            baseConfig = null
            onWindowHotKeyPressed = null
            initializationLatch = null
            // Note: macEventHandler, macEventHandlerRef, macRunLoopMode are cleared by cleanupMacOS()
            // which runs in the handler thread's finally block. Don't clear them here to avoid
            // race condition where CFString leaks if we null the reference before cleanup runs.
            registeredWindows.clear()
            _registrationStatus.value = HotKeyRegistrationStatus.INACTIVE

            println("GlobalHotKeyManager: Stopped")
        } finally {
            // Restore caller's original interrupt status
            if (wasInterrupted) {
                Thread.currentThread().interrupt()
            }
        }
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
            initializationLatch?.countDown()
            return
        }

        winThreadId = api.GetCurrentThreadId()
        if (winThreadId == 0) {
            println("GlobalHotKeyManager: Failed to get thread ID")
            _registrationStatus.value = HotKeyRegistrationStatus.FAILED
            initializationLatch?.countDown()
            return
        }

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
            initializationLatch?.countDown()
            return
        }

        println("GlobalHotKeyManager: Registered Windows hotkeys for windows 1-9")
        _registrationStatus.value = HotKeyRegistrationStatus.REGISTERED

        // Signal that initialization is complete
        initializationLatch?.countDown()

        val msg = MSG()
        try {
            while (isRunning && !Thread.currentThread().isInterrupted) {
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
            _registrationStatus.value = HotKeyRegistrationStatus.FAILED
        } finally {
            // Unregister all hotkeys
            for (windowNum in 1..9) {
                try {
                    api.UnregisterHotKey(null, windowNum)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            // Note: winThreadId is cleared in stopWindows() after posting WM_QUIT
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
        val threadId = winThreadId
        if (threadId != 0) {
            winThreadId = 0  // Reset immediately to prevent duplicate WM_QUIT on re-entry
            try {
                api.PostThreadMessage(threadId, Win32HotKeyApi.WM_QUIT, WPARAM(0), LPARAM(0))
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // ===== macOS Implementation =====

    private fun runMacOSHandler(config: HotKeyConfig) {
        val carbonApi = MacOSHotKeyApi.INSTANCE
        val cfApi = CoreFoundationApi.INSTANCE

        if (carbonApi == null) {
            println("GlobalHotKeyManager: Carbon API not available")
            _registrationStatus.value = HotKeyRegistrationStatus.UNAVAILABLE
            initializationLatch?.countDown()
            return
        }

        if (cfApi == null) {
            println("GlobalHotKeyManager: CoreFoundation API not available")
            _registrationStatus.value = HotKeyRegistrationStatus.UNAVAILABLE
            initializationLatch?.countDown()
            return
        }

        try {
            // Use GetEventDispatcherTarget() for truly global hotkeys (like iTerm2)
            // This works even when the app is not focused
            val target = carbonApi.GetEventDispatcherTarget()
            if (target == null) {
                println("GlobalHotKeyManager: Failed to get event dispatcher target")
                _registrationStatus.value = HotKeyRegistrationStatus.FAILED
                initializationLatch?.countDown()
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
                initializationLatch?.countDown()
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
                initializationLatch?.countDown()
                return
            }

            println("GlobalHotKeyManager: Registered macOS hotkeys for windows 1-9")
            _registrationStatus.value = HotKeyRegistrationStatus.REGISTERED

            // Signal that initialization is complete
            initializationLatch?.countDown()

            // Create the run loop mode string (kCFRunLoopDefaultMode)
            // Use encoding 0x08000100 for kCFStringEncodingUTF8
            macRunLoopMode = cfApi.CFStringCreateWithCString(null, "kCFRunLoopDefaultMode", 0x08000100)

            // Process events using CFRunLoopRunInMode instead of RunApplicationEventLoop
            // This allows us to periodically check if we should stop
            while (isRunning && !Thread.currentThread().isInterrupted) {
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
        // Capture the pointer locally to avoid race with concurrent access
        val runLoopModeToRelease = macRunLoopMode
        macRunLoopMode = null  // Clear reference first to prevent further use

        if (cfApi != null && runLoopModeToRelease != null) {
            try {
                cfApi.CFRelease(runLoopModeToRelease)
            } catch (e: Exception) {
                // Ignore
            }
        }

        macHotKeyRefs.clear()
        macEventHandlerRef = null
        macEventHandler = null
        registeredWindows.clear()
        _registrationStatus.value = HotKeyRegistrationStatus.INACTIVE
    }

    // ===== Linux Implementation =====

    private fun runLinuxHandler(config: HotKeyConfig) {
        val api = LinuxHotKeyApi.INSTANCE
        if (api == null) {
            println("GlobalHotKeyManager: X11 API not available")
            _registrationStatus.value = HotKeyRegistrationStatus.UNAVAILABLE
            initializationLatch?.countDown()
            return
        }

        try {
            val display = api.XOpenDisplay(null)
            if (display == null) {
                println("GlobalHotKeyManager: Failed to open X11 display")
                _registrationStatus.value = HotKeyRegistrationStatus.FAILED
                initializationLatch?.countDown()
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
            val failedWindows = mutableListOf<Int>()
            for (windowNum in 1..9) {
                val keysym = LinuxHotKeyApi.XK_0 + windowNum
                val keycode = api.XKeysymToKeycode(display, NativeLong(keysym.toLong()))

                if (keycode == 0) {
                    println("GlobalHotKeyManager: Failed to get keycode for window $windowNum")
                    failedWindows.add(windowNum)
                    continue
                }

                linuxKeycodes[windowNum] = keycode

                try {
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
                    // Sync to flush any errors
                    api.XSync(display, 0)
                    registeredWindows.add(windowNum)
                    anyRegistered = true
                } catch (e: Exception) {
                    println("GlobalHotKeyManager: Failed to register hotkey for window $windowNum: ${e.message}")
                    println("  This may indicate a conflict with an existing system hotkey")
                    failedWindows.add(windowNum)
                }
            }

            if (!anyRegistered) {
                println("GlobalHotKeyManager: Failed to register any Linux hotkeys")
                println("  This usually indicates conflicts with existing system hotkeys")
                println("  Try using different modifier keys in BossTerm settings")
                _registrationStatus.value = HotKeyRegistrationStatus.FAILED
                initializationLatch?.countDown()
                return
            }

            api.XSelectInput(display, rootWindow, NativeLong(LinuxHotKeyApi.KeyPressMask))
            api.XFlush(display)

            // Evaluate success based on threshold
            val totalAttempted = 9
            val successCount = registeredWindows.size
            val successRate = successCount.toDouble() / totalAttempted

            if (failedWindows.isEmpty()) {
                println("GlobalHotKeyManager: Registered Linux hotkeys for windows 1-9")
                _registrationStatus.value = HotKeyRegistrationStatus.REGISTERED
            } else if (successRate < LINUX_SUCCESS_THRESHOLD) {
                // Below threshold - too many failures to be useful
                val failurePercent = ((1.0 - successRate) * 100).toInt()
                println("GlobalHotKeyManager: Failed to register most Linux hotkeys ($failurePercent% failed)")
                println("  Registered: ${registeredWindows.sorted()} (${successCount}/9)")
                println("  Failed: ${failedWindows.sorted()} - likely conflicts with desktop environment hotkeys")
                println("  Action required: Change modifier keys in BossTerm settings")
                println("  Example: Try using Ctrl+Alt+Shift instead of just Ctrl+Alt")
                _registrationStatus.value = HotKeyRegistrationStatus.FAILED
                initializationLatch?.countDown()
                return
            } else {
                // Above threshold - partial success is acceptable
                println("GlobalHotKeyManager: Registered Linux hotkeys for windows ${registeredWindows.sorted()} (${successCount}/9)")
                println("  Failed: ${failedWindows.sorted()} - likely conflicts with desktop environment")
                println("  Tip: Some window numbers may not respond to hotkeys")
                _registrationStatus.value = HotKeyRegistrationStatus.REGISTERED
            }

            // Signal that initialization is complete
            initializationLatch?.countDown()

            // Event loop - uses a hybrid polling/event-driven approach
            // XNextEvent() blocks indefinitely, so we use XPending() + short sleep
            // to allow periodic checking of isRunning flag
            //
            // Performance tradeoff: 10ms polling adds ~0.1% CPU usage when idle.
            // Alternative: XConnectionNumber() + select() for true event-driven waiting,
            // but requires additional JNA bindings for POSIX select().
            val event = XEvent()
            while (isRunning && !Thread.currentThread().isInterrupted) {
                if (api.XPending(display) > 0) {
                    // Events available - process immediately
                    api.XNextEvent(display, event)
                    if (event.type == LinuxHotKeyApi.KeyPress) {
                        // Extract the actual keycode from the XEvent
                        // This properly handles the X11 union structure without hard-coded offsets
                        // Note: getKeycode() creates a Java wrapper but doesn't allocate new native memory
                        val eventKeycode = event.getKeycode()
                        if (eventKeycode != null) {
                            // Find the matching window number by comparing keycodes
                            for ((windowNum, keycode) in linuxKeycodes) {
                                if (eventKeycode == keycode) {
                                    invokeCallback(windowNum)
                                    break
                                }
                            }
                        }
                    }
                } else {
                    // No events - sleep briefly to reduce CPU usage while remaining responsive
                    // 10ms sleep provides ~5ms average latency for hotkey response
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
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
