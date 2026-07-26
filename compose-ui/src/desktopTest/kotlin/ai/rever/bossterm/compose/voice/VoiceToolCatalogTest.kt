package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shape checks for the curated voice tool surface + its OpenAI function-calling rendering. */
class VoiceToolCatalogTest {

    @Test
    fun `openAiToolsJson renders function entries with schemas`() {
        val arr = VoiceToolCatalog.openAiToolsJson(VoiceToolCatalog.ALL)
        assertEquals(VoiceToolCatalog.ALL.size, arr.size)
        for (el in arr) {
            val o = el.jsonObject
            assertEquals("function", o["type"]?.jsonPrimitive?.content)
            assertTrue(o["name"]?.jsonPrimitive?.content?.isNotBlank() == true)
            assertTrue(o["description"]?.jsonPrimitive?.content?.isNotBlank() == true)
            val params = o["parameters"]?.jsonObject ?: JsonObject(emptyMap())
            assertEquals("object", params["type"]?.jsonPrimitive?.content)
            assertTrue(params.containsKey("properties"))
        }
    }

    @Test
    fun `required params are declared`() {
        fun requiredOf(name: String): List<String> =
            VoiceToolCatalog.ALL.first { it.name == name }
                .parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertEquals(listOf("script"), requiredOf("run_command"))
        assertEquals(listOf("text"), requiredOf("send_input"))
        assertEquals(listOf("signal"), requiredOf("send_signal"))
        assertEquals(listOf("pattern"), requiredOf("search_output"))
        assertEquals(emptyList(), requiredOf("list_tabs"))
    }

    @Test
    fun `write and guiOnly flags match the policy`() {
        val byName = VoiceToolCatalog.ALL.associateBy { it.name }
        // Write tools require the control role.
        assertEquals(
            setOf("run_command", "send_input", "send_signal"),
            byName.values.filter { it.write }.map { it.name }.toSet(),
        )
        // GUI-only tools must not be advertised by the daemon executor.
        assertEquals(
            setOf("list_panes", "search_output", "get_last_command", "run_command"),
            byName.values.filter { it.guiOnly }.map { it.name }.toSet(),
        )
        // The daemon surface is exactly the non-guiOnly set.
        assertEquals(
            setOf("list_tabs", "get_active_tab", "read_scrollback", "send_input", "send_signal"),
            byName.values.filter { !it.guiOnly }.map { it.name }.toSet(),
        )
    }
}
