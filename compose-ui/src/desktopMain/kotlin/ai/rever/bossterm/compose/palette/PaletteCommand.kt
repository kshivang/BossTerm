package ai.rever.bossterm.compose.palette

/**
 * One entry in the command palette.
 *
 * @property group short category label shown on the left ("Action" | "Recent" | …)
 * @property run invoked when the entry is chosen (the palette closes first)
 */
data class PaletteCommand(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val group: String,
    val run: () -> Unit,
)
