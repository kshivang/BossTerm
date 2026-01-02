# BossTerm Selection System Architecture Analysis

## Overview
The BossTerm selection system is well-architected with content-anchored selection that survives buffer scrolling. It uses a 3-layer architecture combining core logic, UI integration, and rendering.

## Key Components Found

### 1. TerminalSelection.kt (Core Model)
- **Location**: `bossterm-core-mpp/src/jvmMain/kotlin/ai/rever/bossterm/terminal/model/TerminalSelection.kt`
- **Purpose**: Basic selection data structure with start/end coordinates
- **Key Methods**:
  - `pointsForRun()`: Returns start/end points with width adjustment
  - `contains()`, `intersects()`: Geometry helpers
  - `intersect()`: Returns range of selection on a line
  - `shiftY()`: Adjusts coordinates during scroll

### 2. SelectionUtil.kt (Word Selection Helpers)
- **Location**: `bossterm-core-mpp/src/jvmMain/kotlin/ai/rever/bossterm/terminal/model/SelectionUtil.kt`
- **Purpose**: Separator-based word selection (fallback mechanism)
- **Features**:
  - Hardcoded SEPARATORS list (space, tab, quotes, brackets, etc.)
  - `getPreviousSeparator()`, `getNextSeparator()`: Double-click word boundaries
  - `getSelectionText()`: Extract selected text with newline handling
  - DWC (double-width character) marker removal for selection text

### 3. ContentAnchoredSelection.kt & SelectionAnchor (Content-Relative Selection)
- **Location**: `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/selection/ContentAnchoredSelection.kt`
- **Architecture**: Solves the core problem of selection surviving buffer scroll
- **Key Insight**: Uses WeakReference to TerminalLine objects for identity-based tracking
- **How It Works**:
  - SelectionAnchor stores: WeakReference<TerminalLine>, column, lineVersion
  - When buffer scrolls, line objects move in cyclic buffer but identity remains
  - Resolution to screen coordinates happens at render time via SelectionTracker
  - Content change detection via line version field

### 4. SelectionTracker.kt (Content-to-Coordinate Resolution)
- **Location**: `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/selection/SelectionTracker.kt`
- **Purpose**: Bridge between content-anchored selection and rendering coordinates
- **Key Method**: `buildLineIndexCache()` (found at line 159)
  - Maps TerminalLine reference to current row index
  - Uses IdentityHashMap for O(1) lookups
  - Called on every `resolveToCoordinates()` call
  - **Important**: Uses `versionedLine.originalLine` not snapshot line (prevents aliasing bugs)
  - Cache invalidation: Cleared and rebuilt on every resolve call (no persistence)

### 5. SelectionEngine.kt (Selection Operations)
- **Location**: `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/selection/SelectionEngine.kt`
- **Methods**:
  - `selectWordAt()`: Separator-based selection (delegates to SelectionUtil)
  - `selectWordAtSmart()`: Pattern-based smart selection (see below)
  - `selectLineAt()`, `expandToLogicalLine()`: Multi-line selection with wrapped line handling
  - `extractSelectedText()`, `extractSelectedTextTrimmed()`: Text extraction with DWC filtering

### 6. SmartWordSelection.kt (Pattern-Based Selection)
- **Location**: `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/selection/SmartWordSelection.kt`
- **Architecture**: Pluggable pattern registry similar to hyperlink detection
- **URL Patterns**:
  - Regex: `https?://[^\s<>"'`\[\](){}]+`
  - Priority: 100 (highest)
  - Quick check: `it.contains("://")`
- **File Paths**:
  - Unix: `(?:^|(?<=[\s"'`]))(/[\w.+-]+)+/?`
  - Windows: `[A-Za-z]:\\[\w\\.\-+]+`
  - Priority: 90
- **Quoted Strings**: Double quotes, single quotes, backticks (priority 80)
- **Email**: `[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}` (priority 85)
- **Default**: `\w+` (priority 0, lowest)
- **Optimization**: Windowed search (150 char radius) for long lines

### 7. HyperlinkDetector.kt (URL Detection for Links)
- **Location**: `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/hyperlinks/HyperlinkDetector.kt`
- **HTTP/HTTPS Pattern**: `\bhttps?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+` (priority 0)
- **Quick check**: `it.contains("http://") || it.contains("https://")`
- **Also supports**: File URLs, FTP, www., mailto links
- **Architecture**: Thread-safe registry with custom pattern support

## Selection Color/Opacity Implementation

### Where Selection Opacity is Hardcoded
- **File**: `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/rendering/TerminalCanvasRenderer.kt`
- **Line 901**: `val highlightColor = ctx.settings.selectionColorValue`
- **Line 943-947**: Renders selection rect with NO alpha modification (solid color)

### Selection Color Definition
- **File**: `compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/settings/TerminalSettings.kt`
- **Line 74**: Default color hex: `0xFF214283` (opaque blue)
- **Line 653**: Converted to Color object: `Color(selectionColor.removePrefix("0x").toULong(16).toLong())`
- **Issue**: The hex value includes FF (full opacity), making it hardcoded as fully opaque

### Comparison with Other UI Elements
- **Search matches** (line 845, 847):
  - Current match: `currentSearchMarkerColorValue.copy(alpha = 0.6f)`
  - Other matches: `searchMarkerColorValue.copy(alpha = 0.4f)`
- **Cursor** (line 1067):
  - Focused: `0.7f` alpha
  - Unfocused: `0.3f` alpha
- **Selection** (line 901):
  - Uses color directly with NO alpha modification (inconsistent!)

## Cache Invalidation Pattern Analysis

### buildLineIndexCache() Behavior
1. **Called in**: `SelectionTracker.resolveToCoordinates()` (line 83)
2. **Invalidation Strategy**: Simple rebuild on each call
3. **Performance Impact**: O(height + historyLinesCount) rebuild cost
4. **No persistence**: New IdentityHashMap created, old discarded
5. **Correctness**: Conservative - always up-to-date (safer than caching)

### Why This Pattern Works
- Selection is typically resolved every render frame (16-60ms)
- Buffer dimensions are stable within a session
- Identity-based lookups are fast O(1) for small maps
- Weakness: Rebuilds happen even when buffer unchanged (optimization opportunity)

## URL Detection Consistency Analysis

### SmartWordSelection URL Regex
- Pattern: `https?://[^\s<>"'`\[\](){}]+`
- Excludes: spaces, angle brackets, quotes, backticks, braces
- Character set: Allows alphanumeric, hyphens, underscores, dots, more

### HyperlinkDetector URL Regex
- Pattern: `\bhttps?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+`
- Includes: Word chars, hyphens, underscores, dots, many URL chars
- Character set: Much broader (RFC 3986 URL syntax)

### Inconsistency Found
1. SmartWordSelection: **Stricter** (excludes more punctuation)
2. HyperlinkDetector: **More permissive** (includes RFC URL chars)
3. **Impact**: A URL that highlights as hyperlink might NOT select on double-click
4. **Example**: URL with `#anchor` might not be selected as a word

## Key Files Summary

| File | Purpose | Status |
|------|---------|--------|
| TerminalSelection.kt | Core selection model | Basic, functional |
| SelectionUtil.kt | Separator-based selection | Functional, has hardcoded SEPARATORS |
| ContentAnchoredSelection.kt | Content-relative selection | Well-designed, identity-based |
| SelectionTracker.kt | Content-to-coords resolution | Uses `buildLineIndexCache()` |
| SelectionEngine.kt | High-level selection API | Delegates to SmartWordSelection/SelectionUtil |
| SmartWordSelection.kt | Pattern-based selection | Pluggable, good design |
| TerminalCanvasRenderer.kt | Selection rendering | **NO ALPHA - hardcoded opaque** |
| TerminalSettings.kt | Configuration | Color defined with full opacity |
| HyperlinkDetector.kt | URL detection | More permissive regex than selection |

## Issue #204 Implications

### Current State
1. Selection renders at full opacity (no translucency)
2. Search/cursor elements have configurable alpha
3. Selection patterns (URL, paths) differ from hyperlink patterns
4. buildLineIndexCache() rebuilt on every resolve (optimization opportunity)

### Opportunities for Issue #204
1. **Make selection translucent** (add selectionOpacity setting)
2. **Unify URL detection** between SmartWordSelection and HyperlinkDetector
3. **Extend separator list** (currently hardcoded in SelectionUtil)
4. **Cache buildLineIndexCache()** when buffer unchanged (performance)
5. **Make separator list configurable** (customizable word boundaries)