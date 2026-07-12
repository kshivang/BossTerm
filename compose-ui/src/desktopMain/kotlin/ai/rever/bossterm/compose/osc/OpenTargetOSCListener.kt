package ai.rever.bossterm.compose.osc

import ai.rever.bossterm.compose.hyperlinks.HyperlinkDetector
import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkType
import ai.rever.bossterm.terminal.TerminalCustomCommandListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Process-wide secret authenticating open requests from the shell shim.
 *
 * Injected into every session's environment as BOSSTERM_OPEN_TOKEN and echoed
 * back by the shim inside the OSC payload. Content merely *displayed* in the
 * terminal (cat of a crafted file, curl of attacker-controlled output, a log
 * line) cannot know it, so [OpenTargetOSCListener] ignores any OpenTarget
 * sequence that doesn't carry it — auto-opening stays gated on the shim,
 * which only forwards what a locally executed command explicitly asked to
 * open.
 */
object OpenTargetToken {
    val value: String by lazy { UUID.randomUUID().toString() }
}

/**
 * A TerminalCustomCommandListener for CLI-originated open requests.
 *
 * The shell-integration `open`/`xdg-open`/`$BROWSER` shim forwards plain
 * "open this URL or file" invocations from CLI commands as:
 *
 *     OSC 1341;OpenTarget;<token>;<base64(target)> BEL
 *
 * which arrives here as args ["OpenTarget", "<token>", "<base64>"]. After
 * verifying the token ([OpenTargetToken]), the target is validated and
 * classified into a [HyperlinkInfo] and dispatched — on the main thread,
 * like Ctrl/Cmd+click — through the same link-open handler, so embedding
 * hosts can route it (e.g. show an open-with dialog). When no handler is
 * wired, or the handler declines, the target opens with the system default,
 * matching what the shimmed command would have done anyway.
 *
 * Because no user click gates this path, only targets a CLI could
 * legitimately ask to open are honored (see [classifyOpenTarget]): http/https
 * URLs and existing absolute filesystem paths. ssh://, mailto:, custom
 * schemes, and relative or non-existing paths are refused.
 *
 * The handler is read through a provider on every event because the host
 * wires it from the composition, after the session (and this listener)
 * already exists.
 */
class OpenTargetOSCListener(
    private val handlerProvider: () -> ((HyperlinkInfo) -> Boolean)?,
    private val fallbackOpener: (String) -> Unit = HyperlinkDetector::openUrl,
    dispatchScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TerminalCustomCommandListener {

    // Dispatch on the main thread so handlers see the same threading as the
    // Ctrl/Cmd+click path (tab creation, snapshot state, focus).
    private val scope = dispatchScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun process(args: MutableList<String?>) {
        if (args.size < 3 || args[0] != OPEN_TARGET_COMMAND) return
        // Unauthenticated request (missing/stale token): ignore silently —
        // this is exactly the crafted-content injection case. Constant-time
        // comparison out of caution; there is no realistic timing oracle here.
        val token = args[1] ?: return
        if (!MessageDigest.isEqual(
                token.toByteArray(Charsets.UTF_8),
                OpenTargetToken.value.toByteArray(Charsets.UTF_8)
            )
        ) {
            return
        }
        val encoded = args[2]
        scope.launch {
            // Classification touches the filesystem (isFile/isDirectory), so
            // it runs on IO — never on the emulator parse thread (which called
            // process()) and not on Main either, in case of a hung mount.
            val info = withContext(ioDispatcher) {
                decodeTarget(encoded)?.let { classifyOpenTarget(it) }
            } ?: return@launch
            try {
                val handled = handlerProvider()?.invoke(info) ?: false
                if (!handled) {
                    fallbackOpener(info.url)
                }
            } catch (e: Exception) {
                // A throwing host handler must not crash the main thread.
                LOG.warn("Open-target handler failed for ${info.url}", e)
            }
        }
    }

    companion object {
        const val OPEN_TARGET_COMMAND = "OpenTarget"

        private val LOG = LoggerFactory.getLogger(OpenTargetOSCListener::class.java)

        private fun decodeTarget(encoded: String?): String? {
            if (encoded.isNullOrBlank()) return null
            return try {
                String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8)
                    .takeIf { it.isNotBlank() }
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * Validate and classify an open-request target. This is deliberately an
 * allow-list, not a general classifier like `Hyperlink.toHyperlinkInfo()`:
 * with no user click gating this path, anything outside "web URL or existing
 * absolute filesystem path" is refused by returning null.
 */
internal fun classifyOpenTarget(target: String): HyperlinkInfo? {
    val trimmed = target.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return HyperlinkInfo(
            url = trimmed,
            type = HyperlinkType.HTTP,
            patternId = "osc:open-target",
            matchedText = trimmed,
            isFile = false,
            isFolder = false,
            scheme = trimmed.substringBefore("://").lowercase(),
            isBuiltin = false
        )
    }

    // Filesystem target: a file: URL or an absolute path. Relative paths are
    // refused rather than resolved — resolving against the JVM working
    // directory would be wrong (the shim absolutizes against the shell's cwd
    // before sending), and an injected relative path must not resolve at all.
    val file: File? = when {
        trimmed.startsWith("file:") -> try {
            File(URI(trimmed))
        } catch (e: URISyntaxException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        else -> File(trimmed).takeIf { it.isAbsolute }
    }
    if (file != null && (file.isFile || file.isDirectory)) {
        val isFolder = file.isDirectory
        return HyperlinkInfo(
            url = file.absolutePath,
            type = if (isFolder) HyperlinkType.FOLDER else HyperlinkType.FILE,
            patternId = "osc:open-target",
            matchedText = trimmed,
            isFile = !isFolder,
            isFolder = isFolder,
            scheme = "file".takeIf { trimmed.startsWith("file:") },
            isBuiltin = false
        )
    }

    return null
}
