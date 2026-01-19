package ai.rever.bossterm.compose.settings.sections

import ai.rever.bossterm.compose.ai.AIAssistantDetector
import ai.rever.bossterm.compose.ai.AIAssistantDefinition
import ai.rever.bossterm.compose.ai.ToolCommandProvider
import ai.rever.bossterm.compose.ai.AIAssistants
import ai.rever.bossterm.compose.settings.AIAssistantConfigData
import ai.rever.bossterm.compose.settings.CustomAIAssistantData
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BackgroundColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary
import ai.rever.bossterm.compose.settings.components.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.UUID

/**
 * AI Assistant settings section: configure AI coding assistant integration.
 */
@Composable
fun AIAssistantSettingsSection(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onSettingsSave: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val detector = remember { AIAssistantDetector() }
    val launcher = remember { ToolCommandProvider() }
    val installationStatus by detector.installationStatus.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showAddCustomDialog by remember { mutableStateOf(false) }
    var editingCustomAssistant by remember { mutableStateOf<CustomAIAssistantData?>(null) }

    // Load custom assistants into registry
    LaunchedEffect(settings.customAIAssistants) {
        AIAssistants.setCustomAssistants(
            settings.customAIAssistants.map { custom ->
                AIAssistantDefinition(
                    id = custom.id,
                    displayName = custom.displayName,
                    command = custom.command,
                    yoloFlag = custom.yoloFlag,
                    yoloLabel = custom.yoloLabel,
                    description = custom.description,
                    websiteUrl = custom.websiteUrl,
                    isBuiltIn = false
                )
            }
        )
    }

    // Initial detection (on-demand, no polling)
    LaunchedEffect(Unit) {
        detector.detectAll()
    }

    Column(modifier = modifier) {
        SettingsSection(title = "Context Menu") {
            SettingsToggle(
                label = "Enable AI Assistants Menu",
                checked = settings.aiAssistantsEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(aiAssistantsEnabled = it)) },
                description = "Show AI Assistants submenu in terminal context menu"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Built-in Assistants Section
        SettingsSection(title = "Built-in Assistants") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Configure AI assistants and their auto-mode flags",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            detector.detectAll()
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = AccentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AIAssistants.BUILTIN.forEach { assistant ->
                val isInstalled = installationStatus[assistant.id] ?: false
                val config = settings.aiAssistantConfigs[assistant.id] ?: AIAssistantConfigData()

                AIAssistantConfigCard(
                    assistant = assistant,
                    config = config,
                    isInstalled = isInstalled,
                    installCommand = launcher.getInstallCommand(assistant).trim(),
                    onConfigChange = { newConfig ->
                        val updatedConfigs = settings.aiAssistantConfigs.toMutableMap()
                        updatedConfigs[assistant.id] = newConfig
                        onSettingsChange(settings.copy(aiAssistantConfigs = updatedConfigs))
                    },
                    onDelete = null  // Built-in assistants can't be deleted
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Custom Assistants Section
        SettingsSection(title = "Custom Assistants") {
            Text(
                text = "Add your own AI coding assistants",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (settings.customAIAssistants.isEmpty()) {
                Text(
                    text = "No custom assistants configured",
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                settings.customAIAssistants.forEach { custom ->
                    val assistant = AIAssistantDefinition(
                        id = custom.id,
                        displayName = custom.displayName,
                        command = custom.command,
                        yoloFlag = custom.yoloFlag,
                        yoloLabel = custom.yoloLabel,
                        description = custom.description,
                        websiteUrl = custom.websiteUrl,
                        isBuiltIn = false
                    )
                    val isInstalled = installationStatus[custom.id] ?: false
                    val config = settings.aiAssistantConfigs[custom.id] ?: AIAssistantConfigData()

                    AIAssistantConfigCard(
                        assistant = assistant,
                        config = config,
                        isInstalled = isInstalled,
                        installCommand = "",
                        onConfigChange = { newConfig ->
                            val updatedConfigs = settings.aiAssistantConfigs.toMutableMap()
                            updatedConfigs[custom.id] = newConfig
                            onSettingsChange(settings.copy(aiAssistantConfigs = updatedConfigs))
                        },
                        onDelete = {
                            val updatedCustom = settings.customAIAssistants.filter { it.id != custom.id }
                            val updatedConfigs = settings.aiAssistantConfigs.toMutableMap()
                            updatedConfigs.remove(custom.id)
                            onSettingsChange(settings.copy(
                                customAIAssistants = updatedCustom,
                                aiAssistantConfigs = updatedConfigs
                            ))
                        },
                        onEdit = { editingCustomAssistant = custom }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showAddCustomDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Custom Assistant")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Usage Notes
        SettingsSection(title = "Usage") {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                NoteItem("Right-click in terminal to access AI Assistants menu")
                Spacer(modifier = Modifier.height(6.dp))
                NoteItem("Auto mode runs assistants with auto-approve flags enabled by default")
                Spacer(modifier = Modifier.height(6.dp))
                NoteItem("Disable auto mode in the card settings to run without flags")
            }
        }
    }

    // Add/Edit Custom Assistant Dialog
    if (showAddCustomDialog || editingCustomAssistant != null) {
        CustomAssistantDialog(
            existing = editingCustomAssistant,
            onDismiss = {
                showAddCustomDialog = false
                editingCustomAssistant = null
            },
            onSave = { custom ->
                val updatedCustom = if (editingCustomAssistant != null) {
                    settings.customAIAssistants.map { if (it.id == custom.id) custom else it }
                } else {
                    settings.customAIAssistants + custom
                }
                onSettingsChange(settings.copy(customAIAssistants = updatedCustom))
                showAddCustomDialog = false
                editingCustomAssistant = null
            }
        )
    }
}

@Composable
private fun AIAssistantConfigCard(
    assistant: AIAssistantDefinition,
    config: AIAssistantConfigData,
    isInstalled: Boolean,
    installCommand: String,
    onConfigChange: (AIAssistantConfigData) -> Unit,
    onDelete: (() -> Unit)?,
    onEdit: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    val borderColor = if (config.enabled && isInstalled) Color(0xFF4CAF50).copy(alpha = 0.5f) else Color(0xFF3C3C3C)
    val backgroundColor = if (config.enabled && isInstalled) Color(0xFF4CAF50).copy(alpha = 0.05f) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = assistant.displayName,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusBadge(isInstalled = isInstalled)
                    if (!assistant.isBuiltIn) {
                        Spacer(modifier = Modifier.width(6.dp))
                        CustomBadge()
                    }
                }
                if (assistant.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = assistant.description,
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Row {
                if (onEdit != null) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, "Edit", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFE57373), modifier = Modifier.size(16.dp))
                    }
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentColor,
                        checkedTrackColor = AccentColor.copy(alpha = 0.5f)
                    )
                )
            }
        }

        if (config.enabled) {
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = Color(0xFF3C3C3C))
            Spacer(modifier = Modifier.height(12.dp))

            // Auto Mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${assistant.yoloLabel} Mode",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    val flag = config.customYoloFlag?.takeIf { it.isNotBlank() } ?: assistant.yoloFlag
                    if (flag.isNotBlank()) {
                        Text(
                            text = "Flag: $flag",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                Switch(
                    checked = config.yoloEnabled,
                    onCheckedChange = { onConfigChange(config.copy(yoloEnabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentColor,
                        checkedTrackColor = AccentColor.copy(alpha = 0.5f)
                    )
                )
            }

            // Expandable advanced settings
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (expanded) "Hide Advanced" else "Show Advanced",
                color = AccentColor,
                fontSize = 12.sp,
                modifier = Modifier.clickable { expanded = !expanded }
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))

                // Custom command
                Text("Custom Command", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = config.customCommand ?: "",
                    onValueChange = { onConfigChange(config.copy(customCommand = it.ifBlank { null })) },
                    placeholder = { Text(assistant.command, color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = AccentColor,
                        textColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Custom flag
                Text("Custom Auto-Mode Flag", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = config.customYoloFlag ?: "",
                    onValueChange = { onConfigChange(config.copy(customYoloFlag = it.ifBlank { null })) },
                    placeholder = { Text(assistant.yoloFlag.ifBlank { "e.g., --auto-approve" }, color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = AccentColor,
                        textColor = TextPrimary
                    )
                )
            }
        }

        // Install command for uninstalled built-in assistants
        if (!isInstalled && assistant.isBuiltIn && installCommand.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = Color(0xFF3C3C3C))
            Spacer(modifier = Modifier.height(12.dp))

            Text("Install Command", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1A1A1A))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = installCommand.take(60) + if (installCommand.length > 60) "..." else "",
                    color = Color(0xFFCE9178),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(installCommand), null)
                        copied = true
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (copied) Color(0xFF4CAF50) else AccentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomAssistantDialog(
    existing: CustomAIAssistantData?,
    onDismiss: () -> Unit,
    onSave: (CustomAIAssistantData) -> Unit
) {
    var displayName by remember { mutableStateOf(existing?.displayName ?: "") }
    var command by remember { mutableStateOf(existing?.command ?: "") }
    var yoloFlag by remember { mutableStateOf(existing?.yoloFlag ?: "") }
    var yoloLabel by remember { mutableStateOf(existing?.yoloLabel ?: "Auto") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var websiteUrl by remember { mutableStateOf(existing?.websiteUrl ?: "") }

    val dialogTextFieldColors = TextFieldDefaults.outlinedTextFieldColors(
        textColor = TextPrimary,
        cursorColor = AccentColor,
        focusedBorderColor = AccentColor,
        unfocusedBorderColor = BorderColor,
        focusedLabelColor = AccentColor,
        unfocusedLabelColor = TextSecondary,
        placeholderColor = TextMuted,
        backgroundColor = BackgroundColor
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (existing != null) "Edit Custom Assistant" else "Add Custom Assistant",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name *", color = TextSecondary, fontSize = 12.sp) },
                    placeholder = { Text("My AI Assistant", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    colors = dialogTextFieldColors
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command *", color = TextSecondary, fontSize = 12.sp) },
                    placeholder = { Text("my-assistant", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    colors = dialogTextFieldColors
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = yoloFlag,
                    onValueChange = { yoloFlag = it },
                    label = { Text("Auto-Mode Flag", color = TextSecondary, fontSize = 12.sp) },
                    placeholder = { Text("--auto-approve", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    colors = dialogTextFieldColors
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = yoloLabel,
                    onValueChange = { yoloLabel = it },
                    label = { Text("Auto-Mode Label", color = TextSecondary, fontSize = 12.sp) },
                    placeholder = { Text("Auto", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    colors = dialogTextFieldColors
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description", color = TextSecondary, fontSize = 12.sp) },
                    placeholder = { Text("Brief description", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    colors = dialogTextFieldColors
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = websiteUrl,
                    onValueChange = { websiteUrl = it },
                    label = { Text("Website URL", color = TextSecondary, fontSize = 12.sp) },
                    placeholder = { Text("https://...", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    colors = dialogTextFieldColors
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (displayName.isNotBlank() && command.isNotBlank()) {
                        onSave(
                            CustomAIAssistantData(
                                id = existing?.id ?: "custom-${UUID.randomUUID().toString().take(8)}",
                                displayName = displayName.trim(),
                                command = command.trim(),
                                yoloFlag = yoloFlag.trim(),
                                yoloLabel = yoloLabel.trim().ifBlank { "Auto" },
                                description = description.trim(),
                                websiteUrl = websiteUrl.trim()
                            )
                        )
                    }
                },
                enabled = displayName.isNotBlank() && command.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AccentColor,
                    disabledBackgroundColor = AccentColor.copy(alpha = 0.3f)
                )
            ) {
                Text("Save", color = Color.White, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary, fontSize = 13.sp)
            }
        },
        backgroundColor = SurfaceColor,
        contentColor = TextPrimary
    )
}

@Composable
private fun StatusBadge(isInstalled: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isInstalled) Color(0xFF2E7D32).copy(alpha = 0.2f)
                else Color(0xFFD32F2F).copy(alpha = 0.2f)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isInstalled) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (isInstalled) Color(0xFF4CAF50) else Color(0xFFE57373),
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isInstalled) "Installed" else "Not Found",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = if (isInstalled) Color(0xFF4CAF50) else Color(0xFFE57373)
            )
        }
    }
}

@Composable
private fun CustomBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AccentColor.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Custom",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = AccentColor
        )
    }
}

@Composable
private fun NoteItem(text: String) {
    Text(
        text = "- $text",
        color = TextMuted,
        fontSize = 12.sp
    )
}
