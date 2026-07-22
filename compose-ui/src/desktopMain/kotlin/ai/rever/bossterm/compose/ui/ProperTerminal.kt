package ai.rever.bossterm.compose.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.CursorShape
import ai.rever.bossterm.terminal.RequestOrigin
import ai.rever.bossterm.terminal.TerminalDisplay
import ai.rever.bossterm.terminal.emulator.mouse.MouseButtonCodes
import ai.rever.bossterm.terminal.emulator.mouse.MouseMode
import ai.rever.bossterm.terminal.model.BufferSnapshot
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.util.CharUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ai.rever.bossterm.compose.ConnectionState
import ai.rever.bossterm.compose.PreConnectScreen
import ai.rever.bossterm.compose.actions.addSplitPaneActions
import ai.rever.bossterm.compose.actions.addTabManagementActions
import ai.rever.bossterm.compose.actions.createBuiltinActions
import ai.rever.bossterm.compose.splits.NavigationDirection
import ai.rever.bossterm.compose.menu.MenuActions
import ai.rever.bossterm.compose.debug.DebugWindow
import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.features.ContextMenuPopup
import ai.rever.bossterm.compose.features.showHyperlinkContextMenu
import ai.rever.bossterm.compose.features.showTerminalContextMenu
import ai.rever.bossterm.compose.hyperlinks.Hyperlink
import ai.rever.bossterm.compose.hyperlinks.HyperlinkDetector
import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkRegistry
import ai.rever.bossterm.compose.hyperlinks.toHyperlinkInfo
import ai.rever.bossterm.compose.SelectionMode
import ai.rever.bossterm.compose.ime.IMEHandler
import ai.rever.bossterm.compose.search.RabinKarpSearch
import ai.rever.bossterm.compose.scrollbar.AlwaysVisibleScrollbar
import ai.rever.bossterm.compose.scrollbar.computeMatchPositions
import ai.rever.bossterm.compose.scrollbar.rememberTerminalScrollbarAdapter
import ai.rever.bossterm.compose.search.SearchBar
import ai.rever.bossterm.compose.rendering.RenderingContext
import ai.rever.bossterm.compose.rendering.RenderableBlock
import ai.rever.bossterm.compose.rendering.TerminalCanvasRenderer
import ai.rever.bossterm.compose.blocks.BlockState
import ai.rever.bossterm.compose.selection.SelectionEngine
import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.theme.ThemeManager
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import ai.rever.bossterm.compose.TerminalSession
import ai.rever.bossterm.compose.util.ColorUtils
import ai.rever.bossterm.compose.util.KeyMappingUtils
import ai.rever.bossterm.compose.input.createComposeMouseEvent
import ai.rever.bossterm.compose.input.createComposeMouseWheelEvent
import ai.rever.bossterm.compose.input.createMouseEvent
import ai.rever.bossterm.compose.input.toMouseModifierFlags
import ai.rever.bossterm.compose.input.isShiftPressed
import ai.rever.bossterm.compose.input.isAltPressed
import ai.rever.bossterm.compose.input.isCtrlOrMetaPressed
import ai.rever.bossterm.core.typeahead.TerminalTypeAheadManager
import org.jetbrains.skia.FontMgr
import ai.rever.bossterm.terminal.TextStyle as BossTextStyle
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JFileChooser
/**
 * Proper terminal implementation using BossTerm's emulator.
 * This uses the real BossTerminal, BossEmulator, and TerminalTextBuffer from the core module.
 *
 * Refactored to support multiple tabs - accepts a TerminalTab with all per-tab state.
 */
@OptIn(
  ExperimentalComposeUiApi::class,
  ExperimentalTextApi::class,
  ExperimentalFoundationApi::class
)
@Composable
fun ProperTerminal(
  tab: TerminalSession,
  isActiveTab: Boolean,
  autoFocus: Boolean = false,  // Request focus after a delay (useful for dialogs)
  sharedFont: FontFamily,
  onTabTitleChange: (String) -> Unit,
  onNewTab: (() -> Unit)? = null,
  onSwitchShell: ((String) -> Unit)? = null,  // Windows: switch current tab's shell
  onNewPreConnectTab: () -> Unit = {},  // Ctrl+Shift+T: Test pre-connection input
  onCloseTab: () -> Unit = {},
  onNextTab: () -> Unit = {},
  onPreviousTab: () -> Unit = {},
  onSwitchToTab: (Int) -> Unit = {},
  onNewWindow: (() -> Unit)? = null,  // Cmd/Ctrl+N: New window
  onShowSettings: (() -> Unit)? = null,  // Open settings window
  onShowWelcomeWizard: (() -> Unit)? = null,  // Open welcome wizard
  onSplitHorizontal: (() -> Unit)? = null,  // Cmd+Shift+H: Split horizontally (top/bottom)
  onSplitVertical: (() -> Unit)? = null,  // Cmd+D: Split vertically (left/right)
  onClosePane: () -> Unit = {},  // Cmd+Shift+W: Close current pane
  onNavigatePane: (NavigationDirection) -> Unit = {},  // Navigate between panes directionally
  onNavigateNextPane: () -> Unit = {},  // Cmd+]: Navigate to next pane (cycles)
  onNavigatePreviousPane: () -> Unit = {},  // Cmd+[: Navigate to previous pane (cycles)
  onMoveToNewTab: (() -> Unit)? = null,  // Move current pane to new tab (context menu)
  onPaneFocus: () -> Unit = {},  // Called when pane receives mouse press (for split focus)
  menuActions: MenuActions? = null,
  enableDebugPanel: Boolean = true,  // Whether to show debug panel option in context menu
  customContextMenuItems: List<ai.rever.bossterm.compose.ContextMenuElement> = emptyList(),
  customContextMenuItemsProvider: (() -> List<ai.rever.bossterm.compose.ContextMenuElement>)? = null,  // Lambda to get fresh items after async callback
  onContextMenuOpen: (() -> Unit)? = null,  // Callback invoked right before context menu is shown (sync)
  onContextMenuOpenAsync: (suspend () -> Unit)? = null,  // Async callback - menu waits for completion before showing
  onLinkClick: ((HyperlinkInfo) -> Boolean)? = null,  // Custom link handler; return true if handled, false for default behavior
  hyperlinkRegistry: HyperlinkRegistry = HyperlinkDetector.registry,  // Per-instance hyperlink patterns
  modifier: Modifier = Modifier
) {
  // Extract session state (no more remember {} blocks - state lives in TerminalSession)
  val processHandle = tab.processHandle.value
  var connectionState by tab.connectionState
  var isFocused by tab.isFocused
  var scrollOffset by tab.scrollOffset
  var userScrollTrigger by remember { mutableStateOf(0) }  // Tracks user-initiated scrolls for scrollbar visibility
  val scope = rememberCoroutineScope()
  var hasPerformedInitialResize by remember { mutableStateOf(false) }  // Track initial resize
  var isModifierPressed by remember { mutableStateOf(false) }  // Track Ctrl/Cmd for hyperlink clicks
  // Remember whether the last press was forwarded to the TUI so Release can
  // mirror that exact decision (instead of re-reading the modifier on release,
  // which would mishandle the case where the user toggles Cmd/Ctrl between
  // press and release and leave the TUI with an unpaired button-down/up).
  var lastPressForwardedToTui by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val textMeasurer = rememberTextMeasurer()
  val clipboardManager = LocalClipboardManager.current
  val density = LocalDensity.current  // Observe display density for multi-monitor support (#206)

  // Settings integration
  val settingsManager = remember { SettingsManager.instance }
  val settings by settingsManager.settings.collectAsState()

  // Active theme, used as a fallback cursor color when no app has set one via OSC 12
  val activeTheme by ThemeManager.instance.currentTheme.collectAsState()

  // Use tab's terminal components
  val terminal = tab.terminal
  val textBuffer = tab.textBuffer
  val display = tab.display
  var gridStabilityJob by remember(terminal) { mutableStateOf<Job?>(null) }

  DisposableEffect(terminal) {
    onDispose { gridStabilityJob?.cancel() }
  }

  // Command blocks captured for this session (OSC 133). Collected so the gutter
  // and scrollbar markers repaint as commands start and finish. Falls back to a
  // stable empty flow when this session does not track blocks.
  val commandBlockFlow = remember(tab) {
    tab.commandBlockTracker?.blocks
      ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<ai.rever.bossterm.compose.blocks.CommandBlock>())
  }
  val commandBlocks by commandBlockFlow.collectAsState()

  // Command palette overlay visibility (toggled by the command_palette action).
  var commandPaletteVisible by remember { mutableStateOf(false) }

  // Workflows for this session (Phase 3); reloaded when cwd / settings change.
  val workflows = remember(tab.workingDirectory.value, settings.workflowsEnabled, settings.workflowExtraDirs) {
    if (settings.workflowsEnabled) {
      ai.rever.bossterm.compose.workflows.WorkflowStore(
        extraDirs = settings.workflowExtraDirs.map { java.io.File(it) }
      ).load(tab.workingDirectory.value)
    } else {
      emptyList()
    }
  }
  // When non-null, the workflow parameter dialog is shown for this workflow.
  var pendingWorkflow by remember { mutableStateOf<ai.rever.bossterm.compose.workflows.Workflow?>(null) }

  // History search overlay visibility (Ctrl+R when enabled and not in an alt-screen app).
  var historySearchVisible by remember { mutableStateOf(false) }

  // Search state from tab
  var searchVisible by tab.searchVisible
  var searchQuery by tab.searchQuery
  var searchCaseSensitive by remember { mutableStateOf(settings.searchCaseSensitive) }
  var searchMatches by tab.searchMatches

  // Debug panel state from tab
  var debugPanelVisible by tab.debugPanelVisible
  val debugCollector = tab.debugCollector
  var currentMatchIndex by tab.currentSearchMatchIndex

  // IME state from tab
  val imeState = tab.imeState

  // Selection state from tab (selectionTracker is the single source of truth)
  val selectionTracker = tab.selectionTracker
  var selectionMode by tab.selectionMode

  // X11-style selection clipboard from tab
  var selectionClipboard by tab.selectionClipboard

  // Scroll terminal to show a search match (only scroll if match not already visible)
  fun scrollToMatch(matchRow: Int) {
    val screenHeight = textBuffer.height
    val historySize = textBuffer.historyLinesCount

    // Calculate currently visible rows (in buffer coordinates)
    // scrollOffset=0 means viewing current screen (rows 0 to screenHeight-1)
    // scrollOffset=N means scrolled up N lines into history
    val visibleRowStart = -scrollOffset  // First visible buffer row
    val visibleRowEnd = visibleRowStart + screenHeight - 1  // Last visible buffer row

    // Only scroll if match is not visible
    if (matchRow < visibleRowStart) {
      // Match is above visible area, scroll up to show it (with 2-line margin from top)
      val targetOffset = -matchRow + 2
      scrollOffset = targetOffset.coerceIn(0, historySize)
    } else if (matchRow > visibleRowEnd) {
      // Match is below visible area, scroll down to show it (with 2-line margin from bottom)
      val targetOffset = -(matchRow - screenHeight + 3)
      scrollOffset = targetOffset.coerceIn(0, historySize)
    }
    // If match is already visible, don't scroll

    display.requestImmediateRedraw()
  }

  // Highlight a search match using selection
  fun highlightMatch(matchCol: Int, matchRow: Int, matchLength: Int) {
    // matchRow is already buffer-relative (from search), use directly
    // Selection rendering will convert buffer to screen coords
    selectionTracker.setSelection(
      startCol = matchCol,
      startRow = matchRow,
      endCol = matchCol + matchLength - 1,
      endRow = matchRow,
      mode = selectionMode
    )

    display.requestImmediateRedraw()
  }

  // Search function using Rabin-Karp algorithm for O(n+m) performance
  fun performSearch() {
    if (searchQuery.isEmpty()) {
      searchMatches = emptyList()
      currentMatchIndex = -1
      return
    }

    // Use snapshot for lock-free search - no blocking during search operation
    val snapshot = terminal.terminalTextBuffer.createSnapshot()

    // Use Rabin-Karp search for O(n+m) average case vs O(n*m) naive indexOf
    val rabinMatches = RabinKarpSearch.searchBuffer(
      snapshot = snapshot,
      pattern = searchQuery,
      ignoreCase = !searchCaseSensitive
    )

    // Convert to the expected Pair<Int, Int> format (column, row)
    val matches = rabinMatches.map { Pair(it.column, it.row) }

    searchMatches = matches
    currentMatchIndex = if (matches.isNotEmpty()) {
      // Scroll to and highlight the first match
      val (col, row) = matches[0]
      scrollToMatch(row)
      highlightMatch(col, row, searchQuery.length)
      0
    } else -1
  }

  // Trigger search on query change
  LaunchedEffect(searchQuery, searchCaseSensitive) {
    performSearch()
  }

  // Hyperlink state from tab
  var hoveredHyperlink by tab.hoveredHyperlink
  var cachedHyperlinks by remember { mutableStateOf<Map<Int, List<Hyperlink>>>(emptyMap()) }
  // Version hash for hyperlink caching - reuse cached hyperlinks when buffer unchanged
  var lastHyperlinkVersionHash by remember { mutableStateOf(0L) }
  // Track previous hover state for consumer callbacks
  var previousHoveredHyperlink by remember { mutableStateOf<Hyperlink?>(null) }

  // Configure terminal's key encoder based on settings
  LaunchedEffect(settings.shiftEnterBehavior, settings.altSendsEscape) {
    val shiftEnterNewline = settings.shiftEnterBehavior == "newline"
    terminal.setShiftEnterSendsNewline(shiftEnterNewline)
    terminal.setAltSendsEscape(settings.altSendsEscape)
  }

  // ProcessTerminalOutput is now defined in TabController
  // No longer needed here since terminal output routing is set up during tab initialization

  // Cursor state is now captured atomically with snapshot inside remember() block
  // This prevents flickering caused by cursor updates triggering separate recompositions
  // Type-ahead cursor override is handled separately in the Canvas

  // Blink state for SLOW_BLINK and RAPID_BLINK text attributes
  var slowBlinkVisible by remember { mutableStateOf(true) }
  var rapidBlinkVisible by remember { mutableStateOf(true) }

  // Whether blink-attributed text is actually present in the visible region. The
  // renderer writes this back each frame via `blinkProbe` (no extra scan), and the
  // blink toggle loops below gate on it: with no blinking text on screen, a focused
  // idle terminal produces zero periodic repaints instead of ~3 full-canvas redraws/sec.
  var hasSlowBlinkText by remember { mutableStateOf(false) }
  var hasRapidBlinkText by remember { mutableStateOf(false) }
  val blinkProbe = remember { BooleanArray(2) }

  // Drag state for text selection
  var isDragging by remember { mutableStateOf(false) }
  var dragStartPos by remember { mutableStateOf<Offset?>(null) }  // Track initial mouse position for drag detection
  var cachedDragSnapshot by remember { mutableStateOf<BufferSnapshot?>(null) }  // Cached snapshot for drag performance

  // Auto-scroll state for drag selection beyond bounds
  var autoScrollJob by remember { mutableStateOf<Job?>(null) }
  var lastDragPosition by remember { mutableStateOf<Offset?>(null) }
  var canvasSize by remember { mutableStateOf(Size.Zero) }

  // Accumulated scroll delta for smooth trackpad scrolling on Windows
  // Windows sends small fractional deltas that get truncated to 0 with toInt()
  var accumulatedScrollDelta by remember { mutableStateOf(0f) }

  // Multi-click tracking for double-click (word select) and triple-click (line select)
  var lastClickTime by remember { mutableStateOf(0L) }
  var lastClickPosition by remember { mutableStateOf(Offset.Zero) }
  var clickCount by remember { mutableIntStateOf(0) }

  // Drag threshold: pixels mouse must move before considering it a drag (not just a click)
  val DRAG_THRESHOLD = 5f
  // Multi-click thresholds
  val MULTI_CLICK_TIME_THRESHOLD = 500L  // milliseconds
  val MULTI_CLICK_DISTANCE_THRESHOLD = 10f  // pixels
  // Auto-scroll constants (matching Swing TerminalPanel.java:218)
  val AUTO_SCROLL_SPEED = 0.05f  // Scroll coefficient: faster when further from bounds
  val AUTO_SCROLL_INTERVAL = 50L  // 20 Hz scroll rate when outside bounds

  // Context menu controller
  val contextMenuController = remember { ContextMenuController() }

  // Helper functions for context menu actions
  fun clearBuffer() {
    scope.launch {
      tab.writeUserInput("clear\n")
    }
  }

  fun clearScrollback() {
    val buffer = terminal.terminalTextBuffer
    // clearHistory() is a write operation, still needs lock
    buffer.lock()
    try {
      buffer.clearHistory()
    } finally {
      buffer.unlock()
    }
    display.requestImmediateRedraw()
  }

  fun selectAll() {
    // Use snapshot for lock-free selection bounds calculation
    val snapshot = terminal.terminalTextBuffer.createSnapshot()
    // Select from start of history to end of screen
    selectionTracker.setSelection(
      startCol = 0,
      startRow = -snapshot.historyLinesCount,
      endCol = snapshot.width - 1,
      endRow = snapshot.height - 1,
      mode = selectionMode
    )
    display.requestImmediateRedraw()
  }

  // Auto-scroll function for drag selection beyond canvas bounds
  // Scrolls proportionally to distance from bounds while mouse is held outside
  // Note: cellWidthParam and cellHeightParam are passed because cellWidth/cellHeight are defined later in the composable
  fun startAutoScroll(position: Offset, cellWidthParam: Float, cellHeightParam: Float) {
    autoScrollJob?.cancel()
    autoScrollJob = scope.launch {
      while (isActive && isDragging) {
        val historySize = textBuffer.historyLinesCount
        val height = canvasSize.height

        // Calculate scroll amount based on distance from bounds
        val scrolled = when {
          position.y < 0 -> {
            // Dragging above canvas - scroll up into history
            val scrollDelta = (-position.y * AUTO_SCROLL_SPEED).toInt().coerceAtLeast(1)
            scrollOffset = (scrollOffset + scrollDelta).coerceIn(0, historySize)
            true
          }
          position.y > height -> {
            // Dragging below canvas - scroll down toward current
            val scrollDelta = ((position.y - height) * AUTO_SCROLL_SPEED).toInt().coerceAtLeast(1)
            scrollOffset = (scrollOffset - scrollDelta).coerceIn(0, historySize)
            true
          }
          else -> false  // Back in bounds
        }

        if (!scrolled) break  // Stop auto-scroll when back in bounds

        // Update selection end to track scroll position (using buffer-relative coordinates)
        lastDragPosition?.let { pos ->
          val visualCol = (pos.x / cellWidthParam).toInt().coerceIn(0, textBuffer.width - 1)
          val screenRow = when {
            pos.y < 0 -> 0  // Top of visible area
            pos.y > height -> ((height / cellHeightParam).toInt()).coerceAtMost(textBuffer.height - 1)
            else -> (pos.y / cellHeightParam).toInt()
          }
          val bufferRow = screenRow - scrollOffset  // Convert screen to buffer-relative row

          // Convert visual column to buffer column for grapheme-aware selection
          // Use cached snapshot for performance (created at drag start)
          val snapshot = cachedDragSnapshot ?: textBuffer.createSnapshot()
          val lineIndex = bufferRow + snapshot.historyLinesCount
          val bufferCol = if (lineIndex >= 0 && lineIndex < snapshot.height + snapshot.historyLinesCount) {
              val line = snapshot.getLine(lineIndex)
              if (line != null) {
                  TerminalCanvasRenderer.visualColToBufferCol(line, visualCol, snapshot.width)
              } else visualCol
          } else visualCol
          selectionTracker.updateEnd(bufferCol, bufferRow)
        }

        display.requestImmediateRedraw()
        delay(AUTO_SCROLL_INTERVAL)
      }
    }
  }

  // Detect macOS for keyboard shortcut handling (Cmd vs Ctrl)
  val isMacOS = remember { ShellCustomizationUtils.isMacOS() }

  // Create action registry with all built-in actions
  // Key on `tab` to ensure paste/write operations target the current tab
  val actionRegistry = remember(isMacOS, tab) {
    val registry = createBuiltinActions(
      selectionTracker = selectionTracker,
      selectionMode = object : MutableState<SelectionMode> {
        override var value: SelectionMode
          get() = selectionMode
          set(value) { selectionMode = value }
        override fun component1() = value
        override fun component2(): (SelectionMode) -> Unit = { selectionMode = it }
      },
      textBuffer = textBuffer,
      clipboardManager = clipboardManager,
      writeUserInput = tab::writeUserInput,
      pasteText = tab::pasteText,
      searchVisible = object : MutableState<Boolean> {
        override var value: Boolean
          get() = searchVisible
          set(value) { searchVisible = value }
        override fun component1() = value
        override fun component2(): (Boolean) -> Unit = { searchVisible = it }
      },
      debugPanelVisible = object : MutableState<Boolean> {
        override var value: Boolean
          get() = debugPanelVisible
          set(value) { debugPanelVisible = value }
        override fun component1() = value
        override fun component2(): (Boolean) -> Unit = { debugPanelVisible = it }
      },
      imeState = imeState,
      display = display,
      scope = scope,
      selectAllCallback = { selectAll() },
      isMacOS = isMacOS
    )

    // Add tab management shortcuts (Phase 5) - only if callbacks provided
    addTabManagementActions(
      registry = registry,
      onNewTab = onNewTab ?: {},
      onNewPreConnectTab = onNewPreConnectTab,
      onCloseTab = onCloseTab,
      onNextTab = onNextTab,
      onPreviousTab = onPreviousTab,
      onSwitchToTab = onSwitchToTab,
      onNewWindow = onNewWindow ?: {},
      isMacOS = isMacOS
    )

    // Add split pane management shortcuts - only if callbacks provided
    addSplitPaneActions(
      registry = registry,
      onSplitVertical = onSplitVertical ?: {},
      onSplitHorizontal = onSplitHorizontal ?: {},
      onClosePane = onClosePane,
      onMoveToNewTab = onMoveToNewTab,
      onNavigateUp = { onNavigatePane(NavigationDirection.UP) },
      onNavigateDown = { onNavigatePane(NavigationDirection.DOWN) },
      onNavigateLeft = { onNavigatePane(NavigationDirection.LEFT) },
      onNavigateRight = { onNavigatePane(NavigationDirection.RIGHT) },
      onNavigateNext = onNavigateNextPane,
      onNavigatePrevious = onNavigatePreviousPane,
      isMacOS = isMacOS
    )

    // Command-block actions (Phase 1). Enabled-gated by `commandBlocksEnabled`,
    // so they no-op and pass keys through when the feature is off.
    registry.registerAll(
      *ai.rever.bossterm.compose.blocks.CommandBlockActions.create(
        session = tab,
        clipboardManager = clipboardManager,
        isMacOS = isMacOS
      ).toTypedArray()
    )

    // Command palette (Phase 2). Cmd/Ctrl+Shift+P. Enabled-gated by
    // `commandPaletteEnabled`, so the hotkey passes through when disabled.
    registry.register(
      ai.rever.bossterm.compose.actions.TerminalAction(
        id = "command_palette",
        name = "Command Palette",
        keyStrokes = if (isMacOS) {
          listOf(ai.rever.bossterm.compose.actions.KeyStroke(key = Key.P, meta = true, shift = true))
        } else {
          listOf(ai.rever.bossterm.compose.actions.KeyStroke(key = Key.P, ctrl = true, shift = true))
        },
        enabled = { SettingsManager.instance.settings.value.commandPaletteEnabled },
        handler = {
          commandPaletteVisible = true
          true
        }
      )
    )

    // History search (Phase 4). Ctrl+R. Enabled-gated by `historySearchEnabled`
    // and ignored while a full-screen app owns the alternate screen, so vim/less
    // keep their own Ctrl+R.
    registry.register(
      ai.rever.bossterm.compose.actions.TerminalAction(
        id = "history_search",
        name = "Search History",
        keyStrokes = listOf(ai.rever.bossterm.compose.actions.KeyStroke(key = Key.R, ctrl = true)),
        enabled = {
          SettingsManager.instance.settings.value.historySearchEnabled &&
            !textBuffer.isUsingAlternateBuffer
        },
        handler = {
          historySearchVisible = true
          true
        }
      )
    )

    registry
  }

  // Wire up menu actions to the action registry
  LaunchedEffect(menuActions, actionRegistry, tab, scope) {
    menuActions?.apply {
      onCopy = { actionRegistry.getAction("copy")?.executeFromMenu() }
      onPaste = { actionRegistry.getAction("paste")?.executeFromMenu() }
      onSelectAll = { actionRegistry.getAction("select_all")?.executeFromMenu() }
      onClear = {
        // Clear screen by sending 'clear' command (same as context menu)
        scope.launch {
          tab.writeUserInput("clear\n")
        }
      }
      onFind = { actionRegistry.getAction("search")?.executeFromMenu() }
      onToggleDebug = { actionRegistry.getAction("debug_panel")?.executeFromMenu() }
      onBlockJumpPrev = { actionRegistry.getAction("block_jump_prev")?.executeFromMenu() }
      onBlockJumpNext = { actionRegistry.getAction("block_jump_next")?.executeFromMenu() }
      onBlockCopyOutput = { actionRegistry.getAction("block_copy_last_output")?.executeFromMenu() }
      onBlockRerun = { actionRegistry.getAction("block_rerun_last")?.executeFromMenu() }
    }
  }

  // Cursor blink state for BLINK_* cursor shapes
  var cursorBlinkVisible by remember { mutableStateOf(true) }

  // Use shared font loaded once by Main.kt (performance optimization for multiple tabs)
  // Font loading is expensive and should only happen once, not per tab

  // Per-tab override (remote mirrors fitting an oversized host grid) wins over the
  // global settings font. The renderer's measurement cache is keyed by font size, so
  // tabs at different sizes coexist safely.
  val effectiveFontSize =
    (tab as? ai.rever.bossterm.compose.tabs.TerminalTab)?.fontSizeOverride?.value ?: settings.fontSize

  val measurementStyle = remember(sharedFont, effectiveFontSize, density) {
    TextStyle(
      fontFamily = sharedFont,
      fontSize = effectiveFontSize.sp,
      fontWeight = FontWeight.Normal
    )
  }

  // Invalidate measurement cache when font, size, or display density changes (issue #147, #206)
  LaunchedEffect(sharedFont, effectiveFontSize, density) {
    ai.rever.bossterm.compose.rendering.TerminalCanvasRenderer.invalidateMeasurementCache()
  }

  // Cache cell dimensions and baseline offset (recalculated when font/size/density changes)
  // Measure a string of 100 characters to get accurate average advance width
  // This prevents cumulative rounding errors when rendering long lines
  val cellMetrics = remember(measurementStyle, density) {
    val sampleString = "W".repeat(100)  // 100 characters for precise averaging
    val measurement = textMeasurer.measure(sampleString, measurementStyle)
    val width = measurement.size.width.toFloat() / sampleString.length
    val singleMeasurement = textMeasurer.measure("W", measurementStyle)
    val height = singleMeasurement.size.height.toFloat()
    // Get baseline offset from top of text bounds
    val baseline = singleMeasurement.firstBaseline
    Triple(width, height, baseline)
  }
  val cellWidth = cellMetrics.first
  val baseCellHeight = cellMetrics.second  // Raw height without line spacing

  // Calculate effective line spacing based on settings and alternate buffer state
  val isUsingAlternateBuffer = textBuffer.isUsingAlternateBuffer
  val effectiveLineSpacing = if (settings.disableLineSpacingInAlternateBuffer && isUsingAlternateBuffer) {
    1.0f  // No line spacing in alternate buffer
  } else {
    settings.lineSpacing
  }

  // Apply line spacing to cell height
  val cellHeight = baseCellHeight * effectiveLineSpacing

  // Calculate line spacing gap (extra space added by line spacing)
  val lineSpacingGap = cellHeight - baseCellHeight

  // Update terminal with actual cell dimensions for accurate image placement.
  // Cell metrics are DEVICE px; the display scale lets image sizing interpret
  // intrinsic/px-spec image dimensions as logical px (half-size otherwise on 2x).
  LaunchedEffect(cellWidth, cellHeight, density) {
    terminal.setDisplayScale(density.density)
    terminal.setCellDimensions(cellWidth, cellHeight)
  }

  // Window focus + tab visibility gate every blink animation. The Canvas draw lambda
  // reads cursorBlinkVisible / slowBlinkVisible / rapidBlinkVisible, so each toggle
  // repaints the ENTIRE terminal canvas. Letting those loops run while the window is in
  // the background — or for a tab that isn't the visible one — wakes the CPU/GPU twice a
  // second forever for no visible benefit (a major battery drain). Gate them so an idle
  // or backgrounded terminal produces zero periodic repaints, matching iTerm2/Terminal.app
  // (the cursor stops blinking when the app isn't frontmost). When a loop is parked we
  // freeze its flag to "visible" so the cursor shows solid and blink-attributed text stays
  // readable; the renderer still draws the unfocused cursor style from `isFocused`.
  val blinkActive = isActiveTab && LocalWindowInfo.current.isWindowFocused

  // SLOW_BLINK animation timer (configurable via settings.slowTextBlinkMs).
  // enableTextBlinking is the master accessibility toggle. A non-positive interval means
  // "no blink" (also guards against a delay(0) busy-loop pegging a core).
  // Also gate on hasSlowBlinkText: if nothing on screen carries the SLOW_BLINK
  // attribute, there is nothing to animate, so park the loop (and freeze the flag
  // to "visible") instead of repainting the whole text canvas once a second for
  // no visible change.
  LaunchedEffect(blinkActive, hasSlowBlinkText, settings.enableTextBlinking, settings.slowTextBlinkMs) {
    if (!blinkActive || !hasSlowBlinkText || !settings.enableTextBlinking || settings.slowTextBlinkMs <= 0) {
      slowBlinkVisible = true
      return@LaunchedEffect
    }
    while (isActive) {
      delay(settings.slowTextBlinkMs.toLong())
      slowBlinkVisible = !slowBlinkVisible
    }
  }

  // RAPID_BLINK animation timer (configurable via settings.rapidTextBlinkMs).
  LaunchedEffect(blinkActive, hasRapidBlinkText, settings.enableTextBlinking, settings.rapidTextBlinkMs) {
    if (!blinkActive || !hasRapidBlinkText || !settings.enableTextBlinking || settings.rapidTextBlinkMs <= 0) {
      rapidBlinkVisible = true
      return@LaunchedEffect
    }
    while (isActive) {
      delay(settings.rapidTextBlinkMs.toLong())
      rapidBlinkVisible = !rapidBlinkVisible
    }
  }

  // Cursor blink animation timer (configurable via settings.caretBlinkMs).
  // caretBlinkMs <= 0 means "solid cursor, never blink" (the settings slider exposes 0 as
  // "Off"); the old code turned that into delay(0) in a tight loop, which pegged a CPU
  // core instead of disabling the blink. The guard now makes "Off" actually off.
  LaunchedEffect(blinkActive, settings.caretBlinkMs) {
    if (!blinkActive || settings.caretBlinkMs <= 0) {
      cursorBlinkVisible = true
      return@LaunchedEffect
    }
    while (isActive) {
      delay(settings.caretBlinkMs.toLong())
      cursorBlinkVisible = !cursorBlinkVisible
    }
  }

  // PTY initialization and process monitoring are now handled by TabController
  // ProperTerminal only handles rendering and user interaction

  // Request focus when tab becomes active or changes
  // Use tab.id as key so effect re-triggers when switching between tabs
  // Also trigger on isActiveTab to handle focus after tab close
  LaunchedEffect(tab.id, isActiveTab) {
    if (isActiveTab) {
      delay(50)
      focusRequester.requestFocus()
    }
  }

  // AutoFocus: Request focus after a longer delay (for dialogs/embedded terminals)
  // This ensures focus is requested after the parent container is fully composed
  LaunchedEffect(autoFocus) {
    if (autoFocus) {
      delay(200)  // Longer delay to ensure dialog is fully ready
      focusRequester.requestFocus()
    }
  }

  // Resize PTY when it becomes available
  // This fixes the initial size issue: onGloballyPositioned fires and resizes the terminal buffer,
  // but processHandle is NULL at that point. When the PTY connects, we need to sync its size.
  LaunchedEffect(processHandle) {
    processHandle?.let { handle ->
      handle.resize(textBuffer.width, textBuffer.height)
    }
  }

  // Observe icon title changes from OSC 1 sequence for tab labels (XTerm standard)
  // Icon title (OSC 1) is used for tab labels in modern terminals
  // Window title (OSC 2) is used for the main window title bar
  // Using reactive Flow instead of polling for immediate updates and better resource efficiency
  //
  // Keyed by tab.id (NOT Unit): TabbedTerminal renders only the active tab and
  // reuses this ProperTerminal composition across tab switches. With a Unit key
  // the collector never relaunched, so after switching tabs it kept the FIRST
  // tab's onTabTitleChange closure — routing OSC-1 titles (e.g. "claude") onto
  // the wrong tab. Re-keying rebinds the flow and the callback together.
  LaunchedEffect(tab.id) {
    display.iconTitleFlow.collect { newTitle ->
      if (newTitle.isNotEmpty()) {
        onTabTitleChange(newTitle)
      }
    }
  }

  // Bell (BEL character) handling - play sound and/or visual flash
  var visualBellActive by remember { mutableStateOf(false) }
  val bellTrigger by display.bellTrigger
  LaunchedEffect(bellTrigger) {
    if (bellTrigger > 0) {
      // Play audible bell if enabled
      if (settings.audibleBell) {
        java.awt.Toolkit.getDefaultToolkit().beep()
      }
      // Visual bell - flash screen if enabled
      if (settings.visualBell) {
        visualBellActive = true
        delay(100)  // Flash duration
        visualBellActive = false
      }
    }
  }

  // Scrollbar adapter that bridges terminal scroll state with Compose scrollbar
  // Note: historySize and screenHeight lambdas read textBuffer properties without locks.
  // This is acceptable because:
  // 1. Reads are atomic (single int reads)
  // 2. Values change rarely (only on resize or history growth)
  // 3. Worst case: scrollbar shows slightly stale values for 1 frame
  // 4. Creating snapshots here would degrade scrolling performance
  val scrollbarAdapter = rememberTerminalScrollbarAdapter(
    terminalScrollOffset = rememberUpdatedState(scrollOffset),
    historySize = {
      textBuffer.historyLinesCount
    },
    screenHeight = { textBuffer.height },
    cellHeight = { cellHeight },
    onScroll = { newOffset ->
      scrollOffset = newOffset
      display.requestImmediateRedraw() // Immediate redraw for responsive scrolling
    }
  )

  /**
   * Mouse reporting decision logic (Issue #20)
   *
   * Determines if mouse event should be forwarded to terminal application (vim, tmux, etc.)
   * or handled locally (selection, scrolling).
   *
   * Key behavior:
   * - Shift+Click ALWAYS forces local action, even when app has mouse mode
   * - Without Shift: respects application's mouse mode settings
   *
   * @return true when mouse event should be forwarded to terminal app
   */
  fun isRemoteMouseAction(shiftPressed: Boolean): Boolean {
    return settings.enableMouseReporting && display.isMouseReporting() && !shiftPressed
  }

  /**
   * Convert pixel position to character cell coordinates (0-based).
   * Clamps coordinates to valid terminal bounds.
   *
   * Note: Reads textBuffer.width/height without locks (acceptable for mouse coordinate conversion).
   * Atomic reads, worst case: mouse maps to slightly wrong cell for 1 frame during resize.
   *
   * @param position Offset in pixels from top-left corner
   * @return Pair of (col, row) in 0-based character coordinates
   */
  fun pixelToCharCoords(position: Offset): Pair<Int, Int> {
    val col = (position.x / cellWidth).toInt().coerceIn(0, textBuffer.width - 1)
    val row = (position.y / cellHeight).toInt().coerceIn(0, textBuffer.height - 1)
    return Pair(col, row)
  }

  /**
   * Drag-and-drop target for file path pasting (like iTerm2).
   * When files are dropped on the terminal, their paths are pasted with shell escaping.
   */
  val dropTarget = remember(tab) {
    object : DragAndDropTarget {
      override fun onDrop(event: DragAndDropEvent): Boolean {
        val transferable = event.awtTransferable
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
          @Suppress("UNCHECKED_CAST")
          val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
          val paths = files.joinToString(" ") { file ->
            escapePathForShell(file.absolutePath)
          }
          if (paths.isNotEmpty()) {
            tab.pasteText(paths)
          }
          return true
        }
        return false
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .onPreviewKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown) {
          // The host user is interacting with this pane — release any remote
          // "fit host to my screen" resize (no-op unless a fit is active here).
          ai.rever.bossterm.compose.share.SessionShareManager.notifyHostInteraction(tab.id)
        }
        // Handle Cmd+F toggle at the top level so it works regardless of which child has focus
        if (keyEvent.type == KeyEventType.KeyDown) {
          val action = actionRegistry.getAction("search")
          if (action != null && action.matchesKeyEvent(keyEvent, isMacOS)) {
            action.execute(keyEvent)
            // When closing search, restore focus to terminal
            if (!searchVisible) {
              scope.launch {
                kotlinx.coroutines.delay(50)
                focusRequester.requestFocus()
              }
            }
            return@onPreviewKeyEvent true
          }
        }
        false
      }
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned { coordinates ->
          // Remote mirror tabs are sized by the host (PaneResize), so we don't auto-fit them to
          // the local canvas — that would fight the host's grid. We only RECORD the grid that
          // would fit our canvas, so the explicit "Fit to host" / "Fit to client" menu actions
          // can use it (resize the host to us, or resize our window to the host's grid).
          if (tab.isRemote) {
            (tab as? ai.rever.bossterm.compose.tabs.TerminalTab)?.let { t ->
              if (cellWidth > 0f && cellHeight > 0f) {
                val w = coordinates.size.width - 4
                val h = coordinates.size.height - 4
                if (w >= 10 && h >= 10) {
                  val fitCols = (w / cellWidth).toInt().coerceAtLeast(2)
                  val fitRows = (h / cellHeight).toInt().coerceAtLeast(2)
                  val changed = t.remoteFitCols != fitCols || t.remoteFitRows != fitRows
                  t.remoteFitCols = fitCols
                  t.remoteFitRows = fitRows
                  // Daemon-attached tab: the GUI is the real display, so drive the daemon session's
                  // size from our canvas (ordinary mirrors leave onRemoteFit null → host stays
                  // authoritative).
                  if (changed) t.onRemoteFit?.invoke(fitCols, fitRows)
                }
              }
            }
            return@onGloballyPositioned
          }
          // Detect window size changes and resize terminal accordingly
          // Note: This fires frequently, but we validate dimensions carefully to prevent crashes
          // Account for Canvas padding (4dp start, 4dp top) - on desktop 1dp ≈ 1px
          val canvasPadding = 4
          val newWidth = coordinates.size.width - canvasPadding
          val newHeight = coordinates.size.height - canvasPadding

          // Ensure we have valid dimensions (minimum 10x10 pixels to prevent crashes)
          if (newWidth >= 10 && newHeight >= 10 && cellWidth > 0f && cellHeight > 0f) {
            // Use floor division to ensure we don't calculate more rows than actually fit
            val newCols = (newWidth / cellWidth).toInt().coerceAtLeast(5)
            val newRows = (newHeight / cellHeight).toInt().coerceAtLeast(2)
            val currentCols = textBuffer.width
            val currentRows = textBuffer.height

            // Resize on first render OR when dimensions change (ensures PTY gets correct size on startup).
            // Tiny panes are valid; geometry-dependent MCP content separately waits
            // for consecutive stable samples so a transient first pass is not trusted.
            if (!hasPerformedInitialResize || currentCols != newCols || currentRows != newRows) {
              val newTermSize = TermSize(newCols, newRows)
              // Resize terminal buffer and notify PTY process (sends SIGWINCH)
              terminal.resize(newTermSize, RequestOrigin.User)
              // Clear type-ahead predictions on resize (terminal state is no longer predictable)
              tab.typeAheadManager?.onResize()
              // Reset scroll to bottom on resize - history size may have changed, making old offset invalid
              // This ensures the user sees the current screen content after resize
              scrollOffset = 0
              // Also notify the process handle if available (must be launched in coroutine)
              scope.launch {
                processHandle?.resize(newCols, newRows)
              }
              // Force redraw with new buffer dimensions (critical for initial size)
              display.requestImmediateRedraw()
              hasPerformedInitialResize = true
            }
            // Layout latch for MCP show_image (issue #324): this terminal has now
            // been measured by a layout pass — real cell metrics pushed
            // explicitly (the LaunchedEffect that normally pushes them has no
            // ordering guarantee vs. this callback), grid resize applied above
            // if needed. Keyed on the TERMINAL's own latch, NOT on
            // hasPerformedInitialResize: this composition is reused across tab
            // switches, so the composition-scoped flag is already true when a
            // freshly created tab's session first lands here. Geometry-dependent
            // paths use the separate grid-stability gate below before trusting
            // the measured dimensions.
            if (!terminal.isUiLayoutReady) {
              terminal.setDisplayScale(density.density)
              terminal.setCellDimensions(cellWidth, cellHeight)
              terminal.markUiLayoutReady()
            }
            // Trust small grids only after they remain unchanged long enough to
            // outlive the transient first layout pass. This also covers show_image
            // calls that reuse an existing tiny pane and therefore do not run the
            // freshly-created-pane readiness gate in BossTermMcpServer.
            gridStabilityJob?.cancel()
            gridStabilityJob = scope.launch {
              kotlinx.coroutines.delay(180)
              if (textBuffer.width == newCols && textBuffer.height == newRows) {
                terminal.markCurrentGridStable()
              }
            }
          }
        }
        .fillMaxSize()
        .background(settings.defaultBackgroundColorWithOpacity)
        .dragAndDropTarget(
          shouldStartDragAndDrop = { true },
          target = dropTarget
        )
        .onPointerEvent(PointerEventType.Press) { event ->
          val change = event.changes.first()
          // Skip if event was already consumed by an overlay (SearchBar, DebugPanel, etc.)
          if (change.isConsumed) return@onPointerEvent
          if (change.pressed && change.previousPressed.not()) {
            // Host user clicked into this pane — release any remote "fit host"
            // resize (no-op unless a fit is active here).
            ai.rever.bossterm.compose.share.SessionShareManager.notifyHostInteraction(tab.id)
            // FIRST: Set pane focus before any other processing
            // This ensures split pane focus is set even when mouse events are
            // forwarded to terminal applications (like nvim with mouse reporting)
            onPaneFocus()

            // Restore keyboard focus on every press. Required when the press is
            // forwarded to a mouse-reporting TUI (claude, vim, htop, nvim, ...)
            // via the early-return below and the previous focus owner was a
            // sibling Compose element (e.g. a Button in an embedder's sidebar).
            // Without this the cursor looks focused but PTY input is dropped.
            // Mirrors the delay(50) pattern at the LaunchedEffect above and the
            // search-close handler — lets recomposition / focus release settle
            // before requesting. Idempotent with the requestFocus() calls in
            // the click branches further down.
            scope.launch {
              delay(50)
              focusRequester.requestFocus()
            }

            // Check if mouse event should be forwarded to terminal application
            // NOTE: Exclude right-click (Secondary) from forwarding so context menu always works
            // NOTE: Also bypass when Cmd/Ctrl is held — the user is interacting with a
            // hyperlink (open via line ~1042) or otherwise driving the BossTerm UI, not
            // the TUI. Symmetric with the Move-handler bypass and how shift bypasses
            // for text selection. Without this, Cmd+click on a URL in mouse-reporting
            // TUIs (claude, vim with mouse=a, ...) gets eaten by the TUI. Modifier is
            // read straight off the AWT event (focus-independent) rather than the
            // canvas-focus-gated isModifierPressed flag.
            val shiftPressed = event.isShiftPressed()
            val cmdOrCtrlHeld = event.isCtrlOrMetaPressed()
            // Reset eagerly; flip true below only if we actually forward this press.
            // Ensures Release pairs with the correct Press decision.
            lastPressForwardedToTui = false
            if (settings.enableMouseReporting && isRemoteMouseAction(shiftPressed) && event.button != PointerButton.Secondary && !cmdOrCtrlHeld) {
              // If button is null, skip remote forwarding and fall through to local handling
              // Button can be null for touch events, stylus input, or exotic input devices
              event.button?.let { button ->
                val (col, row) = pixelToCharCoords(change.position)
                val mouseEvent = createComposeMouseEvent(event, button)
                terminal.mousePressed(col, row, mouseEvent)
                lastPressForwardedToTui = true
                change.consume()
                return@onPointerEvent
              } ?: run {
                if (settings.debugModeEnabled) {
                  println("Mouse press with null button at (${change.position.x}, ${change.position.y}) - handling locally")
                }
              }
            }

            // Check for right-click (secondary button) - always handle locally for context menu
            if (event.button == PointerButton.Secondary) {
              // Capture values needed for showing menu
              val pos = change.position
              val currentHoveredHyperlink = hoveredHyperlink

              // Helper to open folder picker and cd to selected folder
              fun openFolderPicker() {
                // Get current working directory before switching threads
                val cwd = tab.workingDirectory.value

                // Run dialog on AWT Event Dispatch Thread to avoid Compose reentry issues
                javax.swing.SwingUtilities.invokeLater {
                  val fileChooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Open Folder"
                    approveButtonText = "Select"
                    // Start from current working directory if available
                    cwd?.let { currentDirectory = File(it) }
                  }
                  val result = fileChooser.showOpenDialog(null)
                  if (result == JFileChooser.APPROVE_OPTION) {
                    val selectedFolder = fileChooser.selectedFile
                    if (selectedFolder != null && selectedFolder.isDirectory) {
                      // Escape path for shell (handle spaces and special characters)
                      val escapedPath = selectedFolder.absolutePath
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("$", "\\$")
                        .replace("`", "\\`")
                      // Send cd command followed by ls to show folder contents
                      tab.writeUserInput("cd \"$escapedPath\" && ls\n")
                    }
                  }
                }
              }

              // Helper to show the context menu (called after async callback if provided)
              fun doShowContextMenu() {
                // Get fresh items from provider if available, otherwise use static list
                // This is called AFTER onContextMenuOpenAsync completes, so provider can return fresh data
                val baseCustomItems = customContextMenuItemsProvider?.invoke() ?: customContextMenuItems
                // Append command-block actions (only when enabled and blocks exist).
                val blockMenuItems: List<ai.rever.bossterm.compose.ContextMenuElement> =
                  if (settings.commandBlocksEnabled &&
                      tab.commandBlockTracker?.blocks?.value?.isNotEmpty() == true) {
                    listOf(
                      ai.rever.bossterm.compose.ContextMenuSection("cmd_blocks_section", "Command Block"),
                      ai.rever.bossterm.compose.ContextMenuItem("block_copy_output", "Copy Last Command Output") {
                        actionRegistry.getAction("block_copy_last_output")?.executeFromMenu()
                      },
                      ai.rever.bossterm.compose.ContextMenuItem("block_copy_command", "Copy Last Command") {
                        actionRegistry.getAction("block_copy_last_command")?.executeFromMenu()
                      },
                      ai.rever.bossterm.compose.ContextMenuItem("block_rerun", "Re-run Last Command") {
                        actionRegistry.getAction("block_rerun_last")?.executeFromMenu()
                      },
                    )
                  } else {
                    emptyList()
                  }
                val effectiveCustomItems = baseCustomItems + blockMenuItems

                // Check if we're hovering over a hyperlink
                if (currentHoveredHyperlink != null) {
                  val link = currentHoveredHyperlink
                  showHyperlinkContextMenu(
                    controller = contextMenuController,
                    x = pos.x,
                    y = pos.y,
                    url = link.url,
                    onOpenLink = {
                      val info = link.toHyperlinkInfo()
                      val handled = onLinkClick?.invoke(info) ?: false
                      if (!handled) {
                        HyperlinkDetector.openUrl(link.url)
                      }
                    },
                    onCopyLinkAddress = { clipboardManager.setText(AnnotatedString(link.url)) },
                    hasSelection = selectionTracker.hasSelection(),
                    onCopy = {
                      val snapshot = textBuffer.createIncrementalSnapshot()
                      val resolved = selectionTracker.resolveToCoordinates(snapshot)
                      if (resolved != null) {
                        val selectedText = SelectionEngine.extractSelectedTextTrimmed(
                          textBuffer, resolved.toStartPair(), resolved.toEndPair(), resolved.mode
                        )
                        if (selectedText.isNotEmpty()) {
                          clipboardManager.setText(AnnotatedString(selectedText))
                        }
                      }
                    },
                    onPaste = {
                      try {
                        val text = clipboardManager.getText()?.text
                        if (!text.isNullOrEmpty()) {
                          scope.launch {
                            try {
                              tab.pasteText(text)
                            } catch (e: Exception) {
                              println("ERROR: Failed to paste text via context menu: ${e.message}")
                            }
                          }
                        }
                      } catch (e: Exception) {
                        println("ERROR: Failed to access clipboard: ${e.message}")
                      }
                    },
                    onSelectAll = { selectAll() },
                    onClearScreen = { clearBuffer() },
                    onClearScrollback = { clearScrollback() },
                    onFind = { searchVisible = true },
                    onOpenFolder = { openFolderPicker() },
                    onNewTab = onNewTab,
                    onSwitchShell = onSwitchShell,
                    onSplitVertical = onSplitVertical,
                    onSplitHorizontal = onSplitHorizontal,
                    onMoveToNewTab = onMoveToNewTab,
                    onClosePane = if (onMoveToNewTab != null) onClosePane else null,
                    onShowDebug = if (enableDebugPanel && settings.debugModeEnabled) {
                      { debugPanelVisible = !debugPanelVisible }
                    } else null,
                    onShowSettings = onShowSettings,
                    onShowWelcomeWizard = onShowWelcomeWizard,
                    customItems = effectiveCustomItems
                  )
                } else {
                  showTerminalContextMenu(
                    controller = contextMenuController,
                    x = pos.x,
                    y = pos.y,
                    hasSelection = selectionTracker.hasSelection(),
                    onCopy = {
                      val snapshot = textBuffer.createIncrementalSnapshot()
                      val resolved = selectionTracker.resolveToCoordinates(snapshot)
                      if (resolved != null) {
                        val selectedText = SelectionEngine.extractSelectedTextTrimmed(
                          textBuffer, resolved.toStartPair(), resolved.toEndPair(), resolved.mode
                        )
                        if (selectedText.isNotEmpty()) {
                          clipboardManager.setText(AnnotatedString(selectedText))
                        }
                      }
                    },
                    onPaste = {
                      try {
                        val text = clipboardManager.getText()?.text
                        if (!text.isNullOrEmpty()) {
                          scope.launch {
                            try {
                              tab.pasteText(text)
                            } catch (e: Exception) {
                              println("ERROR: Failed to paste text via context menu: ${e.message}")
                            }
                          }
                        }
                      } catch (e: Exception) {
                        println("ERROR: Failed to access clipboard: ${e.message}")
                      }
                    },
                    onSelectAll = { selectAll() },
                    onClearScreen = { clearBuffer() },
                    onClearScrollback = { clearScrollback() },
                    onFind = { searchVisible = true },
                    onOpenFolder = { openFolderPicker() },
                    onNewTab = onNewTab,
                    onSwitchShell = onSwitchShell,
                    onSplitVertical = onSplitVertical,
                    onSplitHorizontal = onSplitHorizontal,
                    onMoveToNewTab = onMoveToNewTab,
                    onClosePane = if (onMoveToNewTab != null) onClosePane else null,
                    onShowDebug = if (enableDebugPanel && settings.debugModeEnabled) {
                      { debugPanelVisible = !debugPanelVisible }
                    } else null,
                    onShowSettings = onShowSettings,
                    onShowWelcomeWizard = onShowWelcomeWizard,
                    customItems = effectiveCustomItems
                  )
                }
              }

              // If async callback provided, wait for it to complete before showing menu
              if (onContextMenuOpenAsync != null) {
                scope.launch {
                  onContextMenuOpenAsync.invoke()
                  doShowContextMenu()
                }
              } else {
                // Legacy sync callback - invoke and show menu immediately
                onContextMenuOpen?.invoke()
                doShowContextMenu()
              }

              change.consume()
              return@onPointerEvent
            }

            // Check for middle-click paste (tertiary button)
            if (event.button == PointerButton.Tertiary && settings.pasteOnMiddleClick) {
              val text = if (settings.emulateX11CopyPaste) {
                // X11 mode: Paste from selection clipboard (middle-click buffer)
                selectionClipboard
              } else {
                // Normal mode: Paste from system clipboard
                clipboardManager.getText()?.text
              }
              if (!text.isNullOrEmpty() && processHandle != null) {
                scope.launch {
                  try {
                    tab.pasteText(text)
                  } catch (e: Exception) {
                    println("ERROR: Failed to paste text via middle-click: ${e.message}")
                    e.printStackTrace()
                  }
                }
                // Ensure focus is on terminal canvas after middle-click paste (for split view)
                focusRequester.requestFocus()
                change.consume()
                return@onPointerEvent
              }
            }

            // Check for hyperlink click with Ctrl/Cmd modifier
            // Standard terminal behavior: Ctrl+Click (Windows/Linux) or Cmd+Click (macOS)
            if (hoveredHyperlink != null && isModifierPressed) {
              val link = hoveredHyperlink!!
              val info = link.toHyperlinkInfo()
              val handled = onLinkClick?.invoke(info) ?: false
              if (!handled) {
                HyperlinkDetector.openUrl(link.url)
              }
              // Opening the URL hands focus to the browser, so the canvas never
              // sees the Cmd/Ctrl KeyUp — isModifierPressed would stay true and
              // the renderer would keep the link underlined indefinitely. Clear
              // both flags and force a redraw so the underline drops now; the
              // next Move event after the user re-engages will reset them
              // naturally based on the actual cursor + modifier state.
              isModifierPressed = false
              hoveredHyperlink = null
              display.requestImmediateRedraw()
              change.consume()
              return@onPointerEvent
            }

            // Multi-click detection for word/line selection
            // Track click count based on time and position deltas
            val currentTime = System.currentTimeMillis()
            val currentPosition = change.position
            val timeDelta = currentTime - lastClickTime
            val dx = currentPosition.x - lastClickPosition.x
            val dy = currentPosition.y - lastClickPosition.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

            // Determine if this is a multi-click or a new click sequence
            if (timeDelta < MULTI_CLICK_TIME_THRESHOLD && distance < MULTI_CLICK_DISTANCE_THRESHOLD) {
              clickCount++
            } else {
              clickCount = 1
            }

            lastClickTime = currentTime
            lastClickPosition = currentPosition

            // Track start position but don't create selection yet
            // This allows distinguishing click (no selection) from drag (selection)
            dragStartPos = change.position

            // Branch on click count for different selection modes
            when (clickCount) {
              1 -> {
                // Single click: Clear selection on LEFT-CLICK only (not right-click)
                // Also preserve selection during search navigation
                if (event.button != androidx.compose.ui.input.pointer.PointerButton.Secondary && !searchVisible) {
                  selectionTracker.clearSelection()
                }
                isDragging = false
                cachedDragSnapshot = null  // Clear cached snapshot
                // Ensure focus is on terminal canvas after click
                focusRequester.requestFocus()
              }
              2 -> {
                // Double-click: Smart word selection (URLs, paths, quoted strings)
                // Convert screen coords to buffer-relative for selection
                val (col, screenRow) = pixelToCharCoords(currentPosition)
                val bufferRow = screenRow - scrollOffset
                val (start, end) = SelectionEngine.selectWordAtSmart(col, bufferRow, textBuffer)
                selectionTracker.setSelection(start.first, start.second, end.first, end.second, selectionMode)
                isDragging = false
                cachedDragSnapshot = null  // Clear cached snapshot

                // Clear search when user manually selects text
                if (searchVisible) {
                  searchVisible = false
                  searchQuery = ""
                  searchMatches = emptyList()
                }
                // Ensure focus is on terminal canvas after click
                focusRequester.requestFocus()
              }
              else -> {
                // Triple-click (or more): Select entire logical line (with wrapped lines)
                // Uses expandToLogicalLine which also trims trailing whitespace
                val (col, screenRow) = pixelToCharCoords(currentPosition)
                val bufferRow = screenRow - scrollOffset
                val (start, end) = SelectionEngine.expandToLogicalLine(bufferRow, textBuffer)
                selectionTracker.setSelection(start.first, start.second, end.first, end.second, selectionMode)
                isDragging = false
                cachedDragSnapshot = null  // Clear cached snapshot

                // Clear search when user manually selects text
                if (searchVisible) {
                  searchVisible = false
                  searchQuery = ""
                  searchMatches = emptyList()
                }
                // Ensure focus is on terminal canvas after click
                focusRequester.requestFocus()
              }
            }

            // Phase 2: Immediate redraw for mouse input
            display.requestImmediateRedraw()
          }
        }
        .onPointerEvent(PointerEventType.Move) { event ->
          val change = event.changes.first()
          // Skip if event was already consumed by an overlay
          if (change.isConsumed) return@onPointerEvent
          val pos = change.position
          val startPos = dragStartPos

          // Check if mouse event should be forwarded to terminal application.
          // When the user is holding Cmd/Ctrl for hyperlink interaction, bypass
          // the mouse-reporting forward (same as shift bypasses for selection)
          // so the hover-detection block below can set hoveredHyperlink and the
          // renderer can draw the link underline. Press/Release apply the same
          // bypass — see those handlers. Scroll keeps current forwarding so
          // Ctrl+wheel (TUI font zoom / pager input) still reaches the TUI.
          //
          // Drag (button held): mirror the Press decision via lastPressForwardedToTui
          // so toggling Cmd mid-drag does not strand half a sequence at the TUI.
          // Pure motion (no button): each event is independent — read the modifier
          // straight off the AWT event (focus-independent).
          val shiftPressed = event.isShiftPressed()
          val forwardThisMove = if (change.pressed) {
            lastPressForwardedToTui
          } else {
            !event.isCtrlOrMetaPressed()
          }
          if (settings.enableMouseReporting && isRemoteMouseAction(shiftPressed) && forwardThisMove) {
            val (col, row) = pixelToCharCoords(pos)
            if (change.pressed) {
              // Button is held - this is a drag event (BUTTON_MOTION or ALL_MOTION modes)
              event.button?.let { button ->
                val mouseEvent = createComposeMouseEvent(event, button)
                terminal.mouseDragged(col, row, mouseEvent)
              }
            } else if (display.mouseMode.value == MouseMode.MOUSE_REPORTING_ALL_MOTION) {
              // No button pressed - pure move event, only sent in ALL_MOTION mode
              // Check mode before sending to avoid excessive events in other modes
              val mouseEvent = createMouseEvent(MouseButtonCodes.NONE, event.toMouseModifierFlags())
              terminal.mouseMoved(col, row, mouseEvent)
            }
            change.consume()
            return@onPointerEvent
          }

          // Check for hyperlink hover
          val col = (pos.x / cellWidth).toInt()
          val row = (pos.y / cellHeight).toInt() + scrollOffset
          val absoluteRow = row - scrollOffset

          // Get hyperlinks for this row (supports multi-row hyperlinks)
          val hyperlinksForRow = cachedHyperlinks[absoluteRow]
          hoveredHyperlink = hyperlinksForRow?.firstOrNull { link ->
            link.containsPosition(col, absoluteRow)
          }

          // Notify hover consumers when hyperlink hover state changes
          if (hoveredHyperlink != previousHoveredHyperlink) {
            // Exit callback for previous hyperlink - notify all consumers
            if (previousHoveredHyperlink != null) {
              tab.hoverConsumers.forEach { it.onMouseExited() }
            }
            // Enter callback for new hyperlink with bounds - notify all consumers
            if (hoveredHyperlink != null) {
              val link = hoveredHyperlink!!
              // For multi-row hyperlinks, provide bounds for the current row's span
              val span = link.rowSpans[absoluteRow] ?: Pair(link.startCol, link.endCol)
              val bounds = Rect(
                left = span.first * cellWidth,
                top = (absoluteRow) * cellHeight,
                right = span.second * cellWidth,
                bottom = (absoluteRow + 1) * cellHeight
              )
              tab.hoverConsumers.forEach { it.onMouseEntered(bounds, link.url) }
            }
            previousHoveredHyperlink = hoveredHyperlink
          }

          if (startPos != null) {
            // Calculate distance from start position
            val dx = pos.x - startPos.x
            val dy = pos.y - startPos.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

            // Only start selecting if moved beyond threshold (5 pixels)
            // This prevents accidental selection on single clicks
            if (distance > DRAG_THRESHOLD) {
              if (!isDragging) {
                // First time crossing threshold - start selection from original position
                isDragging = true

                // Clear search when user manually selects text
                if (searchVisible) {
                  searchVisible = false
                  searchQuery = ""
                  searchMatches = emptyList()
                }
                // Ensure focus is on terminal canvas after click
                focusRequester.requestFocus()

                // Detect Alt+Drag for block selection mode
                selectionMode = if (event.isAltPressed()) SelectionMode.BLOCK else SelectionMode.NORMAL

                val visualCol = (startPos.x / cellWidth).toInt()
                val screenRow = (startPos.y / cellHeight).toInt()
                val bufferRow = screenRow - scrollOffset  // Convert screen to buffer-relative row

                // Convert visual column to buffer column for grapheme-aware selection
                // Cache snapshot at drag start for performance - reused during entire drag
                cachedDragSnapshot = textBuffer.createSnapshot()
                val snapshot = cachedDragSnapshot!!
                val lineIndex = bufferRow + snapshot.historyLinesCount
                val bufferCol = if (lineIndex >= 0 && lineIndex < snapshot.height + snapshot.historyLinesCount) {
                    val line = snapshot.getLine(lineIndex)
                    if (line != null) {
                        TerminalCanvasRenderer.visualColToBufferCol(line, visualCol, snapshot.width)
                    } else visualCol
                } else visualCol
                // Initialize tracker with start position (end will be set below)
                selectionTracker.setSelection(bufferCol, bufferRow, bufferCol, bufferRow, selectionMode)
              }

              // Update selection end point as mouse moves
              // Handle out-of-bounds coordinates for auto-scroll
              // Convert to buffer-relative coordinates for consistent selection model
              val visualEndCol = (pos.x / cellWidth).toInt().coerceIn(0, textBuffer.width - 1)
              val screenRow = when {
                pos.y < 0 -> 0  // Above canvas: first visible row
                pos.y > canvasSize.height -> ((canvasSize.height / cellHeight).toInt()).coerceAtMost(textBuffer.height - 1)
                else -> (pos.y / cellHeight).toInt()
              }
              val bufferEndRow = screenRow - scrollOffset  // Convert screen to buffer-relative row

              // Convert visual column to buffer column for grapheme-aware selection
              // Use cached snapshot for performance (created at drag start)
              val endSnapshot = cachedDragSnapshot ?: textBuffer.createSnapshot()
              val endLineIndex = bufferEndRow + endSnapshot.historyLinesCount
              val bufferEndCol = if (endLineIndex >= 0 && endLineIndex < endSnapshot.height + endSnapshot.historyLinesCount) {
                  val line = endSnapshot.getLine(endLineIndex)
                  if (line != null) {
                      TerminalCanvasRenderer.visualColToBufferCol(line, visualEndCol, endSnapshot.width)
                  } else visualEndCol
              } else visualEndCol
              // Update tracker's end point for content-anchored selection
              selectionTracker.updateEnd(bufferEndCol, bufferEndRow)

              // Track position for auto-scroll updates
              lastDragPosition = pos

              // Start auto-scroll if dragging outside bounds
              if (pos.y < 0 || pos.y > canvasSize.height) {
                if (autoScrollJob?.isActive != true) {
                  startAutoScroll(pos, cellWidth, cellHeight)
                }
              } else {
                // Back in bounds - cancel auto-scroll
                autoScrollJob?.cancel()
              }

              // Phase 2: Immediate redraw during drag
              display.requestImmediateRedraw()
            }
          }
        }
        .onPointerEvent(PointerEventType.Release) { event ->
          val change = event.changes.first()
          // Skip if event was already consumed by an overlay
          if (change.isConsumed) return@onPointerEvent

          // Check if mouse event should be forwarded to terminal application.
          // Mirror the exact Press decision via lastPressForwardedToTui rather
          // than re-reading the modifier here — toggling Cmd/Ctrl between press
          // and release would otherwise leave the TUI with an unpaired event.
          val shiftPressed = event.isShiftPressed()
          if (settings.enableMouseReporting && isRemoteMouseAction(shiftPressed) && lastPressForwardedToTui) {
            // If button is null, skip remote forwarding and fall through to local handling
            // Button can be null for touch events, stylus input, or exotic input devices
            event.button?.let { button ->
              val (col, row) = pixelToCharCoords(change.position)
              val mouseEvent = createComposeMouseEvent(event, button)
              terminal.mouseReleased(col, row, mouseEvent)
              lastPressForwardedToTui = false
              change.consume()
              return@onPointerEvent
            } ?: run {
              if (settings.debugModeEnabled) {
                println("Mouse release with null button at (${change.position.x}, ${change.position.y}) - handling locally")
              }
            }
          }

          // If never started dragging (no movement beyond threshold),
          // ensure selection is cleared - this was just a click, not a drag
          // BUT: Don't clear on right-click to allow context menu → Copy
          // ALSO: Don't clear multi-click selections (double-click word, triple-click line)
          if (!isDragging && clickCount == 1 && event.button != androidx.compose.ui.input.pointer.PointerButton.Secondary) {
            selectionTracker.clearSelection()
          }

          // Copy-on-select: Automatically copy selected text to clipboard
          if (settings.copyOnSelect) {
            val snapshot = textBuffer.createIncrementalSnapshot()
            val resolved = selectionTracker.resolveToCoordinates(snapshot)
            if (resolved != null) {
              val selectedText = SelectionEngine.extractSelectedTextTrimmed(
                textBuffer, resolved.toStartPair(), resolved.toEndPair(), resolved.mode
              )
              if (selectedText.isNotEmpty()) {
                if (settings.emulateX11CopyPaste) {
                  // X11 mode: Copy to selection clipboard (middle-click buffer)
                  // Limit clipboard size to 10MB to prevent memory issues
                  if (selectedText.length <= 10_000_000) {
                    selectionClipboard = selectedText
                  }
                } else {
                  // Normal mode: Copy to system clipboard
                  clipboardManager.setText(AnnotatedString(selectedText))
                }
              }
            }
          }

          // Reset drag state and cancel auto-scroll
          isDragging = false
          dragStartPos = null
          cachedDragSnapshot = null  // Clear cached snapshot
          autoScrollJob?.cancel()
          autoScrollJob = null
          lastDragPosition = null

          // Phase 2: Immediate redraw on release
          display.requestImmediateRedraw()
        }
        .onPointerEvent(PointerEventType.Scroll) { event ->
          val change = event.changes.first()
          // Skip if event was already consumed by an overlay
          if (change.isConsumed) return@onPointerEvent
          val delta = change.scrollDelta.y
          val shiftPressed = event.isShiftPressed()

          // Forward wheel events to app in alternate buffer (vim, less, etc.) unless Shift held
          if (settings.enableMouseReporting &&
            isRemoteMouseAction(shiftPressed) &&
            textBuffer.isUsingAlternateBuffer) {

            // Only forward if delta is significant (reduces sensitivity)
            // Terminal protocols send discrete events, not continuous deltas
            val absDelta = kotlin.math.abs(delta)
            if (absDelta >= settings.mouseScrollThreshold) {
              val (col, row) = pixelToCharCoords(change.position)
              val wheelEvent = createComposeMouseWheelEvent(event, delta)
              terminal.mouseWheelMoved(col, row, wheelEvent)
            }
            change.consume()
            return@onPointerEvent
          }

          // Local scroll (main buffer or Shift+Wheel override)
          // Accumulate fractional deltas for smooth scrolling
          // Windows trackpads send small fractional values, so multiplier helps
          val historySize = textBuffer.historyLinesCount
          accumulatedScrollDelta += delta * settings.scrollMultiplier
          val scrollLines = accumulatedScrollDelta.toInt()
          if (scrollLines != 0) {
            scrollOffset = (scrollOffset - scrollLines).coerceIn(0, historySize)
            accumulatedScrollDelta -= scrollLines.toFloat()
            userScrollTrigger++  // Mark as user-initiated scroll for scrollbar visibility
          }
          // Always consume scroll events to prevent propagation to parent containers
          change.consume()
        }
        .onPreviewKeyEvent { keyEvent ->
          // Track Ctrl/Cmd key state for hyperlink clicks and hover effects
          when (keyEvent.key) {
            Key.CtrlLeft, Key.CtrlRight, Key.MetaLeft, Key.MetaRight -> {
              val wasPressed = isModifierPressed
              isModifierPressed = keyEvent.type == KeyEventType.KeyDown

              // Request immediate redraw if modifier state changed and hovering over hyperlink
              // This ensures the underline appears/disappears immediately when Cmd/Ctrl is pressed/released
              if (wasPressed != isModifierPressed && hoveredHyperlink != null) {
                display.requestImmediateRedraw()
              }
            }
          }

          // Handle actions in preview (before search bar intercepts)
          // This allows shortcuts like Ctrl+F to work even when search bar is focused
          if (keyEvent.type == KeyEventType.KeyDown) {
            // Only handle search action in preview to intercept before search bar
            val action = actionRegistry.getAction("search")
            if (action != null && action.matchesKeyEvent(keyEvent, isMacOS)) {
              return@onPreviewKeyEvent action.execute(keyEvent)
            }
          }
          false  // Let other events pass through
        }
        .onKeyEvent { keyEvent ->
          // Don't consume keys when search bar is visible - let it handle them
          if (searchVisible) {
            return@onKeyEvent false
          }

          if (keyEvent.type == KeyEventType.KeyDown) {
            // Try to execute action from registry
            val action = actionRegistry.findAction(keyEvent)
            if (action != null && action.enabled()) {
              val consumed = action.execute(keyEvent)
              if (consumed) {
                return@onKeyEvent true
              }
            }

            // Clear selection on any printable key (except Ctrl/Cmd+key combinations)
            // This matches standard terminal behavior - typing clears selection
            if (!keyEvent.isCtrlPressed && !keyEvent.isMetaPressed && !keyEvent.isAltPressed) {
              if (selectionTracker.hasSelection()) {
                // Don't clear on navigation keys or function keys
                val isNavigationKey = keyEvent.key in listOf(
                  Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                  Key.Home, Key.MoveEnd, Key.PageUp, Key.PageDown,
                  Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6,
                  Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12
                )
                if (!isNavigationKey) {
                  // Clear selection before processing the key
                  selectionTracker.clearSelection()
                  display.requestImmediateRedraw()
                }
              }
            }

            // IME (Input Method Editor) Support - IMPLEMENTED
            // Press Ctrl+Space to toggle IME mode for CJK (Chinese, Japanese, Korean) input.
            // When enabled, an invisible TextField appears at the cursor position to capture
            // IME composition events (e.g., Pinyin for Chinese, Hiragana for Japanese).
            // The composed text is forwarded to the terminal once committed.
            //
            // Implementation: See IMEHandler component (line ~1176) and IMEState (line ~148)
            // Keyboard shortcut: Ctrl+Space to toggle (line ~547)
            // See: ai.rever.bossterm.compose.ime package

            // Filter out modifier-only keys - they don't produce output
            if (keyEvent.key in listOf(
                Key.ShiftLeft, Key.ShiftRight,
                Key.CtrlLeft, Key.CtrlRight,
                Key.AltLeft, Key.AltRight,
                Key.MetaLeft, Key.MetaRight
              )) {
              return@onKeyEvent false
            }

            scope.launch {
              val text = run {
                // Try to map key to VK code and use terminal's key encoder
                // This handles function keys, navigation keys, and respects terminal mode (DECCKM, DECKPAM)
                val vkCode = KeyMappingUtils.mapComposeKeyToVK(keyEvent.key)
                if (vkCode != null) {
                  val modifiers = KeyMappingUtils.mapComposeModifiers(keyEvent)
                  val bytes = terminal.getCodeForKey(vkCode, modifiers)
                  if (bytes != null) {
                    return@run String(bytes, Charsets.UTF_8)
                  }
                }

                // Fallback: Handle printable characters
                val code = keyEvent.utf16CodePoint
                // Filter out invalid characters (0xFFFF) and Unicode special ranges
                if (code > 0 && code != 0xFFFF && code < 0xFFF0) {
                  code.toChar().toString()
                } else ""
              }

              if (text.isNotEmpty()) {
                // AI Command Interception: Check if typing an AI assistant command
                // For single characters, let the interceptor track input and potentially
                // consume Enter if it detects an uninstalled AI assistant command
                val interceptor = tab.aiCommandInterceptor
                if (interceptor != null && text.length == 1) {
                  if (interceptor.onCharacterTyped(text[0])) {
                    // Character consumed by interceptor (e.g., Enter on AI command)
                    // Don't send to PTY - interceptor triggered install prompt
                    return@launch
                  }
                }

                // Auto-scroll to cursor when user types (standard terminal behavior)
                // If scrolled into history, snap back to current screen so user sees their input
                if (settings.scrollToBottomOnTyping) {
                  scrollOffset = 0
                }

                // Feed keyboard event to type-ahead manager BEFORE sending to PTY
                // This creates predictions that reduce perceived latency on SSH
                tab.typeAheadManager?.let { manager ->
                  val typeAheadEvents = TerminalTypeAheadManager.TypeAheadEvent.fromString(text)
                  for (event in typeAheadEvents) {
                    manager.onKeyEvent(event)
                  }
                }

                tab.writeUserInput(text)
                // Phase 2: Immediate redraw for user input (zero lag)
                display.requestImmediateRedraw()
              }
            }
            true
          } else false
        }
        .focusRequester(focusRequester)
        .focusable()
        .onFocusChanged { focusState ->
          isFocused = focusState.isFocused
        }
    ) {
      // Show loading/error screen before connection is established. Remote mirror tabs have
      // no local PTY connection — they render directly from the streamed buffer.
      if (connectionState !is ConnectionState.Connected && !tab.isRemote) {
        PreConnectScreen(
          state = connectionState,
          onRetry = {
            // Reset to initializing and trigger re-composition
            // The LaunchedEffect will run again automatically
            connectionState = ConnectionState.Initializing
          }
        )
      } else {
        // Only show terminal UI when connected

        // Create snapshot for lock-free rendering with copy-on-write optimization
        // Uses version tracking to reuse unchanged lines (99%+ allocation reduction)
        // Snapshot cached by Compose - recreated when display triggers redraw OR buffer dimensions change
        // Cursor state is captured atomically to prevent flickering from partial updates
        val currentTrigger = display.redrawTrigger.value
        val bufferSnapshot = remember(currentTrigger, textBuffer.width, textBuffer.height) {
          textBuffer.createIncrementalSnapshot()
        }
        // Capture cursor state atomically with redrawTrigger - prevents partial updates
        val cursorX = remember(currentTrigger) { display.cursorXSnapshot }
        val cursorY = remember(currentTrigger) { display.cursorYSnapshot }
        val cursorVisible = remember(currentTrigger) { display.cursorVisibleSnapshot }
        val cursorShape = remember(currentTrigger) { display.cursorShapeSnapshot }
        // Type-ahead manager can override cursor X for local echo prediction
        val effectiveCursorX = tab.typeAheadManager?.let { it.cursorX - 1 } ?: cursorX

        Canvas(modifier = Modifier.padding(start = 4.dp, top = 4.dp).fillMaxSize().clipToBounds()) {
          // Guard against invalid canvas sizes during resize - prevents drawText constraint failures
          if (size.width < cellWidth || size.height < cellHeight) return@Canvas

          // Capture canvas size for auto-scroll bounds detection in pointer event handlers
          canvasSize = size

          // Calculate visible bounds - limit rendering to what fits in canvas
          // Use ceil for rows to include partially visible bottom row (Canvas clips automatically)
          val visibleCols = (size.width / cellWidth).toInt().coerceAtMost(bufferSnapshot.width)
          val visibleRows = kotlin.math.ceil(size.height / cellHeight).toInt().coerceAtMost(bufferSnapshot.height)

          // Get cursor color from terminal (OSC 12), falling back to the active theme's
          // cursor color so the cursor stays visible against light and dark backgrounds
          // alike when no app has overridden it.
          val customCursorColor = terminal.cursorColor
          val baseCursorColor = if (customCursorColor != null) {
            Color(customCursorColor.red, customCursorColor.green, customCursorColor.blue)
          } else activeTheme.cursorColor

          // Version-based hyperlink caching: compute hash including scroll position
          // since visible content changes with scroll even if buffer content is same
          val currentVersionHash = bufferSnapshot.computeVersionHash() * 31 + scrollOffset

          // Reuse cached hyperlinks if buffer content and scroll position unchanged
          val precomputedHyperlinks = if (currentVersionHash == lastHyperlinkVersionHash &&
                                          cachedHyperlinks.isNotEmpty()) {
            cachedHyperlinks
          } else {
            null // Force fresh detection
          }

          // Resolve content-anchored selection to current coordinates
          // This allows selection to follow content as terminal scrolls
          // selectionTracker is the single source of truth for selection
          val resolvedSelection = selectionTracker.resolveToCoordinates(bufferSnapshot)
          val effectiveSelectionStart = resolvedSelection?.toStartPair()
          val effectiveSelectionEnd = resolvedSelection?.toEndPair()
          val effectiveSelectionMode = resolvedSelection?.mode ?: selectionMode

          // Resolve command blocks to on-screen rows (empty unless the feature is
          // enabled). Reading the collected `commandBlocks` state here subscribes
          // the draw to block changes so the gutter repaints as commands run.
          val resolvedCommandBlocks: List<RenderableBlock> = if (settings.commandBlocksEnabled) {
            commandBlocks.mapNotNull { block ->
              val startBuf = selectionTracker.resolveAnchorRow(block.startAnchor, bufferSnapshot)
                ?: return@mapNotNull null
              val endBuf = block.endAnchor?.let { selectionTracker.resolveAnchorRow(it, bufferSnapshot) }
              val startScreen = startBuf + scrollOffset
              val endScreen = endBuf?.let { it + scrollOffset } ?: visibleRows
              if (endScreen < 0 || startScreen > visibleRows) return@mapNotNull null
              val color = when (block.state) {
                BlockState.SUCCESS -> settings.commandBlockSuccessColorValue
                BlockState.ERROR -> settings.commandBlockErrorColorValue
                BlockState.RUNNING -> settings.commandBlockRunningColorValue
              }
              RenderableBlock(
                startRow = startScreen.coerceAtLeast(0),
                endRow = endScreen.coerceIn(0, visibleRows),
                color = color
              )
            }
          } else {
            emptyList()
          }

          // Reset the per-frame blink probe; renderText() sets an entry true if it
          // draws a SLOW_BLINK / RAPID_BLINK cell in the visible region this frame.
          blinkProbe[0] = false
          blinkProbe[1] = false

          // Build rendering context with all state
          val renderingContext = RenderingContext(
            bufferSnapshot = bufferSnapshot,
            blinkProbe = blinkProbe,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            baseCellHeight = baseCellHeight,
            cellBaseline = cellMetrics.third,
            scrollOffset = scrollOffset,
            visibleCols = visibleCols,
            visibleRows = visibleRows,
            textMeasurer = textMeasurer,
            measurementFontFamily = sharedFont,
            fontSize = effectiveFontSize,
            settings = settings,
            ambiguousCharsAreDoubleWidth = display.ambiguousCharsAreDoubleWidth(),
            selectionStart = effectiveSelectionStart,
            selectionEnd = effectiveSelectionEnd,
            selectionMode = effectiveSelectionMode,
            searchVisible = searchVisible,
            searchQuery = searchQuery,
            searchMatches = searchMatches,
            currentMatchIndex = currentMatchIndex,
            cursorX = effectiveCursorX,
            cursorY = cursorY,
            cursorVisible = cursorVisible,
            // The text pass no longer draws the cursor (it lives in its own overlay Canvas
            // below), so pass a constant here. Reading the real cursorBlinkVisible state in
            // this draw lambda would re-subscribe the whole text canvas to the blink and
            // defeat the optimization.
            cursorBlinkVisible = true,
            cursorShape = cursorShape,
            cursorColor = baseCursorColor,
            isFocused = isFocused,
            hoveredHyperlink = hoveredHyperlink,
            isModifierPressed = isModifierPressed,
            slowBlinkVisible = slowBlinkVisible,
            rapidBlinkVisible = rapidBlinkVisible,
            imageDataCache = terminal.getImageDataCache(),
            terminalWidthCells = bufferSnapshot.width,
            terminalHeightCells = bufferSnapshot.height,
            precomputedHyperlinks = precomputedHyperlinks,
            workingDirectory = tab.workingDirectory.value,
            detectFilePaths = settings.detectFilePaths,
            hyperlinkRegistry = hyperlinkRegistry,
            commandBlocks = resolvedCommandBlocks
          )

          // Render terminal using extracted renderer - returns detected hyperlinks
          with(TerminalCanvasRenderer) {
            val detectedHyperlinks = renderTerminal(renderingContext)
            // Update cache for next frame
            cachedHyperlinks = detectedHyperlinks
            lastHyperlinkVersionHash = currentVersionHash
          }

          // Reflect the renderer's blink-presence findings into composition state. Only
          // an actual change notifies (mutableStateOf uses structural equality), so this
          // re-arms/parks the blink loops on transitions and is a no-op otherwise — no
          // per-frame recomposition. Reads here are in the draw phase, not composition.
          if (hasSlowBlinkText != blinkProbe[0]) hasSlowBlinkText = blinkProbe[0]
          if (hasRapidBlinkText != blinkProbe[1]) hasRapidBlinkText = blinkProbe[1]
        }

        // Cursor overlay — drawn in its own Canvas, stacked on top of the text canvas above
        // (same modifier, so coordinates line up exactly). This is the ONLY place that reads
        // cursorBlinkVisible, so the ~0.5s blink invalidates just this tiny layer instead of
        // re-running the full text render. Cursor position is read from the same
        // composition-scoped snapshots the text canvas uses, so it stays in sync as content
        // and scroll change. The cursor is a translucent overlay, so a separate layer is
        // visually identical to drawing it last in one canvas.
        Canvas(modifier = Modifier.padding(start = 4.dp, top = 4.dp).fillMaxSize().clipToBounds()) {
          if (size.width < cellWidth || size.height < cellHeight) return@Canvas
          val customCursorColor = terminal.cursorColor
          val baseCursorColor = if (customCursorColor != null) {
            Color(customCursorColor.red, customCursorColor.green, customCursorColor.blue)
          } else activeTheme.cursorColor
          with(TerminalCanvasRenderer) {
            renderCursorOverlay(
              cursorVisible = cursorVisible,
              cursorBlinkVisible = cursorBlinkVisible,
              cursorShape = cursorShape,
              cursorX = effectiveCursorX,
              cursorY = cursorY,
              scrollOffset = scrollOffset,
              cellWidth = cellWidth,
              cellHeight = cellHeight,
              isFocused = isFocused,
              cursorColor = baseCursorColor,
              focusedAlpha = settings.cursorFocusedAlpha,
              unfocusedAlpha = settings.cursorUnfocusedAlpha,
            )
          }
        }

        // IME (Input Method Editor) handler for CJK input
        // Provides invisible TextField for IME composition (Pinyin, Hiragana, etc.)
        IMEHandler(
          enabled = imeState.isEnabled,
          cursorX = cursorX,
          cursorY = (cursorY - 1).coerceAtLeast(0), // Adjust for 1-indexed cursor
          charWidth = cellWidth,
          charHeight = cellHeight,
          onTextCommit = { text ->
            // Forward composed text to terminal
            scope.launch {
              tab.writeUserInput(text)
            }
            // Disable IME after successful input and restore focus to terminal
            imeState.disable()
            scope.launch {
              delay(50)
              focusRequester.requestFocus()
            }
          }
        )

        // Search bar UI
        SearchBar(
          visible = searchVisible,
          searchQuery = searchQuery,
          currentMatch = currentMatchIndex + 1,
          totalMatches = searchMatches.size,
          caseSensitive = searchCaseSensitive,
          onSearchQueryChange = { searchQuery = it },
          onFindNext = {
            if (searchMatches.isNotEmpty()) {
              currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
              val (col, row) = searchMatches[currentMatchIndex]
              scrollToMatch(row)
              highlightMatch(col, row, searchQuery.length)
            }
          },
          onFindPrevious = {
            if (searchMatches.isNotEmpty()) {
              currentMatchIndex = if (currentMatchIndex <= 0) searchMatches.size - 1 else currentMatchIndex - 1
              val (col, row) = searchMatches[currentMatchIndex]
              scrollToMatch(row)
              highlightMatch(col, row, searchQuery.length)
            }
          },
          onCaseSensitiveToggle = { searchCaseSensitive = !searchCaseSensitive },
          onClose = {
            searchVisible = false
            searchQuery = ""
            searchMatches = emptyList()

            // Clear search highlight
            selectionTracker.clearSelection()

            // Restore focus to terminal when search closes
            scope.launch {
              kotlinx.coroutines.delay(50)  // Let SearchBar unmount first
              focusRequester.requestFocus()
            }
          },
          modifier = Modifier.align(Alignment.TopEnd)
        )

        // Command palette overlay (Phase 2)
        if (commandPaletteVisible) {
          val paletteCommands = remember(commandBlocks, actionRegistry, workflows) {
            ai.rever.bossterm.compose.palette.PaletteSources.collect(
              actions = actionRegistry,
              recentCommands = commandBlocks.mapNotNull { it.commandText },
              insertCommand = { cmd -> tab.writeUserInput(cmd) },
              workflows = workflows,
              onRunWorkflow = { wf -> pendingWorkflow = wf }
            )
          }
          ai.rever.bossterm.compose.palette.CommandPalette(
            visible = true,
            commands = paletteCommands,
            onDismiss = {
              commandPaletteVisible = false
              scope.launch {
                kotlinx.coroutines.delay(50)
                focusRequester.requestFocus()
              }
            }
          )
        }

        // Workflow parameter dialog (Phase 3)
        pendingWorkflow?.let { wf ->
          ai.rever.bossterm.compose.workflows.WorkflowRunDialog(
            workflow = wf,
            autoRun = settings.workflowsAutoRun,
            onDismiss = {
              pendingWorkflow = null
              scope.launch {
                kotlinx.coroutines.delay(50)
                focusRequester.requestFocus()
              }
            },
            onSubmit = { rendered ->
              tab.writeUserInput(rendered + if (settings.workflowsAutoRun) "\n" else "")
            }
          )
        }

        // History search overlay (Phase 4)
        if (historySearchVisible) {
          // Load shell-history files off the UI thread; show empty until ready.
          val historyEntries by androidx.compose.runtime.produceState(
            initialValue = emptyList<String>(), commandBlocks
          ) {
            val recent = commandBlocks.mapNotNull { it.commandText }
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
              ai.rever.bossterm.compose.history.HistoryStore.load(recent)
            }
          }
          val restoreFocus = {
            historySearchVisible = false
            scope.launch {
              kotlinx.coroutines.delay(50)
              focusRequester.requestFocus()
            }
            Unit
          }
          ai.rever.bossterm.compose.history.HistorySearchOverlay(
            visible = true,
            history = historyEntries,
            onSelect = { cmd -> tab.writeUserInput(cmd); restoreFocus() },
            onDismiss = { restoreFocus() },
            aiEnabled = settings.aiCommandBarEnabled && settings.aiCommandBarPrintFlag.isNotBlank(),
            onAskAi = { query ->
              restoreFocus()
              scope.launch {
                val suggestion = ai.rever.bossterm.compose.ai.AiCommandSuggester.suggest(
                  agentCommand = settings.aiCommandBarAgentCommand,
                  printFlag = settings.aiCommandBarPrintFlag,
                  naturalLanguage = query
                )
                if (!suggestion.isNullOrBlank()) tab.writeUserInput(suggestion)
              }
            }
          )
        }

        // Restore focus to terminal when debug window closes
        LaunchedEffect(debugPanelVisible) {
          if (!debugPanelVisible && isActiveTab) {
            // Delay for Compose window to fully close before focus restoration
            kotlinx.coroutines.delay(50)
            focusRequester.requestFocus()
          }
        }

        // Restore focus to terminal when context menu closes.
        // Critical for embedded scenarios where focus returns to parent container
        // instead of terminal after AWT JPopupMenu dismissal. Fixes #126.
        val contextMenuState by contextMenuController.menuState
        LaunchedEffect(contextMenuState.isVisible) {
          if (!contextMenuState.isVisible && isActiveTab) {
            // Delay for AWT JPopupMenu to fully close before focus restoration
            kotlinx.coroutines.delay(50)
            focusRequester.requestFocus()
          }
        }
      }

      // Vertical scrollbar on the right side - Always visible custom scrollbar
      if (settings.showScrollbar) {
        // Compute match positions for scrollbar markers
        val matchPositions = remember(searchMatches, textBuffer.historyLinesCount, textBuffer.height) {
          if (searchVisible && settings.showSearchMarkersInScrollbar) {
            computeMatchPositions(
              matches = searchMatches,
              historyLinesCount = textBuffer.historyLinesCount,
              screenHeight = textBuffer.height
            )
          } else {
            emptyList()
          }
        }

        // Compute command-block markers for the scrollbar track. Resolved against
        // a fresh snapshot so positions reflect current history depth.
        val (blockMarkerPositions, blockMarkerColors) = remember(
          commandBlocks,
          textBuffer.historyLinesCount,
          textBuffer.height,
          settings.commandBlocksEnabled,
          settings.commandBlockShowScrollbarMarkers
        ) {
          if (!settings.commandBlocksEnabled ||
              !settings.commandBlockShowScrollbarMarkers ||
              commandBlocks.isEmpty()) {
            emptyList<Float>() to emptyList<Color>()
          } else {
            val snap = textBuffer.createIncrementalSnapshot()
            val total = (snap.historyLinesCount + textBuffer.height).coerceAtLeast(1)
            val positions = ArrayList<Float>(commandBlocks.size)
            val colors = ArrayList<Color>(commandBlocks.size)
            for (block in commandBlocks) {
              val row = selectionTracker.resolveAnchorRow(block.startAnchor, snap) ?: continue
              positions.add(((row + snap.historyLinesCount).toFloat() / total).coerceIn(0f, 1f))
              colors.add(
                when (block.state) {
                  BlockState.SUCCESS -> settings.commandBlockSuccessColorValue
                  BlockState.ERROR -> settings.commandBlockErrorColorValue
                  BlockState.RUNNING -> settings.commandBlockRunningColorValue
                }
              )
            }
            positions.toList() to colors.toList()
          }
        }

        AlwaysVisibleScrollbar(
          adapter = scrollbarAdapter,
          redrawTrigger = display.redrawTrigger,
          modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight(),
          thickness = settings.scrollbarWidth.dp,
          thumbColor = settings.scrollbarThumbColorValue,
          trackColor = settings.scrollbarColorValue,
          minThumbHeight = 32.dp,
          matchPositions = matchPositions,
          currentMatchIndex = currentMatchIndex,
          matchMarkerColor = settings.searchMarkerColorValue,
          currentMatchMarkerColor = settings.currentSearchMarkerColorValue,
          onMatchClicked = { matchIndex ->
            if (matchIndex in searchMatches.indices) {
              currentMatchIndex = matchIndex
              val (col, row) = searchMatches[matchIndex]
              scrollToMatch(row)
              highlightMatch(col, row, searchQuery.length)
            }
          },
          userScrollTrigger = rememberUpdatedState(userScrollTrigger),
          blockMarkerPositions = blockMarkerPositions,
          blockMarkerColors = blockMarkerColors
        )

        // Visual bell overlay - flashes when BEL character received and visualBell enabled
        if (visualBellActive) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(settings.defaultForegroundColor.copy(alpha = 0.15f))
          )
        }

        // Progress bar (OSC 1337;SetProgress / OSC 9;4)
        val progressState by display.progressState
        val progressValue by display.progressValue
        if (settings.progressBarEnabled && progressState != TerminalDisplay.ProgressState.HIDDEN) {
          val progressColor = when (progressState) {
            TerminalDisplay.ProgressState.NORMAL -> Color(0xFF4A90E2)  // Blue
            TerminalDisplay.ProgressState.ERROR -> Color(0xFFE24A4A)   // Red
            TerminalDisplay.ProgressState.WARNING -> Color(0xFFE2B44A) // Yellow/Orange
            TerminalDisplay.ProgressState.INDETERMINATE -> Color(0xFF4A90E2) // Blue
            TerminalDisplay.ProgressState.HIDDEN -> Color.Transparent
          }

          val progressAlignment = if (settings.progressBarPosition == "top") Alignment.TopStart else Alignment.BottomStart
          Box(
            modifier = Modifier
              .align(progressAlignment)
              .fillMaxWidth()
              .height(settings.progressBarHeight.dp)
              .background(progressColor.copy(alpha = 0.15f))
          ) {
            if (progressState == TerminalDisplay.ProgressState.INDETERMINATE) {
              // Indeterminate: animated gradient bar with seamless loop.
              // The infinite transition is created INSIDE this branch on purpose: it
              // drives the Compose frame clock continuously (~60fps), so it must only run
              // while an indeterminate bar is actually on screen. A determinate bar ignores
              // animatedOffset entirely — keeping the transition in the outer scope pinned
              // the compositor at 60fps for the bar's whole (possibly minutes-long) life.
              val infiniteTransition = rememberInfiniteTransition(label = "progress")
              val animatedOffset by infiniteTransition.animateFloat(
                initialValue = -0.3f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                  animation = tween(2500, easing = LinearEasing),
                  repeatMode = RepeatMode.Restart
                ),
                label = "progressOffset"
              )
              // Gradient width is 30% of bar, animates from -30% to 130% for smooth entry/exit
              Canvas(modifier = Modifier.fillMaxSize()) {
                val gradientWidth = size.width * 0.35f
                val centerX = animatedOffset * (size.width + gradientWidth) - gradientWidth / 2

                drawRect(
                  brush = Brush.horizontalGradient(
                    colors = listOf(
                      Color.Transparent,
                      progressColor.copy(alpha = 0.4f),
                      progressColor,
                      progressColor,
                      progressColor.copy(alpha = 0.4f),
                      Color.Transparent
                    ),
                    startX = centerX - gradientWidth / 2,
                    endX = centerX + gradientWidth / 2
                  ),
                  size = size
                )
              }
            } else if (progressValue >= 0) {
              // Determinate: progress bar
              Box(
                modifier = Modifier
                  .fillMaxWidth(progressValue / 100f)
                  .fillMaxHeight()
                  .background(progressColor)
              )
            }
          }
        }
      }
    } // end Box

    // Context menu popup
    ContextMenuPopup(controller = contextMenuController)

    // Debug window (separate window)
    DebugWindow(
      visible = debugPanelVisible,
      collector = debugCollector,
      textBuffer = textBuffer,
      onClose = { debugPanelVisible = false }
    )

    DisposableEffect(Unit) {
      onDispose {
        // Notify hover consumers when terminal is disposed
        if (previousHoveredHyperlink != null) {
          tab.hoverConsumers.forEach { it.onMouseExited() }
        }
        scope.launch {
          processHandle?.kill()
        }
      }
    }
  } // end else (Connected state)
}

/**
 * Escape a file path for safe shell usage.
 * Handles spaces, quotes, and other special characters.
 * Uses single quotes with escaped internal single quotes (iTerm2 style).
 */
private fun escapePathForShell(path: String): String {
  // Characters that need quoting in shell
  val needsQuoting = path.contains(' ') ||
    path.contains('\'') ||
    path.contains('"') ||
    path.contains('\\') ||
    path.contains('$') ||
    path.contains('`') ||
    path.contains('!') ||
    path.contains('*') ||
    path.contains('?') ||
    path.contains('[') ||
    path.contains(']') ||
    path.contains('(') ||
    path.contains(')') ||
    path.contains('{') ||
    path.contains('}') ||
    path.contains('&') ||
    path.contains(';') ||
    path.contains('<') ||
    path.contains('>') ||
    path.contains('|')

  return if (needsQuoting) {
    // Wrap in single quotes and escape any internal single quotes
    // 'foo'bar' becomes 'foo'\''bar'
    "'" + path.replace("'", "'\\''") + "'"
  } else {
    path
  }
}
