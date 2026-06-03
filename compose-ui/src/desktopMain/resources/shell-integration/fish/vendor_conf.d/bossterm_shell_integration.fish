# BossTerm Shell Integration for Fish
# Provides OSC 133 command tracking for notifications

# Avoid loading twice
if set -q BOSSTERM_SHELL_INTEGRATION_LOADED
    return
end

# Skip inside tmux/screen unless explicitly enabled
if not set -q BOSSTERM_ENABLE_INTEGRATION_WITH_TMUX
    if string match -q "tmux-256color" "$TERM"
        return
    end
    if string match -q "screen*" "$TERM"
        return
    end
end

# Skip in dumb terminals
if test "$TERM" = "dumb"
    return
end

set -g BOSSTERM_SHELL_INTEGRATION_LOADED 1

# Optional customizations requested by BossTerm settings (Phase 7).
if set -q BOSSTERM_VI_MODE
    fish_vi_key_bindings
end

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
    # Command line capture (OSC 1341;BossTermCmd). Emitted before 133;B so the
    # command-block tracker has the text when onCommandStarted fires. fish passes
    # the command line as the first event argument.
    printf '\e]1341;BossTermCmd;%s\a' "$argv[1]"
    # B - Command starting
    printf '\e]133;B\a'
end

# Called after a command completes to capture exit status
function __bossterm_fish_postexec --on-event fish_postexec
    set -g __bossterm_last_status $status
end

# Emit initial prompt marker
printf '\e]133;A\a'
