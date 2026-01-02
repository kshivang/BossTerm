package ai.rever.bossterm.compose.hyperlinks

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves and validates file paths for hyperlink detection.
 *
 * Supports:
 * - Absolute Unix paths: /path/to/file
 * - Absolute Windows paths: C:\path\to\file
 * - Home-relative paths: ~/path/to/file
 * - Relative paths: ./path, ../path (requires working directory)
 *
 * Thread-safe: All operations are safe to call from any thread.
 */
object FilePathResolver {
    /**
     * Cache for path existence checks to avoid hitting the filesystem on every render.
     * Key: absolute path string, Value: Pair(exists, timestamp)
     * Cache entries expire after 5 seconds to catch file changes.
     */
    private val pathCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private const val CACHE_TTL_MS = 5000L // 5 seconds
    private const val MAX_CACHE_SIZE = 1000

    /**
     * Windows path pattern (C:\, D:\, etc.)
     */
    private val windowsPathPattern = Regex("""^[A-Za-z]:\\.*""")

    /**
     * Resolve a path string to an absolute File, optionally using a working directory.
     *
     * @param path The path string to resolve
     * @param workingDirectory The current working directory for relative paths (optional)
     * @return Resolved File if path is valid format, null otherwise
     */
    fun resolvePath(path: String, workingDirectory: String?): File? {
        if (path.isBlank()) return null

        val resolved = when {
            // Absolute Unix path
            path.startsWith("/") -> File(path)

            // Absolute Windows path (C:\, D:\, etc.)
            windowsPathPattern.matches(path) -> File(path)

            // Home-relative path (~/...)
            path.startsWith("~/") -> {
                val home = System.getProperty("user.home") ?: return null
                File(home, path.drop(2))
            }

            // Relative paths (./... or ../...)
            path.startsWith("./") || path.startsWith("../") -> {
                val cwd = workingDirectory ?: return null
                File(cwd, path).canonicalFile
            }

            // Not a recognized path format
            else -> null
        }

        return resolved?.canonicalFile
    }

    /**
     * Check if a resolved path exists, with caching for performance.
     *
     * @param file The file to check
     * @return true if the file exists
     */
    fun exists(file: File): Boolean {
        val path = file.absolutePath
        val now = System.currentTimeMillis()

        // Check cache first
        pathCache[path]?.let { (exists, timestamp) ->
            if (now - timestamp < CACHE_TTL_MS) {
                return exists
            }
        }

        // Evict old entries if cache is too large
        if (pathCache.size > MAX_CACHE_SIZE) {
            val expiredThreshold = now - CACHE_TTL_MS
            pathCache.entries.removeIf { it.value.second < expiredThreshold }
        }

        // Check filesystem and cache result
        val exists = file.exists()
        pathCache[path] = Pair(exists, now)
        return exists
    }

    /**
     * Resolve path and validate it exists.
     *
     * @param path The path string to resolve
     * @param workingDirectory The current working directory for relative paths
     * @return Resolved File if it exists, null otherwise
     */
    fun resolveAndValidate(path: String, workingDirectory: String?): File? {
        val resolved = resolvePath(path, workingDirectory) ?: return null
        return if (exists(resolved)) resolved else null
    }

    /**
     * Convert a File to a file:// URL string suitable for opening.
     *
     * @param file The file to convert
     * @return file:// URL string
     */
    fun toFileUrl(file: File): String {
        return file.toURI().toString()
    }

    /**
     * Clear the path existence cache.
     * Call this if you know files have been created/deleted and want immediate refresh.
     */
    fun clearCache() {
        pathCache.clear()
    }

    /**
     * Check if a path string looks like a Unix absolute path.
     * Fast check for use in quickCheck callbacks.
     */
    fun looksLikeUnixPath(text: String): Boolean {
        return text.contains('/') && !text.contains("://")
    }

    /**
     * Check if a path string looks like a home-relative path.
     * Fast check for use in quickCheck callbacks.
     */
    fun looksLikeHomePath(text: String): Boolean {
        return text.contains("~/")
    }

    /**
     * Check if a path string looks like a relative path.
     * Fast check for use in quickCheck callbacks.
     */
    fun looksLikeRelativePath(text: String): Boolean {
        return text.contains("./")
    }

    /**
     * Check if a path string looks like a Windows path.
     * Fast check for use in quickCheck callbacks.
     */
    fun looksLikeWindowsPath(text: String): Boolean {
        return text.contains(":\\")
    }
}
