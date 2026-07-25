package ai.rever.bossterm.compose.share

import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondResource
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
