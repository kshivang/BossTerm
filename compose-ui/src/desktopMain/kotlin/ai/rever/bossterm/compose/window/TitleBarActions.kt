package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.features.ContextMenuController
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Session actions grouped into compact capsules over the window's native glass. */
@Composable
internal fun TitleBarActions(
    onNewTab: () -> Unit,
    onSplitVertical: () -> Unit,
    onSplitHorizontal: () -> Unit,
    onAddRemote: () -> Unit,
    onSettings: () -> Unit
) {
    if (LocalTitleBarTrailing.current) {
        val menu = remember { ContextMenuController() }
        DisposableEffect(menu) { onDispose { menu.hideMenu() } }
        val compact = LocalCompactTitleBar.current
        ActionCapsule {
            ActionIcon(Icons.Default.MoreHoriz, "More terminal actions", {
                val items = buildList<ContextMenuController.MenuElement> {
                    if (compact) {
                        add(ContextMenuController.MenuItem("split_vertical", "Split Left/Right", enabled = true, action = onSplitVertical))
                        add(ContextMenuController.MenuItem("split_horizontal", "Split Top/Bottom", enabled = true, action = onSplitHorizontal))
                        add(ContextMenuController.MenuSeparator("connections"))
                    }
                    add(ContextMenuController.MenuItem("add_remote", "Add Remote Session", enabled = true, action = onAddRemote))
                    add(ContextMenuController.MenuSeparator("preferences"))
                    add(ContextMenuController.MenuItem("settings", "Settings…", enabled = true, action = onSettings))
                }
                menu.showMenu(0f, 0f, items)
            })
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionCapsule { ActionIcon(Icons.Default.Add, "New tab", onNewTab) }
            if (!LocalCompactTitleBar.current) ActionCapsule {
                ActionIcon(Icons.Default.VerticalSplit, "Split left/right", onSplitVertical)
                ActionIcon(Icons.Default.HorizontalSplit, "Split top/bottom", onSplitHorizontal)
            }
        }
    }
}

@Composable
internal fun ActionCapsule(modifier: Modifier = Modifier, active: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val chrome = LocalTitleBarTheme.current
    val preferences = LocalTitleBarPreferences.current
    Surface(
        modifier = modifier,
        color = if (active) chrome.signalWash else if (preferences.reduceTransparency) chrome.raised else chrome.ink.copy(alpha = 0.65f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(if (active) 1.5.dp else if (preferences.increaseContrast) 1.dp else 0.5.dp,
            if (active) chrome.signalText else chrome.chalk.copy(alpha = if (preferences.increaseContrast) 0.7f else 0.15f))
    ) {
        Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    if (LocalNativeToolbar.current != null) {
        val id = when (icon.name.substringAfterLast('.')) {
            "Add" -> "new"
            "VerticalSplit" -> "split_vertical"
            "HorizontalSplit" -> "split_horizontal"
            else -> "more"
        }
        val symbol = when (id) {
            "new" -> "plus"
            "split_vertical" -> "rectangle.split.2x1"
            "split_horizontal" -> "rectangle.split.1x2"
            else -> "ellipsis"
        }
        RegisterNativeToolbarAction(NativeToolbarAction(id, label, symbol, onClick = onClick))
        return
    }
    val chrome = LocalTitleBarTheme.current
    TooltipArea(tooltip = {
        Surface(color = chrome.panel, shape = RoundedCornerShape(6.dp), elevation = 4.dp) {
            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = chrome.chalk, fontSize = 11.sp)
        }
    }) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            MacToolbarIcon(icon, contentDescription = label, tint = chrome.chalk, modifier = Modifier.size(16.dp))
        }
    }
}
