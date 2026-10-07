package ai.rever.bossterm.compose.update

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowlessUpdateScriptTest {
    @Test
    fun `deferred macOS helper relaunches without windows only when requested`() {
        if (ShellCustomizationUtils.isWindows()) return
        for (requestRelaunch in listOf(false, true)) {
            val dir = Files.createTempDirectory("bossterm-idle-update").toFile()
            var helper: Process? = null
            var script: File? = null
            try {
                val volume = File(dir, "volume").apply { mkdirs() }
                val sourceApp = File(volume, "BossTerm.app").apply { mkdirs() }
                File(sourceApp, "version").writeText("new version")
                val targetApp = File(dir, "Installed BossTerm.app").apply { mkdirs() }
                File(targetApp, "version").writeText("old version")
                val marker = File(dir, "windowless.request")
                if (requestRelaunch) marker.writeText("--no-window\n")
                val openArgs = File(dir, "open-args")
                val bin = File(dir, "bin").apply { mkdirs() }
                // Stub macOS tools and sleep. Copy/removal operate only on these
                // temporary fixture bundles; no app is installed or launched.
                fun command(name: String, body: String) {
                    File(bin, name).apply {
                        writeText("#!/bin/sh\n$body\n")
                        assertTrue(setExecutable(true))
                    }
                }
                command("hdiutil", "exit 0")
                command("xattr", "exit 0")
                command("sleep", "exit 0")
                command("ls", "printf '%s\\n' '${volume.absolutePath}'")
                command("open", "printf '%s\\n' \"\$@\" >> '${openArgs.absolutePath}'")
                script = UpdateScriptGenerator.generateMacOSUpdateScript(
                    dmgPath = File(dir, "update.dmg").absolutePath,
                    targetAppPath = targetApp.absolutePath,
                    appPid = 123456789,
                    restartAutomatically = false,
                    windowlessRelaunchRequestPath = marker.absolutePath,
                )
                helper = ProcessBuilder("bash", script.absolutePath).apply {
                    environment()["PATH"] = "${bin.absolutePath}:/usr/bin:/bin"
                    redirectErrorStream(true)
                }.start()
                assertTrue(helper.waitFor(10, TimeUnit.SECONDS), "Helper did not finish")
                assertEquals(0, helper.exitValue(), helper.inputStream.bufferedReader().readText())
                assertEquals("new version", File(targetApp, "version").readText())
                if (requestRelaunch) {
                    assertEquals(listOf("-n", targetApp.absolutePath, "--args", "--no-window"), openArgs.readLines())
                } else {
                    assertFalse(openArgs.exists(), "Manual quit must not relaunch the app")
                }
                assertFalse(marker.exists(), "The helper must clean up its relaunch request")
            } finally {
                helper?.destroyForcibly()
                script?.delete()
                dir.deleteRecursively()
            }
        }
    }
}
