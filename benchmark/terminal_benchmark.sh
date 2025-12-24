#!/bin/bash
#
# Terminal Emulator Benchmark Suite
# Compares BossTerm, iTerm2, Terminal.app, and other terminals
#
# Usage: ./terminal_benchmark.sh [options]
#
# Options:
#   -t, --terminal <name>    Terminal to benchmark (bossterm, iterm2, terminal, alacritty, all)
#   -b, --benchmarks <list>  Comma-separated list of benchmarks to run (throughput,latency,unicode,memory,startup)
#   -o, --output <dir>       Output directory for results (default: ./benchmark_results)
#   -r, --runs <n>           Number of runs per benchmark (default: 3)
#   -v, --verbose            Verbose output
#   -h, --help               Show this help
#
# Example:
#   ./terminal_benchmark.sh -t all -b throughput,latency -r 5

set -euo pipefail

# === Configuration ===
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/benchmark_results"
RUNS=3
VERBOSE=false
TERMINALS=()
BENCHMARKS=("throughput" "latency" "unicode" "memory" "startup" "scrollback")

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# === Helper Functions ===

log() {
    echo -e "${BLUE}[BENCH]${NC} $*"
}

log_success() {
    echo -e "${GREEN}[OK]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*"
}

verbose() {
    if [[ "$VERBOSE" == "true" ]]; then
        echo -e "${YELLOW}[DEBUG]${NC} $*"
    fi
}

usage() {
    head -24 "$0" | tail -20
    exit 0
}

# === Terminal Detection ===

detect_terminals() {
    local available=()

    # BossTerm
    if [[ -f "${SCRIPT_DIR}/../gradlew" ]]; then
        available+=("bossterm")
    fi

    # iTerm2
    if [[ -d "/Applications/iTerm.app" ]]; then
        available+=("iterm2")
    fi

    # Terminal.app (macOS built-in)
    if [[ -d "/System/Applications/Utilities/Terminal.app" ]]; then
        available+=("terminal")
    fi

    # Alacritty
    if command -v alacritty &> /dev/null; then
        available+=("alacritty")
    fi

    # Kitty
    if command -v kitty &> /dev/null; then
        available+=("kitty")
    fi

    # WezTerm
    if [[ -d "/Applications/WezTerm.app" ]] || command -v wezterm &> /dev/null; then
        available+=("wezterm")
    fi

    echo "${available[@]}"
}

# === Data Generation Functions ===

generate_throughput_data() {
    local size=$1  # in MB
    local bytes=$((size * 1024 * 1024))

    # Generate random printable ASCII data
    head -c "$bytes" /dev/urandom | base64 | head -c "$bytes"
}

generate_line_data() {
    local lines=$1
    local line_length=${2:-80}

    for ((i=1; i<=lines; i++)); do
        printf '%*s\n' "$line_length" | tr ' ' 'x'
    done
}

generate_unicode_data() {
    local chars=$1
    local type=${2:-mixed}

    case "$type" in
        emoji)
            # Emoji with variation selectors
            local emojis=("☁️" "☀️" "⭐" "❤️" "✨" "⚡" "⚠️" "✅" "❌" "☑️" "✔️" "➡️" "🎨" "🚀" "💡" "🔥" "⭕" "❗" "💯" "🎉")
            for ((i=0; i<chars; i++)); do
                echo -n "${emojis[$((RANDOM % ${#emojis[@]}))]}"
            done
            ;;
        cjk)
            # Chinese/Japanese/Korean characters
            for ((i=0; i<chars; i++)); do
                printf "\\u$(printf '%04x' $((0x4E00 + RANDOM % 0x5000)))"
            done
            ;;
        surrogate)
            # Characters requiring surrogate pairs (>U+FFFF)
            local surrogates=("𝕳" "𝖊" "𝖑" "𝖑" "𝖔" "🎭" "🎪" "🎫" "🎬" "🎯")
            for ((i=0; i<chars; i++)); do
                echo -n "${surrogates[$((RANDOM % ${#surrogates[@]}))]}"
            done
            ;;
        zwj)
            # Zero-width joiner sequences (family emoji, etc.)
            local zwj_seqs=("👨‍👩‍👧‍👦" "👨‍💻" "👩‍🔬" "🧑‍🚀" "👨‍🎨" "👩‍🏫" "🧑‍⚕️" "👨‍🍳")
            for ((i=0; i<chars; i++)); do
                echo -n "${zwj_seqs[$((RANDOM % ${#zwj_seqs[@]}))]}"
            done
            ;;
        mixed)
            # Mix of all types
            generate_unicode_data $((chars/4)) emoji
            generate_unicode_data $((chars/4)) cjk
            generate_unicode_data $((chars/4)) surrogate
            generate_unicode_data $((chars/4)) zwj
            ;;
    esac
    echo
}

generate_ansi_stress_data() {
    local iterations=$1

    for ((i=0; i<iterations; i++)); do
        # Random foreground color
        echo -ne "\033[38;5;$((RANDOM % 256))m"
        # Random background color
        echo -ne "\033[48;5;$((RANDOM % 256))m"
        # Random text
        echo -n "X"
        # Reset
        if ((RANDOM % 10 == 0)); then
            echo -ne "\033[0m"
        fi
    done
    echo -e "\033[0m"
}

# === Benchmark Functions ===

benchmark_throughput() {
    local terminal=$1
    local results_file=$2

    log "Running throughput benchmark for $terminal..."

    local sizes=(1 5 10 25)  # MB
    local results=()

    for size in "${sizes[@]}"; do
        verbose "Testing ${size}MB throughput..."

        # Generate test data
        local data_file="/tmp/bench_data_${size}mb.txt"
        if [[ ! -f "$data_file" ]]; then
            generate_throughput_data "$size" > "$data_file"
        fi

        local total_time=0
        for ((run=1; run<=RUNS; run++)); do
            # Time the cat command
            local start_time
            start_time=$(python3 -c 'import time; print(time.time())')

            cat "$data_file" > /dev/null 2>&1

            local end_time
            end_time=$(python3 -c 'import time; print(time.time())')

            local elapsed
            elapsed=$(python3 -c "print(${end_time} - ${start_time})")
            total_time=$(python3 -c "print(${total_time} + ${elapsed})")
        done

        local avg_time
        avg_time=$(python3 -c "print(${total_time} / ${RUNS})")
        local throughput_mbps
        throughput_mbps=$(python3 -c "print(round(${size} / ${avg_time}, 2))")

        results+=("${size}MB: ${throughput_mbps} MB/s (avg ${avg_time}s)")
        verbose "${size}MB: ${throughput_mbps} MB/s"
    done

    # Write results
    {
        echo "=== Throughput Benchmark ==="
        echo "Terminal: $terminal"
        echo "Date: $(date)"
        echo "Runs per test: $RUNS"
        echo ""
        for result in "${results[@]}"; do
            echo "$result"
        done
    } >> "$results_file"
}

benchmark_latency() {
    local terminal=$1
    local results_file=$2

    log "Running latency benchmark for $terminal..."

    # Create a latency test script that measures echo round-trip time
    local latency_script="/tmp/bench_latency.py"
    cat > "$latency_script" << 'PYTHON_EOF'
#!/usr/bin/env python3
import sys
import time
import subprocess
import statistics

def measure_echo_latency(iterations=100):
    latencies = []

    for i in range(iterations):
        start = time.perf_counter_ns()
        subprocess.run(['echo', 'x'], capture_output=True)
        end = time.perf_counter_ns()
        latencies.append((end - start) / 1_000_000)  # Convert to ms

    return {
        'min': min(latencies),
        'max': max(latencies),
        'mean': statistics.mean(latencies),
        'median': statistics.median(latencies),
        'stdev': statistics.stdev(latencies) if len(latencies) > 1 else 0,
        'p95': sorted(latencies)[int(len(latencies) * 0.95)],
        'p99': sorted(latencies)[int(len(latencies) * 0.99)]
    }

def measure_printf_latency(iterations=100):
    latencies = []

    for i in range(iterations):
        start = time.perf_counter_ns()
        subprocess.run(['printf', '%s\n', 'x' * 80], capture_output=True)
        end = time.perf_counter_ns()
        latencies.append((end - start) / 1_000_000)

    return {
        'min': min(latencies),
        'max': max(latencies),
        'mean': statistics.mean(latencies),
        'median': statistics.median(latencies),
        'stdev': statistics.stdev(latencies) if len(latencies) > 1 else 0,
        'p95': sorted(latencies)[int(len(latencies) * 0.95)],
        'p99': sorted(latencies)[int(len(latencies) * 0.99)]
    }

if __name__ == '__main__':
    iterations = int(sys.argv[1]) if len(sys.argv) > 1 else 100

    echo_results = measure_echo_latency(iterations)
    printf_results = measure_printf_latency(iterations)

    print(f"Echo latency (ms):")
    print(f"  Min: {echo_results['min']:.3f}")
    print(f"  Max: {echo_results['max']:.3f}")
    print(f"  Mean: {echo_results['mean']:.3f}")
    print(f"  Median: {echo_results['median']:.3f}")
    print(f"  Stdev: {echo_results['stdev']:.3f}")
    print(f"  P95: {echo_results['p95']:.3f}")
    print(f"  P99: {echo_results['p99']:.3f}")
    print()
    print(f"Printf latency (ms):")
    print(f"  Min: {printf_results['min']:.3f}")
    print(f"  Max: {printf_results['max']:.3f}")
    print(f"  Mean: {printf_results['mean']:.3f}")
    print(f"  Median: {printf_results['median']:.3f}")
    print(f"  Stdev: {printf_results['stdev']:.3f}")
    print(f"  P95: {printf_results['p95']:.3f}")
    print(f"  P99: {printf_results['p99']:.3f}")
PYTHON_EOF

    chmod +x "$latency_script"

    # Run the latency test
    local output
    output=$(python3 "$latency_script" 100)

    {
        echo ""
        echo "=== Latency Benchmark ==="
        echo "Terminal: $terminal"
        echo "Date: $(date)"
        echo "Iterations: 100"
        echo ""
        echo "$output"
    } >> "$results_file"
}

benchmark_unicode() {
    local terminal=$1
    local results_file=$2

    log "Running Unicode/emoji benchmark for $terminal..."

    local test_types=("emoji" "cjk" "surrogate" "zwj" "mixed")
    local char_counts=(100 500 1000)

    {
        echo ""
        echo "=== Unicode/Emoji Benchmark ==="
        echo "Terminal: $terminal"
        echo "Date: $(date)"
        echo ""
    } >> "$results_file"

    for type in "${test_types[@]}"; do
        for count in "${char_counts[@]}"; do
            verbose "Testing ${type} with ${count} characters..."

            # Generate test data
            local data_file="/tmp/bench_unicode_${type}_${count}.txt"
            generate_unicode_data "$count" "$type" > "$data_file"

            local total_time=0
            for ((run=1; run<=RUNS; run++)); do
                local start_time
                start_time=$(python3 -c 'import time; print(time.time())')

                cat "$data_file"

                local end_time
                end_time=$(python3 -c 'import time; print(time.time())')

                local elapsed
                elapsed=$(python3 -c "print(${end_time} - ${start_time})")
                total_time=$(python3 -c "print(${total_time} + ${elapsed})")
            done

            local avg_time
            avg_time=$(python3 -c "print(round(${total_time} / ${RUNS} * 1000, 3))")

            echo "${type} (${count} chars): ${avg_time} ms" >> "$results_file"
        done
    done
}

benchmark_memory() {
    local terminal=$1
    local results_file=$2

    log "Running memory benchmark for $terminal..."

    # Memory measurement script
    local mem_script="/tmp/bench_memory.py"
    cat > "$mem_script" << 'PYTHON_EOF'
#!/usr/bin/env python3
import subprocess
import sys
import time
import re

def get_process_memory(process_name):
    """Get memory usage for a process in MB"""
    try:
        # macOS specific: use ps
        result = subprocess.run(
            ['ps', '-A', '-o', 'pid,rss,comm'],
            capture_output=True, text=True
        )

        for line in result.stdout.strip().split('\n')[1:]:
            parts = line.split()
            if len(parts) >= 3:
                pid, rss, comm = parts[0], parts[1], ' '.join(parts[2:])
                if process_name.lower() in comm.lower():
                    return int(rss) / 1024  # Convert KB to MB

        return None
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return None

def main():
    if len(sys.argv) < 2:
        print("Usage: bench_memory.py <process_name>")
        sys.exit(1)

    process_name = sys.argv[1]
    memory = get_process_memory(process_name)

    if memory:
        print(f"{process_name}: {memory:.2f} MB")
    else:
        print(f"{process_name}: Not found or unable to measure")

if __name__ == '__main__':
    main()
PYTHON_EOF

    chmod +x "$mem_script"

    {
        echo ""
        echo "=== Memory Benchmark ==="
        echo "Terminal: $terminal"
        echo "Date: $(date)"
        echo ""
    } >> "$results_file"

    # Baseline memory (idle terminal)
    local process_patterns=()
    case "$terminal" in
        bossterm)
            process_patterns=("java" "BossTerm")
            ;;
        iterm2)
            process_patterns=("iTerm2")
            ;;
        terminal)
            process_patterns=("Terminal")
            ;;
        alacritty)
            process_patterns=("alacritty")
            ;;
        kitty)
            process_patterns=("kitty")
            ;;
        wezterm)
            process_patterns=("wezterm")
            ;;
    esac

    echo "Process patterns: ${process_patterns[*]}" >> "$results_file"

    # Measure baseline
    for pattern in "${process_patterns[@]}"; do
        python3 "$mem_script" "$pattern" >> "$results_file"
    done

    # Generate some output and measure again
    echo "" >> "$results_file"
    echo "After 10MB output:" >> "$results_file"

    generate_throughput_data 10 > /dev/null
    sleep 1

    for pattern in "${process_patterns[@]}"; do
        python3 "$mem_script" "$pattern" >> "$results_file"
    done
}

benchmark_startup() {
    local terminal=$1
    local results_file=$2

    log "Running startup time benchmark for $terminal..."

    {
        echo ""
        echo "=== Startup Time Benchmark ==="
        echo "Terminal: $terminal"
        echo "Date: $(date)"
        echo "Runs: $RUNS"
        echo ""
    } >> "$results_file"

    local startup_times=()

    for ((run=1; run<=RUNS; run++)); do
        local start_time
        local end_time
        local elapsed

        case "$terminal" in
            bossterm)
                # BossTerm startup via Gradle (cold start not accurate, would need packaged app)
                echo "Note: BossTerm startup measured via Gradle (not representative of packaged app)" >> "$results_file"
                start_time=$(python3 -c 'import time; print(time.time())')
                timeout 10 "${SCRIPT_DIR}/../gradlew" :bossterm-app:run --no-daemon -q &
                local pid=$!
                sleep 5
                kill $pid 2>/dev/null || true
                end_time=$(python3 -c 'import time; print(time.time())')
                ;;
            iterm2)
                start_time=$(python3 -c 'import time; print(time.time())')
                open -a "iTerm" --args -e "exit"
                sleep 2
                end_time=$(python3 -c 'import time; print(time.time())')
                osascript -e 'tell application "iTerm2" to quit' 2>/dev/null || true
                ;;
            terminal)
                start_time=$(python3 -c 'import time; print(time.time())')
                open -a "Terminal" --args -e "exit"
                sleep 2
                end_time=$(python3 -c 'import time; print(time.time())')
                osascript -e 'tell application "Terminal" to quit' 2>/dev/null || true
                ;;
            alacritty)
                start_time=$(python3 -c 'import time; print(time.time())')
                alacritty -e /bin/sh -c "exit" &
                local pid=$!
                sleep 2
                kill $pid 2>/dev/null || true
                end_time=$(python3 -c 'import time; print(time.time())')
                ;;
            *)
                echo "Startup benchmark not implemented for $terminal" >> "$results_file"
                return
                ;;
        esac

        elapsed=$(python3 -c "print(round(${end_time} - ${start_time}, 3))")
        startup_times+=("$elapsed")
        verbose "Run $run: ${elapsed}s"
    done

    # Calculate statistics
    local sum=0
    for t in "${startup_times[@]}"; do
        sum=$(python3 -c "print(${sum} + ${t})")
    done
    local avg
    avg=$(python3 -c "print(round(${sum} / ${#startup_times[@]}, 3))")

    echo "Individual runs: ${startup_times[*]} seconds" >> "$results_file"
    echo "Average startup time: ${avg} seconds" >> "$results_file"
}

benchmark_scrollback() {
    local terminal=$1
    local results_file=$2

    log "Running scrollback benchmark for $terminal..."

    {
        echo ""
        echo "=== Scrollback Performance Benchmark ==="
        echo "Terminal: $terminal"
        echo "Date: $(date)"
        echo ""
    } >> "$results_file"

    local line_counts=(1000 5000 10000 50000)

    for lines in "${line_counts[@]}"; do
        verbose "Testing scrollback with ${lines} lines..."

        local data_file="/tmp/bench_scrollback_${lines}.txt"
        if [[ ! -f "$data_file" ]]; then
            generate_line_data "$lines" > "$data_file"
        fi

        local total_time=0
        for ((run=1; run<=RUNS; run++)); do
            local start_time
            start_time=$(python3 -c 'import time; print(time.time())')

            cat "$data_file"

            local end_time
            end_time=$(python3 -c 'import time; print(time.time())')

            local elapsed
            elapsed=$(python3 -c "print(${end_time} - ${start_time})")
            total_time=$(python3 -c "print(${total_time} + ${elapsed})")
        done

        local avg_time
        avg_time=$(python3 -c "print(round(${total_time} / ${RUNS}, 3))")
        local lines_per_sec
        lines_per_sec=$(python3 -c "print(int(${lines} / ${avg_time}))")

        echo "${lines} lines: ${avg_time}s (${lines_per_sec} lines/sec)" >> "$results_file"
    done
}

benchmark_ansi() {
    local terminal=$1
    local results_file=$2

    log "Running ANSI escape sequence benchmark for $terminal..."

    {
        echo ""
        echo "=== ANSI Escape Sequence Benchmark ==="
        echo "Terminal: $terminal"
        echo "Date: $(date)"
        echo ""
    } >> "$results_file"

    local char_counts=(1000 10000 50000)

    for count in "${char_counts[@]}"; do
        verbose "Testing ANSI with ${count} colored characters..."

        local data_file="/tmp/bench_ansi_${count}.txt"
        if [[ ! -f "$data_file" ]]; then
            generate_ansi_stress_data "$count" > "$data_file"
        fi

        local total_time=0
        for ((run=1; run<=RUNS; run++)); do
            local start_time
            start_time=$(python3 -c 'import time; print(time.time())')

            cat "$data_file"

            local end_time
            end_time=$(python3 -c 'import time; print(time.time())')

            local elapsed
            elapsed=$(python3 -c "print(${end_time} - ${start_time})")
            total_time=$(python3 -c "print(${total_time} + ${elapsed})")
        done

        local avg_time
        avg_time=$(python3 -c "print(round(${total_time} / ${RUNS} * 1000, 2))")

        echo "${count} colored chars: ${avg_time} ms" >> "$results_file"
    done
}

# === Main Execution ===

run_benchmarks() {
    local terminal=$1
    local results_dir="$OUTPUT_DIR/$terminal"
    mkdir -p "$results_dir"

    local timestamp
    timestamp=$(date +%Y%m%d_%H%M%S)
    local results_file="$results_dir/benchmark_${timestamp}.txt"

    log_success "Starting benchmarks for $terminal"
    log "Results will be saved to: $results_file"

    {
        echo "========================================"
        echo "Terminal Benchmark Results"
        echo "========================================"
        echo "Terminal: $terminal"
        echo "Date: $(date)"
        echo "Host: $(hostname)"
        echo "OS: $(uname -srm)"
        echo "Runs per test: $RUNS"
        echo "========================================"
    } > "$results_file"

    for bench in "${BENCHMARKS[@]}"; do
        case "$bench" in
            throughput)
                benchmark_throughput "$terminal" "$results_file"
                ;;
            latency)
                benchmark_latency "$terminal" "$results_file"
                ;;
            unicode)
                benchmark_unicode "$terminal" "$results_file"
                ;;
            memory)
                benchmark_memory "$terminal" "$results_file"
                ;;
            startup)
                benchmark_startup "$terminal" "$results_file"
                ;;
            scrollback)
                benchmark_scrollback "$terminal" "$results_file"
                ;;
            ansi)
                benchmark_ansi "$terminal" "$results_file"
                ;;
            *)
                log_warn "Unknown benchmark: $bench"
                ;;
        esac
    done

    echo "" >> "$results_file"
    echo "========================================"  >> "$results_file"
    echo "Benchmark complete: $(date)" >> "$results_file"
    echo "========================================" >> "$results_file"

    log_success "Benchmarks complete for $terminal"
    echo ""
    cat "$results_file"
    echo ""
}

compare_results() {
    log "Comparing benchmark results..."

    local comparison_file="$OUTPUT_DIR/comparison_$(date +%Y%m%d_%H%M%S).md"

    {
        echo "# Terminal Benchmark Comparison"
        echo ""
        echo "Generated: $(date)"
        echo ""
        echo "## Terminals Tested"
        echo ""
        for terminal in "${TERMINALS[@]}"; do
            echo "- $terminal"
        done
        echo ""
        echo "## Results"
        echo ""

        for terminal in "${TERMINALS[@]}"; do
            local results_dir="$OUTPUT_DIR/$terminal"
            if [[ -d "$results_dir" ]]; then
                local latest_result
                latest_result=$(ls -t "$results_dir"/benchmark_*.txt 2>/dev/null | head -1)
                if [[ -n "$latest_result" ]]; then
                    echo "### $terminal"
                    echo ""
                    echo '```'
                    cat "$latest_result"
                    echo '```'
                    echo ""
                fi
            fi
        done
    } > "$comparison_file"

    log_success "Comparison saved to: $comparison_file"
}

cleanup_temp_files() {
    verbose "Cleaning up temporary files..."
    rm -f /tmp/bench_data_*.txt
    rm -f /tmp/bench_unicode_*.txt
    rm -f /tmp/bench_scrollback_*.txt
    rm -f /tmp/bench_ansi_*.txt
    rm -f /tmp/bench_latency.py
    rm -f /tmp/bench_memory.py
}

main() {
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            -t|--terminal)
                IFS=',' read -ra TERMINALS <<< "$2"
                shift 2
                ;;
            -b|--benchmarks)
                IFS=',' read -ra BENCHMARKS <<< "$2"
                shift 2
                ;;
            -o|--output)
                OUTPUT_DIR="$2"
                shift 2
                ;;
            -r|--runs)
                RUNS="$2"
                shift 2
                ;;
            -v|--verbose)
                VERBOSE=true
                shift
                ;;
            -h|--help)
                usage
                ;;
            *)
                log_error "Unknown option: $1"
                usage
                ;;
        esac
    done

    # Detect available terminals if none specified
    if [[ ${#TERMINALS[@]} -eq 0 ]]; then
        log "No terminals specified, detecting available terminals..."
        read -ra TERMINALS <<< "$(detect_terminals)"
    fi

    # Handle 'all' option
    if [[ "${TERMINALS[*]}" == "all" ]]; then
        read -ra TERMINALS <<< "$(detect_terminals)"
    fi

    if [[ ${#TERMINALS[@]} -eq 0 ]]; then
        log_error "No terminals available for benchmarking"
        exit 1
    fi

    log "Terminals to benchmark: ${TERMINALS[*]}"
    log "Benchmarks to run: ${BENCHMARKS[*]}"
    log "Output directory: $OUTPUT_DIR"
    log "Runs per test: $RUNS"
    echo ""

    mkdir -p "$OUTPUT_DIR"

    # Run benchmarks for each terminal
    for terminal in "${TERMINALS[@]}"; do
        run_benchmarks "$terminal"
    done

    # Generate comparison if multiple terminals
    if [[ ${#TERMINALS[@]} -gt 1 ]]; then
        compare_results
    fi

    cleanup_temp_files

    log_success "All benchmarks complete!"
}

main "$@"
