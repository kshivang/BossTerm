package ai.rever.bossterm.compose.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** Round-trip + discriminator checks for the session-sharing wire protocol (issue #276). */
class ShareProtocolTest {

    @Test
    fun `server messages encode with the t discriminator`() {
        val out = ShareProtocol.encodeServer(ServerMessage.Output("hello[0m"))
        assertTrue(out.contains("\"t\":\"output\""), "expected output discriminator in: $out")

        val snap = ShareProtocol.encodeServer(ServerMessage.Snapshot("scrollback", 120, 40))
        assertTrue(snap.contains("\"t\":\"snapshot\""))
        assertTrue(snap.contains("\"cols\":120"))
        assertTrue(snap.contains("\"rows\":40"))

        val resize = ShareProtocol.encodeServer(ServerMessage.Resize(80, 24))
        assertTrue(resize.contains("\"t\":\"resize\""))

        val presence = ShareProtocol.encodeServer(ServerMessage.Presence(3))
        assertTrue(presence.contains("\"viewers\":3"))
    }

    @Test
    fun `client messages decode by discriminator`() {
        val hello = ShareProtocol.decodeClient("""{"t":"hello","name":"phone"}""")
        assertIs<ClientMessage.Hello>(hello)
        assertEquals("phone", hello.name)

        val input = ShareProtocol.decodeClient("""{"t":"input","data":"ls\n"}""")
        assertIs<ClientMessage.Input>(input)
        assertEquals("ls\n", input.data)

        val req = ShareProtocol.decodeClient("""{"t":"requestControl"}""")
        assertIs<ClientMessage.RequestControl>(req)
    }

    @Test
    fun `unknown keys are tolerated for forward-compat`() {
        // A newer host adds a field an older viewer-side decoder doesn't know.
        val msg = ShareProtocol.decodeClient("""{"t":"hello","name":"x","futureField":42}""")
        assertIs<ClientMessage.Hello>(msg)
    }
}
