# Glass-to-pixel latency baseline

**Date:** 2026-08-27
**Base:** `perf/terminal-latency` @ 736b646b, off `origin/master` @ cacaba54
**Platform:** macOS (Darwin 25.5.0, Apple Silicon), Compose Multiplatform 1.9.3
**Method:** `FrameLatencyProbe` + `benchmark/latency/workloads.sh`, one process per config,
probe reset and scrollback wiped before each workload. All figures are milliseconds.

Latencies are measured to **draw-issued**, excluding GPU present and vsync, so they are a
lower bound. Comparisons between configs are sound; absolute values still need the external
camera anchor described in `benchmark/latency/README.md`.

---

## Finding 0: an occluded window is throttled to ~3 fps, and that swamps everything

Same workload, same process, only window visibility differing:

| `tui` | paints | byteToPaint p50 | triggerToPaint p50 |
|---|---|---|---|
| occluded behind another window | 29 | 294.9 | 294.9 |
| raised and focused | 653 | **9.2** | 13.3 |

macOS throttles a covered window and Compose's frame clock follows it down. This is not a
BossTerm defect, but it is a measurement trap: it is ~30x larger than any effect being
measured, and it silently makes a healthy build look catastrophic. **Every measurement in
this document was taken with the window raised**, verified per run by asserting the
frontmost process before the workload starts.

An earlier draft of this file reported 262-917 ms figures that were entirely this artefact.

---

## Results

| workload | config | byteToPaint p50 | p95 | paintCost p50 | drawCalls p50 |
|---|---|---|---|---|---|
| interactive | baseline | 16.4 | 18.4 | 1.15 | 11 |
| interactive | fast-text only | 20.5 | 24.6 | 1.79 | 10 |
| interactive | debounce 0 + fast-text | **10.2** | 14.3 | 3.07 | 10 |
| bulk | baseline | 81.9 | **1310.7** | 1.41 | 20 |
| bulk | fast-text only | 589.8 | **1703.9** | 2.56 | 56 |
| bulk | debounce 0 + fast-text | **9.2** | **10.2** | 1.92 | 15 |
| tui | baseline | 9.2 | 15.4 | 11.26 | **208** |
| tui | fast-text only | 8.2 | 15.4 | 9.22 | **28** |
| tui | debounce 0 + fast-text | 10.2 | 12.3 | 9.22 | 28 |
| scroll | baseline | 41.0 | 73.7 | 5.63 | 176 |
| scroll | debounce 0 + fast-text | 32.8 | 73.7 | **1.66** | 56 |

---

## What the numbers say

**1. The interactive echo path costs what the constants said it would.**
Baseline `interactive` is 16.4 ms, of which only 1.8 ms is `triggerToPaint`. So ~14.6 ms is
spent before the redraw trigger: the 5 ms `BALANCED` poll plus the 8 ms debounce plus parse.
That is the ~13 ms predicted from reading the code, confirmed independently. Setting
`BOSSTERM_REDRAW_DEBOUNCE_MS=0` recovers 6 ms of it.

**2. The debounce, not the renderer, owns the multi-second lag on bulk output.**
This is the largest single effect found:

| bulk p95 | |
|---|---|
| baseline | 1310.7 |
| fast-text only, debounce untouched | 1703.9 |
| debounce 0 | **10.2** |

Turning the renderer reductions on while leaving the debounce alone does **not** help - the
tail stays above a second. Zeroing the debounce collapses it by ~99%. `triggerToPaint` p95
stays at 12-18 ms throughout, so the time is all upstream of the trigger: output piles up
behind `HIGH_VOLUME`'s 50 ms sleep once the rate detector trips, and the queue never drains
while the workload runs.

**3. Blank batching cuts draw calls ~7x, but paint cost only ~19%.**
On `tui`, where frame content is comparable across configs, `drawCallsPerFrame` p50 goes
208 -> 28 while `paintCost` p50 goes 11.26 -> 9.22. So the number of `drawText` calls was
**not** the dominant paint cost. Whatever remains is per-cell work in the two full-grid
passes, not per-run text layout.

This is the most consequential result for planning, because it argues **against** the
next renderer step as originally scoped: caching `TextLayoutResult`, or dropping to a Skia
`TextBlob` fast path, both attack the same ~19% slice that run-merging just showed is small.
The per-cell scan and colour-conversion work in `renderBackgrounds` / `renderText` is the
bigger target.

**4. Recomposition is not a bottleneck.**
`triggerToPaint` - the window covering recomposition, layout and draw - is 1.8-13.3 ms and
tracks `paintCost` closely. The 2400-line `ProperTerminal` recomposing per frame was
suspected as a major cost; it is not.

**5. The O(scrollback) snapshot is real but small.**
`lockedCaptureMs` p50 rises from 0.03 ms on a fresh buffer to 1.28 ms with 10 000 lines of
history (`aged`), ~40x, confirming the per-frame walk over screen plus full history. But
1.3 ms of a 16.7 ms frame is ~8%, not the reason long sessions feel worse. Worth fixing,
not worth prioritising.

---

## Reproducing

```bash
BOSSTERM_FRAME_PROBE=1 ./gradlew :bossterm-app:run --no-daemon
# raise the window, then per workload:
./benchmark/latency/probe.sh reset
./benchmark/latency/workloads.sh <interactive|bulk|tui|scroll|aged>
./benchmark/latency/probe.sh show
```

Add `BOSSTERM_REDRAW_DEBOUNCE_MS=0`, `BOSSTERM_HIGH_VOLUME_DEBOUNCE_MS=0` and
`BOSSTERM_FAST_TEXT=1` to the launch for the other configs. **Raise the window before each
run** or finding 0 will dominate the result.
