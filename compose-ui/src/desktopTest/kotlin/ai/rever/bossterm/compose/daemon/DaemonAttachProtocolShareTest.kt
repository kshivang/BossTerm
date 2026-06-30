package ai.rever.bossterm.compose.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire round-trips for the Phase-2 share-management lane on [DaemonAttachProtocol] — the
 * GUI↔daemon control/observation channel for daemon-hosted public shares. Each new
 * [DaemonAttachProtocol.Server.ShareState] / [DaemonAttachProtocol.Client] verb must survive
 * encode→decode with every field intact and decode back to its exact subtype (the `t`
 * discriminator drives [TabController]'s share UI, so a mistyped/dropped field silently breaks it).
 *
 * Depends ONLY on the protocol object (no daemon, no Ktor), so it is a hard guard on the wire shape.
 */
class DaemonAttachProtocolShareTest {

    @Test
    fun `ShareScopeKind constants are the documented wire strings`() {
        assertEquals("all", DaemonAttachProtocol.ShareScopeKind.ALL)
        assertEquals("session", DaemonAttachProtocol.ShareScopeKind.SESSION)
    }

    @Test
    fun `ShareState round-trips with multiple shares and pending approvals intact`() {
        // A fully-populated ALL-scoped share (non-default values on every field) ...
        val shareAll = DaemonAttachProtocol.ShareView(
            token = "tok-all-123",
            scope = DaemonAttachProtocol.ShareScopeKind.ALL,
            sessionId = null,
            url = "https://example.trycloudflare.com/?t=tok-all-123#k=AAAA",
            controlUrl = "https://example.trycloudflare.com/?t=ctrl-all-123#k=AAAA",
            secure = true,
            e2eCode = "12-34-56",
            viewers = 3,
            sessionName = "shivang@host",
            remoteMode = "cloudflare",
            remoteStatus = "active",
            remoteAttempt = 2,
            remoteMaxAttempts = 5,
        )
        // ... and a SESSION-scoped share with sessionId set and remote off.
        val shareSession = DaemonAttachProtocol.ShareView(
            token = "tok-sess-789",
            scope = DaemonAttachProtocol.ShareScopeKind.SESSION,
            sessionId = "sess-42",
            url = "http://127.0.0.1:7677/?t=tok-sess-789#k=BBBB",
            controlUrl = "http://127.0.0.1:7677/?t=ctrl-sess-789#k=BBBB",
            secure = true,
            e2eCode = "ab-cd-ef",
            viewers = 0,
            sessionName = null,
            remoteMode = "off",
            remoteStatus = "off",
            remoteAttempt = 0,
            remoteMaxAttempts = 0,
        )
        val pending = listOf(
            DaemonAttachProtocol.PendingApproval(token = "tok-all-123", clientId = "client-aaa", name = "iPhone", control = true),
            DaemonAttachProtocol.PendingApproval(token = "tok-sess-789", clientId = "client-bbb", name = null, control = false),
        )
        val msg: DaemonAttachProtocol.Server =
            DaemonAttachProtocol.Server.ShareState(shares = listOf(shareAll, shareSession), pending = pending)

        val decoded = DaemonAttachProtocol.decodeServer(DaemonAttachProtocol.encodeServer(msg))
        assertTrue(decoded is DaemonAttachProtocol.Server.ShareState, "ShareState must decode to ShareState, got ${decoded::class}")
        // Data classes have structural equality, so a single assertEquals proves every field survived.
        assertEquals(msg, decoded)

        // Spell out a couple of the load-bearing fields to make a regression obvious if equality is loosened.
        assertEquals(2, decoded.shares.size)
        assertEquals(shareAll, decoded.shares[0])
        assertEquals("sess-42", decoded.shares[1].sessionId)
        assertEquals(DaemonAttachProtocol.ShareScopeKind.SESSION, decoded.shares[1].scope)
        assertEquals(2, decoded.pending.size)
        assertEquals("iPhone", decoded.pending[0].name)
        assertTrue(decoded.pending[0].control)
        assertEquals(null, decoded.pending[1].name)
    }

    @Test
    fun `empty ShareState round-trips (defaults)`() {
        val msg: DaemonAttachProtocol.Server = DaemonAttachProtocol.Server.ShareState()
        val decoded = DaemonAttachProtocol.decodeServer(DaemonAttachProtocol.encodeServer(msg))
        assertTrue(decoded is DaemonAttachProtocol.Server.ShareState)
        assertTrue(decoded.shares.isEmpty())
        assertTrue(decoded.pending.isEmpty())
    }

    @Test
    fun `StartShare round-trips for ALL and SESSION scopes`() {
        val all: DaemonAttachProtocol.Client =
            DaemonAttachProtocol.Client.StartShare(scope = DaemonAttachProtocol.ShareScopeKind.ALL, sessionId = null, remoteMode = "funnel")
        val allBack = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(all))
        assertTrue(allBack is DaemonAttachProtocol.Client.StartShare, "decoded to ${allBack::class}")
        assertEquals(all, allBack)
        assertEquals("funnel", allBack.remoteMode)
        assertEquals(null, allBack.sessionId)

        val session: DaemonAttachProtocol.Client =
            DaemonAttachProtocol.Client.StartShare(scope = DaemonAttachProtocol.ShareScopeKind.SESSION, sessionId = "sess-1", remoteMode = null)
        val sessionBack = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(session))
        assertTrue(sessionBack is DaemonAttachProtocol.Client.StartShare)
        assertEquals(session, sessionBack)
        assertEquals(DaemonAttachProtocol.ShareScopeKind.SESSION, sessionBack.scope)
        assertEquals("sess-1", sessionBack.sessionId)
    }

    @Test
    fun `StartShare uses ALL scope by default`() {
        // The default scope must be the wire string "all" so an omitted scope is unambiguous.
        val decoded = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(DaemonAttachProtocol.Client.StartShare()))
        assertTrue(decoded is DaemonAttachProtocol.Client.StartShare)
        assertEquals(DaemonAttachProtocol.ShareScopeKind.ALL, decoded.scope)
    }

    @Test
    fun `StopShare round-trips`() {
        val msg: DaemonAttachProtocol.Client = DaemonAttachProtocol.Client.StopShare(token = "tok-xyz")
        val decoded = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(msg))
        assertTrue(decoded is DaemonAttachProtocol.Client.StopShare, "decoded to ${decoded::class}")
        assertEquals(msg, decoded)
        assertEquals("tok-xyz", decoded.token)
    }

    @Test
    fun `SetShareRemoteMode round-trips`() {
        val msg: DaemonAttachProtocol.Client = DaemonAttachProtocol.Client.SetShareRemoteMode(token = "tok-1", mode = "cloudflare")
        val decoded = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(msg))
        assertTrue(decoded is DaemonAttachProtocol.Client.SetShareRemoteMode, "decoded to ${decoded::class}")
        assertEquals(msg, decoded)
        assertEquals("tok-1", decoded.token)
        assertEquals("cloudflare", decoded.mode)
    }

    @Test
    fun `SetShareName round-trips`() {
        val msg: DaemonAttachProtocol.Client = DaemonAttachProtocol.Client.SetShareName(token = "tok-2", name = "Demo box")
        val decoded = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(msg))
        assertTrue(decoded is DaemonAttachProtocol.Client.SetShareName, "decoded to ${decoded::class}")
        assertEquals(msg, decoded)
        assertEquals("tok-2", decoded.token)
        assertEquals("Demo box", decoded.name)
    }

    @Test
    fun `ApproveViewer round-trips, control flag preserved both ways`() {
        val withControl: DaemonAttachProtocol.Client =
            DaemonAttachProtocol.Client.ApproveViewer(token = "tok-3", clientId = "c-1", control = true)
        val withControlBack = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(withControl))
        assertTrue(withControlBack is DaemonAttachProtocol.Client.ApproveViewer, "decoded to ${withControlBack::class}")
        assertEquals(withControl, withControlBack)
        assertTrue(withControlBack.control)

        // Default control=false must survive too (view-only approval).
        val viewOnly: DaemonAttachProtocol.Client = DaemonAttachProtocol.Client.ApproveViewer(token = "tok-3", clientId = "c-2")
        val viewOnlyBack = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(viewOnly))
        assertTrue(viewOnlyBack is DaemonAttachProtocol.Client.ApproveViewer)
        assertEquals(viewOnly, viewOnlyBack)
        assertEquals(false, viewOnlyBack.control)
    }

    @Test
    fun `DenyViewer round-trips`() {
        val msg: DaemonAttachProtocol.Client = DaemonAttachProtocol.Client.DenyViewer(token = "tok-4", clientId = "c-3")
        val decoded = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(msg))
        assertTrue(decoded is DaemonAttachProtocol.Client.DenyViewer, "decoded to ${decoded::class}")
        assertEquals(msg, decoded)
        assertEquals("tok-4", decoded.token)
        assertEquals("c-3", decoded.clientId)
    }

    @Test
    fun `each share verb decodes to its own subtype, not a sibling`() {
        // Cross-check: a StopShare must NOT decode as a StartShare etc. The `t` discriminator
        // (classDiscriminator = "t") is what disambiguates them on the wire.
        val verbs: List<DaemonAttachProtocol.Client> = listOf(
            DaemonAttachProtocol.Client.StartShare(),
            DaemonAttachProtocol.Client.StopShare("a"),
            DaemonAttachProtocol.Client.SetShareRemoteMode("a", "off"),
            DaemonAttachProtocol.Client.SetShareName("a", "n"),
            DaemonAttachProtocol.Client.ApproveViewer("a", "c", true),
            DaemonAttachProtocol.Client.DenyViewer("a", "c"),
        )
        verbs.forEach { original ->
            val decoded = DaemonAttachProtocol.decodeClient(DaemonAttachProtocol.encodeClient(original))
            assertEquals(original::class, decoded::class, "subtype changed across the wire for $original")
            assertEquals(original, decoded)
        }
    }
}
