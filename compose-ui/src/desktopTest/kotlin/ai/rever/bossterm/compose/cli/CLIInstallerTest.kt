package ai.rever.bossterm.compose.cli

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the mac/Linux CLI install dir handling — specifically the fix where a MISSING bin dir
 * is created instead of erroring ("Directory /usr/local/bin does not exist" on Apple Silicon,
 * where that dir doesn't exist by default). Uses [CLIInstaller.testInstallDir]/[testScript] to
 * point the installer at a temp dir + a stub script, so nothing touches the real /usr/local.
 */
class CLIInstallerTest {

    private val script = "#!/bin/bash\necho bossterm test cli\n"
    private var tmp: File? = null

    @AfterTest
    fun cleanup() {
        CLIInstaller.testInstallDir = null
        CLIInstaller.testScript = null
        tmp?.deleteRecursively()
    }

    // Windows takes the AppData/.cmd path; this fix is mac/Linux-only.
    private fun skipOnWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun `install creates a missing bin dir and writes the executable script`() {
        if (skipOnWindows()) return
        val root = File.createTempFile("bossterm-cli-test", "").apply { delete(); mkdirs() }
        tmp = root
        val binDir = File(root, "nested/bin") // does NOT exist yet (parent is user-writable)
        assertTrue(!binDir.exists(), "precondition: bin dir must be absent")

        CLIInstaller.testInstallDir = binDir.absolutePath
        CLIInstaller.testScript = script

        val result = CLIInstaller.install()

        assertEquals(CLIInstaller.InstallResult.Success, result)
        assertTrue(binDir.isDirectory, "the missing bin dir should have been created")
        val installed = File(binDir, "bossterm")
        assertTrue(installed.isFile, "the script should be written")
        assertEquals(script, installed.readText())
        assertTrue(installed.canExecute(), "the script should be marked executable")
    }

    @Test
    fun `install into an already-existing bin dir still works`() {
        if (skipOnWindows()) return
        val root = File.createTempFile("bossterm-cli-test", "").apply { delete(); mkdirs() }
        tmp = root
        val binDir = File(root, "bin").apply { mkdirs() } // already exists

        CLIInstaller.testInstallDir = binDir.absolutePath
        CLIInstaller.testScript = script

        val result = CLIInstaller.install()

        assertEquals(CLIInstaller.InstallResult.Success, result)
        val installed = File(binDir, "bossterm")
        assertTrue(installed.isFile)
        assertEquals(script, installed.readText())
    }
}
