package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One tool the voice agent may call. [parameters] is the OpenAI function-calling JSON schema;
 * [write] marks tools that mutate the session (controller role required); [guiOnly] marks tools
 * that only exist on the GUI MCP surface (the daemon executor doesn't advertise them).
 */
data class VoiceToolDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val write: Boolean,
    val guiOnly: Boolean = false,
)

/**
 * The curated tool surface for Boss Calling — a 1:1 mapping onto existing BossTerm MCP tools,
 * trimmed to what a voice agent needs. Deliberately excluded: `run_in_panel` (subsumed by
 * `run_command`), `show_image` (renders on the HOST screen — useless to a remote caller),
 * `read_debug_console` (niche/verbose), `manage_tools` (meta), and the daemon's
 * `open/close/resize_session` (destructive or session-escape).
 */
object VoiceToolCatalog {

    private const val TAB_ID_DESC =
        "Tab to target. Omit to use the tab the user is currently viewing."

    val ALL: List<VoiceToolDef> = listOf(
        VoiceToolDef(
            name = "list_tabs",
            description = "List the terminal tabs in this shared session (id, title, cwd, isActive).",
            parameters = objectSchema { },
            write = false,
        ),
        VoiceToolDef(
            name = "get_active_tab",
            description = "Return the tab the user is currently viewing.",
            parameters = objectSchema { },
            write = false,
        ),
        VoiceToolDef(
            name = "list_panes",
            description = "List the split panes of a tab (id, title, cwd, isFocused).",
            parameters = objectSchema {
                putJsonObject("tab_id") { put("type", "string"); put("description", TAB_ID_DESC) }
            },
            write = false,
            guiOnly = true,
        ),
        VoiceToolDef(
            name = "read_scrollback",
            description = "Read the last N lines of a tab's terminal buffer — what's on screen plus history.",
            parameters = objectSchema {
                putJsonObject("tab_id") { put("type", "string"); put("description", TAB_ID_DESC) }
                putJsonObject("lines") {
                    put("type", "integer")
                    put("description", "How many lines to read (default 200).")
                }
            },
            write = false,
        ),
        VoiceToolDef(
            name = "search_output",
            description = "Regex-search a tab's terminal buffer (errors, filenames, URLs…).",
            parameters = objectSchema(required = listOf("pattern")) {
                putJsonObject("pattern") { put("type", "string"); put("description", "Regex to search for.") }
                putJsonObject("tab_id") { put("type", "string"); put("description", TAB_ID_DESC) }
                putJsonObject("max_matches") {
                    put("type", "integer")
                    put("description", "Cap on returned matches.")
                }
            },
            write = false,
            guiOnly = true,
        ),
        VoiceToolDef(
            name = "get_last_command",
            description = "The most recently completed command in a tab: command text, exit code, duration.",
            parameters = objectSchema {
                putJsonObject("tab_id") { put("type", "string"); put("description", TAB_ID_DESC) }
            },
            write = false,
            guiOnly = true,
        ),
        VoiceToolDef(
            name = "run_command",
            description = "Run a shell command in the session and return its output and exit code. " +
                    "Use this for one-shot commands; use send_input for interactive programs.",
            parameters = objectSchema(required = listOf("script")) {
                putJsonObject("script") { put("type", "string"); put("description", "Shell command to run.") }
                putJsonObject("tab_id") { put("type", "string"); put("description", TAB_ID_DESC) }
                putJsonObject("timeout_ms") {
                    put("type", "integer")
                    put("description", "Hard timeout in milliseconds (default 120000).")
                }
            },
            write = true,
            guiOnly = true,
        ),
        VoiceToolDef(
            name = "send_input",
            description = "Type raw text into a tab's terminal (for interactive programs — TUIs, prompts). " +
                    "Include a trailing \\n to submit.",
            parameters = objectSchema(required = listOf("text")) {
                putJsonObject("text") { put("type", "string"); put("description", "Text to type.") }
                putJsonObject("tab_id") { put("type", "string"); put("description", TAB_ID_DESC) }
            },
            write = true,
        ),
        VoiceToolDef(
            name = "send_signal",
            description = "Send a control signal to a tab: ctrl_c (interrupt), ctrl_d (EOF), ctrl_z (suspend).",
            parameters = objectSchema(required = listOf("signal")) {
                putJsonObject("signal") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("ctrl_c"))
                        add(JsonPrimitive("ctrl_d"))
                        add(JsonPrimitive("ctrl_z"))
                    }
                    put("description", "Which signal to send.")
                }
                putJsonObject("tab_id") { put("type", "string"); put("description", TAB_ID_DESC) }
            },
            write = true,
        ),
    )

    /**
     * The parameter names a tool actually advertises. Both executors filter the model's arguments
     * through this: the underlying MCP schemas are WIDER than this catalog (run_command also takes
     * panel/working_dir), and the two surfaces must agree on that invariant — otherwise the next
     * parameter added to a daemon tool becomes silently reachable from voice.
     */
    fun declaredParameters(def: VoiceToolDef): Set<String> =
        runCatching { (def.parameters["properties"] as? JsonObject)?.keys?.toSet() }
            .getOrNull().orEmpty()

    /** Render [defs] as the OpenAI Realtime session `tools` array (`[{type:"function",…}]`). */
    fun openAiToolsJson(defs: List<VoiceToolDef>): JsonArray = buildJsonArray {
        for (d in defs) {
            add(buildJsonObject {
                put("type", "function")
                put("name", d.name)
                put("description", d.description)
                put("parameters", d.parameters)
            })
        }
    }

    private inline fun objectSchema(
        required: List<String> = emptyList(),
        crossinline properties: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties() }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
    }
}
