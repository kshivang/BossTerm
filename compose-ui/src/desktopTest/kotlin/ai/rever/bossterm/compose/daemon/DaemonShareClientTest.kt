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
    @Test
    fun `hosted surfaces aggregate shares and route approvals to the owning service`() {
        val firstMessages = mutableListOf<DaemonAttachProtocol.Client>()
        val secondMessages = mutableListOf<DaemonAttachProtocol.Client>()
        val first = DaemonShareClient.Sender { firstMessages.add(it) }
        val second = DaemonShareClient.Sender { secondMessages.add(it) }
        try {
            DaemonShareClient.registerSender(first, hosted = true)
            DaemonShareClient.registerSender(second, hosted = true)
            DaemonShareClient.update(DaemonAttachProtocol.Server.ShareState(
                pending = listOf(DaemonAttachProtocol.PendingApproval("first", "a"))), first)
            DaemonShareClient.update(DaemonAttachProtocol.Server.ShareState(
                pending = listOf(DaemonAttachProtocol.PendingApproval("second", "b"))), second)
            assertEquals(2, DaemonShareClient.state.value.pending.size)
            DaemonShareClient.approve("first", "a", false)
            assertEquals(1, firstMessages.size)
            assertTrue(secondMessages.isEmpty())
            DaemonShareClient.clearSender(first)
            assertEquals(listOf("second"), DaemonShareClient.state.value.pending.map { it.token })
            DaemonShareClient.deny("first", "a")
            assertTrue(secondMessages.isEmpty(), "a removed share must not target a different service")
            DaemonShareClient.deny("second", "b")
            assertEquals(1, secondMessages.size)
        } finally { DaemonShareClient.clearSender(first); DaemonShareClient.clearSender(second) }
    }

}
