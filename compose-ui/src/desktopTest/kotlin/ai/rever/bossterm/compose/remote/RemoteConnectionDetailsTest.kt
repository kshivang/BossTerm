package ai.rever.bossterm.compose.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteConnectionDetailsTest {
    @Test
    fun `unmeasured panes and matching grids do not offer resize`() {
        for (grid in listOf(
            intArrayOf(0, 24, 80, 24), intArrayOf(80, 24, 0, 24),
            intArrayOf(80, 1, 80, 24), intArrayOf(80, 24, 80, 1),
            intArrayOf(80, 24, 80, 24), intArrayOf(80, 24, 82, 22),
        )) {
            assertFalse(remoteGridMismatch(grid[0], grid[1], grid[2], grid[3]))
        }
        assertTrue(remoteGridMismatch(80, 24, 83, 24))
        assertTrue(remoteGridMismatch(80, 24, 80, 21))
    }

    @Test
    fun `connection labels never fall back to bearer URLs`() {
        val link = "https://host.example/?t=private-token#k=private-key"
        assertEquals("host.example", remoteConnectionName(null, null, link))
        assertEquals("Remote session", remoteConnectionName(null, null, "not a URI ?t=private-token#k=private-key"))
        assertEquals("Remote session", remoteConnectionName(" ", "", "relative/path?t=private-token#k=private-key"))
        assertEquals("Work laptop", remoteConnectionName("Work laptop", "Server", link))
        assertEquals("Server", remoteConnectionName(null, "Server", link))
    }

    @Test
    fun `retrying a failed connection replaces the failure with reconnecting`() {
        val failed = RemoteStatus.Failed("Lost connection")
        assertTrue(canReconnectRemote(failed))
        assertEquals("Disconnected", remoteConnectionStatus(failed, true))
        assertFalse(canReconnectRemote(RemoteStatus.Connecting))
        assertEquals("Reconnecting…", remoteConnectionStatus(RemoteStatus.Connecting, true))
        assertEquals("Connecting…", remoteConnectionStatus(RemoteStatus.Connecting, false))
        assertFalse(canReconnectRemote(RemoteStatus.Connected(true)))
        assertEquals("Connected · control", remoteConnectionStatus(RemoteStatus.Connected(true), true))
    }

    @Test
    fun `approval and denial cannot offer reconnect as an authorization bypass`() {
        assertEquals("Awaiting approval…", remoteConnectionStatus(RemoteStatus.Pending, false))
        assertEquals("Access denied", remoteConnectionStatus(RemoteStatus.Denied("Denied"), false))
        assertEquals("Connected · view only", remoteConnectionStatus(RemoteStatus.Connected(false), true))
        for (status in listOf(RemoteStatus.Pending, RemoteStatus.Denied("Denied"), RemoteStatus.Closed)) {
            assertFalse(canReconnectRemote(status))
        }
    }
}
