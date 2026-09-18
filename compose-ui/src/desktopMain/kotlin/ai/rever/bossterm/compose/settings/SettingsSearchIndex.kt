package ai.rever.bossterm.compose.settings

import androidx.compose.runtime.staticCompositionLocalOf
import ai.rever.bossterm.compose.window.isLiquidGlassTheme

internal data class SettingsSearchEntry(val category: SettingsCategory, val group: String, val label: String)
internal data class SettingsSearchDestination(val group: String, val nonce: Int)
internal val LocalSettingsSearchDestination = staticCompositionLocalOf<SettingsSearchDestination?> { null }

/** Searchable labels grouped by their destination section. Keep these with the settings UI labels. */
internal object SettingsSearchIndex {
    private fun group(category: SettingsCategory, title: String, labels: String): List<SettingsSearchEntry> =
        (listOf(title) + labels.split('|').filter { it.isNotBlank() }).distinct().map {
            SettingsSearchEntry(category, title, it)
        }

    val entries: List<SettingsSearchEntry> = listOf(
        group(SettingsCategory.VISUAL, "Font", "Font Family|Font Size|Line Spacing|Disable Line Spacing in Fullscreen Apps|Fill Background in Line Spacing|Use Antialiasing|Minimum Text Contrast (Light Backgrounds)|Symbol Font"),
        group(SettingsCategory.VISUAL, "Window Style", "Use Native Title Bar"),
        group(SettingsCategory.VISUAL, "Background Image", "Image Path|Image Opacity|Blur Background Image|Blur Radius"),
        group(SettingsCategory.THEMES, "Select Theme", ""),
        group(SettingsCategory.THEMES, "Glass", "Glass Coverage|Glass Tint|Glass Style|Background Opacity"),
        group(SettingsCategory.THEMES, "Transparency", "Background Opacity"),
        group(SettingsCategory.THEMES, "Terminal Colors", "Foreground|Background|Cursor|Selection|Search Match|Hyperlink"),
        group(SettingsCategory.THEMES, "Color Palette", ""),
        group(SettingsCategory.THEMES, "ANSI Color Palette", ""),
        group(SettingsCategory.BEHAVIOR, "Shell", "Default Shell|Use Login Session (macOS)|Initial Command|Initial Command Delay"),
        group(SettingsCategory.BEHAVIOR, "Tab Bar", "Always Show Tab Bar|Position|Vertical Bar Width|Expand Sidebar on Hover|Summary Mode|Color Tabs by Directory|Preview Output on Tab Hover"),
        group(SettingsCategory.BEHAVIOR, "Clipboard", "Copy on Select|Paste on Middle Click|Emulate X11 Copy/Paste|OSC 52 Clipboard Access|Allow Clipboard Read (OSC 52)|Allow Clipboard Write (OSC 52)"),
        group(SettingsCategory.BEHAVIOR, "Keyboard", "Shift+Enter Behavior|Alt Sends Escape|Scroll to Bottom on Typing"),
        group(SettingsCategory.BEHAVIOR, "Mouse", "Native Context Menus|Enable Mouse Reporting|Force Local Actions|Scroll Sensitivity Threshold|Scroll Speed Multiplier"),
        group(SettingsCategory.BEHAVIOR, "Selection", "Use Inverse Selection Color"),
        group(SettingsCategory.BEHAVIOR, "Bell", "Audible Bell|Visual Bell"),
        group(SettingsCategory.BEHAVIOR, "Progress Bar", "Enable Progress Bar|Position|Height"),
        group(SettingsCategory.SCROLLBAR, "Appearance", "Show Scrollbar|Show scrollbar gutter|Always Visible|Scrollbar Width"),
        group(SettingsCategory.SCROLLBAR, "Terminal spacing", "Right edge gap|Gap width"),
        group(SettingsCategory.SCROLLBAR, "Colors", "Gutter Color|Thumb Color"),
        group(SettingsCategory.SCROLLBAR, "Search Markers", "Show Search Markers|Marker Color|Current Match Color"),
        group(SettingsCategory.COMMAND_BLOCKS, "Command Blocks", "Enable Command Blocks|Gutter Width|Show Scrollbar Markers|Highlight Block Background"),
        group(SettingsCategory.COMMAND_BLOCKS, "Colors", "Success|Error|Running"),
        group(SettingsCategory.COMMAND_BLOCKS, "Command Palette", "Enable Command Palette"),
        group(SettingsCategory.WORKFLOWS, "Workflows", "Enable Workflows|Run on Submit"),
        group(SettingsCategory.HISTORY_AI, "History Search", "Enable History Search"),
        group(SettingsCategory.HISTORY_AI, "AI Command Bar", "Enable AI Command Bar"),
        group(SettingsCategory.SESSION, "Session Restore", "Restore Session on Launch"),
        group(SettingsCategory.EXTRAS, "Status", "Git Branch Indicator"),
        group(SettingsCategory.EXTRAS, "Shell", "Vi Mode|Autosuggestions"),
        group(SettingsCategory.EXTRAS, "Power", "Prevent Sleep During Long Commands|Threshold"),
        group(SettingsCategory.PERFORMANCE, "GPU Rendering (requires restart)", "GPU Acceleration|Render API|GPU Selection|VSync|GPU Cache Size|Max Cache % of RAM"),
        group(SettingsCategory.PERFORMANCE, "Performance Mode", "Optimization Mode"),
        group(SettingsCategory.PERFORMANCE, "Rendering", "Maximum Refresh Rate"),
        group(SettingsCategory.PERFORMANCE, "Buffer", "Scrollback Buffer Lines"),
        group(SettingsCategory.PERFORMANCE, "Sessions", "Max Session Threads"),
        group(SettingsCategory.PERFORMANCE, "Cursor", "Cursor Blink Rate|Cursor Opacity (Focused)|Cursor Opacity (Unfocused)"),
        group(SettingsCategory.PERFORMANCE, "Text Blinking", "Enable Text Blinking|Slow Blink Rate|Rapid Blink Rate"),
        group(SettingsCategory.EMULATION, "Compatibility", "DEC Compatibility Mode|Allow Kitty File Image Transfers"),
        group(SettingsCategory.EMULATION, "Character Encoding", "Character Encoding|Ambiguous Chars Are Double-Width"),
        group(SettingsCategory.EMULATION, "Mouse Emulation", "Simulate Scroll in Alternate Screen"),
        group(SettingsCategory.SEARCH, "Search Defaults", "Case Sensitive by Default|Use Regex by Default"),
        group(SettingsCategory.HYPERLINKS, "Hyperlink Behavior", "Underline on Hover|Require Modifier Key to Click"),
        group(SettingsCategory.TYPE_AHEAD, "Type-Ahead Prediction", "Enable Type-Ahead|Latency Threshold (nanoseconds)"),
        group(SettingsCategory.DEBUG, "Debug Mode", "Enable Debug Mode"),
        group(SettingsCategory.DEBUG, "Data Capture", "Max I/O Chunks|Max State Snapshots|Snapshot Interval (ms)"),
        group(SettingsCategory.DEBUG, "Visualization", "Show Chunk IDs|Show Invisible Characters|Wrap Long Lines|Color-Code Sequences"),
        group(SettingsCategory.LOGGING, "File Logging", "Enable File Logging"),
        group(SettingsCategory.LOGGING, "Log Files", "Log Directory|Filename Pattern"),
        group(SettingsCategory.NOTIFICATIONS, "Shell Integration", "Auto-inject Shell Integration"),
        group(SettingsCategory.NOTIFICATIONS, "Command Notifications", "Enable Notifications|Minimum Duration"),
        group(SettingsCategory.NOTIFICATIONS, "Notification Style", "Show Exit Code|Play Sound"),
        group(SettingsCategory.SPLITS, "Split Behavior", "Default Split Ratio|Minimum Pane Size|Inherit Working Directory"),
        group(SettingsCategory.SPLITS, "Focus Indicator", "Show Focus Border|Match Theme|Focus Border Color"),
        group(SettingsCategory.AI_ASSISTANTS, "Context Menu", "Enable AI Assistants Menu"),
        group(SettingsCategory.AI_ASSISTANTS, "Built-in Assistants", ""),
        group(SettingsCategory.AI_ASSISTANTS, "Custom Assistants", ""),
        group(SettingsCategory.AI_ASSISTANTS, "Usage", ""),
        group(SettingsCategory.GLOBAL_HOTKEY, "Global Hotkey", "Enable Global Hotkeys|Show/Hide Toggle Hotkey|Show Hotkey Hint in Window"),
        group(SettingsCategory.GLOBAL_HOTKEY, "Modifier Keys", ""),
        group(SettingsCategory.MCP, "BossTerm MCP Server", "Enable BossTerm MCP Server|Port|Show Status Indicator in Tab Bar|Default Split Size for `run_in_panel` / `run_command`|Default Panel Mode for `run_command`|Default `run_command` Timeout (ms)|Use `run_command` as AI clients' default shell"),
        group(SettingsCategory.MCP, "Exposed Tools", ""),
        group(SettingsCategory.MCP, "Attach to AI CLI", ""),
        group(SettingsCategory.DAEMON, "Session Daemon", "Run sessions in a background daemon|Start daemon at login"),
        group(SettingsCategory.DAEMON, "Status", ""),
        group(SettingsCategory.SESSION_SHARING, "Session Sharing", "Enable Session Sharing|Port|Bind scope|Custom bind host"),
        group(SettingsCategory.SESSION_SHARING, "Remote Access (advanced)", "Remote access|Public URL override|Require device approval"),
        group(SettingsCategory.ABOUT, "Application", ""),
        group(SettingsCategory.ABOUT, "Version Management", ""),
        group(SettingsCategory.ABOUT, "System", ""),
        group(SettingsCategory.ABOUT, "GPU Rendering", ""),
        group(SettingsCategory.ABOUT, "Links", ""),
        group(SettingsCategory.ABOUT, "Keyboard Shortcuts", ""),
        group(SettingsCategory.ABOUT, "License", ""),
        group(SettingsCategory.ABOUT, "Acknowledgments", ""),
    ).flatten()

    fun search(query: String, categories: List<SettingsCategory>, settings: TerminalSettings): List<SettingsSearchEntry> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val pages = categories.map { SettingsSearchEntry(it, "", it.displayName) }
        val themes = ai.rever.bossterm.compose.settings.theme.BuiltinThemes.ALL.map {
            SettingsSearchEntry(SettingsCategory.THEMES, "Select Theme", it.name)
        }
        return (entries + pages + themes).asSequence()
            .filter { it.category in categories }
            .filter { it.category != SettingsCategory.THEMES || when (it.group) {
                "Glass" -> settings.isLiquidGlassTheme
                "Transparency" -> !settings.isLiquidGlassTheme
                else -> true
            } }
            .filter { it.label != "Glass Style" || ai.rever.bossterm.compose.shell.ShellCustomizationUtils.isMacOS() }
            .mapNotNull { entry ->
                val label = entry.label.lowercase()
                val context = "${entry.group} ${entry.category.displayName} ${entry.category.description}".lowercase()
                if (tokens.all { it in label || it in context }) {
                    entry to tokens.sumOf { if (it == label) 100 else if (label.startsWith(it)) 50 else if (it in label) 30 else 1 }
                } else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(40)
            .toList()
    }
}
