package ai.rever.bossterm.compose.voice.local

/**
 * What the managed local voice runtime is doing, as the settings UI and the call bar see it.
 *
 * Modelled as distinct states rather than a pair of booleans because the difference between "not
 * installed", "installing", and "installed but not running" decides which button the user is shown,
 * and collapsing them produces the class of UI that offers Start for something that cannot start.
 */
sealed interface LocalVoiceRuntimeState {

    /** Nothing on disk yet. The only state with an install affordance behind it. */
    data object NotInstalled : LocalVoiceRuntimeState

    /**
     * Install in progress. [detail] is a human-readable step, not a percentage: the work is a
     * package resolve followed by model downloads whose total size is not known up front, and a
     * progress bar that cannot reach 100% is worse than a truthful label.
     */
    data class Installing(val detail: String) : LocalVoiceRuntimeState

    /** On disk, not running. */
    data object Stopped : LocalVoiceRuntimeState

    /**
     * Process spawned, not yet answering. Distinct from [Running] because first start loads models
     * into memory and can take tens of seconds; a call placed here must wait rather than fail.
     */
    data object Starting : LocalVoiceRuntimeState

    /** Answering on [url]. The only state from which a local call can be placed. */
    data class Running(val url: String) : LocalVoiceRuntimeState

    /**
     * Terminal failure with an actionable [message].
     *
     * [canRetry] separates "this might work next time" (port taken, process died) from "this will
     * never work here" (no Python, unsupported platform), so the UI does not offer a retry that is
     * guaranteed to fail.
     */
    data class Failed(val message: String, val canRetry: Boolean = true) : LocalVoiceRuntimeState

    /** The URL a call should use, or null when this state cannot carry one. */
    val endpointUrl: String?
        get() = (this as? Running)?.url

    /** Whether an install is the next useful action. */
    val needsInstall: Boolean
        get() = this is NotInstalled

    /** Whether something is in flight, for spinner/disabled-button decisions. */
    val busy: Boolean
        get() = this is Installing || this is Starting
}
