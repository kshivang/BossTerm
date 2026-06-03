package ai.rever.bossterm.compose.palette

import ai.rever.bossterm.compose.actions.ActionRegistry

/**
 * Builds the list of [PaletteCommand]s shown in the command palette.
 *
 * Phase 2 covers two sources: every registered [ai.rever.bossterm.compose.actions.TerminalAction]
 * (run immediately via `executeFromMenu`) and recent commands captured by the
 * command-block tracker (inserted at the prompt, not auto-run, so the user can
 * review before pressing Enter). Git / AI / workflow sources are planned follow-ups.
 */
object PaletteSources {

    fun collect(
        actions: ActionRegistry,
        recentCommands: List<String>,
        insertCommand: (String) -> Unit,
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
