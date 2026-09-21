package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
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
            description = "Read the last N lines of a tab's terminal buffer - what's on screen plus history.",
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
                    put("description", "Hard timeout in milliseconds. Omit for the host's " +
                            "configured default.")
                }
            },
            write = true,
            guiOnly = true,
        ),
        VoiceToolDef(
            name = "send_input",
            description = "Type raw text into a tab's terminal (for interactive programs - TUIs, prompts). " +
                    "Include a trailing \\r to submit it; \\n only inserts a newline.",
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
    @JvmOverloads
    fun openAiToolsJson(defs: List<VoiceToolDef>, lean: Boolean = false): JsonArray = buildJsonArray {
        for (d in defs) {
            add(buildJsonObject {
                put("type", "function")
                put("name", d.name)
                put("description", d.description)
                put("parameters", requiredFirst(d.parameters).let { if (lean) withoutArgumentDocs(it) else it })
            })
        }
    }

    /**
     * Drop the per-argument `description` text from OPTIONAL arguments only.
     *
     * For a local model the tool prompt is paid for in latency on every turn, and argument docs
     * are the single largest part of it. Upstream's own note on `to_code_prompt` puts it at 906
     * tokens without argument docs versus 3,434 with, for their default tool profile - close to a
     * 4x difference, and BossTerm's MCP descriptions are wordier than theirs.
     *
     * REQUIRED arguments keep their prose as a conservative default: getting an optional argument
     * wrong degrades a call, while losing a required one voids it, and optional arguments are also
     * the bulk of the text since tools tend to have one or two required parameters and a long tail
     * of optional ones.
     *
     * Note for anyone tempted to widen this again: a run of missing-`script` failures on
     * `run_command` was briefly blamed on stripping required descriptions. It was not the cause.
     * The model was emitting a POSITIONAL call - `run_command("ls")` - which this server drops
     * ("Dropping positional arguments for 'run_command': {'__arg_0__'}") because it prompts tools
     * as Python-style signatures but accepts only keyword arguments. The same failure occurred
     * with full descriptions present. That is addressed in the instructions, not here.
     *
     * The TOOL's own description is always kept: that is what the model chooses between. The
     * OpenAI backend keeps the full schema either way.
     */
    internal fun withoutArgumentDocs(parameters: JsonObject): JsonObject {
        val properties = parameters["properties"] as? JsonObject ?: return parameters
        if (properties.isEmpty()) return parameters
        val required = (parameters["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()
        val stripped = buildJsonObject {
            for ((name, spec) in properties) {
                val obj = spec as? JsonObject
                if (obj == null || "description" !in obj || name in required) {
                    put(name, spec)
                } else {
                    putJsonObject(name) {
                        for ((k, v) in obj) if (k != "description") put(k, v)
                    }
                }
            }
        }
        return buildJsonObject {
            for ((key, value) in parameters) {
                if (key == "properties") put("properties", stripped) else put(key, value)
            }
        }
    }

    /**
     * Reorder `properties` so required ones come first, preserving order within each group.
     *
     * JSON Schema attaches no meaning to property ORDER - `required` is a separate list - so this
     * changes nothing semantically and OpenAI Realtime is unaffected. It exists because a
     * Realtime-compatible server may render each tool as a positional function signature, and
     * Python cannot express `def f(optional=None, mandatory)`.
     *
     * Measured against huggingface/speech-to-speech 1.0.0: `signature_from_schema` walks
     * `properties` in declaration order, gives every non-required property `default=None`, and
     * hands the result to `inspect.Signature`, which raises
     * `ValueError: non-default argument follows default argument`. That escapes through
     * `build_tool_system_prompt`, so ONE badly ordered tool takes down the tool prompt for EVERY
     * tool - the agent then transcribes perfectly and answers nothing, on every turn, with the
     * cause visible only in the server's own log.
     *
     * Applied at the single point every tool reaches, curated and MCP-derived alike, because tool
     * schemas arrive from the MCP server and from embedders and this file cannot police their
     * authoring order.
     */
    internal fun requiredFirst(parameters: JsonObject): JsonObject {
        val properties = parameters["properties"] as? JsonObject ?: return parameters
        val required = (parameters["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()
        // Nothing to do when the declared order already satisfies the constraint; skipping the
        // rebuild keeps the common case allocation-free and the emitted JSON byte-identical.
        if (required.isEmpty()) return parameters
        val ordered = properties.keys.sortedByDescending { it in required }
        if (ordered == properties.keys.toList()) return parameters
        return buildJsonObject {
            for ((key, value) in parameters) {
                if (key == "properties") {
                    putJsonObject("properties") { ordered.forEach { put(it, properties.getValue(it)) } }
                } else {
                    put(key, value)
                }
            }
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
