package ai.rever.bossterm.compose.window

import com.sun.jna.Pointer

/** Public AppKit operations, isolated so fullscreen host changes can be tested without AppKit. */
internal interface MacToolbarSurfaceApi {
    fun styleMask(window: Pointer): Long
    fun appearance(window: Pointer): Pointer?
    fun toolbarWindow(window: Pointer): Pointer?
    fun setTransparent(window: Pointer, transparent: Boolean)
    fun setAppearance(window: Pointer, appearance: Pointer?)
}

/** Runs on the AppKit queue; never retains the transient fullscreen toolbar window. */
internal fun synchronizeMacToolbarSurface(window: Pointer, api: MacToolbarSurfaceApi) {
    val fullscreen = api.styleMask(window) and (1L shl 14) != 0L // NSWindowStyleMaskFullScreen
    // Windowed chrome has our Compose surface behind it. Fullscreen chrome may live in
    // a separate AppKit window with no Compose backing, so let AppKit paint its material.
    api.setTransparent(window, !fullscreen)
    val host = api.toolbarWindow(window) ?: return
    if (host != window) {
        // A separate window does not inherit the terminal's theme from its owner. Refresh
        // both on theme changes and when AppKit replaces the host during reveal/transition.
        api.setAppearance(host, api.appearance(window))
        api.setTransparent(host, false)
    }
}
