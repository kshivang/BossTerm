package ai.rever.bossterm.compose.settings

/**
 * Settings override container with nullable fields for selective customization.
 *
 * Use this to override specific settings per terminal instance while
 * respecting global defaults for unspecified fields.
 *
 * Example usage:
 * ```kotlin
 * TabbedTerminal(
 *     settingsOverride = TerminalSettingsOverride(
 *         alwaysShowTabBar = true,
 *         fontSize = 16f
 *     ),
 *     onExit = { ... }
 * )
 * ```
 *
 * Only non-null fields will override the global settings.
 */
data class TerminalSettingsOverride(
    // ===== Visual Settings =====
    val fontSize: Float? = null,
    val fontName: String? = null,
    val lineSpacing: Float? = null,
    val disableLineSpacingInAlternateBuffer: Boolean? = null,
    val fillBackgroundInLineSpacing: Boolean? = null,
    val useAntialiasing: Boolean? = null,
    val preferTerminalFontForSymbols: Boolean? = null,
    val defaultForeground: String? = null,
    val defaultBackground: String? = null,
    val selectionColor: String? = null,
    val selectionAlpha: Float? = null,
    val foundPatternColor: String? = null,
    val hyperlinkColor: String? = null,
    val activeThemeId: String? = null,
    val colorPaletteId: String? = null,
    val backgroundOpacity: Float? = null,
    val windowBlur: Boolean? = null,
    val blurRadius: Float? = null,
    val backgroundImagePath: String? = null,
    val backgroundImageOpacity: Float? = null,
    val useNativeTitleBar: Boolean? = null,
    val showUnfocusedOverlay: Boolean? = null,

    // ===== Behavior Settings =====
    val useLoginSession: Boolean? = null,
    val initialCommand: String? = null,
    val initialCommandDelayMs: Int? = null,
    val copyOnSelect: Boolean? = null,
    val pasteOnMiddleClick: Boolean? = null,
    val emulateX11CopyPaste: Boolean? = null,
    val scrollToBottomOnTyping: Boolean? = null,
    val altSendsEscape: Boolean? = null,
    val enableMouseReporting: Boolean? = null,
    val forceActionOnMouseReporting: Boolean? = null,
    val mouseScrollThreshold: Float? = null,
    val audibleBell: Boolean? = null,
    val visualBell: Boolean? = null,
    val shiftEnterBehavior: String? = null,

    // ===== Progress Bar Settings =====
    val progressBarEnabled: Boolean? = null,
    val progressBarPosition: String? = null,
    val progressBarHeight: Float? = null,

    // ===== Clipboard Settings (OSC 52) =====
    val clipboardOsc52Enabled: Boolean? = null,
    val clipboardOsc52AllowRead: Boolean? = null,
    val clipboardOsc52AllowWrite: Boolean? = null,
    val useInverseSelectionColor: Boolean? = null,

    // ===== Scrollbar Settings =====
    val showScrollbar: Boolean? = null,
    val scrollbarAlwaysVisible: Boolean? = null,
    val scrollbarWidth: Float? = null,
    val scrollbarColor: String? = null,
    val scrollbarThumbColor: String? = null,
    val showSearchMarkersInScrollbar: Boolean? = null,
    val searchMarkerColor: String? = null,
    val currentSearchMarkerColor: String? = null,

    // ===== GPU Rendering Settings =====
    val gpuAcceleration: Boolean? = null,
    val gpuRenderApi: String? = null,
    val gpuPriority: String? = null,
    val gpuVsyncEnabled: Boolean? = null,
    val gpuCacheSizeMb: Int? = null,
    val gpuCacheMaxPercent: Int? = null,

    // ===== Performance Settings =====
    val performanceMode: String? = null,
    val maxRefreshRate: Int? = null,
    val bufferMaxLines: Int? = null,
    val caretBlinkMs: Int? = null,
    val enableTextBlinking: Boolean? = null,
    val slowTextBlinkMs: Int? = null,
    val rapidTextBlinkMs: Int? = null,

    // ===== Terminal Emulation Settings =====
    val decCompatibilityMode: Boolean? = null,
    val ambiguousCharsAreDoubleWidth: Boolean? = null,
    val characterEncoding: String? = null,
    val simulateMouseScrollInAlternateScreen: Boolean? = null,

    // ===== Search Settings =====
    val searchCaseSensitive: Boolean? = null,
    val searchUseRegex: Boolean? = null,

    // ===== Hyperlink Settings =====
    val hyperlinkUnderlineOnHover: Boolean? = null,
    val hyperlinkRequireModifier: Boolean? = null,
    val detectFilePaths: Boolean? = null,

    // ===== Type-Ahead Settings =====
    val typeAheadEnabled: Boolean? = null,
    val typeAheadLatencyThresholdNanos: Long? = null,

    // ===== Debug Settings =====
    val debugModeEnabled: Boolean? = null,
    val debugMaxChunks: Int? = null,
    val debugMaxSnapshots: Int? = null,
    val debugCaptureInterval: Long? = null,
    val debugShowChunkIds: Boolean? = null,
    val debugShowInvisibleChars: Boolean? = null,
    val debugWrapLines: Boolean? = null,
    val debugColorCodeSequences: Boolean? = null,

    // ===== File Logging Settings =====
    val fileLoggingEnabled: Boolean? = null,
    val fileLoggingDirectory: String? = null,
    val fileLoggingPattern: String? = null,

    // ===== Notification Settings =====
    val notifyOnCommandComplete: Boolean? = null,
    val notifyMinDurationSeconds: Int? = null,
    val notifyShowExitCode: Boolean? = null,
    val notifyWithSound: Boolean? = null,
    val notificationPermissionRequested: Boolean? = null,

    // ===== Split Pane Settings =====
    val splitDefaultRatio: Float? = null,
    val splitMinimumSize: Float? = null,
    val splitFocusBorderEnabled: Boolean? = null,
    val splitFocusBorderColor: String? = null,
    val splitInheritWorkingDirectory: Boolean? = null,

    // ===== Tab Bar Settings =====
    val alwaysShowTabBar: Boolean? = null,

    // ===== AI Assistant Settings =====
    val aiAssistantsEnabled: Boolean? = null,
    val aiAssistantConfigs: Map<String, AIAssistantConfigData>? = null,
    val customAIAssistants: List<CustomAIAssistantData>? = null
)

/**
 * Merge this settings with an override, using override values where non-null.
 *
 * @param override The override settings (null fields use base settings)
 * @return A new TerminalSettings with overrides applied
 */
fun TerminalSettings.withOverrides(override: TerminalSettingsOverride?): TerminalSettings {
    if (override == null) return this

    return copy(
        // Visual Settings
        fontSize = override.fontSize ?: fontSize,
        fontName = override.fontName ?: fontName,
        lineSpacing = override.lineSpacing ?: lineSpacing,
        disableLineSpacingInAlternateBuffer = override.disableLineSpacingInAlternateBuffer ?: disableLineSpacingInAlternateBuffer,
        fillBackgroundInLineSpacing = override.fillBackgroundInLineSpacing ?: fillBackgroundInLineSpacing,
        useAntialiasing = override.useAntialiasing ?: useAntialiasing,
        preferTerminalFontForSymbols = override.preferTerminalFontForSymbols ?: preferTerminalFontForSymbols,
        defaultForeground = override.defaultForeground ?: defaultForeground,
        defaultBackground = override.defaultBackground ?: defaultBackground,
        selectionColor = override.selectionColor ?: selectionColor,
        selectionAlpha = override.selectionAlpha ?: selectionAlpha,
        foundPatternColor = override.foundPatternColor ?: foundPatternColor,
        hyperlinkColor = override.hyperlinkColor ?: hyperlinkColor,
        activeThemeId = override.activeThemeId ?: activeThemeId,
        colorPaletteId = override.colorPaletteId ?: colorPaletteId,
        backgroundOpacity = override.backgroundOpacity ?: backgroundOpacity,
        windowBlur = override.windowBlur ?: windowBlur,
        blurRadius = override.blurRadius ?: blurRadius,
        backgroundImagePath = override.backgroundImagePath ?: backgroundImagePath,
        backgroundImageOpacity = override.backgroundImageOpacity ?: backgroundImageOpacity,
        useNativeTitleBar = override.useNativeTitleBar ?: useNativeTitleBar,
        showUnfocusedOverlay = override.showUnfocusedOverlay ?: showUnfocusedOverlay,

        // Behavior Settings
        useLoginSession = override.useLoginSession ?: useLoginSession,
        initialCommand = override.initialCommand ?: initialCommand,
        initialCommandDelayMs = override.initialCommandDelayMs ?: initialCommandDelayMs,
        copyOnSelect = override.copyOnSelect ?: copyOnSelect,
        pasteOnMiddleClick = override.pasteOnMiddleClick ?: pasteOnMiddleClick,
        emulateX11CopyPaste = override.emulateX11CopyPaste ?: emulateX11CopyPaste,
        scrollToBottomOnTyping = override.scrollToBottomOnTyping ?: scrollToBottomOnTyping,
        altSendsEscape = override.altSendsEscape ?: altSendsEscape,
        enableMouseReporting = override.enableMouseReporting ?: enableMouseReporting,
        forceActionOnMouseReporting = override.forceActionOnMouseReporting ?: forceActionOnMouseReporting,
        mouseScrollThreshold = override.mouseScrollThreshold ?: mouseScrollThreshold,
        audibleBell = override.audibleBell ?: audibleBell,
        visualBell = override.visualBell ?: visualBell,
        shiftEnterBehavior = override.shiftEnterBehavior ?: shiftEnterBehavior,

        // Progress Bar Settings
        progressBarEnabled = override.progressBarEnabled ?: progressBarEnabled,
        progressBarPosition = override.progressBarPosition ?: progressBarPosition,
        progressBarHeight = override.progressBarHeight ?: progressBarHeight,

        // Clipboard Settings
        clipboardOsc52Enabled = override.clipboardOsc52Enabled ?: clipboardOsc52Enabled,
        clipboardOsc52AllowRead = override.clipboardOsc52AllowRead ?: clipboardOsc52AllowRead,
        clipboardOsc52AllowWrite = override.clipboardOsc52AllowWrite ?: clipboardOsc52AllowWrite,
        useInverseSelectionColor = override.useInverseSelectionColor ?: useInverseSelectionColor,

        // Scrollbar Settings
        showScrollbar = override.showScrollbar ?: showScrollbar,
        scrollbarAlwaysVisible = override.scrollbarAlwaysVisible ?: scrollbarAlwaysVisible,
        scrollbarWidth = override.scrollbarWidth ?: scrollbarWidth,
        scrollbarColor = override.scrollbarColor ?: scrollbarColor,
        scrollbarThumbColor = override.scrollbarThumbColor ?: scrollbarThumbColor,
        showSearchMarkersInScrollbar = override.showSearchMarkersInScrollbar ?: showSearchMarkersInScrollbar,
        searchMarkerColor = override.searchMarkerColor ?: searchMarkerColor,
        currentSearchMarkerColor = override.currentSearchMarkerColor ?: currentSearchMarkerColor,

        // GPU Rendering Settings
        gpuAcceleration = override.gpuAcceleration ?: gpuAcceleration,
        gpuRenderApi = override.gpuRenderApi ?: gpuRenderApi,
        gpuPriority = override.gpuPriority ?: gpuPriority,
        gpuVsyncEnabled = override.gpuVsyncEnabled ?: gpuVsyncEnabled,
        gpuCacheSizeMb = override.gpuCacheSizeMb ?: gpuCacheSizeMb,
        gpuCacheMaxPercent = override.gpuCacheMaxPercent ?: gpuCacheMaxPercent,

        // Performance Settings
        performanceMode = override.performanceMode ?: performanceMode,
        maxRefreshRate = override.maxRefreshRate ?: maxRefreshRate,
        bufferMaxLines = override.bufferMaxLines ?: bufferMaxLines,
        caretBlinkMs = override.caretBlinkMs ?: caretBlinkMs,
        enableTextBlinking = override.enableTextBlinking ?: enableTextBlinking,
        slowTextBlinkMs = override.slowTextBlinkMs ?: slowTextBlinkMs,
        rapidTextBlinkMs = override.rapidTextBlinkMs ?: rapidTextBlinkMs,

        // Terminal Emulation Settings
        decCompatibilityMode = override.decCompatibilityMode ?: decCompatibilityMode,
        ambiguousCharsAreDoubleWidth = override.ambiguousCharsAreDoubleWidth ?: ambiguousCharsAreDoubleWidth,
        characterEncoding = override.characterEncoding ?: characterEncoding,
        simulateMouseScrollInAlternateScreen = override.simulateMouseScrollInAlternateScreen ?: simulateMouseScrollInAlternateScreen,

        // Search Settings
        searchCaseSensitive = override.searchCaseSensitive ?: searchCaseSensitive,
        searchUseRegex = override.searchUseRegex ?: searchUseRegex,

        // Hyperlink Settings
        hyperlinkUnderlineOnHover = override.hyperlinkUnderlineOnHover ?: hyperlinkUnderlineOnHover,
        hyperlinkRequireModifier = override.hyperlinkRequireModifier ?: hyperlinkRequireModifier,
        detectFilePaths = override.detectFilePaths ?: detectFilePaths,

        // Type-Ahead Settings
        typeAheadEnabled = override.typeAheadEnabled ?: typeAheadEnabled,
        typeAheadLatencyThresholdNanos = override.typeAheadLatencyThresholdNanos ?: typeAheadLatencyThresholdNanos,

        // Debug Settings
        debugModeEnabled = override.debugModeEnabled ?: debugModeEnabled,
        debugMaxChunks = override.debugMaxChunks ?: debugMaxChunks,
        debugMaxSnapshots = override.debugMaxSnapshots ?: debugMaxSnapshots,
        debugCaptureInterval = override.debugCaptureInterval ?: debugCaptureInterval,
        debugShowChunkIds = override.debugShowChunkIds ?: debugShowChunkIds,
        debugShowInvisibleChars = override.debugShowInvisibleChars ?: debugShowInvisibleChars,
        debugWrapLines = override.debugWrapLines ?: debugWrapLines,
        debugColorCodeSequences = override.debugColorCodeSequences ?: debugColorCodeSequences,

        // File Logging Settings
        fileLoggingEnabled = override.fileLoggingEnabled ?: fileLoggingEnabled,
        fileLoggingDirectory = override.fileLoggingDirectory ?: fileLoggingDirectory,
        fileLoggingPattern = override.fileLoggingPattern ?: fileLoggingPattern,

        // Notification Settings
        notifyOnCommandComplete = override.notifyOnCommandComplete ?: notifyOnCommandComplete,
        notifyMinDurationSeconds = override.notifyMinDurationSeconds ?: notifyMinDurationSeconds,
        notifyShowExitCode = override.notifyShowExitCode ?: notifyShowExitCode,
        notifyWithSound = override.notifyWithSound ?: notifyWithSound,
        notificationPermissionRequested = override.notificationPermissionRequested ?: notificationPermissionRequested,

        // Split Pane Settings
        splitDefaultRatio = override.splitDefaultRatio ?: splitDefaultRatio,
        splitMinimumSize = override.splitMinimumSize ?: splitMinimumSize,
        splitFocusBorderEnabled = override.splitFocusBorderEnabled ?: splitFocusBorderEnabled,
        splitFocusBorderColor = override.splitFocusBorderColor ?: splitFocusBorderColor,
        splitInheritWorkingDirectory = override.splitInheritWorkingDirectory ?: splitInheritWorkingDirectory,

        // Tab Bar Settings
        alwaysShowTabBar = override.alwaysShowTabBar ?: alwaysShowTabBar,

        // AI Assistant Settings
        aiAssistantsEnabled = override.aiAssistantsEnabled ?: aiAssistantsEnabled,
        aiAssistantConfigs = override.aiAssistantConfigs ?: aiAssistantConfigs,
        customAIAssistants = override.customAIAssistants ?: customAIAssistants
    )
}
