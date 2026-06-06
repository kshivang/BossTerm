package ai.rever.bossterm.compose.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** Round-trip + discriminator checks for the window/tab/pane sharing protocol (issue #276). */
class ShareProtocolTest {

    @Test
    fun `server messages encode with the t discriminator`() {
        val out = ShareProtocol.encodeServer(ServerMessage.PaneOutput("p1", "hello[0m"))
        assertTrue(out.contains("\"t\":\"paneOutput\""), "expected paneOutput discriminator in: $out")
        assertTrue(out.contains("\"paneId\":\"p1\""))

        val snap = ShareProtocol.encodeServer(ServerMessage.PaneSnapshot("p1", "scrollback", 120, 40))
        assertTrue(snap.contains("\"t\":\"paneSnapshot\""))
        assertTrue(snap.contains("\"cols\":120") && snap.contains("\"rows\":40"))

        val resize = ShareProtocol.encodeServer(ServerMessage.PaneResize("p1", 80, 24))
        assertTrue(resize.contains("\"t\":\"paneResize\""))

        val presence = ShareProtocol.encodeServer(ServerMessage.Presence(3))
        assertTrue(presence.contains("\"viewers\":3"))
    }

    @Test
    fun `layout serializes a recursive split tree`() {
        val tree = PaneTreeNode.Split(
            dir = "v",
            ratio = 0.5f,
            a = PaneTreeNode.Pane("p1", "zsh", "/home", focused = true),
            b = PaneTreeNode.Split(
                dir = "h",
                ratio = 0.4f,
                a = PaneTreeNode.Pane("p2", "vim", "/home/x", focused = false),
                b = PaneTreeNode.Pane("p3", "claude", "/home/y", focused = false),
            ),
        )
        val layout = ServerMessage.Layout(
            tabs = listOf(TabNode("t1", "~", active = true, tree = tree)),
            activeTabId = "t1",
        )
        val json = ShareProtocol.encodeServer(layout)
        assertTrue(json.contains("\"t\":\"layout\""))
        assertTrue(json.contains("\"t\":\"split\"") && json.contains("\"t\":\"pane\""))
        assertTrue(json.contains("\"paneId\":\"p3\"") && json.contains("\"activeTabId\":\"t1\""))
    }

    @Test
    fun `client messages decode by discriminator`() {
        val hello = ShareProtocol.decodeClient("""{"t":"hello","name":"phone"}""")
        assertIs<ClientMessage.Hello>(hello)
        assertEquals("phone", hello.name)

        val input = ShareProtocol.decodeClient("""{"t":"input","paneId":"p2","data":"ls\n"}""")
        assertIs<ClientMessage.Input>(input)
        assertEquals("p2", input.paneId)
        assertEquals("ls\n", input.data)

        val focus = ShareProtocol.decodeClient("""{"t":"focus","tabId":"t1","paneId":"p2"}""")
        assertIs<ClientMessage.Focus>(focus)

        assertIs<ClientMessage.RequestControl>(ShareProtocol.decodeClient("""{"t":"requestControl"}"""))
    }

    @Test
    fun `unknown keys are tolerated for forward-compat`() {
        val msg = ShareProtocol.decodeClient("""{"t":"hello","name":"x","futureField":42}""")
        assertIs<ClientMessage.Hello>(msg)
    }

    @Test
    fun `viewer resize messages decode by discriminator`() {
        val rh = ShareProtocol.decodeClient("""{"t":"resizeHost","tabId":"t1","cols":120,"rows":40}""")
        assertIs<ClientMessage.ResizeHost>(rh)
        assertEquals("t1", rh.tabId)
        assertEquals(120, rh.cols)
        assertEquals(40, rh.rows)

        val rs = ShareProtocol.decodeClient("""{"t":"resizeSplit","tabId":"t1","splitId":"s1","ratio":0.3}""")
        assertIs<ClientMessage.ResizeSplit>(rs)
        assertEquals("s1", rs.splitId)
        assertEquals(0.3f, rs.ratio)
    }

    @Test
    fun `split id serializes and is optional for forward-compat`() {
        val tree = PaneTreeNode.Split(
            "v", 0.5f,
            PaneTreeNode.Pane("p1", "zsh", "/home", focused = true),
            PaneTreeNode.Pane("p2", "vim", "/x", focused = false),
            id = "split-1",
        )
        val json = ShareProtocol.encodeServer(ServerMessage.Layout(listOf(TabNode("t1", "~", true, tree)), "t1"))
        assertTrue(json.contains("\"id\":\"split-1\""), "split id should serialize: $json")

        // An older host→viewer payload without `id` still decodes (default ""), so a viewer
        // built against the new protocol tolerates an older peer.
        val old = """{"t":"layout","tabs":[{"id":"t1","title":"~","active":true,"tree":{"t":"split","dir":"v","ratio":0.5,"a":{"t":"pane","paneId":"p1","title":"z","cwd":null,"focused":true},"b":{"t":"pane","paneId":"p2","title":"v","cwd":null,"focused":false}}}],"activeTabId":"t1"}"""
        val decoded = ShareProtocol.json.decodeFromString(ServerMessage.serializer(), old)
        assertIs<ServerMessage.Layout>(decoded)
        val split = decoded.tabs[0].tree
        assertIs<PaneTreeNode.Split>(split)
        assertEquals("", split.id)
    }
}
