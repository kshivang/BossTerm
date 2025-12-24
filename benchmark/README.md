# Terminal Benchmark Suite

Comprehensive benchmarking tools for comparing BossTerm with iTerm2, Terminal.app, Alacritty, and other terminal emulators.

## BossTerm Performance Highlights

Based on comprehensive benchmarks (December 2025), BossTerm demonstrates significant performance advantages:

### Where BossTerm Excels

| Benchmark | BossTerm Advantage | Use Case |
|-----------|-------------------|----------|
| **Raw Throughput (1-10MB)** | **+66% to +97% faster** than iTerm2 | Large file outputs, build logs |
| **Line Throughput (1K-100K)** | **+7% to +65% faster** than iTerm2 | find, grep, ls, rapid line output |
| **Compiler Output** | **+23% faster** | Development workflows |
| **Log File Output** | **+34% faster** | Server monitoring, debugging |
| **Git Diff Rendering** | **+34% faster** | Code review, version control |
| **htop-like TUI** | **+42% faster** | System monitoring |
| **Vim-like Editor** | **+46% faster** | Text editing simulations |
| **Mixed Workload** | **+45% faster** | Real-world development |

### Performance Comparison Charts

#### Raw Throughput @ 10MB (MB/s) - Higher is Better
```
BossTerm   ████████████████████████████████████████████████████ 1,308 MB/s
Terminal   ████████████████████████████████████████████         1,092 MB/s
Alacritty  ███████████████████████████                            676 MB/s
iTerm2     ██████████████████████████                             665 MB/s
           └─────────────────────────────────────────────────────────────────┘
           0                    500                  1000                1500
```

#### Line Throughput @ 10K lines (lines/sec) - Higher is Better
```
BossTerm   ████████████████████████████████████████████████████  4.19M ✓
iTerm2     ███████████████████████████████████                   2.85M
Alacritty  ██████████████████████████████████                    2.78M
Terminal   ████████████████████████████████                      2.48M
           └─────────────────────────────────────────────────────────────────┘
           0                   1.5M                  3.0M                 4.5M
```

#### Real-World Simulations (ms) - Lower is Better
```
Vim-like Editor:
BossTerm   ██████████████                                         2.82 ms ✓
Terminal   █████████████████                                      3.52 ms
Alacritty  ███████████████████                                    3.89 ms
iTerm2     ████████████████████                                   4.11 ms

Compiler Output:
BossTerm   ███████████████                                        3.16 ms ✓
Terminal   █████████████████                                      3.44 ms
iTerm2     ███████████████████                                    3.90 ms
Alacritty  ██████████████████████                                 4.40 ms

Git Diff:
BossTerm   ███████████████                                        3.09 ms ✓
Terminal   ██████████████████                                     3.62 ms
Alacritty  ███████████████████                                    3.87 ms
iTerm2     ████████████████████                                   4.15 ms

Log Output:
BossTerm   ███████████████                                        3.07 ms ✓
Terminal   █████████████████                                      3.40 ms
iTerm2     ████████████████████                                   4.10 ms
Alacritty  ████████████████████                                   4.14 ms
```

#### Unicode/Emoji Rendering (chars/sec) - Higher is Better
```
ZWJ Sequences (👨‍👩‍👧‍👦):
iTerm2     ████████████████████████████████████████████████████  1.68M ✓
Terminal   ███████████████████████████████████████████           1.39M
BossTerm   ██████████████████████████████████████████            1.32M
Alacritty  █████████████████████████████████████                 1.22M

CJK Characters (中文):
iTerm2     ████████████████████████████████████████████████████  2.85M ✓
Terminal   ████████████████████████████████████████████          2.41M
Alacritty  ████████████████████████████████████████              2.28M
BossTerm   ███████████████████████████████████████               2.26M
```

#### ANSI Color Processing (sequences/sec) - Higher is Better
```
256 Colors:
iTerm2     ████████████████████████████████████████████████████  3.38M ✓
BossTerm   █████████████████████████████████████████████         3.01M
Terminal   ██████████████████████████████████████████            2.86M
Alacritty  ████████████████████████████████████████              2.55M
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
| Line Throughput | **BossTerm** | +7-65% |
| Real-World Simulations | **BossTerm** | +23-46% |
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
