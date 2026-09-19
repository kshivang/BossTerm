package ai.rever.bossterm.compose.remote.files

import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import org.junit.Assume.assumeTrue

/** Skip only unsupported OSes, never a broken backend on a supported host. */
internal fun requireFileHostPlatform() {
    assumeTrue(
        "Remote file hosting supports macOS and Linux; Windows remains a client",
        ShellCustomizationUtils.isMacOS() || ShellCustomizationUtils.isLinux(),
    )
}
