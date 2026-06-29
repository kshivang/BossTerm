package ai.rever.bossterm.compose.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.awt.Desktop

/**
 * `bossterm://` deep links. Today the only route is the sign-in callback
 * (`bossterm://auth/verify?token_hash=…&type=…` → [BossAccountManager]); anything else
 * is buffered on [pendingUri] for future consumers.
 *
 * Delivery per platform:
 *  - macOS: LaunchServices sends the URL to the running .app as an Apple Event;
 *    [install] registers `Desktop.setOpenURIHandler`. The JDK buffers OpenURI events
 *    that arrive before registration and replays them, so installing first thing in
 *    main() also covers cold starts. (Dev `gradlew run` has no bundle, so the scheme
 *    isn't registered with LaunchServices — test via createDistributable, or the
 *    `-Dbossterm.debug.deeplink=<uri>` hook below.)
 *  - Windows/Linux: the OS launches a NEW process with the URL in argv; [install]
 *    forwards it to the running instance via [DeepLinkSocket] and tells the caller to
 *    exit, or handles it locally when this is the only instance.
 */
object DeepLinkHandler {

    private val log = LoggerFactory.getLogger(DeepLinkHandler::class.java)

    private val _pendingUri = MutableStateFlow<String?>(null)
    /** Last received non-auth deep link, for future routes. */
    val pendingUri: StateFlow<String?> = _pendingUri.asStateFlow()

    /**
     * Wire up deep-link delivery. Call FIRST THING in main(), after Skiko/WM_CLASS setup
     * but before `application {}` (this touches `Desktop`, which initializes AWT).
     *
     * @return true when this launch existed only to carry a deep link that was forwarded
     *   to an already-running instance — the caller should return immediately.
     */
    fun install(args: Array<String>): Boolean {
        // Dev hook: simulate an incoming link without OS scheme registration.
        System.getProperty("bossterm.debug.deeplink")?.takeIf { it.isNotBlank() }?.let {
            log.info("Handling debug deep link")
            handle(it)
        }

        args.firstOrNull { it.startsWith("bossterm://") }?.let { uri ->
            if (DeepLinkSocket.tryForward(uri)) {
                log.info("Deep link forwarded to the running BossTerm instance")
                return true
            }
            handle(uri) // we're the only instance — process it ourselves once init completes
        }

        // macOS: receive URLs in-process (also replays a buffered cold-start event).
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
                Desktop.getDesktop().setOpenURIHandler { event -> handle(event.uri.toString()) }
            }
        }.onFailure { log.warn("OpenURI handler unavailable: {}", it.message) }

        // Windows: make sure the scheme points at this packaged exe (HKCU, best-effort).
        WindowsProtocolRegistrar.registerIfPackaged()

        // Windows/Linux: accept forwards from later launches.
        DeepLinkSocket.startPrimaryListener(::handle)
        return false
    }

    /** Route one URI. Non-blocking — auth verification runs on the manager's own scope. */
    private fun handle(uri: String) {
        if (!uri.startsWith("bossterm://")) return
        if (parseAuthDeepLink(uri) != null) {
            BossAccountManager.handleAuthDeepLink(uri)
        } else {
            log.info("Unrouted deep link (host={})", runCatching { java.net.URI(uri).host }.getOrNull())
            _pendingUri.value = uri
        }
    }
}
