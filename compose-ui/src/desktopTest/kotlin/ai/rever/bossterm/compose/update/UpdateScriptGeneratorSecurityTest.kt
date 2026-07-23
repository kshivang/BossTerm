package ai.rever.bossterm.compose.update

import kotlin.test.Test
import kotlin.test.assertTrue

class UpdateScriptGeneratorSecurityTest {

    @Test
    fun `macOS script strips quarantine and keeps failure visible`() {
        val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
            dmgPath = "/tmp/update.dmg",
            targetAppPath = "/Applications/BossTerm.app",
            appPid = 12345
        )

        try {
            val scriptContent = scriptFile.readText()
            assertTrue(
                scriptContent.contains("xattr -dr com.apple.quarantine '/Applications/BossTerm.app'"),
                "Script should strip quarantine from the installed bundle"
            )
            assertTrue(
                scriptContent.contains("Warning: failed to clear quarantine attribute"),
                "Quarantine-removal failures should remain visible without aborting the update"
            )
        } finally {
            scriptFile.delete()
        }
    }

    @Test
    fun `macOS script retries a rejected relaunch request`() {
        val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
            dmgPath = "/tmp/update.dmg",
            targetAppPath = "/Applications/BossTerm.app",
            appPid = 12345
        )

        try {
            val scriptContent = scriptFile.readText()
            val expectedRetryBlock = """
                open '/Applications/BossTerm.app'
                if [ ${'$'}? -ne 0 ]; then
                    echo "First relaunch attempt failed - retrying in 2s..."
                    sleep 2
                    open '/Applications/BossTerm.app' || echo "Relaunch failed - please start BossTerm manually"
                fi
            """.trimIndent()

            assertTrue(
                scriptContent.contains(expectedRetryBlock),
                "Script should retry a rejected LaunchServices request and provide a manual fallback"
            )
        } finally {
            scriptFile.delete()
        }
    }
}
