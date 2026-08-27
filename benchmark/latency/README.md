# Glass-to-pixel latency harness

The suite in `benchmark/` measures how fast the emulator **consumes** bytes: `cat` a file,
time how long it takes to return. Every number in `benchmark_results/` is of that shape.

None of it can see the interval that decides whether a terminal feels snappy - the time
between a byte landing in the PTY and the pixel that byte produces. That interval holds the
redraw debounce, the data-stream poll timeout and the whole paint pass, and it is invisible
to a throughput benchmark: an emulator can parse 1.6 GB/s and still wait 50 ms before
drawing any of it.

This harness measures that interval.

## What is measured

Build with the probe compiled in (it always is; it is inert unless the env flag is set) and
launch with `BOSSTERM_FRAME_PROBE=1`. A daemon thread then writes a JSON snapshot once a
second to `~/.bossterm/frame-probe.json`:

| Field | Meaning |
|---|---|
| `byteToPaintMs` | PTY chunk arrival to the end of the paint pass that first draws it. The number a user feels. |
| `paintCostMs` | Wall time inside `renderTerminal`. Decides whether a throttle is needed at all. |
| `lockedCaptureMs` | UI-thread time holding the terminal buffer lock to capture a frame. Expected to grow with scrollback depth. |
| `drawCallsPerFrame` | `drawText` invocations per paint. The multiplier on text layout cost. |
| `idleFrames` | Paints that drew no newly-arrived data (blink, resize, selection, scroll). |

Each is `{n, p50, p95, p99, max, mean}`. Latencies are milliseconds. **Report percentiles,
never means** - the tail is what gets noticed.

### The honest caveat

`byteToPaintMs` is measured to *draw-issued*, not to photons. It excludes GPU present and
vsync, so it is a **lower bound** on real latency. Two builds measured the same way compare
soundly; an absolute claim about "how many milliseconds a user waits" does not follow from
this number alone and needs the external anchor below.

## Running it

```bash
# 1. Launch with the probe on. The user runs the app; nothing here launches it.
BOSSTERM_FRAME_PROBE=1 ./gradlew :bossterm-app:run --no-daemon

# 2. Zero the histograms immediately before a workload.
./benchmark/latency/probe.sh reset

# 3. Run one workload in the BossTerm window under test.
./benchmark/latency/workloads.sh keystrokes     # (a) 200 single keypresses at a prompt
./benchmark/latency/workloads.sh bulk           # (b) cat a 5 MB log
./benchmark/latency/workloads.sh tui            # (c) full-screen redraw loop
./benchmark/latency/workloads.sh scroll         # (d) full-screen scroll
./benchmark/latency/workloads.sh aged           # (e) (b) again, after 10k lines of scrollback

# 4. Read the result.
./benchmark/latency/probe.sh show
```

Workload (e) is the one that exposes scrollback-dependent cost: run it in the *same* tab as
a preceding `bulk`, never a fresh one.

## A/B without a rebuild

Two configurations can be compared inside one process, against the same warmed JIT and the
same window geometry, which removes the largest source of run-to-run noise:

| Knob | Baseline | Alternative |
|---|---|---|
| `BOSSTERM_REDRAW_DEBOUNCE_MS` | unset (8 ms) | `0` |
| `BOSSTERM_HIGH_VOLUME_DEBOUNCE_MS` | unset (50 ms) | `0` |
| `BOSSTERM_FAST_TEXT` | unset | `1` |
| `performanceMode` in `~/.bossterm/settings.json` | `balanced` | `latency` |

`BOSSTERM_FAST_TEXT` turns on the renderer reductions: ASCII cells skip the grapheme
sequence probes, and blanks extend a batched run instead of flushing it (one `drawText` per
line rather than per word). Watch `drawCallsPerFrame` and `paintCostMs` for its effect;
`byteToPaintMs` should follow only if paint cost was actually on the critical path.

All three env vars are measurement scaffolding and come out once the questions they answer
are settled. With none set, the build behaves exactly as shipped, so a run with no env is a
true baseline.

Suggested ladder, one workload at a time, resetting between each:

1. nothing set - baseline
2. `performanceMode=latency` - removes the 5 ms data-stream poll
3. `+ BOSSTERM_REDRAW_DEBOUNCE_MS=0` - removes the 8 ms interactive debounce
4. `+ BOSSTERM_HIGH_VOLUME_DEBOUNCE_MS=0` - removes the 50 ms bulk-output throttle
5. `+ BOSSTERM_FAST_TEXT=1` - renderer reductions

Step 4 is the one to watch for regressions rather than gains: if paint cost is still high,
removing the throttle can cost throughput without buying latency. That is the measurement
that decides whether the renderer work in step 5 is a prerequisite or an optimisation.

## External anchor - do this once

The in-process number is a proxy. Before any conclusion rests on it, confirm it tracks
reality: film ~10 keypresses at a shell prompt with a phone at 240 fps, count frames from
key-down to the glyph appearing, and compare the median against `byteToPaintMs.p50` plus one
frame of present. If they disagree by more than a frame, the probe is wrong and gets fixed
before any tuning decision is made on it.
