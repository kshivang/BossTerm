package ai.rever.bossterm.compose.workflows

import java.io.File

/**
 * Discovers and loads [Workflow]s from disk (Warp-compatible).
 *
 * Search order (later sources can shadow earlier by name):
 *  1. `.yaml` files in `~/.bossterm/workflows`
 *  2. project-local `<cwd>/.warp/workflows`
 *  3. any [extraDirs] from settings
 *
 * Honors the `bossterm.settings.dir` system property for the home location,
 * matching `SettingsManager`. Files that fail to parse are skipped (logged).
 */
class WorkflowStore(
    private val extraDirs: List<File> = emptyList(),
) {
    fun load(projectDir: String?): List<Workflow> {
        val dirs = buildList {
            add(File(bosstermHome(), "workflows"))
            if (!projectDir.isNullOrBlank()) add(File(projectDir, ".warp/workflows"))
            addAll(extraDirs)
        }
        return dirs
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.listFiles { f -> f.isFile && (f.extension == "yaml" || f.extension == "yml") }
                    ?.sortedBy { it.name }
                    ?.toList()
                    .orEmpty()
            }
            .mapNotNull { file ->
                runCatching { WorkflowYaml.parse(file.readText(), file.absolutePath) }
                    .onFailure { System.err.println("Skipping workflow ${file.name}: ${it.message}") }
                    .getOrNull()
            }
    }

    private fun bosstermHome(): File {
        val override = System.getProperty("bossterm.settings.dir")?.takeIf { it.isNotBlank() }
        return if (override != null) File(override) else File(System.getProperty("user.home"), ".bossterm")
    }
}
