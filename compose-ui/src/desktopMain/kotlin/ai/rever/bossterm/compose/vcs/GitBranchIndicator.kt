package ai.rever.bossterm.compose.vcs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small, unobtrusive indicator showing the current git branch for [cwd].
 *
 * Resolution runs off the main thread via [GitUtils.getCurrentBranch] whenever
 * [cwd] or [enabled] changes. Nothing is rendered when [enabled] is false or
 * when the directory is not inside a git repository (branch resolves to null).
 *
 * @param cwd the working directory to inspect; may be null.
 * @param enabled whether the indicator is active. When false, renders nothing.
 * @param modifier optional [Modifier] applied to the branch text.
 */
@Composable
fun GitBranchIndicator(cwd: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return

    var branch by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cwd, enabled) {
        branch = if (enabled) {
            withContext(Dispatchers.IO) { GitUtils.getCurrentBranch(cwd) }
        } else {
            null
        }
    }

    branch?.let {
        Text(
            text = "⎇ $it",
            color = Color(0xFFAAAAAA),
            fontSize = 11.sp,
            modifier = modifier,
        )
    }
}
