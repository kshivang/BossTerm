#!/bin/zsh
# BossTerm Shell Integration for Zsh
# Provides OSC 133 command tracking for notifications

# Avoid loading twice
[[ -n "$BOSSTERM_SHELL_INTEGRATION_LOADED" ]] && return

# Skip inside tmux/screen unless explicitly enabled
# (OSC sequences may not pass through correctly)
if [[ -z "${BOSSTERM_ENABLE_INTEGRATION_WITH_TMUX-}" ]]; then
    [[ "$TERM" == "tmux-256color" ]] && return
    [[ "$TERM" == "screen"* ]] && return
fi

# Skip in dumb terminals
[[ "$TERM" == "dumb" ]] && return

BOSSTERM_SHELL_INTEGRATION_LOADED=1

# OSC 133 sequences:
# A - Prompt started (shell ready for input)
# B - Command started (user entered command)
# C - Command output ended (not commonly used)
# D;exitcode - Command finished with exit code

# Called before each prompt is displayed
_bossterm_precmd() {
    local exit_code=$?
    # D - Command finished with exit code (from previous command)
    printf '\e]133;D;%s\a' "$exit_code"
    # A - Prompt starting
    printf '\e]133;A\a'
}

# Called just before a command is executed
_bossterm_preexec() {
    # B - Command starting
    printf '\e]133;B\a'
}

# Register hooks using zsh's add-zsh-hook
autoload -Uz add-zsh-hook
add-zsh-hook precmd _bossterm_precmd
add-zsh-hook preexec _bossterm_preexec

# Emit initial prompt marker
printf '\e]133;A\a'
