package ai.rever.bossterm.compose.window

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

internal data class NativeToolbarAction(val id: String, val label: String, val symbol: String, val active: Boolean = false, val onClick: () -> Unit)
/** The native frame exists independently of the selected theme material. */
val LocalNativeWindowFrame = staticCompositionLocalOf { false }
internal val LocalNativeToolbar = staticCompositionLocalOf<NativeToolbarController?> { null }

@Composable
internal fun RegisterNativeToolbarAction(action: NativeToolbarAction) {
    val controller = LocalNativeToolbar.current ?: return
    SideEffect { controller.put(action) }
    DisposableEffect(controller, action.id) { onDispose { controller.remove(action.id) } }
}

@Composable
internal fun NativeTitleToolbar(handle: Long, title: String, onToggleSidebar: (() -> Unit)?,
                                actions: (@Composable () -> Unit)?, leading: (@Composable () -> Unit)?, sidebarPanelWidth: Double, dark: Boolean) {
    var contentInset by remember(handle) { mutableStateOf(0.dp) }
    val controller = remember(handle) { NativeToolbarController(handle) }
    DisposableEffect(controller) {
        val timer = javax.swing.Timer(100) {
            controller.measureHeader { contentInset = it.toFloat().dp }
        }.apply { start() }
        onDispose { timer.stop() }
    }
    Spacer(Modifier.height(contentInset))
    DisposableEffect(controller) { onDispose { controller.close() } }
    SideEffect { controller.setTitle(title); controller.setAppearance(dark); controller.setSidebarWidth(sidebarPanelWidth) }
    CompositionLocalProvider(LocalNativeToolbar provides controller, LocalInTitleBar provides true,
        LocalCompactTitleBar provides false) {
        if (onToggleSidebar != null) RegisterNativeToolbarAction(NativeToolbarAction("sidebar", "Toggle Sidebar", "sidebar.left", onClick = onToggleSidebar))
        // Render only the action registrations; AppKit owns all visible buttons and overflow.
        Box(Modifier.size(0.dp)) {
            leading?.invoke()
            actions?.invoke()
            CompositionLocalProvider(LocalTitleBarTrailing provides true) { leading?.invoke() }
        }
    }
}

internal class NativeToolbarController(private val handle: Long) : AutoCloseable {
    private val actions = ConcurrentHashMap<String, NativeToolbarAction>()
    private var toolbar: Pointer? = null
    private var delegate: Pointer? = null
    private val items = mutableMapOf<String, Pointer>() // AppKit queue only
    private var displayed = emptyList<String>()
    @Volatile private var sidebarWidth = 0.0
    fun setSidebarWidth(width: Double) {
        if (sidebarWidth != width) { sidebarWidth = width; schedule() }
    }
    @Volatile private var closed = false
    private var queued = false // Swing EDT only
    private val order = listOf("sidebar", "new", "split_vertical", "split_horizontal", "sharing", "call", "mcp", "more")

    fun measureHeader(onMeasured: (Double) -> Unit) { NativeGlass.dispatch {
        if (!closed && NativeGlass.isLiveWindow(handle)) {
            NativeGlass.headerHeight(Pointer(handle))?.let { height ->
                SwingUtilities.invokeLater { if (!closed) onMeasured(height) }
            }
        }
    } }

    fun setAppearance(dark: Boolean) { NativeGlass.dispatch {
        if (!closed && NativeGlass.isLiveWindow(handle)) NativeGlass.setWindowAppearance(Pointer(handle), dark)
    } }
    fun put(action: NativeToolbarAction) { actions[action.id] = action; schedule() }
    fun remove(id: String) { actions.remove(id); schedule() }
    fun setTitle(title: String) { NativeGlass.dispatch {
        if (!closed && NativeGlass.isLiveWindow(handle)) NativeGlass.sendVoid(Pointer(handle), "setTitle:", ns(title))
    } }
    private fun schedule() {
        if (queued || closed) return
        queued = true
        SwingUtilities.invokeLater {
            queued = false
            if (!closed) NativeGlass.dispatch { if (!closed && NativeGlass.isLiveWindow(handle)) update() }
        }
    }
    private fun update() {
        if (toolbar == null) {
            nativeWindows.add(handle)
            delegate = NativeGlass.sendPointer(bridgeClass, "new")
            owners[Pointer.nativeValue(delegate)] = this
            toolbar = NativeGlass.sendPointer(NativeGlass.sendPointer(NativeGlass.getClass("NSToolbar"), "alloc"),
                "initWithIdentifier:", ns("ai.rever.bossterm.native-actions"))
            NativeGlass.sendVoid(toolbar, "setDelegate:", delegate)
            NativeGlass.sendVoid(toolbar, "setDisplayMode:", 2L) // icon only
            NativeGlass.sendVoid(toolbar, "setAllowsUserCustomization:", 0.toByte())
            NativeGlass.sendVoid(Pointer(handle), "setToolbar:", toolbar)
            NativeGlass.sendVoid(Pointer(handle), "setToolbarStyle:", 3L) // NSWindowToolbarStyleUnified
            NativeGlass.sendVoid(Pointer(handle), "setTitleVisibility:", 0L)
            // AWT remains undecorated: keep full-window content and reserve AppKit’s
            // measured contentLayoutRect inset in Compose.
            val mask = long(Pointer(handle), "styleMask")
            NativeGlass.sendVoid(Pointer(handle), "setStyleMask:", (mask or 1L or 32768L))
        }
        val ids = buildList {
            if (actions.containsKey("sidebar")) add("sidebar")
            if (sidebarWidth > 0 && actions.containsKey("sidebar")) add("sidebar_boundary")
            add("NSToolbarFlexibleSpaceItem")
            val groups = listOf(listOf("new"), listOf("split_vertical", "split_horizontal"),
                listOf("sharing"), listOf("call"), listOf("mcp"), listOf("more"))
            groups.map { group -> group.filter { actions.containsKey(it) } }.filter { it.isNotEmpty() }
                .forEachIndexed { index, group ->
                    if (index > 0) add("NSToolbarSpaceItem")
                    addAll(group)
                }
        }
        if (ids != displayed) {
            val count = long(NativeGlass.sendPointer(toolbar, "items"), "count")
            for (i in count - 1 downTo 0) NativeGlass.sendVoid(toolbar, "removeItemAtIndex:", i)
            ids.forEach { id ->
                val index = long(NativeGlass.sendPointer(toolbar, "items"), "count")
                NativeGlass.sendVoid(toolbar, "insertItemWithItemIdentifier:atIndex:", ns(id), index)
                if (long(NativeGlass.sendPointer(toolbar, "items"), "count") == index)
                    System.err.println("Native toolbar did not insert: $id")
            }
            displayed = ids
            org.slf4j.LoggerFactory.getLogger(NativeToolbarController::class.java)
                .info("Native toolbar installed: {} actions, {} items", actions.size,
                    long(NativeGlass.sendPointer(toolbar, "items"), "count"))
        }
        items["sidebar_boundary"]?.let { updateSidebarBoundary(it) }
        actions.forEach { (id, action) -> items[id]?.let { updateItem(it, action) } }
    }
    private fun updateItem(item: Pointer, action: NativeToolbarAction) {
        NativeGlass.sendVoid(item, "setLabel:", ns(action.label))
        NativeGlass.sendVoid(item, "setPaletteLabel:", ns(action.label))
        NativeGlass.sendVoid(item, "setToolTip:", ns(action.label + if (action.active) " — Active" else ""))
        // NSToolbar creates its own button, symbol sizing and glass background. A
        // custom NSButton view here opts out of that automatic geometry.
        val image = if (action.symbol == "mcp") mcpImage else
            NativeGlass.sendPointer(NativeGlass.getClass("NSImage"), "imageWithSystemSymbolName:accessibilityDescription:", ns(action.symbol), ns(action.label))
        NativeGlass.sendVoid(item, "setImage:", image)
        NativeGlass.sendVoid(item, "setEnabled:", 1.toByte())
        NativeGlass.sendVoid(item, "setBordered:", 1.toByte())
        NativeGlass.sendVoid(item, "setNavigational:", if (action.id == "sidebar") 1.toByte() else 0.toByte())
        NativeGlass.sendVoid(item, "setVisibilityPriority:", if (action.id in setOf("sidebar", "new", "more")) 1000L else 0L)
        NativeGlass.sendVoid(item, "setTarget:", delegate)
        NativeGlass.sendVoid(item, "setAction:", selector("activate:"))
        // AppKit's documented state treatment tints just this item's native glass.
        if (msg.invokeInt(arrayOf(item, selector("respondsToSelector:"), selector("setStyle:"))) != 0) {
            NativeGlass.sendVoid(item, "setStyle:", if (action.active) 1L else 0L)
        }
    }
    private fun updateSidebarBoundary(item: Pointer) {
        // Reserve the remainder of the sidebar column after the standard macOS
        // traffic-light and sidebar-control cluster, before the native title.
        val width = (sidebarWidth - 140.0).coerceAtLeast(0.0)
        NativeGlass.sendVoid(item, "setMinSize:", NativeSize(width, 1.0))
        NativeGlass.sendVoid(item, "setMaxSize:", NativeSize(width, 1.0))
    }
    private fun makeItem(identifier: Pointer?): Pointer? {
        val id = NativeGlass.sendPointer(identifier, "UTF8String")?.getString(0) ?: return null
        if (id == "sidebar_boundary") {
            return items.getOrPut(id) {
                val item = NativeGlass.sendPointer(NativeGlass.sendPointer(NativeGlass.getClass("NSToolbarItem"), "alloc"), "initWithItemIdentifier:", identifier)!!
                val view = NativeGlass.sendPointer(NativeGlass.getClass("NSView"), "new")
                NativeGlass.sendVoid(item, "setView:", view)
                NativeGlass.sendVoid(view, "release")
                NativeGlass.sendVoid(item, "setNavigational:", 1.toByte())
                NativeGlass.sendVoid(item, "setBordered:", 0.toByte())
                updateSidebarBoundary(item)
                item
            }
        }
        val action = actions[id] ?: return null
        val item = items.getOrPut(id) {
            NativeGlass.sendPointer(NativeGlass.sendPointer(NativeGlass.getClass("NSToolbarItem"), "alloc"), "initWithItemIdentifier:", identifier)!!
        }
        updateItem(item, action)
        return item
    }
    override fun close() {
        closed = true
        NativeGlass.dispatch {
            nativeWindows.remove(handle)
            if (NativeGlass.isLiveWindow(handle) && NativeGlass.sendPointer(Pointer(handle), "toolbar") == toolbar)
                NativeGlass.sendVoid(Pointer(handle), "setToolbar:", null)
            NativeGlass.sendVoid(toolbar, "setDelegate:", null)
            owners.remove(Pointer.nativeValue(delegate))
            items.values.forEach { NativeGlass.sendVoid(it, "release") }
            items.clear()
            NativeGlass.sendVoid(toolbar, "release")
            NativeGlass.sendVoid(delegate, "release")
        }
    }
    companion object {
        val nativeWindows = ConcurrentHashMap.newKeySet<Long>()
        private val objc = NativeLibrary.getInstance("objc")
        private val msg = objc.getFunction("objc_msgSend")
        private val owners = ConcurrentHashMap<Long, NativeToolbarController>()
        private fun selector(s: String) = objc.getFunction("sel_registerName").invokePointer(arrayOf(s))
        private fun ns(s: String) = NativeGlass.sendPointer(NativeGlass.getClass("NSString"), "stringWithUTF8String:", s)
        private fun long(p: Pointer?, s: String) = msg.invokeLong(arrayOf(p, selector(s)))
        private interface ItemCallback : Callback { fun invoke(self: Pointer?, cmd: Pointer?, toolbar: Pointer?, identifier: Pointer?, inserted: Byte): Pointer? }
        private interface ListCallback : Callback { fun invoke(self: Pointer?, cmd: Pointer?, toolbar: Pointer?): Pointer? }
        private interface ActionCallback : Callback { fun invoke(self: Pointer?, cmd: Pointer?, sender: Pointer?) }
        private val itemCallback = object : ItemCallback {
            override fun invoke(self: Pointer?, cmd: Pointer?, toolbar: Pointer?, identifier: Pointer?, inserted: Byte): Pointer? =
                owners[Pointer.nativeValue(self)]?.makeItem(identifier)
        }
        private val listCallback = object : ListCallback {
            override fun invoke(self: Pointer?, cmd: Pointer?, toolbar: Pointer?): Pointer? {
                val array = NativeGlass.sendPointer(NativeGlass.getClass("NSMutableArray"), "array")
                owners[Pointer.nativeValue(self)]?.let { owner ->
                    owner.order.filter { owner.actions.containsKey(it) }.forEach {
                        NativeGlass.sendVoid(array, "addObject:", ns(it))
                    }
                }
                NativeGlass.sendVoid(array, "addObject:", ns("NSToolbarFlexibleSpaceItem"))
                NativeGlass.sendVoid(array, "addObject:", ns("NSToolbarSpaceItem"))
                NativeGlass.sendVoid(array, "addObject:", ns("sidebar_boundary"))
                return array
            }
        }
        private val actionCallback = object : ActionCallback {
            override fun invoke(self: Pointer?, cmd: Pointer?, sender: Pointer?) {
                val owner = owners[Pointer.nativeValue(self)] ?: return
                val identifier = NativeGlass.sendPointer(sender, "itemIdentifier")
                val id = NativeGlass.sendPointer(identifier, "UTF8String")?.getString(0) ?: return
                SwingUtilities.invokeLater { if (!owner.closed) owner.actions[id]?.onClick?.invoke() }
            }
        }
        private val bridgeClass: Pointer by lazy {
            val cls = objc.getFunction("objc_allocateClassPair").invokePointer(arrayOf(NativeGlass.getClass("NSObject"), "BossTermToolbarDelegate", 0L))
            fun method(name: String, callback: Callback, types: String) {
                check(objc.getFunction("class_addMethod").invokeInt(arrayOf(cls, selector(name), CallbackReference.getFunctionPointer(callback), types)) != 0)
            }
            method("toolbar:itemForItemIdentifier:willBeInsertedIntoToolbar:", itemCallback, "@@:@@c")
            method("toolbarAllowedItemIdentifiers:", listCallback, "@@:@")
            method("toolbarDefaultItemIdentifiers:", listCallback, "@@:@")
            val protocol = objc.getFunction("objc_getProtocol").invokePointer(arrayOf("NSToolbarDelegate"))
            if (protocol != null) objc.getFunction("class_addProtocol").invokeInt(arrayOf(cls, protocol))
            method("activate:", actionCallback, "v@:@")
            objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(cls))
            cls
        }
        private val mcpImage: Pointer? by lazy {
            val bytes = NativeToolbarController::class.java.getResourceAsStream("/icons/mcp-toolbar.svg")?.use { it.readBytes() }
                ?: return@lazy null
            val png = org.jetbrains.skia.Surface.makeRasterN32Premul(180, 180).use { surface ->
                org.jetbrains.skia.svg.SVGDOM(org.jetbrains.skia.Data.makeFromBytes(bytes)).use { svg -> svg.render(surface.canvas) }
                surface.makeImageSnapshot().use { it.encodeToData()?.bytes }
            } ?: return@lazy null
            val data = NativeGlass.sendPointer(NativeGlass.getClass("NSData"), "dataWithBytes:length:", png, png.size.toLong())
            NativeGlass.sendPointer(NativeGlass.sendPointer(NativeGlass.getClass("NSImage"), "alloc"), "initWithData:", data)?.also {
                NativeGlass.sendVoid(it, "setSize:", NativeSize(16.0, 16.0))
                NativeGlass.sendVoid(it, "setTemplate:", 1.toByte())
            }
        }
    }
}
