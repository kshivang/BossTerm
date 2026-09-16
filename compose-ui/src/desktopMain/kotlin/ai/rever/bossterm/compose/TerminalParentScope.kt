package ai.rever.bossterm.compose

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** State and composable may specify the same owner, but not conflicting jobs. */
internal fun resolveTerminalParentScope(
    stateScope: CoroutineScope?,
    suppliedScope: CoroutineScope?
): CoroutineScope? {
    require(stateScope == null || suppliedScope == null ||
        stateScope.coroutineContext[Job] === suppliedScope.coroutineContext[Job]) {
        "Terminal state and composable must use the same parent Job"
    }
    return stateScope ?: suppliedScope
}
