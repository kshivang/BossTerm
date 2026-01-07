# Welcome Wizard (Onboarding)

BossTerm includes a Welcome Wizard that guides first-time users through setting up their terminal environment. The wizard helps users install shells, customization tools, version control, and AI coding assistants.

## Features

- **Shell Selection**: Choose between Zsh, Bash, or Fish
- **Shell Customization**: Install Starship, Oh My Zsh, or Prezto
- **Version Control**: Install Git and GitHub CLI
- **AI Assistants**: Install Claude Code, Gemini CLI, Codex, or OpenCode
- **GitHub Authentication**: Authenticate with GitHub CLI after installation
- **Progress Tracking**: Embedded terminal shows installation progress

## Automatic First-Run

The wizard automatically appears on first launch when `onboardingCompleted` is `false` in settings. After completion (or skipping), this flag is set to `true` and the wizard won't auto-show again.

## Manual Access

Users can access the wizard anytime from **Help > Welcome Wizard** in the menu bar.

## Using in Your Application

### Basic Usage

```kotlin
import ai.rever.bossterm.compose.onboarding.OnboardingWizard
import ai.rever.bossterm.compose.settings.SettingsManager

@Composable
fun MyApp() {
    val settingsManager = remember { SettingsManager.instance }
    val settings by settingsManager.settings.collectAsState()
    var showWizard by remember { mutableStateOf(false) }

    // Show wizard on first launch
    LaunchedEffect(Unit) {
        if (!settings.onboardingCompleted) {
            showWizard = true
        }
    }

    // Your main UI
    MainContent()

    // Wizard dialog
    if (showWizard) {
        OnboardingWizard(
            onDismiss = { showWizard = false },
            onComplete = { showWizard = false },
            settingsManager = settingsManager
        )
    }
}
```

### With Menu Bar

```kotlin
MenuBar {
    Menu("Help") {
        Item("Welcome Wizard...", onClick = { showWizard = true })
    }
}
```

### Full Example

See the [tabbed-example](../tabbed-example) module for a complete implementation:

```bash
./gradlew :tabbed-example:run
```

## API Reference

### OnboardingWizard

The main wizard composable.

```kotlin
@Composable
fun OnboardingWizard(
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    settingsManager: SettingsManager
)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `onDismiss` | `() -> Unit` | Called when wizard is closed or skipped |
| `onComplete` | `() -> Unit` | Called when wizard completes successfully |
| `settingsManager` | `SettingsManager` | Settings manager for persisting `onboardingCompleted` |

### Wizard Steps

The wizard guides users through these steps:

1. **Welcome**: Introduction and option to skip
2. **Shell Selection**: Choose Zsh, Bash, Fish, or keep current
3. **Shell Customization**: Choose Starship, Oh My Zsh, Prezto, or none
4. **Version Control**: Select Git and/or GitHub CLI
5. **AI Assistants**: Multi-select Claude Code, Gemini CLI, Codex, OpenCode
6. **Review**: Summary of selections before installation
7. **Installing**: Embedded terminal showing installation progress
8. **GitHub Auth**: (Conditional) Run `gh auth login` if GitHub CLI was installed
9. **Complete**: Success message with Relaunch/Dismiss buttons

### Data Classes

#### OnboardingSelections

Holds user selections during the wizard:

```kotlin
data class OnboardingSelections(
    val shell: ShellChoice = ShellChoice.ZSH,
    val shellCustomization: ShellCustomizationChoice = ShellCustomizationChoice.STARSHIP,
    val installGit: Boolean = true,
    val installGitHubCLI: Boolean = true,
    val aiAssistants: Set<String> = setOf("claude-code", "gemini-cli", "codex", "opencode")
)
```

#### InstalledTools

Detected installed tools:

```kotlin
data class InstalledTools(
    val zsh: Boolean,
    val bash: Boolean,
    val fish: Boolean,
    val starship: Boolean,
    val ohMyZsh: Boolean,
    val prezto: Boolean,
    val git: Boolean,
    val gh: Boolean,
    val claudeCode: Boolean,
    val gemini: Boolean,
    val codex: Boolean,
    val opencode: Boolean
)
```

### Enums

#### ShellChoice

```kotlin
enum class ShellChoice(val id: String, val displayName: String, val description: String) {
    ZSH("zsh", "Zsh", "Modern shell with powerful features"),
    BASH("bash", "Bash", "Classic Unix shell, widely compatible"),
    FISH("fish", "Fish", "User-friendly shell with autosuggestions"),
    KEEP_CURRENT("keep", "Keep Current", "Use your current default shell")
}
```

#### ShellCustomizationChoice

```kotlin
enum class ShellCustomizationChoice(
    val id: String,
    val displayName: String,
    val description: String,
    val requiresZsh: Boolean
) {
    STARSHIP("starship", "Starship", "Fast, customizable prompt for any shell", false),
    OH_MY_ZSH("oh-my-zsh", "Oh My Zsh", "Framework with 300+ plugins", true),
    PREZTO("prezto", "Prezto", "Lightweight Zsh framework", true),
    NONE("none", "None", "Keep the default shell prompt", false),
    KEEP_EXISTING("keep", "Keep Existing", "You already have customization installed", false)
}
```

## Conflict Handling

The wizard automatically handles conflicts between shell customization tools:

- **Installing Starship**: Uninstalls Oh My Zsh and Prezto if present
- **Installing Oh My Zsh**: Uninstalls Prezto and Starship if present
- **Installing Prezto**: Uninstalls Oh My Zsh and Starship if present
- **Selecting None**: Uninstalls all existing customization tools

## Platform Support

The wizard detects the platform and uses appropriate installation commands:

| Platform | Package Manager |
|----------|-----------------|
| macOS | Homebrew (`brew install`) |
| Linux (Debian/Ubuntu) | APT (`sudo apt install`) |
| Linux (Fedora/RHEL) | DNF (`sudo dnf install`) |
| Linux (Arch) | Pacman (`sudo pacman -S`) |
| Windows | WinGet (`winget install`) |

### Node.js Handling

For AI assistants (npm-based), the wizard:

1. Checks if npm is available
2. If not on macOS: installs Node.js via Homebrew
3. If not on Linux: installs nvm, then Node.js LTS
4. Installs the selected AI assistants via `npm install -g`

## Settings Integration

The wizard uses `SettingsManager` to persist the `onboardingCompleted` flag:

```kotlin
// In TerminalSettings
data class TerminalSettings(
    // ... other settings
    val onboardingCompleted: Boolean = false
)
```

After the wizard completes (or is skipped), this flag is set to `true`:

```kotlin
settingsManager.updateSetting {
    copy(onboardingCompleted = true)
}
```

## Customization

### Custom Default Selections

Override the default selections by modifying `OnboardingSelections`:

```kotlin
// Default selections (all AI assistants selected, Git/GitHub CLI selected)
data class OnboardingSelections(
    val shell: ShellChoice = ShellChoice.ZSH,
    val shellCustomization: ShellCustomizationChoice = ShellCustomizationChoice.STARSHIP,
    val installGit: Boolean = true,
    val installGitHubCLI: Boolean = true,
    val aiAssistants: Set<String> = setOf("claude-code", "gemini-cli", "codex", "opencode")
)
```

### Skipping the Wizard

To programmatically mark onboarding as completed (skip the wizard):

```kotlin
LaunchedEffect(Unit) {
    settingsManager.updateSetting {
        copy(onboardingCompleted = true)
    }
}
```

### Resetting the Wizard

To show the wizard again (for testing or user request):

```kotlin
// Reset the flag
settingsManager.updateSetting {
    copy(onboardingCompleted = false)
}

// Then show the wizard
showWizard = true
```

## Related Documentation

- [Embedding Guide](embedding.md) - Embed a single terminal
- [Tabbed Terminal Guide](tabbed-terminal.md) - Full-featured tabbed terminal
- [Main README](../README.md) - Overview and installation
