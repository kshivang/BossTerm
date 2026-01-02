package ai.rever.bossterm.compose.hyperlinks

import java.io.File
import java.net.URI

/**
 * Link type categories for hyperlinks detected in terminal output.
 */
enum class HyperlinkType {
    /** HTTP or HTTPS URLs (e.g., https://example.com) */
    HTTP,
    /** File path pointing to an existing file */
    FILE,
    /** File path pointing to an existing directory/folder */
    FOLDER,
    /** Email addresses (mailto: links) */
    EMAIL,
    /** FTP or FTPS URLs */
    FTP,
    /** User-defined custom patterns (e.g., JIRA tickets, custom URLs) */
    CUSTOM
}

/**
 * Rich metadata about a clicked hyperlink.
 *
 * This class provides comprehensive information about a hyperlink that was clicked,
 * allowing host applications to handle different link types appropriately.
 *
 * @property url The resolved URL or file path (ready to open)
 * @property type Categorized link type (HTTP, FILE, FOLDER, EMAIL, FTP, CUSTOM)
 * @property patternId The ID of the pattern that matched this link (e.g., "builtin:http", "jira")
 * @property matchedText The original text that was matched in the terminal output
 * @property isFile True if the link points to an existing file on disk
 * @property isFolder True if the link points to an existing directory on disk
 * @property scheme URL scheme if applicable (http, https, file, mailto, ftp, etc.)
 * @property isBuiltin True if this was matched by a builtin pattern (vs custom user pattern)
 */
data class HyperlinkInfo(
    val url: String,
    val type: HyperlinkType,
    val patternId: String,
    val matchedText: String,
    val isFile: Boolean,
    val isFolder: Boolean,
    val scheme: String?,
    val isBuiltin: Boolean
)

/**
 * Convert a Hyperlink to rich HyperlinkInfo metadata.
 *
 * This extension function analyzes the hyperlink's URL and pattern information
 * to determine the link type and validate file system paths.
 */
fun Hyperlink.toHyperlinkInfo(): HyperlinkInfo {
    // Extract URL scheme (http, https, file, mailto, etc.)
    val scheme = url.substringBefore("://", "").lowercase().takeIf {
        it != url.lowercase() && it.isNotEmpty()
    }

    val isBuiltin = patternId?.startsWith("builtin:") == true

    // Determine if this is a file system path and validate it
    val file: File? = try {
        when {
            url.startsWith("file://") -> File(URI(url))
            url.startsWith("file:") -> File(URI(url))
            patternId?.contains("path") == true -> {
                // For path patterns, the URL is already a file:// URL
                if (url.startsWith("file:")) {
                    File(URI(url))
                } else {
                    File(url)
                }
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    val isFile = file?.isFile == true
    val isFolder = file?.isDirectory == true

    // Determine the link type based on pattern and validation
    val type = when {
        isFolder -> HyperlinkType.FOLDER
        isFile -> HyperlinkType.FILE
        patternId == "builtin:http" || patternId == "builtin:www" -> HyperlinkType.HTTP
        patternId == "builtin:mailto" -> HyperlinkType.EMAIL
        patternId == "builtin:ftp" -> HyperlinkType.FTP
        patternId == "builtin:file" -> if (isFolder) HyperlinkType.FOLDER else HyperlinkType.FILE
        patternId?.startsWith("builtin:path") == true -> {
            when {
                isFolder -> HyperlinkType.FOLDER
                isFile -> HyperlinkType.FILE
                else -> HyperlinkType.FILE // Default to FILE for path patterns
            }
        }
        scheme == "http" || scheme == "https" -> HyperlinkType.HTTP
        scheme == "mailto" -> HyperlinkType.EMAIL
        scheme == "ftp" || scheme == "ftps" -> HyperlinkType.FTP
        scheme == "file" -> if (isFolder) HyperlinkType.FOLDER else HyperlinkType.FILE
        else -> HyperlinkType.CUSTOM
    }

    return HyperlinkInfo(
        url = url,
        type = type,
        patternId = patternId ?: "unknown",
        matchedText = matchedText ?: url,
        isFile = isFile,
        isFolder = isFolder,
        scheme = scheme,
        isBuiltin = isBuiltin
    )
}
