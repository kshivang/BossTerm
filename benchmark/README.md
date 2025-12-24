# Terminal Benchmark Suite

Comprehensive benchmarking tools for comparing BossTerm with iTerm2, Terminal.app, Alacritty, and other terminal emulators.

## BossTerm Performance Highlights

Based on comprehensive benchmarks (December 2025), BossTerm demonstrates significant performance advantages:

### Where BossTerm Excels

| Benchmark | BossTerm Advantage | Use Case |
|-----------|-------------------|----------|
| **Raw Throughput (1-10MB)** | **+66% to +97% faster** than iTerm2 | Large file outputs, build logs |
| **Compiler Output** | **+23% faster** | Development workflows |
| **Log File Output** | **+34% faster** | Server monitoring, debugging |
| **Git Diff Rendering** | **+34% faster** | Code review, version control |
| **htop-like TUI** | **+42% faster** | System monitoring |
| **Vim-like Editor** | **+46% faster** | Text editing simulations |
| **Mixed Workload** | **+45% faster** | Real-world development |

### Real-World Performance

```
Throughput @ 10MB:
  BossTerm:  1,308 MB/s
  iTerm2:      665 MB/s  (BossTerm is 2x faster)

Vim Simulation:
  BossTerm:  2.82 ms
  iTerm2:    4.11 ms  (BossTerm is 46% faster)
```

> **Full benchmark results:** [BENCHMARK_SUMMARY.md](benchmark_results/BENCHMARK_SUMMARY.md)

---

## Quick Start

### Basic Benchmarks

```bash
cd benchmark

# Run basic benchmarks on all detected terminals
python3 benchmark_suite.py --compare

# Run specific benchmarks
python3 benchmark_suite.py -t bossterm,iterm2 -b throughput,unicode -r 5
```

### Comprehensive Benchmarks (25 tests)

```bash
# Run full comprehensive suite with comparison
python3 benchmark_comprehensive.py -t bossterm,iterm2,terminal,alacritty --compare

# List all available benchmarks
python3 benchmark_comprehensive.py --list

# Run specific category
python3 benchmark_comprehensive.py -b simulation -r 3
```

## Benchmark Suites

### Basic Suite (`benchmark_suite.py`)
7 benchmark categories for quick performance comparison.

### Comprehensive Suite (`benchmark_comprehensive.py`)
25 benchmarks across 7 categories for thorough analysis.

| Category | Benchmarks |
|----------|------------|
| **Throughput** | Raw data (1-50MB), lines (1K-100K), varied content |
| **Latency** | Echo, printf (1-200 chars), sequential commands |
| **Unicode** | Basic emoji, ZWJ, skin tones, flags, surrogate pairs, CJK, combining chars |
| **ANSI** | 16/256/truecolor, attributes, cursor movements |
| **Special** | Box drawing, block elements, powerline, braille, math symbols |
| **Simulation** | Compiler output, logs, git diff, htop, vim, mixed workload |
| **Resources** | Memory usage, CPU usage |

## Complete Results

### Category Winners (December 2025)

| Category | Winner | Margin |
|----------|--------|--------|
| Raw Throughput | **BossTerm** | +14-97% |
| Real-World Simulations | **BossTerm** | +23-46% |
| Line Throughput | iTerm2 | +15-22% |
| Unicode/Emoji | iTerm2 | +7-27% |
| ANSI Colors | iTerm2 | +8-20% |
| Latency | iTerm2 | +2-20% |

> **Detailed analysis with charts and methodology:** [BENCHMARK_SUMMARY.md](benchmark_results/BENCHMARK_SUMMARY.md)

## Output Files

Results saved to `./benchmark_results/`:
- [`BENCHMARK_SUMMARY.md`](benchmark_results/BENCHMARK_SUMMARY.md) - Executive summary with analysis
- `{terminal}_comprehensive_{timestamp}.md` - Individual terminal results
- `comparison_comprehensive_{timestamp}.md` - Side-by-side comparison

## Requirements

```bash
pip3 install psutil --break-system-packages  # or use venv
```

## Shell Script (Alternative)

```bash
chmod +x terminal_benchmark.sh
./terminal_benchmark.sh -t all -b throughput,latency -r 5
```

## Notes

- Run in a clean terminal session for accurate results
- Close other applications to reduce interference
- Multiple runs (`-r`) improve statistical accuracy
- BossTerm memory includes JVM overhead (~1.5GB vs ~200MB for native apps)

---

## Why BossTerm?

BossTerm is optimized for **real developer workflows**:

1. **Fastest throughput** for build outputs and large files
2. **Superior simulation performance** for tools like compilers, logs, git, and editors
3. **Modern architecture** with Kotlin/Compose Desktop
4. **Extensible** with built-in debug tools and performance metrics

While iTerm2 leads in Unicode rendering and ANSI colors, BossTerm's throughput advantage makes it ideal for developers who work with large outputs, build systems, and log files.
