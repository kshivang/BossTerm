package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.shell.ShellCustomizationUtils
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonCommandPtyTest {
    @Test
    fun `run_command captures real shell output and preserves scratch context after client reconnect`() = runBlocking {
        if (ShellCustomizationUtils.isWindows() || !File("/bin/bash").exists()) return@runBlocking
        val dir = Files.createTempDirectory(File("/tmp").toPath(), "boss-cmd-").toFile()
        val rc = File(dir, "bashrc")
        // Real shell integration with isolated startup files; never source the user's dotfiles.
        rc.writeText(checkNotNull(javaClass.getResourceAsStream("/shell-integration/bossterm_shell_integration.bash"))
            .bufferedReader().use { it.readText() })
        val actual = getPlatformServices()
        val processService = actual.getProcessService()
        val platform = object : PlatformServices by actual {
            override fun getProcessService() = object : PlatformServices.ProcessService {
                override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig) =
                    processService.spawnProcess(config.copy(
                        command = "/bin/bash", arguments = listOf("--noprofile", "--rcfile", rc.absolutePath, "-i"),
                        environment = config.environment + mapOf("PS1" to "boss-test> ", "BOSSTERM_SHELL_INTEGRATION_LOADED" to ""),
                    ))
            }
        }
        val host = SessionHost(TerminalSettings.DEFAULT.copy(autoInjectShellIntegration = false, useLoginSession = false),
            platformServices = platform)
        val server = DaemonMcpServer(host)
        var client = server.createServer()
        suspend fun call(script: String, pane: String? = null): JsonObject {
            val response = client.tools.getValue("run_command").handler(
                ai.rever.bossterm.compose.mcp.InProcessClientConnection,
                io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                    io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams("run_command", buildJsonObject {
                        put("script", script); put("working_dir", dir.absolutePath); put("timeout_ms", 5000)
                        pane?.let { put("pane_id", it) }
                    }),
                ),
            )
            assertFalse(response.isError == true, response.toString())
            return Json.parseToJsonElement((response.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text) as JsonObject
        }
        try {
            withTimeout(15_000) {
                val first = call("export BOSS_DAEMON_TEST_VALUE=kept; printf 'FIRST_RESULT\\n'")
                assertEquals(0, first.getValue("exitCode").jsonPrimitive.int)
                assertTrue(first.getValue("output").jsonPrimitive.content.contains("FIRST_RESULT"), first.toString())
                val pane = first.getValue("paneId").jsonPrimitive.content
                client.close()
                client = server.createServer() // no GUI and a new MCP client; session remains daemon-owned.
                val second = call("printf '%s\\n' \"\$BOSS_DAEMON_TEST_VALUE\"; pwd; false", pane)
                assertEquals(1, second.getValue("exitCode").jsonPrimitive.int)
                assertEquals(pane, second.getValue("paneId").jsonPrimitive.content)
                val output = second.getValue("output").jsonPrimitive.content
                assertTrue(output.contains("kept") && (output.contains(dir.canonicalPath) || output.contains(dir.absolutePath)), second.toString())
                assertEquals(1, host.count())
            }
        } finally {
            client.close()
            host.close()
            dir.deleteRecursively()
        }
    }
}
