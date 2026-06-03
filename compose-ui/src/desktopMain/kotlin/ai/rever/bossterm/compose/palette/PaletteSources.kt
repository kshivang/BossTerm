package ai.rever.bossterm.compose.palette

import ai.rever.bossterm.compose.actions.ActionRegistry
import ai.rever.bossterm.compose.workflows.Workflow

/**
 * Builds the list of [PaletteCommand]s shown in the command palette.
 *
 * Sources: every registered [ai.rever.bossterm.compose.actions.TerminalAction]
 * (run immediately via `executeFromMenu`), workflows (open the parameter dialog),
 * and recent commands captured by the command-block tracker (inserted at the
 * prompt, not auto-run). Git / AI sources are planned follow-ups.
 */
object PaletteSources {

    fun collect(
        actions: ActionRegistry,
        recentCommands: List<String>,
        insertCommand: (String) -> Unit,
        workflows: List<Workflow> = emptyList(),
        onRunWorkflow: (Workflow) -> Unit = {},
    ): List<PaletteCommand> = buildList {
        actions.getAllActions()
            .filter { it.name.isNotBlank() }
            .sortedBy { it.name.lowercase() }
            .forEach { action ->
                add(
                    PaletteCommand(
                        id = "action:${action.id}",
                        title = action.name,
                        group = "Action",
                        run = { action.executeFromMenu() },
                    )
                )
            }

        workflows.sortedBy { it.name.lowercase() }.forEach { wf ->
            add(
                PaletteCommand(
                    id = "workflow:${wf.sourcePath ?: wf.name}",
                    title = wf.name,
                    subtitle = wf.description?.takeIf { it.isNotBlank() }
                        ?: "Workflow — ${wf.arguments.size} parameter(s)",
                    group = "Workflow",
                    run = { onRunWorkflow(wf) },
                )
            )
        }

        // recentCommands arrive oldest-first; show newest first, de-duplicated.
        recentCommands.asReversed().distinct().take(50).forEach { cmd ->
            add(
                PaletteCommand(
                    id = "recent:$cmd",
                    title = cmd,
                    subtitle = "Recent command — inserts at prompt",
                    group = "Recent",
                    run = { insertCommand(cmd) },
                )
            )
        }
    }
}
