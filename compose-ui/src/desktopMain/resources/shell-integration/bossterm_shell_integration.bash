#!/bin/bash
# BossTerm Shell Integration for Bash
# Provides OSC 133 command tracking for notifications

# Avoid loading twice
[[ -n "$BOSSTERM_SHELL_INTEGRATION_LOADED" ]] && return

# Skip inside tmux/screen unless explicitly enabled
if [[ -z "${BOSSTERM_ENABLE_INTEGRATION_WITH_TMUX-}" ]]; then
    [[ "$TERM" == "tmux-256color" ]] && return
    [[ "$TERM" == screen* ]] && return
fi

# Skip in dumb terminals
[[ "$TERM" == "dumb" ]] && return

BOSSTERM_SHELL_INTEGRATION_LOADED=1

# Track if we're in a command (to avoid duplicate B markers)
__bossterm_in_command=0

# Called via PROMPT_COMMAND before each prompt
__bossterm_prompt_command() {
    local exit_code=$?

    # Only emit D if we were in a command
    if [[ $__bossterm_in_command -eq 1 ]]; then
        # D - Command finished with exit code
        printf '\e]133;D;%s\a' "$exit_code"
        __bossterm_in_command=0
    fi

    # A - Prompt starting
    printf '\e]133;A\a'
}

# Called via DEBUG trap before each command
__bossterm_preexec() {
    # Skip if this is the PROMPT_COMMAND itself
    [[ "$BASH_COMMAND" == "__bossterm_prompt_command" ]] && return
    [[ "$BASH_COMMAND" == "$PROMPT_COMMAND" ]] && return

    # Skip if we're already in a command (avoid duplicates)
    [[ $__bossterm_in_command -eq 1 ]] && return

    __bossterm_in_command=1
    # B - Command starting
    printf '\e]133;B\a'
}

# Preserve existing PROMPT_COMMAND
if [[ -n "$PROMPT_COMMAND" ]]; then
    PROMPT_COMMAND="__bossterm_prompt_command; $PROMPT_COMMAND"
else
    PROMPT_COMMAND="__bossterm_prompt_command"
fi

# Set DEBUG trap for preexec functionality
# Preserve existing DEBUG trap if any
__bossterm_old_debug_trap=$(trap -p DEBUG | sed "s/trap -- '\\(.*\\)' DEBUG/\\1/")
if [[ -n "$__bossterm_old_debug_trap" ]]; then
    trap "__bossterm_preexec; $__bossterm_old_debug_trap" DEBUG
else
    trap '__bossterm_preexec' DEBUG
fi
unset __bossterm_old_debug_trap

# Emit initial prompt marker
printf '\e]133;A\a'
