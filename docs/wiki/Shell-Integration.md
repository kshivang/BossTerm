# Shell Integration

Shell integration enables advanced BossTerm features like working directory tracking and command completion notifications.

---

## Quick Setup

Add the following to your shell configuration file.

### Bash (~/.bashrc)

```bash
# BossTerm Shell Integration
# OSC 7 (directory tracking) + OSC 133 (command notifications)
__bossterm_prompt_command() {
    local exit_code=$?
    # D: Command finished with exit code
    echo -ne "\033]133;D;${exit_code}\007"
    # A: Prompt starting
    echo -ne "\033]133;A\007"
    # OSC 7: Working directory
    echo -ne "\033]7;file://${HOSTNAME}${PWD}\007"
}
PROMPT_COMMAND='__bossterm_prompt_command'

# B: Command starting (before command execution)
trap 'echo -ne "\033]133;B\007"' DEBUG
```

### Zsh (~/.zshrc)

```bash
# BossTerm Shell Integration
# OSC 7 (directory tracking) + OSC 133 (command notifications)
precmd() {
    local exit_code=$?
    # D: Command finished with exit code
    print -Pn "\e]133;D;${exit_code}\a"
    # A: Prompt starting
    print -Pn "\e]133;A\a"
    # OSC 7: Working directory
    print -Pn "\e]7;file://${HOST}${PWD}\a"
}

preexec() {
    # B: Command starting (before command execution)
    print -Pn "\e]133;B\a"
}
```

### Fish (~/.config/fish/config.fish)

```fish
# BossTerm Shell Integration
function __bossterm_postexec --on-event fish_postexec
    printf "\033]133;D;%d\007" $status
end

function __bossterm_preexec --on-event fish_preexec
    printf "\033]133;B\007"
end

function __bossterm_prompt --on-event fish_prompt
    printf "\033]133;A\007"
    printf "\033]7;file://%s%s\007" (hostname) (pwd)
end
```

---

## Features Enabled

### OSC 7: Working Directory Tracking

When configured, BossTerm tracks your current working directory:

- **New tabs inherit directory** - New tabs open in the same directory as the active tab
- **New splits inherit directory** - Split panes start in the parent's directory
- **Tab titles** - Tab titles can show the current directory

### OSC 133: Command Notifications

When configured, BossTerm can notify you when commands complete:

- **System notifications** - Get notified when long-running commands finish
- **Exit code display** - See whether the command succeeded or failed
- **Duration tracking** - Only notifies for commands longer than threshold (default 5 seconds)

**Requirements for notifications:**
1. Shell integration configured (OSC 133)
2. Window must be unfocused when command completes
3. Command must run longer than `notifyMinDurationSeconds` (default: 5)

---

## Protocol Reference

### OSC 7 (Working Directory)

```
ESC ] 7 ; file://hostname/path BEL
```

Tells the terminal the current working directory.

### OSC 133 (FinalTerm Protocol)

| Sequence | Meaning |
|----------|---------|
| `ESC ] 133 ; A BEL` | Prompt started |
| `ESC ] 133 ; B BEL` | Command started |
| `ESC ] 133 ; C BEL` | Command output started |
| `ESC ] 133 ; D ; exitcode BEL` | Command finished |

---

## Verification

To verify shell integration is working:

1. **Test OSC 7**:
   ```bash
   cd /tmp && echo "Check if tab title changed"
   ```

2. **Test OSC 133**:
   - Run a command that takes more than 5 seconds: `sleep 6`
   - Switch to another application
   - You should receive a notification when the command completes

3. **Debug mode**: Press Ctrl/Cmd+Shift+D to open the debug panel and inspect incoming escape sequences

---

## Troubleshooting

### Notifications not appearing

1. Ensure OSC 133 is configured in your shell
2. Check that `notifyOnCommandComplete` is `true` in settings
3. Verify the window was unfocused when command completed
4. Check that command ran longer than `notifyMinDurationSeconds`

### Working directory not tracked

1. Ensure OSC 7 is configured in your shell
2. Check that the shell is actually emitting the sequence (use debug panel)
3. Some remote SSH sessions may not pass through OSC sequences

### macOS notification permissions

On first run, BossTerm requests notification permission. If denied:
1. Open System Settings > Notifications
2. Find BossTerm and enable notifications

---

## See Also

- [[Configuration]] - Notification settings
- [[Troubleshooting]] - More troubleshooting tips
