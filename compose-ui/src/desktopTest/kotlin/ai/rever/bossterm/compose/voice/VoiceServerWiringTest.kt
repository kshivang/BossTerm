package ai.rever.bossterm.compose.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two policies that live in the SERVERS' wiring, where no unit test can reach them.
 *
 * `VoiceCallService` enforces both correctly and is well covered — but only if the servers hand it
 * the right values, and that handoff is four lines of plumbing across two files. Someone
 * "simplifying" `confidential = vc.confidential` to `confidential = true`, or dropping the
 * per-viewer `withReason`, would break a security guarantee and fail no existing test.
 *
 * Source-text assertions, for the same reason [VoiceCrossLanguageContractTest] uses them: what is
 * being checked is not behaviour but AGREEMENT between places that are edited separately. The
 * behaviour on each side already has real tests.
 */
class VoiceServerWiringTest {

    /**
     * Read a production source file.
     *
     * Walks up from the working directory rather than assuming one: Gradle can run this from the
     * module or the root. Fails loudly if the tree cannot be found — a wiring test that silently
     * skips is worse than no test, because it reports green while checking nothing.
     */
    private fun source(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "compose-ui/src/desktopMain/kotlin/ai/rever/bossterm/compose/$path")
            if (candidate.isFile) return candidate.readText()
            val here = File(dir, "src/desktopMain/kotlin/ai/rever/bossterm/compose/$path")
            if (here.isFile) return here.readText()
            dir = dir.parentFile
        }
        error("could not locate $path from ${File("").absolutePath} — this test would otherwise pass vacuously")
    }

    private val servers = listOf(
        "share/SessionShareManager.kt",
        "share/MirrorShare.kt",
        "daemon/DaemonShareServer.kt",
    )

    /**
     * The ephemeral secret may only go to a connection that completed the E2E handshake — TLS alone
     * is not enough, because these links are routinely published through a tunnel that terminates it.
     */
    @Test
    fun `both servers derive confidential from the negotiated cipher`() {
        for (path in listOf("share/SessionShareManager.kt", "daemon/DaemonShareServer.kt")) {
            val text = source(path)
            assertTrue(
                text.contains("confidential = serverCipher != null"),
                "$path must derive confidentiality from the E2E handshake, not assert it",
            )
        }
        for (path in listOf("share/MirrorShare.kt", "daemon/DaemonShareServer.kt")) {
            assertTrue(
                source(path).contains("confidential = vc.confidential"),
                "$path must forward the CONNECTION's value into handleStart",
            )
        }
    }

    /** Nothing may hand handleStart a hardcoded `true` — that is the fail-open the parameter removed. */
    @Test
    fun `no server claims confidentiality unconditionally`() {
        for (path in servers) {
            assertTrue(
                !source(path).contains("confidential = true"),
                "$path hardcodes confidential = true, defeating the insecure_transport gate",
            )
        }
    }

    /**
     * `reason` distinguishes "no key" from "switched off" — host configuration a view-only guest has
     * no business learning. Every place a status is sent has to make that per-viewer choice.
     */
    @Test
    fun `status is redacted per viewer wherever it is sent`() {
        for (path in listOf("share/MirrorShare.kt", "daemon/DaemonShareServer.kt")) {
            val text = source(path)
            assertTrue(
                text.contains("withReason = canControl") || text.contains("withReason = vc.canControl"),
                "$path must gate the reason on the viewer's role",
            )
        }
    }
}
