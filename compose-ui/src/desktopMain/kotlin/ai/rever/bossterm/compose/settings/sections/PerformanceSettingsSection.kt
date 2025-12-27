package ai.rever.bossterm.compose.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SystemInfoUtils
import ai.rever.bossterm.compose.settings.components.*

/**
 * Performance settings section: performance mode, refresh rate, buffer, and blink settings.
 */
@Composable
fun PerformanceSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    onRestartApp: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Platform detection
    val osName = System.getProperty("os.name").lowercase()
    val isMacOS = osName.contains("mac")
    val isWindows = osName.contains("windows")

    // Calculate dynamic GPU cache limits based on system memory and user's max percent setting
    // Computed directly (no remember) to ensure reactivity when max percent changes
    val gpuCacheLimits = calculateGpuCacheLimits(settings.gpuCacheMaxPercent)

    Column(modifier = modifier) {
        // GPU Rendering Settings
        // Note: All GPU settings require app restart to take effect
        SettingsSection(title = "GPU Rendering (requires restart)") {
            SettingsToggle(
                label = "GPU Acceleration",
                checked = settings.gpuAcceleration,
                onCheckedChange = {
                    onSettingsChange(settings.copy(gpuAcceleration = it))
                },
                description = "Use hardware-accelerated rendering via Skia"
            )

            // Render API dropdown - only show when GPU acceleration is enabled
            if (settings.gpuAcceleration) {
                val apiOptions = buildList {
                    add("Auto")
                    if (isMacOS) add("Metal")
                    add("OpenGL")
                    if (isWindows) add("Direct3D")
                    add("Software")
                }

                SettingsDropdown(
                    label = "Render API",
                    options = apiOptions,
                    selectedOption = when (settings.gpuRenderApi.lowercase()) {
                        "metal" -> "Metal"
                        "opengl" -> "OpenGL"
                        "direct3d" -> "Direct3D"
                        "software" -> "Software"
                        else -> "Auto"
                    },
                    onOptionSelected = { selected ->
                        onSettingsChange(settings.copy(gpuRenderApi = selected.lowercase()))
                    },
                    description = when (settings.gpuRenderApi.lowercase()) {
                        "metal" -> "Apple Metal - Best performance on macOS"
                        "opengl" -> "OpenGL - Cross-platform, widely supported"
                        "direct3d" -> "DirectX 12 - Best performance on Windows"
                        "software" -> "CPU rendering - Fallback when GPU unavailable"
                        else -> "Automatic - Platform optimal (recommended)"
                    }
                )

                // GPU Priority dropdown - only for macOS and Windows
                if (isMacOS || isWindows) {
                    SettingsDropdown(
                        label = "GPU Selection",
                        options = listOf("Auto", "Integrated", "Discrete"),
                        selectedOption = when (settings.gpuPriority.lowercase()) {
                            "integrated" -> "Integrated"
                            "discrete" -> "Discrete"
                            else -> "Auto"
                        },
                        onOptionSelected = { selected ->
                            onSettingsChange(settings.copy(gpuPriority = selected.lowercase()))
                        },
                        description = when (settings.gpuPriority.lowercase()) {
                            "integrated" -> "Use integrated GPU - Lower power, cooler"
                            "discrete" -> "Use discrete GPU - Higher performance"
                            else -> "System decides - Usually integrated for power saving"
                        }
                    )
                }

                SettingsToggle(
                    label = "VSync",
                    checked = settings.gpuVsyncEnabled,
                    onCheckedChange = {
                        onSettingsChange(settings.copy(gpuVsyncEnabled = it))
                    },
                    description = if (settings.gpuVsyncEnabled)
                        "Synchronized with display - Smooth, no tearing"
                    else
                        "Uncapped frame rate - May cause tearing"
                )

                SettingsSlider(
                    label = "GPU Cache Size",
                    value = settings.gpuCacheSizeMb.toFloat().coerceIn(gpuCacheLimits.min.toFloat(), gpuCacheLimits.max.toFloat()),
                    onValueChange = {
                        onSettingsChange(settings.copy(gpuCacheSizeMb = it.toInt()))
                    },
                    onValueChangeFinished = onSettingsSave,
                    valueRange = gpuCacheLimits.min.toFloat()..gpuCacheLimits.max.toFloat(),
                    steps = ((gpuCacheLimits.max - gpuCacheLimits.min) / 64).coerceIn(1, 30),
                    valueDisplay = { SystemInfoUtils.formatMemorySize(it.toInt()) },
                    description = "GPU memory for caching glyphs/textures (max ${SystemInfoUtils.formatMemorySize(gpuCacheLimits.max)})"
                )

                // Advanced: Max cache percentage (continuous slider, no steps)
                SettingsSlider(
                    label = "Max Cache % of RAM",
                    value = settings.gpuCacheMaxPercent.toFloat(),
                    onValueChange = {
                        val newPercent = it.toInt()
                        // Also clamp current cache size if it exceeds new max
                        val newLimits = calculateGpuCacheLimits(newPercent)
                        val clampedCache = settings.gpuCacheSizeMb.coerceAtMost(newLimits.max)
                        onSettingsChange(settings.copy(
                            gpuCacheMaxPercent = newPercent,
                            gpuCacheSizeMb = clampedCache
                        ))
                    },
                    onValueChangeFinished = onSettingsSave,
                    valueRange = 10f..90f,
                    valueDisplay = { "${it.toInt()}%" },
                    description = "Advanced: Maximum GPU cache as % of system RAM (${gpuCacheLimits.systemRamMb} MB)"
                )
            }

            // Restart button - saves pending settings before restart
            if (onRestartApp != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            // Save any pending settings before restart
                            onSettingsSave?.invoke()
                            onRestartApp()
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = AccentColor
                        )
                    ) {
                        Text(
                            text = "Restart Now",
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Performance Mode
        SettingsSection(title = "Performance Mode") {
            SettingsDropdown(
                label = "Optimization Mode",
                options = listOf("Balanced", "Latency", "Throughput"),
                selectedOption = settings.performanceMode.replaceFirstChar { it.uppercase() },
                onOptionSelected = { selected ->
                    onSettingsChange(settings.copy(performanceMode = selected.lowercase()))
                },
                description = when (settings.performanceMode) {
                    "latency" -> "Fastest response time for typing and commands. Best for SSH sessions, vim, and interactive tools."
                    "throughput" -> "Maximum speed for large outputs. Best for build logs, cat large files, and data processing."
                    else -> "Good for most users. Balances quick response with efficient bulk output handling."
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Rendering Settings
        SettingsSection(title = "Rendering") {
            SettingsSlider(
                label = "Maximum Refresh Rate",
                value = settings.maxRefreshRate.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(maxRefreshRate = it.toInt())) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 30f..120f,
                steps = 8,
                valueDisplay = { "${it.toInt()} FPS" },
                description = "Target frame rate (0 = unlimited)"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Buffer Settings
        SettingsSection(title = "Buffer") {
            SettingsNumberInput(
                label = "Scrollback Buffer Lines",
                value = settings.bufferMaxLines,
                onValueChange = { onSettingsChange(settings.copy(bufferMaxLines = it)) },
                range = 1000..100000,
                description = "Maximum lines in history (1000-100000)"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Cursor Settings
        SettingsSection(title = "Cursor") {
            SettingsSlider(
                label = "Cursor Blink Rate",
                value = settings.caretBlinkMs.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(caretBlinkMs = it.toInt())) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 0f..1000f,
                steps = 9,
                valueDisplay = { if (it.toInt() == 0) "Off" else "${it.toInt()} ms" },
                description = "Cursor blink interval (0 = no blink)"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Text Blink Settings
        SettingsSection(title = "Text Blinking") {
            SettingsToggle(
                label = "Enable Text Blinking",
                checked = settings.enableTextBlinking,
                onCheckedChange = { onSettingsChange(settings.copy(enableTextBlinking = it)) },
                description = "Master toggle for all text blink animations"
            )

            SettingsSlider(
                label = "Slow Blink Rate",
                value = settings.slowTextBlinkMs.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(slowTextBlinkMs = it.toInt())) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 500f..2000f,
                steps = 14,
                valueDisplay = { "${it.toInt()} ms" },
                description = "Standard blink rate for BLINK attribute",
                enabled = settings.enableTextBlinking
            )

            SettingsSlider(
                label = "Rapid Blink Rate",
                value = settings.rapidTextBlinkMs.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(rapidTextBlinkMs = it.toInt())) },
                onValueChangeFinished = onSettingsSave,
                valueRange = 200f..1000f,
                steps = 7,
                valueDisplay = { "${it.toInt()} ms" },
                description = "Fast blink rate for RAPID_BLINK",
                enabled = settings.enableTextBlinking
            )
        }
    }
}

/**
 * GPU cache size limits based on system memory.
 */
private data class GpuCacheLimits(
    val min: Int,
    val max: Int,
    val default: Int,
    val systemRamMb: Int
)

/**
 * Calculate GPU cache limits based on available system memory.
 * - Min: GPU_CACHE_MIN_MB (enough for basic glyph caching)
 * - Max: User-configurable % of system RAM, capped at GPU_CACHE_MAX_MB
 * - Default: 10% of system RAM, capped at 512 MB
 *
 * @param maxPercent Maximum cache as percentage of system RAM (10-90)
 */
private fun calculateGpuCacheLimits(maxPercent: Int = 75): GpuCacheLimits {
    val systemMemoryMb = SystemInfoUtils.getSystemMemoryMb()

    val min = SystemInfoUtils.GPU_CACHE_MIN_MB
    val maxCacheMb = (systemMemoryMb * (maxPercent / 100.0)).toInt()
        .coerceAtLeast(256)
        .coerceAtMost(SystemInfoUtils.GPU_CACHE_MAX_MB)
    val default = (systemMemoryMb * 0.10).toInt().coerceIn(128, 512)

    return GpuCacheLimits(min = min, max = maxCacheMb, default = default, systemRamMb = systemMemoryMb)
}
