package ai.rever.bossterm.compose.auth

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import org.slf4j.LoggerFactory

/**
 * Registers the `bossterm://` URL scheme on Windows (HKCU — no elevation needed) so
 * sign-in links can open the app. Only runs in PACKAGED builds: `jpackage.app-path`
 * is set by the jpackage launcher (absent under `gradlew run`) and conveniently IS
 * the exe path the registry command must point at. macOS registers the scheme via
 * Info.plist CFBundleURLTypes; Linux via the .desktop MimeType. Best-effort —
 * failure just means links don't open the app on this machine.
 */
internal object WindowsProtocolRegistrar {

    private val log = LoggerFactory.getLogger(WindowsProtocolRegistrar::class.java)

    fun registerIfPackaged() {
        if (!ShellCustomizationUtils.isWindows()) return
        val exe = System.getProperty("jpackage.app-path") ?: return
        runCatching {
            reg("add", """HKCU\Software\Classes\bossterm""", "/ve", "/d", "URL:BossTerm Protocol", "/f")
            reg("add", """HKCU\Software\Classes\bossterm""", "/v", "URL Protocol", "/d", "", "/f")
            reg("add", """HKCU\Software\Classes\bossterm\shell\open\command""", "/ve", "/d", "\"$exe\" \"%1\"", "/f")
            log.info("Registered bossterm:// URL scheme (HKCU)")
        }.onFailure { log.warn("Could not register bossterm:// scheme: {}", it.message) }
    }

    private fun reg(vararg args: String) {
        val code = ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start().waitFor()
        check(code == 0) { "reg ${args.firstOrNull()} exited $code" }
    }
}
