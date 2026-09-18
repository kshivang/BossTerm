package ai.rever.bossterm.compose.settings

import ai.rever.bossterm.compose.window.GlassAlertDialog as AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.DialogTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.Danger
import ai.rever.bossterm.compose.settings.DialogTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.settings.sections.*
import ai.rever.bossterm.compose.window.isLiquidGlassTheme

private val NavRailWidth = 180.dp

/**
 * Main settings panel with navigation and content area.
 *
 * @param onSettingsChange Called on every settings change for immediate UI feedback
 * @param onSettingsSave Called when a slider is released (use for persistence to avoid I/O during drag)
 */
@Composable
fun SettingsPanel(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    onResetToDefaults: () -> Unit,
    onRestartApp: (() -> Unit)? = null,
    /** Initial category. Null falls back to [SettingsCategory.default]. */
    initialCategory: SettingsCategory? = null,
    modifier: Modifier = Modifier
) {
    // Embedder may have hidden the MCP category. When that happens we drop it
    // from the nav rail and refuse to honor it as the initial category.
    val mcpCfg = ai.rever.bossterm.compose.mcp.LocalBossTermMcpConfig.current
    val hiddenCategories: Set<SettingsCategory> = remember(mcpCfg) {
        if (mcpCfg?.showInSettingsUi == false) setOf(SettingsCategory.MCP) else emptySet()
    }
    val visibleCategories = remember(hiddenCategories) {
        SettingsCategory.entries.filter { it !in hiddenCategories }
    }
    val resolvedInitial = if (initialCategory != null && initialCategory !in hiddenCategories) {
        initialCategory
    } else {
        SettingsCategory.default
    }
    var selectedCategory by remember(resolvedInitial) { mutableStateOf(resolvedInitial) }
    var query by remember { mutableStateOf("") }
    var selectedHit by remember { mutableStateOf(0) }
    var destination by remember { mutableStateOf<SettingsSearchDestination?>(null) }
    var revealNonce by remember { mutableStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    val hits = remember(query, visibleCategories, settings.isLiquidGlassTheme) {
        SettingsSearchIndex.search(query, visibleCategories, settings)
    }
    val pickHit: (SettingsSearchEntry) -> Unit = { hit ->
        selectedCategory = hit.category
        destination = SettingsSearchDestination(hit.group, ++revealNonce)
    }
    var showResetConfirmation by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.F &&
                    (event.isMetaPressed || event.isCtrlPressed)) {
                    searchFocus.requestFocus()
                    true
                } else false
            }
            .fillMaxSize()
            .background(BackgroundColor)
    ) {
        Column(Modifier.width(NavRailWidth).fillMaxHeight().background(SurfaceColor)) {
            BasicTextField(
                value = query,
                onValueChange = { query = it; selectedHit = 0 },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(AccentColor),
                modifier = Modifier.padding(8.dp).fillMaxWidth()
                    .focusRequester(searchFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionDown -> { selectedHit = (selectedHit + 1).coerceAtMost((hits.size - 1).coerceAtLeast(0)); true }
                            Key.DirectionUp -> { selectedHit = (selectedHit - 1).coerceAtLeast(0); true }
                            Key.Enter -> { hits.getOrNull(selectedHit)?.let(pickHit); true }
                            Key.Escape -> { query = ""; destination = null; true }
                            else -> false
                        }
                    },
                decorationBox = { field ->
                    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(BackgroundColor)
                        .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search settings", color = TextMuted, fontSize = 12.sp)
                            field()
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; destination = null }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, "Clear search", tint = TextSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            )
            if (query.isBlank()) {
                NavigationRail(visibleCategories, selectedCategory, {
                    selectedCategory = it
                    destination = null
                }, Modifier.weight(1f))
            } else {
                val resultScroll = rememberLazyListState()
                LaunchedEffect(selectedHit, hits) {
                    if (hits.isNotEmpty()) resultScroll.animateScrollToItem(selectedHit.coerceAtMost(hits.lastIndex))
                }
                if (hits.isEmpty()) Text("No settings found", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                LazyColumn(state = resultScroll, modifier = Modifier.weight(1f)) {
                    itemsIndexed(hits) { index, hit ->
                        Column(Modifier.fillMaxWidth()
                            .background(if (index == selectedHit) AccentColor.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { selectedHit = index; pickHit(hit) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(hit.label, color = TextPrimary, fontSize = 13.sp)
                            Text(listOf(hit.category.displayName, hit.group).filter { it.isNotEmpty() }.distinct().joinToString(" › "),
                                color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(BorderColor)
        )

        // Right content area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                CompositionLocalProvider(LocalSettingsSearchDestination provides destination) {
                    key(selectedCategory) {
                        SettingsContent(
                            category = selectedCategory,
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onSettingsSave = onSettingsSave,
                            onRestartApp = onRestartApp,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Footer with reset button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceColor)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Changes are saved automatically",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    TextButton(
                        onClick = { showResetConfirmation = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = TextSecondary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset to Defaults", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    // Reset confirmation dialog
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = {
                Text(
                    text = "Reset Settings?",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "This will reset all settings to their default values. This action cannot be undone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetToDefaults()
                        showResetConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Danger
                    )
                ) {
                    Text("Reset", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmation = false }
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            backgroundColor = SurfaceColor,
            contentColor = TextPrimary
        )
    }
}

/**
 * Navigation rail with category icons and labels.
 */
@Composable
private fun NavigationRail(
    categories: List<SettingsCategory>,
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .background(SurfaceColor)
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp)
    ) {
        categories.forEach { category ->
            val isSelected = category == selectedCategory
            NavigationRailItem(
                category = category,
                isSelected = isSelected,
                onClick = { onCategorySelected(category) }
            )
        }
    }
}

/**
 * Single navigation rail item.
 */
@Composable
private fun NavigationRailItem(
    category: SettingsCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) AccentColor.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Selection indicator
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isSelected) AccentColor else Color.Transparent)
        )

        Icon(
            imageVector = category.icon,
            contentDescription = category.displayName,
            tint = if (isSelected) AccentColor else TextSecondary,
            modifier = Modifier.size(18.dp)
        )

        Text(
            text = category.displayName,
            color = if (isSelected) AccentColor else TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/**
 * Content area displaying the selected category's settings.
 */
@Composable
private fun SettingsContent(
    category: SettingsCategory,
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    onRestartApp: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val destination = LocalSettingsSearchDestination.current
    LaunchedEffect(destination) {
        if (destination?.group == "") scrollState.scrollTo(0)
    }


    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(20.dp)
    ) {
        // Category header
        Text(
            text = category.displayName,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = category.description,
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        // Category-specific content
        when (category) {
            SettingsCategory.VISUAL -> VisualSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave,
                onRestartApp = onRestartApp
            )
            SettingsCategory.THEMES -> ThemeSettingsSection(
                settings = settings,
                onSettingsSave = onSettingsSave,
                onRestartApp = onRestartApp,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.BEHAVIOR -> BehaviorSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.SCROLLBAR -> ScrollbarSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.COMMAND_BLOCKS -> CommandBlocksSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.WORKFLOWS -> WorkflowsSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.HISTORY_AI -> HistoryAiSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.SESSION -> SessionSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.EXTRAS -> ExtrasSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.PERFORMANCE -> PerformanceSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave,
                onRestartApp = onRestartApp
            )
            SettingsCategory.EMULATION -> TerminalEmulationSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.SEARCH -> SearchSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.HYPERLINKS -> HyperlinkSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.TYPE_AHEAD -> TypeAheadSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.DEBUG -> DebugSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.LOGGING -> LoggingSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.NOTIFICATIONS -> NotificationSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.SPLITS -> SplitsSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.AI_ASSISTANTS -> AIAssistantSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.GLOBAL_HOTKEY -> GlobalHotkeySection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.MCP -> McpSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.DAEMON -> ai.rever.bossterm.compose.settings.sections.DaemonSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onSettingsSave = onSettingsSave
            )
            SettingsCategory.SESSION_SHARING -> SessionSharingSettingsSection(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
            SettingsCategory.ABOUT -> AboutSection()
        }
    }
}
