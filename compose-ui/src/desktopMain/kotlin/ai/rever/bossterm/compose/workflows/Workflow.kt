package ai.rever.bossterm.compose.workflows

/**
 * A single parameter of a [Workflow], mirroring Warp's `arguments` schema.
 */
data class WorkflowArgument(
    val name: String,
    val description: String? = null,
    val defaultValue: String? = null,
)

/**
 * A saved, parameterized command template — Warp-compatible.
 *
 * Loaded from `*.yaml` files (see [WorkflowStore]) using the Warp schema:
 * `name`, `command`, `description`, `arguments: [{name, description, default_value}]`,
 * `tags`, `shells`. Parameters are referenced in [command] as `{{name}}`.
 */
data class Workflow(
    val name: String,
    val command: String,
    val description: String? = null,
    val arguments: List<WorkflowArgument> = emptyList(),
    val tags: List<String> = emptyList(),
    val shells: List<String> = emptyList(),
    /** Absolute path of the file this workflow was loaded from (for de-duplication). */
    val sourcePath: String? = null,
) {
    /**
     * Substitute `{{arg}}` placeholders with the supplied [values], falling back
     * to each argument's default (then empty string) when a value is missing.
     */
    fun render(values: Map<String, String>): String {
        var out = command
        for (arg in arguments) {
            val value = values[arg.name] ?: arg.defaultValue ?: ""
            out = out.replace("{{${arg.name}}}", value)
        }
        return out
    }
}
