package ai.rever.bossterm.compose.daemon

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Headless end-to-end command execution, including the endpoint used by the preferred-shell hook. */
class DaemonRunCommandTest {
    @Test
    fun `invalid persisted settings cannot reenable a disabled preferred-shell command`() = withSettingsDir {
        val file = BossTermPaths.dir().resolve("settings.json")
        val gui = ai.rever.bossterm.compose.settings.SettingsManager(file.path)
        gui.updateSettings(TerminalSettings.DEFAULT.copy(disabledMcpTools = emptySet()))
        val daemon = ai.rever.bossterm.compose.settings.SettingsManager(file.path)
        val disabled = daemonMcpDisabledTools(daemon)
        assertTrue(disabled().isEmpty())
        gui.updateSetting { copy(disabledMcpTools = setOf("run_command")) }
        assertEquals(setOf("run_command"), disabled())
        file.writeText("transiently invalid settings")
        assertEquals(setOf("run_command"), disabled(), "keep the last valid persisted tool policy")
        gui.updateSetting { copy(disabledMcpTools = emptySet()) }
        assertTrue(disabled().isEmpty())
    }

    @Test
    fun `SSE advertises and executes run_command with ordinary MCP success semantics`() = withSettingsDir {
        runBlocking {
            val fixture = Fixture()
            val wrapper = DaemonMcpServer(fixture.host, shouldWriteMarker = { true }, disabledTools = { emptySet() })
            var client: RawMcpClient? = null
            try {
                val port = assertNotNull(wrapper.start(freePort()))
                awaitPort(port)
                client = RawMcpClient(this, port)
                val initialized = client.request("initialize", initializeParams())
                assertNotNull(initialized["result"], "initialize must succeed: $initialized")
                assertTrue(initialized["result"]!!.jsonObject["instructions"]?.jsonPrimitive?.content.orEmpty().contains("run_command"),
                    "preferred-shell initialization should direct clients to the available tool")
                client.notify("notifications/initialized")
                val listed = client.request("tools/list")
                val names = listed["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                assertTrue("run_command" in names, "the preferred-shell endpoint must expose its promised tool")
                assertTrue(BossTermPaths.mcpPortFile().exists())
                val called = client.request("tools/call", buildJsonObject {
                    put("name", "run_command")
                    put("arguments", command("exit 7\n\n"))
                })["result"]!!.jsonObject
                assertEquals(false, called["isError"]?.jsonPrimitive?.boolean ?: false, "a completed nonzero shell exit is a valid MCP result")
                val result = Json.parseToJsonElement(called["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
                assertTrue(result["ok"]!!.jsonPrimitive.boolean)
                assertEquals(7, result["exitCode"]!!.jsonPrimitive.int)
                assertEquals(JsonNull, result["error"])
                assertTrue(result["output"]!!.jsonPrimitive.content.contains("FAILED_COMMAND_OUTPUT"))
                assertTrue(result["durationMs"]!!.jsonPrimitive.long >= 20, "duration should measure command execution")
                assertEquals("exit 7\r", fixture.processes.single().writes.single(), "submit exactly one CR")
                val invalid = client.request("tools/call", buildJsonObject {
                    put("name", "run_command")
                    put("arguments", command("must not run") { put("pane_id", "missing") })
                })["result"]!!.jsonObject
                assertTrue(invalid["isError"]!!.jsonPrimitive.boolean, "invalid targets must be MCP errors")
                assertEquals(1, fixture.processes.single().writes.size, "invalid target must not submit input")
            } finally {
                client?.close()
                wrapper.stop()
                fixture.close()
            }
        }
    }

    @Test
    fun `disabled run_command suppresses preferred-shell marker and live reenable restores tool`() = withSettingsDir {
        runBlocking {
            val fixture = Fixture()
            var disabled = setOf("run_command")
            val wrapper = DaemonMcpServer(fixture.host, shouldWriteMarker = { true }, disabledTools = { disabled })
            var client: RawMcpClient? = null
            try {
                val port = assertNotNull(wrapper.start(freePort()))
                awaitPort(port)
                client = RawMcpClient(this, port)
                val initialized = client.request("initialize", initializeParams())["result"]!!.jsonObject
                assertFalse(initialized["instructions"]?.jsonPrimitive?.content.orEmpty().contains("run_command"))
                client.notify("notifications/initialized")
                assertFalse(BossTermPaths.mcpPortFile().exists(), "never deny Bash in favor of a disabled tool")
                fun containsRun(result: JsonObject) = result["result"]!!.jsonObject["tools"]!!.jsonArray.any {
                    it.jsonObject["name"]!!.jsonPrimitive.content == "run_command"
                }
                assertFalse(containsRun(client.request("tools/list")))
                disabled = emptySet()
                wrapper.syncDisabledTools()
                wrapper.syncPortMarker()
                assertTrue(containsRun(client.request("tools/list")), "reenable must update the same connected client's catalog")
                assertTrue(BossTermPaths.mcpPortFile().exists())
                disabled = setOf("run_command")
                wrapper.syncDisabledTools()
                wrapper.syncPortMarker()
                assertFalse(containsRun(client.request("tools/list")))
                assertFalse(BossTermPaths.mcpPortFile().exists())
            } finally {
                client?.close()
                wrapper.stop()
                fixture.close()
            }
        }
    }

    @Test
    fun `reused pane retains shell context and subsequent output excludes prior commands`() = runBlocking {
        val fixture = Fixture()
        val tools = DaemonMcpTools(fixture.host)
        try {
            val first = decode(tools.runCommand(command("save context") { put("working_dir", "/tmp/source") }))
            val id = first.string("paneId")
            val second = decode(tools.runCommand(command("read context") { put("pane_id", id) }))
            assertTrue(second["ok"]!!.jsonPrimitive.boolean)
            assertEquals(id, second.string("paneId"))
            assertEquals(first.string("tabId"), second.string("tabId"))
            assertEquals(1, fixture.processes.size, "explicit pane reuse must retain the same shell")
            assertEquals("/tmp/source", fixture.processes.single().initialDirectory)
            assertTrue(second.string("output").contains("persisted-value"))
            assertTrue(second.string("output").contains("/tmp/changed"))
            assertFalse(second.string("output").contains("CONTEXT_SAVED"))
            assertEquals("/tmp/changed", fixture.host.get(id)!!.workingDirectory.value)
            val implicitReuse = decode(tools.runCommand(command("read context")))
            assertEquals(id, implicitReuse.string("paneId"), "the default scratch pane must be reused without an explicit pane id")
            assertEquals(1, fixture.host.count())
        } finally { fixture.close() }
    }

    @Test
    fun `group scratch pane follows split topology and reuse while new_tab works on an uncached source`() = runBlocking {
        val fixture = Fixture()
        val tools = DaemonMcpTools(fixture.host)
        try {
            val (source, group) = fixture.host.openWindow(cwd = "/tmp/group")
            val first = decode(tools.runCommand(command("first") {
                put("tab_id", group); put("panel", "vertical_split"); put("split_ratio", 0.25)
            }))
            assertEquals(group, first.string("tabId"))
            assertTrue(source != first.string("paneId"))
            val tree = assertIs<GroupTreeDto.Split>(fixture.host.listGroups().single().tree)
            assertEquals("v", tree.dir)
            assertEquals(0.75f, tree.ratio, "split_ratio describes the new scratch pane, so the original pane retains 75 percent")
            assertEquals(setOf(source, first.string("paneId")), DaemonShareServer.groupSessionIds(tree))
            val reused = decode(tools.runCommand(command("second") { put("tab_id", group) }))
            assertEquals(first.string("paneId"), reused.string("paneId"))
            assertEquals(2, fixture.host.count())
            val (_, otherGroup) = fixture.host.openWindow()
            val fresh = decode(tools.runCommand(command("new tab") { put("tab_id", otherGroup); put("panel", "new_tab") }))
            assertTrue(fresh.string("tabId") != otherGroup)
            assertTrue(fresh.string("paneId") != reused.string("paneId"))
            assertEquals(3, fixture.host.listGroups().size)
            assertEquals(4, fixture.host.count())
        } finally { fixture.close() }
    }

    @Test
    fun `explicit pane membership and argument validation cannot create or type into another session`() = runBlocking {
        val fixture = Fixture()
        val tools = DaemonMcpTools(fixture.host)
        try {
            val (first, group) = fixture.host.openWindow()
            val (foreign, _) = fixture.host.openWindow()
            val bad = listOf(
                buildJsonObject {}, command("  "), command("x") { put("pane_id", "missing") },
                command("x") { put("tab_id", "missing") }, command("x") { put("tab_id", group); put("pane_id", foreign) },
                command("x") { put("panel", "invalid") }, command("x") { put("pane_id", true) },
                command("x") { put("timeout_ms", "100") }, command("x") { put("working_dir", false) },
            )
            for (args in bad) assertNotEquals(JsonNull, decode(tools.runCommand(args))["error"], "invalid request must fail: $args")
            assertEquals(2, fixture.host.count())
            assertTrue(fixture.processes.all { it.writes.isEmpty() }, "validation must happen before input submission")
            val okay = decode(tools.runCommand(command("valid") { put("tab_id", group); put("pane_id", first) }))
            assertTrue(okay["ok"]!!.jsonPrimitive.boolean)
            assertEquals(first, okay.string("paneId"))
            assertEquals(2, fixture.host.count())
        } finally { fixture.close() }
    }

    @Test
    fun `concurrent commands from different tool adapters serialize on the same pane`() = runBlocking {
        val fixture = Fixture()
        val firstTools = DaemonMcpTools(fixture.host)
        val secondTools = DaemonMcpTools(fixture.host)
        try {
            val id = fixture.host.openSession()
            await { fixture.processes.isNotEmpty() }
            val process = fixture.processes.single()
            val first = async { decode(firstTools.runCommand(command("hold") { put("pane_id", id) })) }
            withTimeout(5000) { process.holdStarted.await() }
            val second = async { decode(secondTools.runCommand(command("after hold") { put("pane_id", id) })) }
            delay(100)
            assertEquals(listOf("hold\r"), process.writes.toList(), "second command must not reach stdin before first command finishes")
            process.releaseHold.complete(Unit)
            val firstResult = withTimeout(5000) { first.await() }
            assertTrue(firstResult["ok"]!!.jsonPrimitive.boolean, firstResult.toString())
            val secondResult = withTimeout(5000) { second.await() }
            assertTrue(secondResult["ok"]!!.jsonPrimitive.boolean, secondResult.toString())
            assertTrue(secondResult.string("output").contains("SECOND_COMMAND_OUTPUT"), secondResult.toString())
            assertFalse(secondResult.string("output").contains("HOLD_OUTPUT"))
            assertEquals(listOf("hold\r", "after hold\r"), process.writes.toList())
        } finally { fixture.processes.forEach { it.releaseHold.complete(Unit) }; fixture.close() }
    }

    private class Fixture {
        val processes = CopyOnWriteArrayList<FakeProcess>()
        val settings = TerminalSettings.DEFAULT.copy(autoInjectShellIntegration = false, useLoginSession = false,
            mcpRunCommandPreferredShell = true, mcpRunCommandShellReadyTimeoutMs = 0, mcpRunCommandDefaultTimeoutMs = 3000)
        val host = SessionHost(settings, platformServices = object : PlatformServices by getPlatformServices() {
            override fun getProcessService() = object : PlatformServices.ProcessService {
                override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig): PlatformServices.ProcessService.ProcessHandle {
                    val process = FakeProcess(config.workingDirectory ?: "/tmp")
                    processes.add(process)
                    process.output.send("\u001b]133;A\u0007$ ")
                    return process
                }
            }
        })
        fun close() { host.close() }
    }

    private class FakeProcess(val initialDirectory: String) : PlatformServices.ProcessService.ProcessHandle {
        val output = Channel<String>(Channel.UNLIMITED)
        val writes = CopyOnWriteArrayList<String>()
        val holdStarted = CompletableDeferred<Unit>()
        val releaseHold = CompletableDeferred<Unit>()
        private val exit = CompletableDeferred<Int>()
        private var directory = initialDirectory
        private var saved = ""
        override suspend fun write(data: String) {
            writes.add(data)
            val script = data.trimEnd('\r', '\n')
            output.send("$script\r\n\u001b]133;B\u0007\u001b]133;C\u0007")
            val text = when (script) {
                "save context" -> { saved = "persisted-value"; directory = "/tmp/changed"; "CONTEXT_SAVED" }
                "read context" -> "$saved\r\n$directory"
                "exit 7" -> "FAILED_COMMAND_OUTPUT"
                "hold" -> { holdStarted.complete(Unit); releaseHold.await(); "HOLD_OUTPUT" }
                "after hold" -> "SECOND_COMMAND_OUTPUT"
                else -> "RESULT $script"
            }
            delay(35)
            output.send("$text\r\n\u001b]7;file://localhost$directory\u0007\u001b]133;D;${if (script == "exit 7") 7 else 0}\u0007\u001b]133;A\u0007$ ")
        }
        override suspend fun writeBytes(data: ByteArray) = write(data.toString(Charsets.UTF_8))
        override suspend fun read(): String? = output.receiveCatching().getOrNull()
        override suspend fun waitFor() = exit.await()
        override suspend fun kill() { output.close(); exit.complete(0); releaseHold.complete(Unit) }
        override suspend fun resize(columns: Int, rows: Int) {}
        override fun isAlive() = !exit.isCompleted
        override fun getExitCode(): Int? = if (isAlive()) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory() = directory
    }

    /** Exercise the existing SSE GET/POST transport without adding a second MCP SDK dependency. */
    private class RawMcpClient(scope: CoroutineScope, private val port: Int) {
        private val http = HttpClient(CIO)
        private val endpoint = CompletableDeferred<String>()
        private val messages = Channel<JsonObject>(Channel.UNLIMITED)
        private var sequence = 0
        private val readerJob = scope.launch(Dispatchers.IO) {
            try {
                http.prepareGet("http://127.0.0.1:$port/") { header("Accept", "text/event-stream") }.execute { response ->
                    check(response.status.value == 200) { "SSE GET failed" }
                    val reader = response.bodyAsChannel()
                    var event = ""
                    val data = StringBuilder()
                    while (true) {
                        val line = reader.readUTF8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (event == "endpoint") endpoint.complete(data.toString())
                                else if (data.isNotEmpty()) messages.send(Json.parseToJsonElement(data.toString()).jsonObject)
                                event = ""; data.setLength(0)
                            }
                            line.startsWith("event:") -> event = line.substringAfter(':').trim()
                            line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substringAfter(':').trimStart()) }
                        }
                    }
                }
            } catch (t: Throwable) { if (!endpoint.isCompleted) endpoint.completeExceptionally(t); messages.close(t) }
        }
        suspend fun request(method: String, params: JsonObject = buildJsonObject {}) = withTimeout(8000) {
            val id = ++sequence
            post(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
            var response: JsonObject
            do { response = messages.receive() } while (response["id"]?.jsonPrimitive?.intOrNull != id)
            response
        }
        suspend fun notify(method: String) = post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) })
        private suspend fun post(message: JsonObject) {
            val path = withTimeout(5000) { endpoint.await() }
            withContext(Dispatchers.IO) {
                val url = URI("http://127.0.0.1:$port/").resolve(path).toURL()
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.connectTimeout = 3000; connection.readTimeout = 5000
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(message.toString().toByteArray()) }
                    check(connection.responseCode in 200..299) { "MCP POST failed: ${connection.responseCode}" }
                } finally { connection.disconnect() }
            }
        }
        fun close() { readerJob.cancel(); http.close(); messages.close() }
    }

    private fun command(script: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("script", script); put("timeout_ms", 3000); extra()
    }
    private fun decode(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun initializeParams() = buildJsonObject {
        put("protocolVersion", "2025-11-25")
        put("capabilities", buildJsonObject {})
        put("clientInfo", buildJsonObject { put("name", "daemon-regression"); put("version", "1") })
    }
    private fun freePort() = ServerSocket(0).use { it.localPort }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(5) }
    private suspend fun awaitPort(port: Int) = await { runCatching { Socket("127.0.0.1", port).close(); true }.getOrDefault(false) }
    private fun <T> withSettingsDir(block: () -> T): T {
        val prior = System.getProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
        val directory = Files.createTempDirectory("daemon-run-command").toFile()
        System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, directory.absolutePath)
        try { return block() } finally {
            if (prior == null) System.clearProperty(BossTermPaths.SETTINGS_DIR_PROPERTY)
            else System.setProperty(BossTermPaths.SETTINGS_DIR_PROPERTY, prior)
            directory.deleteRecursively()
        }
    }
}
