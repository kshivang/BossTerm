package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DaemonGuiLauncherTest {
    @Test
    fun `dock icon is captured from native JVM launcher arguments`() {
        if (!ShellCustomizationUtils.isMacOS()) return
        val icon = File(System.getProperty("bossterm.repoRoot"), "BossTerm.icns").absolutePath
        val classpath = listOf(
            DaemonGuiBrandingProbe::class.java, DaemonLauncher::class.java,
            org.slf4j.LoggerFactory::class.java, kotlin.Unit::class.java,
        ).map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xdock:icon=$icon", "-cp", classpath, DaemonGuiBrandingProbe::class.java.name,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals(icon, process.inputStream.bufferedReader().readText().trim())
        } finally { process.destroyForcibly() }
    }

    @Test
    fun `dev GUI preserves dock branding and profile through daemon launch`() {
        if (!ShellCustomizationUtils.isMacOS()) return
        val iconKey = DaemonLauncher.GUI_DOCK_ICON_PROPERTY
        val profileKey = BossTermPaths.SETTINGS_DIR_PROPERTY
        val previousIcon = System.getProperty(iconKey)
        val previousProfile = System.getProperty(profileKey)
        try {
            val icon = "/test resources/BossTerm.icns"
            System.setProperty(profileKey, "/test profiles/daemon")
            DaemonLauncher.captureGuiBranding(listOf("-Xdock:icon=$icon"))
            val daemon = assertNotNull(DaemonLauncher.buildCommand())
            assertTrue("-D$iconKey=$icon" in daemon)
            val gui = assertNotNull(DaemonLauncher.buildGuiCommand())
            assertTrue("-Xdock:name=BossTerm" in gui)
            assertTrue("-Xdock:icon=$icon" in gui)
            assertTrue("-D$profileKey=${BossTermPaths.dir().absolutePath}" in gui)
            assertTrue(gui.none { it == "-Dapple.awt.UIElement=true" || it == "-Djava.awt.headless=true" })
        } finally {
            if (previousIcon == null) System.clearProperty(iconKey) else System.setProperty(iconKey, previousIcon)
            if (previousProfile == null) System.clearProperty(profileKey) else System.setProperty(profileKey, previousProfile)
        }
    }
}

internal object DaemonGuiBrandingProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        DaemonLauncher.captureGuiBranding()
        println(System.getProperty(DaemonLauncher.GUI_DOCK_ICON_PROPERTY))
    }
}
