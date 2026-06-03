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

# Optional customizations requested by BossTerm settings (Phase 7).
[[ -n "$BOSSTERM_VI_MODE" ]] && bindkey -v
if [[ -n "$BOSSTERM_AUTOSUGGEST" ]]; then
    for __bossterm_zas in \
        /opt/homebrew/share/zsh-autosuggestions/zsh-autosuggestions.zsh \
        /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh \
        /usr/local/share/zsh-autosuggestions/zsh-autosuggestions.zsh; do
        [[ -r "$__bossterm_zas" ]] && source "$__bossterm_zas" && break
    done
    unset __bossterm_zas
fi

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
    # Command line capture (OSC 1341;BossTermCmd). Emitted before 133;B so the
    # command-block tracker has the text when onCommandStarted fires. $1 is the
    # command line as typed (zsh passes it to preexec hooks).
    printf '\e]1341;BossTermCmd;%s\a' "$1"
    # B - Command starting
    printf '\e]133;B\a'
}

# Register hooks using zsh's add-zsh-hook
autoload -Uz add-zsh-hook
add-zsh-hook precmd _bossterm_precmd
add-zsh-hook preexec _bossterm_preexec

# Emit initial prompt marker
printf '\e]133;A\a'
