package ai.rever.bossterm.compose.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fuzzy command palette overlay (Phase 2). Renders nothing when [visible] is
 * false. Type to fuzzy-filter [commands], ↑/↓ to move, Enter to run, Esc or a
 * click on the scrim to dismiss.
 */
@Composable
fun CommandPalette(
    visible: Boolean,
    commands: List<PaletteCommand>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val filtered = remember(query, commands) {
        if (query.isBlank()) {
            commands.take(100)
        } else {
            commands.mapNotNull { c -> FuzzyMatch.score(c.title, query)?.let { it.score to c } }
                .sortedByDescending { it.first }
                .map { it.second }
                .take(100)
        }
    }
    LaunchedEffect(filtered.size) {
        selected = selected.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun choose() {
        val cmd = filtered.getOrNull(selected) ?: return
        onDismiss()
        cmd.run()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .width(560.dp)
                // Swallow clicks inside the palette so they don't hit the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
            color = Color(0xFF2A2A2A),
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 8.dp,
        ) {
            Column {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; selected = 0 },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> {
                                    if (filtered.isNotEmpty()) selected = (selected + 1) % filtered.size
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (filtered.isNotEmpty()) selected = (selected - 1 + filtered.size) % filtered.size
                                    true
                                }
                                Key.Enter -> { choose(); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("Run a command…", color = Color(0xFF808080), fontSize = 14.sp)
                        }
                        inner()
                    },
                )

                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A3A3A)))

                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(filtered) { i, c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (i == selected) Color(0xFF094771) else Color.Transparent)
                                .clickable { onDismiss(); c.run() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                c.group,
                                color = Color(0xFF888888),
                                fontSize = 11.sp,
                                modifier = Modifier.width(64.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(c.title, color = Color.White, fontSize = 13.sp, maxLines = 1)
                                c.subtitle?.let {
                                    Text(it, color = Color(0xFFAAAAAA), fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
