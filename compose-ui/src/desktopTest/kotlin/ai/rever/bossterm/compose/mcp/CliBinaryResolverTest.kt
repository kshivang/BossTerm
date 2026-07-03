package ai.rever.bossterm.compose.mcp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class CliBinaryResolverTest {

    private fun tempDirWithExecutable(name: String): Pair<File, File> {
        val dir = createTempDirectory("cli-resolver-test").toFile().apply { deleteOnExit() }
        val bin = File(dir, name).apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
            deleteOnExit()
        }
        return dir to bin
    }

    @Test
    fun `finds binary on PATH`() {
        val (dir, bin) = tempDirWithExecutable("claude")
        val resolved = CliBinaryResolver.resolveUncached(
            binary = "claude",
            pathValue = dir.absolutePath,
            extraDirs = emptyList(),
            isWindows = false
        )
        assertEquals(bin.absolutePath, resolved)
    }

    @Test
    fun `falls back to well-known dir when PATH misses`() {
        val (dir, bin) = tempDirWithExecutable("claude")
        val resolved = CliBinaryResolver.resolveUncached(
            binary = "claude",
            pathValue = "/usr/bin:/bin",
            extraDirs = listOf(File("/nonexistent-dir"), dir),
            isWindows = false
        )
        assertEquals(bin.absolutePath, resolved)
    }

    @Test
    fun `PATH wins over extra dirs`() {
        val (pathDir, pathBin) = tempDirWithExecutable("claude")
        val (extraDir, _) = tempDirWithExecutable("claude")
        val resolved = CliBinaryResolver.resolveUncached(
            binary = "claude",
            pathValue = pathDir.absolutePath,
            extraDirs = listOf(extraDir),
            isWindows = false
        )
        assertEquals(pathBin.absolutePath, resolved)
    }

    @Test
    fun `non-executable file is skipped`() {
        val dir = createTempDirectory("cli-resolver-test").toFile().apply { deleteOnExit() }
        File(dir, "claude").apply {
            writeText("not executable")
            setExecutable(false)
            deleteOnExit()
        }
        val resolved = CliBinaryResolver.resolveUncached(
            binary = "claude",
            pathValue = "",
            extraDirs = listOf(dir),
            isWindows = false
        )
        assertEquals("claude", resolved)
    }

    @Test
    fun `login shell probe used as last resort`() {
        val (_, bin) = tempDirWithExecutable("claude")
        val resolved = CliBinaryResolver.resolveUncached(
            binary = "claude",
            pathValue = "",
            extraDirs = emptyList(),
            isWindows = false,
            loginShellProbe = { bin.absolutePath }
        )
        assertEquals(bin.absolutePath, resolved)
    }

    @Test
    fun `unresolvable binary returned unchanged`() {
        val resolved = CliBinaryResolver.resolveUncached(
            binary = "definitely-not-a-real-cli",
            pathValue = "/usr/bin:/bin",
            extraDirs = emptyList(),
            isWindows = false,
            loginShellProbe = { null }
        )
        assertEquals("definitely-not-a-real-cli", resolved)
    }

    @Test
    fun `explicit paths and windows are passed through`() {
        assertEquals(
            "/opt/custom/claude",
            CliBinaryResolver.resolveUncached("/opt/custom/claude", "", emptyList(), isWindows = false)
        )
        assertEquals(
            "claude",
            CliBinaryResolver.resolveUncached("claude", "", emptyList(), isWindows = true)
        )
    }

    @Test
    fun `well-known dirs include the usual install locations`() {
        val dirs = CliBinaryResolver.wellKnownDirs("/Users/someone").map { it.path }
        assertTrue("/Users/someone/.local/bin" in dirs)
        assertTrue("/opt/homebrew/bin" in dirs)
        assertTrue("/usr/local/bin" in dirs)
    }
}
