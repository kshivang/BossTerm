# BossTerm Shell Integration Loader for Zsh
# This file is sourced when ZDOTDIR is hijacked by BossTerm

# Store integration directory path (this is where BossTerm extracts scripts)
typeset -g __bossterm_integration_dir="$HOME/.bossterm/shell-integration"

# Restore original ZDOTDIR immediately
if [[ -n "${BOSSTERM_ORIG_ZDOTDIR+x}" ]]; then
    ZDOTDIR="$BOSSTERM_ORIG_ZDOTDIR"
    unset BOSSTERM_ORIG_ZDOTDIR
else
    ZDOTDIR="$HOME"
fi

# Source user's original .zshenv if it exists
[[ -f "$ZDOTDIR/.zshenv" ]] && source "$ZDOTDIR/.zshenv"

# Load BossTerm shell integration for interactive shells only
if [[ -o interactive && -n "${BOSSTERM_INJECT_INTEGRATION-}" ]]; then
    source "$__bossterm_integration_dir/bossterm_shell_integration.zsh"
fi

unset __bossterm_integration_dir
