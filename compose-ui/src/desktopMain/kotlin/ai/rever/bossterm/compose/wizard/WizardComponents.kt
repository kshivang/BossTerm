package ai.rever.bossterm.compose.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.bossterm.compose.settings.SettingsTheme.AccentColor
import ai.rever.bossterm.compose.settings.SettingsTheme.BorderColor
import ai.rever.bossterm.compose.settings.SettingsTheme.SurfaceColor
import ai.rever.bossterm.compose.settings.SettingsTheme.TextMuted
import ai.rever.bossterm.compose.settings.SettingsTheme.TextPrimary
import ai.rever.bossterm.compose.settings.SettingsTheme.TextSecondary

/**
 * Generic step indicator for wizards.
 * Shows progress through wizard steps with circles and connecting lines.
 *
 * @param currentIndex Current step index (0-based)
 * @param steps List of step display names
 */
@Composable
fun WizardStepIndicator(
    currentIndex: Int,
    steps: List<String>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, stepName ->
            val isActive = index == currentIndex
            val isComplete = index < currentIndex

            // Step circle
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isActive -> AccentColor
                            isComplete -> AccentColor.copy(alpha = 0.6f)
                            else -> SurfaceColor
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isComplete) "✓" else "${index + 1}",
                    color = if (isActive || isComplete) Color.White else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Connector line (not after last step)
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(2.dp)
                        .background(
                            if (index < currentIndex)
                                AccentColor.copy(alpha = 0.6f)
                            else
                                SurfaceColor
                        )
                )
            }
        }
    }
}

/**
 * Selection card for radio-style single select options.
 */
@Composable
fun SelectionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    isRecommended: Boolean = false,
    isDisabled: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit
) {
    val alpha = if (isDisabled) 0.5f else 1f
    val bgColor = if (isSelected) AccentColor.copy(alpha = 0.15f * alpha) else SurfaceColor.copy(alpha = alpha)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDisabled) { onClick() },
        backgroundColor = bgColor,
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, AccentColor)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, BorderColor.copy(alpha = alpha))
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = { if (!isDisabled) onClick() },
                enabled = !isDisabled,
                colors = RadioButtonDefaults.colors(
                    selectedColor = AccentColor,
                    unselectedColor = TextMuted
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary.copy(alpha = alpha)
                    )
                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(AccentColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Recommended", fontSize = 10.sp, color = Color.White)
                        }
                    }
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(TextMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(badge, fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextSecondary.copy(alpha = alpha)
                )
            }
        }
    }
}

/**
 * Checkbox card for multi-select options.
 */
@Composable
fun CheckboxCard(
    title: String,
    description: String,
    isChecked: Boolean,
    isDisabled: Boolean = false,
    badge: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (isDisabled) 0.5f else 1f
    val bgColor = if (isChecked) AccentColor.copy(alpha = 0.15f * alpha) else SurfaceColor.copy(alpha = alpha)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDisabled) { onCheckedChange(!isChecked) },
        backgroundColor = bgColor,
        border = if (isChecked && !isDisabled) {
            androidx.compose.foundation.BorderStroke(2.dp, AccentColor)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, BorderColor.copy(alpha = alpha))
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { if (!isDisabled) onCheckedChange(it) },
                enabled = !isDisabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentColor,
                    uncheckedColor = TextMuted,
                    checkmarkColor = Color.White
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary.copy(alpha = alpha)
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(TextMuted.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(badge, fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextSecondary.copy(alpha = alpha)
                )
            }
        }
    }
}
