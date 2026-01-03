# BossTerm Shell Integration for Fish
# Provides OSC 133 command tracking for notifications

# Avoid loading twice
if set -q BOSSTERM_SHELL_INTEGRATION_LOADED
    exit 0
end

# Skip inside tmux/screen unless explicitly enabled
if not set -q BOSSTERM_ENABLE_INTEGRATION_WITH_TMUX
    if string match -q "tmux-256color" "$TERM"
        exit 0
    end
    if string match -q "screen*" "$TERM"
        exit 0
    end
end

# Skip in dumb terminals
if test "$TERM" = "dumb"
    exit 0
end

set -g BOSSTERM_SHELL_INTEGRATION_LOADED 1

# Track exit code from last command
set -g __bossterm_last_status 0

# Called before each prompt
function __bossterm_fish_prompt --on-event fish_prompt
    # D - Command finished with exit code (from previous command)
    printf '\e]133;D;%s\a' $__bossterm_last_status
    # A - Prompt starting
    printf '\e]133;A\a'
end

# Called just before a command is executed
function __bossterm_fish_preexec --on-event fish_preexec
    # B - Command starting
    printf '\e]133;B\a'
end

# Called after a command completes to capture exit status
function __bossterm_fish_postexec --on-event fish_postexec
    set -g __bossterm_last_status $status
end

# Emit initial prompt marker
printf '\e]133;A\a'
