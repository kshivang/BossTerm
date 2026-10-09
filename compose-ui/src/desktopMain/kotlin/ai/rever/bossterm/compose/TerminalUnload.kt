package ai.rever.bossterm.compose

import ai.rever.bossterm.compose.tabs.TerminalTab

/**
 * External host unload boundary. Stops the state and waits for its PTY engines,
 * including startup, reads, writes and process teardown, before a host closes the
 * terminal classloader. Ordinary [TabbedTerminalState.dispose] remains suitable
 * for callbacks and normal UI closure.
 *
 * Call on the UI owner thread, outside terminal callbacks, after stopping admission
 * of new terminal UI. Never invoke this from an engine's own worker or callback.
 */
fun TabbedTerminalState.disposeForUnload() {
    val engines = (
        tabController?.tabs.orEmpty() +
            splitStates.values.flatMap { it.getAllSessions().filterIsInstance<TerminalTab>() }
        ).mapNotNull { it.sessionEngine }.distinct()
    engines.forEach { it.checkUnloadThread() }
    dispose()
    engines.forEach { it.awaitStoppedForUnload() }
}
