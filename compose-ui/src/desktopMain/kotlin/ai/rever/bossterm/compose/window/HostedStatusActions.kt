package ai.rever.bossterm.compose.window

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector

/** A live status control exported to an embedding application's native toolbar. */
data class HostedStatusAction(
    val id: String,
    val label: String,
    val symbol: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit,
)

internal val LocalHostedStatusActions = staticCompositionLocalOf<MutableMap<String, HostedStatusAction>?> { null }

/** Compose the existing status controls without drawing a duplicate floating strip. */
@Composable
fun HostedStatusActions(onActions: (List<HostedStatusAction>) -> Unit, content: @Composable () -> Unit) {
    val actions = remember { mutableStateMapOf<String, HostedStatusAction>() }
    val currentCallback by rememberUpdatedState(onActions)
    LaunchedEffect(actions) {
        snapshotFlow { listOfNotNull(actions["sharing"], actions["call"], actions["mcp"]) }
            .collect { currentCallback(it) }
    }
    DisposableEffect(Unit) { onDispose { currentCallback(emptyList()) } }
    CompositionLocalProvider(LocalHostedStatusActions provides actions, LocalInTitleBar provides true) { content() }
}

@Composable
internal fun RegisterHostedStatusAction(action: HostedStatusAction) {
    val actions = LocalHostedStatusActions.current ?: return
    val currentClick by rememberUpdatedState(action.onClick)
    val click = remember { { currentClick() } }
    val stableAction = action.copy(onClick = click)
    SideEffect { actions[action.id] = stableAction }
    DisposableEffect(actions, action.id) { onDispose { actions.remove(action.id) } }
}
