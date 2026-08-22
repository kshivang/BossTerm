package ai.rever.bossterm.compose.history

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import ai.rever.bossterm.compose.settings.theme.BossUiTheme
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
import ai.rever.bossterm.compose.palette.FuzzyMatch

/**
 * Fuzzy history-search overlay. Renders nothing when [visible] is false. Type to
 * fuzzy-filter [history], ↑/↓ to move (wrapping), Enter to select, Esc or a
 * click on the scrim to dismiss. Mirrors the command-palette overlay's
 * structure and styling, but each row is a plain command string.
 */
@Composable
fun HistorySearchOverlay(
    visible: Boolean,
    history: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    aiEnabled: Boolean = false,
    onAskAi: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val filtered = remember(query, history) {
        if (query.isBlank()) {
            history.take(100)
        } else {
            history.mapNotNull { cmd -> FuzzyMatch.score(cmd, query)?.let { it.score to cmd } }
                .sortedByDescending { it.first }
                .map { it.second }
                .take(100)
        }
    }
    LaunchedEffect(filtered.size) {
        selected = selected.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val aiRowVisible = aiEnabled && query.isNotBlank() &&
        ai.rever.bossterm.compose.ai.InputClassifier.isNaturalLanguage(query)

    fun choose() {
        if (filtered.isEmpty() && aiRowVisible) {
            onDismiss()
            onAskAi(query)
            return
        }
        val cmd = filtered.getOrNull(selected) ?: return
        onDismiss()
        onSelect(cmd)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The modal scrim stays literal black. A scrim's job is to dim whatever is
            // behind it, and it is black under a light theme too - that is what every
            // light-mode design system does. Allowlisted in ChromeTokenCoverageTest.
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
                // Swallow clicks inside the overlay so they don't hit the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
            color = BossUiTheme.current.panel,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 8.dp,
        ) {
            Column {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; selected = 0 },
                    singleLine = true,
                    textStyle = TextStyle(color = BossUiTheme.current.chalk, fontSize = 14.sp),
                    cursorBrush = SolidColor(BossUiTheme.current.chalk),
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
                            Text("Search history…", color = BossUiTheme.current.mist, fontSize = 14.sp)
                        }
                        inner()
                    },
                )

                Box(Modifier.fillMaxWidth().height(1.dp).background(BossUiTheme.current.line))

                if (aiRowVisible) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDismiss(); onAskAi(query) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("✨ Ask AI:  $query", color = BossUiTheme.current.data, fontSize = 13.sp, maxLines = 1)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(BossUiTheme.current.line))
                }

                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(filtered) { i, cmd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // See the note in CommandPalette: IntrinsicSize.Min is what
                                // lets the indicator's fillMaxHeight() resolve inside a
                                // LazyColumn item.
                                .height(IntrinsicSize.Min)
                                .background(if (i == selected) BossUiTheme.current.signalWash else Color.Transparent)
                                .clickable { onDismiss(); onSelect(cmd) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(
                                        if (i == selected) BossUiTheme.current.signal
                                        else Color.Transparent
                                    )
                            )
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(cmd, color = BossUiTheme.current.chalk, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
