#!/usr/bin/env bash
# Fixed workloads for the frame-latency probe.
#
# Run these INSIDE the BossTerm tab being measured, with the probe reset immediately
# beforehand (`probe.sh reset`). Each one is deterministic so two builds see identical work.
#
# Scope note: the probe stamps a chunk when it ARRIVES from the PTY, so everything here
# measures the output half of the loop (arrival -> pixel). The input half (keypress ->
# PTY write) is not covered by any of these and needs the external camera anchor described
# in README.md. `interactive` is the closest proxy: isolated single-character writes, which
# is the shape a shell echo has, and the case the redraw debounce penalises hardest.
set -euo pipefail

DURATION_TUI="${DURATION_TUI:-10}"
FIXTURE_DIR="${TMPDIR:-/tmp}/bossterm-latency"
mkdir -p "$FIXTURE_DIR"

make_log() {
    local path="$FIXTURE_DIR/bulk-5mb.log"
    if [[ ! -f "$path" ]]; then
        # Deterministic content, mixed line lengths and some ANSI colour so the run
        # exercises style runs rather than one uniform batch.
        awk 'BEGIN {
            for (i = 0; i < 60000; i++) {
                printf "\033[32m%06d\033[0m  INFO  module-%02d  request completed in %3d ms  path=/api/v1/resource/%d\n", i, i % 32, i % 250, i
            }
        }' > "$path"
    fi
    echo "$path"
}

case "${1:-}" in
    interactive)
        # 200 isolated single-character writes, 50 ms apart. Each one is its own PTY chunk,
        # so each pays the full arrival-to-paint cost with no batching to hide behind.
        for _ in $(seq 1 200); do
            printf 'x'
            sleep 0.05
        done
        printf '\n'
        ;;
    bulk)
        cat "$(make_log)"
        ;;
    tui)
        # Full-screen repaint loop on the alternate screen: the regime that trips
        # HIGH_VOLUME mode (>100 redraws/sec) and drops the terminal to 20 fps.
        printf '\033[?1049h'
        end=$(( SECONDS + DURATION_TUI ))
        frame=0
        while (( SECONDS < end )); do
            printf '\033[H'
            for row in $(seq 1 40); do
                printf '\033[%dm row %02d  frame %06d  %s\033[0m\n' \
                    $(( 31 + (row + frame) % 7 )) "$row" "$frame" \
                    'the quick brown fox jumps over the lazy dog 0123456789'
            done
            frame=$(( frame + 1 ))
            sleep 0.02
        done
        printf '\033[?1049l'
        echo "tui: $frame frames in ${DURATION_TUI}s"
        ;;
    scroll)
        # Continuous single-line scrolling: every chunk shifts the whole viewport.
        for i in $(seq 1 5000); do
            printf '\033[36m%05d\033[0m  scrolling line with enough width to fill a normal terminal column count\n' "$i"
        done
        ;;
    aged)
        # Finding E: the per-frame snapshot walks screen AND full history, so cost is
        # expected to rise with scrollback depth. Run in the SAME tab, never a fresh one.
        seq 1 10000 | sed 's/^/scrollback filler line /'
        cat "$(make_log)"
        ;;
    *)
        echo "usage: workloads.sh {interactive|bulk|tui|scroll|aged}" >&2
        exit 1
        ;;
esac
