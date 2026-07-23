package ai.rever.bossterm.compose.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class UpdateInstallerPathResolutionTest {

    @Test
    fun `non-translocated app path is returned without filesystem lookup`() {
        val path = "/Applications/BossTerm.app"

        val resolvedPath = realAppPathFor(
            path = path,
            appExists = { fail("Non-translocated paths must not query the filesystem") },
            installedAppLookup = { fail("Non-translocated paths must not query Spotlight") }
        )

        assertEquals(path, resolvedPath)
    }

    @Test
    fun `translocated path resolves bundle name in Applications`() {
        val path = "/private/var/folders/xx/T/AppTranslocation/uuid/d/BossTerm Preview.app/Contents/MacOS/BossTerm"
        val expectedPath = "/Applications/BossTerm Preview.app"

        val resolvedPath = realAppPathFor(
            path = path,
            appExists = { it == expectedPath },
            installedAppLookup = { fail("Applications path should be preferred over Spotlight") }
        )

        assertEquals(expectedPath, resolvedPath)
    }

    @Test
    fun `bundle suffix is matched at path segment boundary`() {
        val path = "/private/var/folders/xx/T/AppTranslocation/uuid/d/My.application.app/Contents/MacOS/App"
        val expectedPath = "/Applications/My.application.app"

        val resolvedPath = realAppPathFor(
            path = path,
            appExists = { it == expectedPath },
            installedAppLookup = { fail("Applications path should be preferred over Spotlight") }
        )

        assertEquals(expectedPath, resolvedPath)
    }

    @Test
    fun `library path preserves app substring inside bundle name`() {
        val libraryPath = listOf(
            "/usr/local/lib",
            "/Applications/My.application.app/Contents/runtime/Contents/Home/lib"
        ).joinToString(File.pathSeparator)

        assertEquals(
            "/Applications/My.application.app",
            macOSAppBundlePathFromLibraryPath(libraryPath)
        )
    }

    @Test
    fun `translocated path falls back to installed app lookup`() {
        val path = "/private/var/folders/xx/T/AppTranslocation/uuid/d/BossTerm.app"
        val spotlightPath = "/Users/test/Applications/BossTerm.app"

        val resolvedPath = realAppPathFor(
            path = path,
            appExists = { it == spotlightPath },
            installedAppLookup = { spotlightPath }
        )

        assertEquals(spotlightPath, resolvedPath)
    }

    @Test
    fun `Spotlight candidates prefer Applications regardless of result order`() {
        val applicationsPath = "/Applications/BossTerm Preview.app"
        val candidates = listOf(
            "/Users/test/Downloads/BossTerm.app",
            applicationsPath,
            "/Users/test/Applications/BossTerm.app"
        )

        assertEquals(
            applicationsPath,
            preferredInstalledAppPath(candidates.asSequence()) { true }
        )
        assertEquals(
            applicationsPath,
            preferredInstalledAppPath(candidates.reversed().asSequence()) { true }
        )
    }

    @Test
    fun `Spotlight candidates use path order as a deterministic tie breaker`() {
        val expectedPath = "/Users/alpha/Applications/BossTerm.app"
        val candidates = listOf(
            "/Users/zulu/Applications/BossTerm.app",
            expectedPath
        )

        assertEquals(
            expectedPath,
            preferredInstalledAppPath(candidates.asSequence()) { true }
        )
        assertEquals(
            expectedPath,
            preferredInstalledAppPath(candidates.reversed().asSequence()) { true }
        )
    }

    @Test
    fun `unresolved translocated path is preserved`() {
        val path = "/private/var/folders/xx/T/AppTranslocation/uuid/d/BossTerm.app"

        val resolvedPath = realAppPathFor(
            path = path,
            appExists = { false },
            installedAppLookup = { null }
        )

        assertEquals(path, resolvedPath)
    }
}
