package ai.rever.bossterm.compose.window

import ai.rever.bossterm.compose.share.SessionShareManager
import ai.rever.bossterm.compose.share.ShareScope

/** Keep hosted controls on user-managed shares belonging to the selected terminal. */
internal class HostedShareController(
    private val userShares: () -> Set<String> = { SessionShareManager.sharedTabIds.value },
    private val infoFor: (String) -> SessionShareManager.ShareInfo? = SessionShareManager::infoFor,
    private val createShare: suspend (String, ShareScope) -> SessionShareManager.ShareInfo? =
        { id, scope -> SessionShareManager.share(id, scope) },
) {
    fun existing(tabId: String?): SessionShareManager.ShareInfo? =
        tabId?.takeIf { it in userShares() }?.let(infoFor)

    // Always go through the manager: infoFor also returns account-managed shares, which must
    // never replace an explicitly requested TAB/WINDOW share or bypass its ownership guard.
    suspend fun open(tabId: String, scope: ShareScope): SessionShareManager.ShareInfo? =
        createShare(tabId, scope)
}
