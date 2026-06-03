package ai.rever.bossterm.compose.workflows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
 * Parameter-entry overlay for running a [Workflow]. Renders one field per
 * argument (prefilled with its default), a live preview of the substituted
 * command, and a submit button labeled per [autoRun]. Esc cancels.
 *
 * @param onSubmit receives the fully rendered command string.
 */
@Composable
fun WorkflowRunDialog(
    workflow: Workflow,
    autoRun: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (rendered: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val values = remember(workflow) {
        mutableStateMapOf<String, String>().apply {
            workflow.arguments.forEach { put(it.name, it.defaultValue ?: "") }
        }
    }
    val firstFieldFocus = remember { FocusRequester() }

    fun submit() {
        onDismiss()
        onSubmit(workflow.render(values))
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
                .width(460.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        onDismiss(); true
                    } else {
                        false
                    }
                },
            color = Color(0xFF2A2A2A),
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(workflow.name, color = Color.White, fontSize = 15.sp)
                workflow.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xFFAAAAAA), fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))

                workflow.arguments.forEachIndexed { index, arg ->
                    Text(arg.name, color = Color(0xFFCCCCCC), fontSize = 12.sp)
                    arg.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color(0xFF808080), fontSize = 11.sp)
                    }
                    BasicTextField(
                        value = values[arg.name].orEmpty(),
                        onValueChange = { values[arg.name] = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Color(0xFF1E1E1E))
                            .padding(8.dp)
                            .then(if (index == 0) Modifier.focusRequester(firstFieldFocus) else Modifier)
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { submit(); true } else false
                            },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Live preview of the command that will run.
                Text("Preview", color = Color(0xFF808080), fontSize = 11.sp)
                Text(workflow.render(values), color = Color(0xFF66D9EF), fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogButton("Cancel", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    DialogButton(if (autoRun) "Run" else "Insert", primary = true, onClick = { submit() })
                }
            }
        }
    }

    LaunchedEffect(workflow) {
        if (workflow.arguments.isNotEmpty()) firstFieldFocus.requestFocus()
    }
}

@Composable
private fun DialogButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (primary) Color(0xFF094771) else Color(0xFF3A3A3A),
                RoundedCornerShape(4.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}
