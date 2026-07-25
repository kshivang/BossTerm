package ai.rever.bossterm.compose.share

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondResource
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val IMMUTABLE_FONT_CACHE = "public, max-age=31536000, immutable"

private val SHARE_VIEWER_FONT_RESOURCES = listOf(
    "MesloLGSNF-Regular.ttf",
    "MesloLGSNF-NOTICE.txt",
    "NotoSansSymbols2-Regular.ttf",
    "NotoSansSymbols2-OFL.txt",
)

/** Install only the font files referenced by the viewer stylesheet and their license notices. */
internal fun Route.installShareViewerFontRoutes() {
    for (name in SHARE_VIEWER_FONT_RESOURCES) {
        get("/fonts/$name") {
            call.response.headers.append(HttpHeaders.CacheControl, IMMUTABLE_FONT_CACHE)
            call.respondResource("fonts/$name")
        }
    }
}

/** Placeholder inside the shell's `connect-src`, filled in per request by [respondShareViewerIndex]. */
internal const val WS_ORIGIN_PLACEHOLDER = "__BOSSTERM_WS_ORIGINS__"

/**
 * Placeholder for Boss Calling's SDP origin, filled in only when a key is configured — a viewer of a
 * host that never set one gets no remote origin in its policy at all.
 */
internal const val VOICE_ORIGIN_PLACEHOLDER = "__BOSSTERM_VOICE_ORIGIN__"

/** The single origin a voice call needs, or empty when this host can't place one. */
internal fun voiceCspSource(keyConfigured: Boolean): String =
    if (keyConfigured) "https://api.openai.com" else ""

/** Bare `host` / `host:port` / `[v6]:port` — anything else can't go in a CSP directive. */
private val SAFE_HTTP_AUTHORITY =
    Regex("""(?:[A-Za-z0-9._\-]+|\[[0-9A-Fa-f:.]+])(?::\d{1,5})?""")

/**
 * Serve the viewer shell with a `connect-src` that names THIS request's own WebSocket origin.
 *
 * `connect-src 'self'` matching `ws://`/`wss://` on the document's own host is CSP **Level 3**
 * behavior. Older mobile WebKit/Blink required the scheme to be named explicitly and blocked the
 * socket outright — which bricks the viewer rather than degrading it, and phones on a LAN are
 * exactly this feature's audience. Naming the concrete authority keeps the policy as narrow as
 * `'self'` (no `ws:` wildcard that would permit any host) while working on those browsers.
 *
 * Register alongside `staticResources(..., index = null)` with `index.html` excluded, so the
 * un-templated file can never be served instead of this route.
 */
internal fun Route.installShareViewerIndexRoute() {
    get("/") { call.respondShareViewerIndex() }
    get("/index.html") { call.respondShareViewerIndex() }
}

/** True for the shell itself, which [installShareViewerIndexRoute] owns. */
internal fun isShareViewerIndexResource(path: String): Boolean =
    path.endsWith("/index.html") || path == "index.html"

private object ShareViewerIndex {
    val template: String by lazy {
        checkNotNull(javaClass.classLoader.getResourceAsStream("share-viewer/index.html")) {
            "missing resource: share-viewer/index.html"
        }.bufferedReader().use { it.readText() }
    }
}

private suspend fun ApplicationCall.respondShareViewerIndex() {
    // no-cache so a phone re-validates the shell (filenames aren't content-hashed) — a 304 keeps
    // it cheap, and the per-request CSP is never reused across a different Host.
    response.headers.append(HttpHeaders.CacheControl, "no-cache")
    respondText(
        ShareViewerIndex.template
            .replace(WS_ORIGIN_PLACEHOLDER, webSocketCspSources(request.headers[HttpHeaders.Host]))
            .replace(
                VOICE_ORIGIN_PLACEHOLDER,
                voiceCspSource(ai.rever.bossterm.compose.voice.VoiceAgentStorage.keyPresentFlow.value),
            ),
        ContentType.Text.Html.withCharset(Charsets.UTF_8),
    )
}

/**
 * The `ws:`/`wss:` sources for [hostHeader]'s origin, or empty when it is missing or not a bare
 * authority — it is client-supplied and lands inside a CSP directive, so anything else (a space, a
 * `;`, a path) could append a directive of its own. Falling back to no extra source leaves the
 * policy at `'self'`, i.e. today's behavior on a CSP3 browser.
 */
internal fun webSocketCspSources(hostHeader: String?): String {
    val authority = hostHeader?.trim().orEmpty()
    if (!SAFE_HTTP_AUTHORITY.matches(authority)) return ""
    return "ws://$authority wss://$authority"
}
