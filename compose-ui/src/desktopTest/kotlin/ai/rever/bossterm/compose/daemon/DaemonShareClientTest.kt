package ai.rever.bossterm.compose.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonShareClientTest {
    @Test
    fun `old bridge cannot clear new bridge or publish its stale share state`() {
        val messages = mutableListOf<DaemonAttachProtocol.Client>()
        val old = DaemonShareClient.Sender { error("old sender must no longer receive actions") }
        val current = DaemonShareClient.Sender { messages.add(it) }
        val expected = DaemonAttachProtocol.Server.ShareState(
            pending = listOf(DaemonAttachProtocol.PendingApproval("token", "current")))
        try {
            DaemonShareClient.registerSender(old)
            DaemonShareClient.registerSender(current)
            DaemonShareClient.update(expected, current)
            DaemonShareClient.clearSender(old)
            DaemonShareClient.update(DaemonAttachProtocol.Server.ShareState(), old)
            assertEquals(expected, DaemonShareClient.state.value)
            DaemonShareClient.startShare("group", groupId = "group")
            assertEquals("group", (messages.single() as DaemonAttachProtocol.Client.StartShare).groupId)
            DaemonShareClient.clearSender(current)
            assertTrue(DaemonShareClient.state.value.pending.isEmpty())
        } finally {
            DaemonShareClient.clearSender(current)
            DaemonShareClient.clearSender(old)
        }
    }
}
