package ai.rever.bossterm.compose.osc

import ai.rever.bossterm.compose.hyperlinks.HyperlinkDetector
import ai.rever.bossterm.compose.hyperlinks.HyperlinkInfo
import ai.rever.bossterm.compose.hyperlinks.HyperlinkType
import ai.rever.bossterm.terminal.TerminalCustomCommandListener
import java.io.File
import java.net.URI
import java.util.Base64

/**
 * A TerminalCustomCommandListener for CLI-originated open requests.
 *
 * The shell-integration `open`/`xdg-open`/`$BROWSER` shim forwards plain
 * "open this URL or file" invocations from CLI commands as:
 *
 *     OSC 1341;OpenTarget;<base64(target)> BEL
 *
 * which arrives here as args ["OpenTarget", "<base64>"]. The target is
 * classified into a [HyperlinkInfo] and dispatched through the same
 * link-open handler used for Ctrl/Cmd+click, so embedding hosts can route
 * it (e.g. show an open-with dialog). When no handler is wired, or the
 * handler declines, the target opens with the system default — matching
 * what the shimmed command would have done anyway.
 *
 * The handler is read through a provider on every event because the host
 * wires it from the composition, after the session (and this listener)
 * already exists.
 *
 * Thread safety: called from the emulator processing thread. Handlers must
 * not assume the UI thread.
 */
class OpenTargetOSCListener(
    private val handlerProvider: () -> ((HyperlinkInfo) -> Boolean)?
) : TerminalCustomCommandListener {

    override fun process(args: MutableList<String?>) {
        if (args.size < 2 || args[0] != OPEN_TARGET_COMMAND) return
        val target = decodeTarget(args[1]) ?: return
        val info = classifyOpenTarget(target) ?: return
        val handled = handlerProvider()?.invoke(info) ?: false
        if (!handled) {
            HyperlinkDetector.openUrl(info.url)
        }
    }

    companion object {
        const val OPEN_TARGET_COMMAND = "OpenTarget"

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
 * Classify a raw open-request target (URL or file path) into [HyperlinkInfo],
 * mirroring the semantics of [ai.rever.bossterm.compose.hyperlinks.toHyperlinkInfo].
 * Returns null for targets that can't be meaningfully opened (blank input).
 */
internal fun classifyOpenTarget(target: String): HyperlinkInfo? {
    val trimmed = target.trim()
    if (trimmed.isEmpty()) return null

    val scheme = trimmed.substringBefore("://", "").lowercase().takeIf {
        it.isNotEmpty() && it != trimmed.lowercase()
    }

    val file: File? = when {
        trimmed.startsWith("file:") -> try {
            File(URI(trimmed))
        } catch (e: Exception) {
            null
        }
        scheme == null && !trimmed.startsWith("mailto:") -> File(trimmed)
        else -> null
    }
    val isFile = file?.isFile == true
    val isFolder = file?.isDirectory == true

    val type = when {
        scheme == "http" || scheme == "https" -> HyperlinkType.HTTP
        scheme == "ftp" || scheme == "ftps" -> HyperlinkType.FTP
        trimmed.startsWith("mailto:") -> HyperlinkType.EMAIL
        isFolder -> HyperlinkType.FOLDER
        isFile -> HyperlinkType.FILE
        else -> HyperlinkType.CUSTOM
    }

    // For filesystem targets hand hosts the resolved absolute path (the shim
    // already absolutizes plain paths; file: URLs are resolved here).
    val url = if (isFile || isFolder) file!!.absolutePath else trimmed

    return HyperlinkInfo(
        url = url,
        type = type,
        patternId = "osc:open-target",
        matchedText = trimmed,
        isFile = isFile,
        isFolder = isFolder,
        scheme = scheme ?: "mailto".takeIf { trimmed.startsWith("mailto:") },
        isBuiltin = false
    )
}
