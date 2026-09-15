# BOSS Term setup

BOSS Term starts with a confirmation screen for first-run setup. It detects the current shell and
installed tools and shows an illustrative prompt preview above the setup choices. The preview
responds to the selected shell and customization; it does not reproduce the user's existing theme.
Starship is selected by default for compatible shells, even when another prompt framework is
detected. **Keep existing** remains available as an explicit choice. The user can review:

- the default shell;
- the shell prompt (Starship is recommended on a fresh Unix install);
- the detected package manager used by setup;
- administrator authorization for Unix installs (kept only for the active setup session);
- Git and GitHub CLI;
- every registered AI coding agent and local model runtime.

Package manager, shell, and shell customization share a row. Version-control tools and AI tools
appear as checkbox groups, with installed tools marked. Smaller windows and larger fonts allow
scrolling while the setup actions remain visible.

Choosing **Set up BOSS Term** keeps the wizard open and moves it to an interactive installation
terminal. The current task and progress appear above the terminal; the full task list expands on
request. Users can type into installer prompts. The controller runs installation and verification
commands in the same terminal session. A failed task stops the sequence and remains available in
`failureMessage` and the bounded `failureOutput` diagnostic, with a **Try again** action.

Successful setup opens a dedicated completion screen after any requested GitHub sign-in has
finished or been explicitly skipped. It stays visible until acknowledged; failed or unfinished
setup does not show this success screen.

`BossTermSetupController` owns the installation outside the dialog's Compose lifecycle and publishes
task state through `BossTermSetupController.state`. An embedder can use **Continue in background** to
dismiss only the dialog while the same work continues, then call `bringToForeground()` and render the
wizard again. BOSS exposes that state as a clickable bottom-bar progress item; clicking it reopens the
same setup session and terminal process. Closing the terminal view does not dispose this
controller-owned process.

The wizard appears while `TerminalSettings.onboardingCompleted` is false and remains available from
**Help > Welcome Wizard**. Choosing **Not now** also marks first-run onboarding complete.

## Embedding

```kotlin
OnboardingWizard(
    onDismiss = { showWizard = false },
    onComplete = { showWizard = false },
    settingsManager = SettingsManager.instance,
)
```

An embedder that exposes a way to reopen the same controller session can pass
`canRunInBackground = true`. This capability is independent of optional Fluck Agent supervision.

The UI follows the active `BossUiTheme`, including the terminal preview.

## Optional supervision

Embedders can supply a `BossTermSetupSupervisor`:

```kotlin
OnboardingWizard(
    onDismiss = { showWizard = false },
    onComplete = { showWizard = false },
    settingsManager = SettingsManager.instance,
    supervisor = hostSupervisor,
)
```

At setup start, the controller calls `start`. A `true` response enables progress events and up to two
repair decisions after a task failure. Repair responses are deliberately bounded:

- `RETRY` reruns the same code-owned task;
- `REFRESH_PACKAGES_AND_RETRY` refreshes package metadata, then reruns the same task;
- `STOP` leaves the task in `NEEDS_ATTENTION`.

The supervisor never provides a shell command. Administrator passwords, authentication prompts,
and other user decisions cannot be approved through this interface.
The administrator password is passed only to the local installer process. It is not persisted,
rendered in progress output, or included in supervisor events.

### Ask Fluck to debug and fix

During installation or after a failure, the user can request an interactive Fluck debugging
session. This explicit handoff is separate from automatic, bounded repair decisions. The
controller pauses its command queue and gives the host the actual embedded terminal ID, the
current task, and recent redacted output. If an active command cannot be interrupted cleanly,
the handoff reports an error instead of allowing competing terminal commands.

BOSS exposes dedicated setup-terminal tools for status, scrollback, input, and signals. Input
and signals require the current terminal ID and debugging request ID, and only work while that
handoff is active. Fluck opens a conversation for the repair. When the turn finishes, the user
chooses **Resume and verify**; the controller checks the step's postcondition before continuing
the remaining setup. Finishing an agent turn alone does not mark installation successful.

## Platform behavior

| Platform | Package manager used by setup |
|---|---|
| macOS | Homebrew |
| Debian/Ubuntu | APT |
| Fedora/RHEL | DNF |
| Arch | Pacman |
| Windows | WinGet, with Chocolatey fallback |

Changing to an already installed shell still emits the `chsh` operation; earlier versions only
changed the default when the selected shell also needed installation.
