#!/usr/bin/env bash
# Read and reset the BossTerm frame-latency probe.
#
# The probe writes a JSON snapshot once a second to $BOSSTERM_FRAME_PROBE_OUT, or
# ~/.bossterm/frame-probe.json by default. It only runs when the app was launched with
# BOSSTERM_FRAME_PROBE=1; without that, the file never appears.
set -euo pipefail

OUT="${BOSSTERM_FRAME_PROBE_OUT:-$HOME/.bossterm/frame-probe.json}"
RESET="$(dirname "$OUT")/frame-probe.reset"

usage() {
    cat <<USAGE
usage: probe.sh {reset|show|watch|path}

  reset  zero the histograms, then wait for the sampler to confirm
  show   print the current snapshot
  watch  print a new snapshot every second until interrupted
  path   print where the snapshot file lives

Snapshot file: $OUT
USAGE
}

require_snapshot() {
    if [[ ! -f "$OUT" ]]; then
        echo "no snapshot at $OUT" >&2
        echo "launch the app with BOSSTERM_FRAME_PROBE=1 and give it a second to sample" >&2
        exit 1
    fi
}

case "${1:-}" in
    reset)
        require_snapshot
        before=$(stat -f %m "$OUT" 2>/dev/null || stat -c %Y "$OUT")
        touch "$RESET"
        # The sampler deletes the marker as it zeroes. Waiting for that, rather than
        # returning immediately, is what stops a workload from starting against counters
        # that still hold the previous run.
        for _ in $(seq 1 50); do
            if [[ ! -f "$RESET" ]]; then
                now=$(stat -f %m "$OUT" 2>/dev/null || stat -c %Y "$OUT")
                if [[ "$now" != "$before" ]]; then
                    echo "reset confirmed"
                    exit 0
                fi
            fi
            sleep 0.2
        done
        echo "reset marker was not consumed within 10s - is the app running with BOSSTERM_FRAME_PROBE=1?" >&2
        exit 1
        ;;
    show)
        require_snapshot
        cat "$OUT"
        ;;
    watch)
        require_snapshot
        while true; do
            date +%H:%M:%S
            cat "$OUT"
            echo
            sleep 1
        done
        ;;
    path) echo "$OUT" ;;
    *) usage; exit 1 ;;
esac
