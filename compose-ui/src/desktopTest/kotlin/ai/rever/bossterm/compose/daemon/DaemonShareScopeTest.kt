package ai.rever.bossterm.compose.daemon

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The write-isolation invariant for SESSION-scoped daemon shares: an approved CONTROL viewer on a
 * "This session" link must only be able to mutate (type into / resize / close) its OWN session, never
 * another session by id. ALL-scoped shares cover every session. Guards [DaemonShareServer.handleClient]'s
 * scope gate, the fix for the share write-scope bypass.
 */
class DaemonShareScopeTest {

    private val ALL = DaemonAttachProtocol.ShareScopeKind.ALL
    private val SESSION = DaemonAttachProtocol.ShareScopeKind.SESSION

    @Test
    fun `ALL scope permits mutating any session`() {
        assertTrue(DaemonShareServer.mutationInScope(ALL, defSessionId = null, targetId = "s1"))
        assertTrue(DaemonShareServer.mutationInScope(ALL, defSessionId = "s1", targetId = "s2"))
    }

    @Test
    fun `SESSION scope permits only its own session`() {
        assertTrue(DaemonShareServer.mutationInScope(SESSION, defSessionId = "s1", targetId = "s1"))
    }

    @Test
    fun `SESSION scope rejects another session's id`() {
        // The bypass: a "This session" controller sending Input/Resize/Close for a different session id.
        assertFalse(DaemonShareServer.mutationInScope(SESSION, defSessionId = "s1", targetId = "s2"))
        // And a SESSION share with no backing session must not match anything.
        assertFalse(DaemonShareServer.mutationInScope(SESSION, defSessionId = null, targetId = "s2"))
    }
}
