# Glass-to-pixel latency: baseline and the debounce removal

**Date:** 2026-08-27
**Base:** `perf/terminal-latency`, off `origin/master` @ cacaba54
**Platform:** macOS (Darwin 25.5.0, Apple Silicon), Compose Multiplatform 1.9.3
**Method:** `FrameLatencyProbe` + `benchmark/latency/workloads.sh`, one process per config,
probe reset and scrollback wiped before each workload. Figures are milliseconds.

Latencies are measured to **draw-issued**, excluding GPU present and vsync, so they are a
lower bound. Comparisons between configs are sound; absolute values still want the external
camera anchor described in `benchmark/latency/README.md`.

---

## Finding 0: an occluded window is throttled to ~3 fps, and that swamps everything

Same workload, same process, only window visibility differing:

| `tui` | paints | byteToPaint p50 | triggerToPaint p50 |
|---|---|---|---|
| covered by another window | 29 | 294.9 | 294.9 |
| raised and focused | 653 | **9.2** | 13.3 |

macOS throttles a covered window and Compose's frame clock follows it down. Not a BossTerm
defect, but a measurement trap: it is ~30x larger than any effect being measured, so it does
not look like noise, it looks like a catastrophic product bug. **Every figure below was taken
with the window raised**, asserted per run before the workload starts. An earlier draft of
this file reported 262-917 ms numbers that were entirely this artefact.

---

## Results

| workload | config | byteToPaint p50 | p95 | paintCost p50 | drawCalls p50 |
|---|---|---|---|---|---|
| interactive | baseline (balanced, 8/50 ms debounce) | 16.4 | 18.4 | 1.15 | 11 |
| interactive | latency mode only | 12.3 | 14.3 | 1.54 | - |
| interactive | **shipped defaults (debounce removed + latency)** | **1.9 - 2.3** | 2.6 - 3.1 | 1.92 | 11 |
| bulk | baseline | 81.9 | 1310.7 | 1.41 | 20 |
| bulk | latency mode only | 196.6 | 1441.8 | 2.56 | - |
| bulk | **shipped defaults** | 491.5 | **983.0** | 4.10 | 128 |
| tui | baseline | 9.2 | 15.4 | 11.26 | 208 |
| tui | fast-text only | 8.2 | 15.4 | 9.22 | **28** |
| tui | shipped defaults | 13.3 | 15.4 | 12.29 | 208 |
| scroll | baseline | 41.0 | 73.7 | 5.63 | 176 |
| scroll | shipped defaults | 20.5 | 65.5 | 4.61 | 176 |

The `interactive` and `bulk` "shipped defaults" rows are the median of three consecutive
runs each; they were tight (interactive p50 1.9/2.3/2.0, bulk p95 983.0 three times).

---

## What shipped, and what it bought

**The interactive echo path: 16.4 ms -> ~2.0 ms, an 87% cut.**
Baseline `interactive` was 16.4 ms of which only 1.8 ms was `triggerToPaint`, so ~14.6 ms sat
ahead of the redraw trigger: the 5 ms `BALANCED` poll, the 8 ms debounce, and parse. Removing
both leaves `byteToPaint` essentially equal to `triggerToPaint` (1.9 vs 1.8), which is the
signature of nothing being spent before the trigger. This is the path a user feels while
typing, and it is now bounded by the frame clock rather than by a sleep.

**Frame counts stayed vsync-capped without the debounce** (~62/sec on `tui`), so the sleep was
not what kept the terminal from over-rendering: the frame clock was, and the CONFLATED
channel plus Compose's own per-frame coalescing already do the job the debounce was added for.

---

## Correction: the debounce does NOT own the bulk-output tail

An earlier revision of this file claimed removing the debounce took `bulk` p95 from 1310 ms to
10.2 ms, a ~99% collapse. **That was wrong.** It rested on a single run that returned only 33
samples, which should have been treated as suspect rather than reported. Three consecutive
runs on the shipped defaults give p95 983.0 ms every time.

The honest result is a ~25% improvement on the bulk tail (1310 -> 983 ms), not a fix.

Where the remaining second goes is now unambiguous, because the probe is split at the redraw
trigger: on `bulk`, `triggerToPaint` p50 is 6.7 ms while `byteToPaint` p50 is 491.5 ms. So
~485 ms of it is upstream of the trigger, and with the debounce gone that leaves **queue wait
and parse**. A 5 MB `cat` arrives as ~640 chunks through an 8 KiB read buffer
(`PlatformServices.desktop.kt`), each one allocating a `ByteArray`, a `copyOf`, and a
`String`, before an emulator that pulls them back out one `Char` at a time.

**So the bulk-output fix is the PTY and parse path, not the renderer and not the debounce.**
That is a different piece of work from anything on this branch.

---

## Also measured, and worth knowing before scoping renderer work

**Draw-call count is not the dominant paint cost.** Blank batching (`BOSSTERM_FAST_TEXT=1`)
cut `drawText` calls 208 -> 28 per frame on `tui`, an 86% reduction, while `paintCost` moved
only 11.26 -> 9.22 ms, 19%. Caching `TextLayoutResult`, or dropping to a Skia `TextBlob` fast
path, both attack that same 19% slice. The per-cell scan and colour-conversion work in the two
full-grid passes is the bigger target.

**Recomposition is not a bottleneck.** `triggerToPaint` covers recomposition, layout and draw;
it is 1.8-13.3 ms and tracks `paintCost` closely. The 2400-line `ProperTerminal` recomposing
per frame had been suspected as a major cost. It is not.

**The O(scrollback) snapshot is real but small.** `lockedCapture` p50 rises 0.03 -> 1.28 ms
with 10 000 lines of history (`aged`), roughly 40x, confirming the per-frame walk over screen
plus full history. But 1.3 ms of a 16.7 ms frame is ~8%: worth fixing, not worth prioritising.

---

## Reproducing

```bash
BOSSTERM_FRAME_PROBE=1 ./gradlew :bossterm-app:run --no-daemon
# raise the window, then per workload:
./benchmark/latency/probe.sh reset
./benchmark/latency/workloads.sh <interactive|bulk|tui|scroll|aged>
./benchmark/latency/probe.sh show
```

`BOSSTERM_FAST_TEXT=1` enables the renderer reductions, which remain opt-in. **Raise the
window before every run** or finding 0 will dominate the result.
