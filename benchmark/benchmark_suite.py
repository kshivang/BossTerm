#!/usr/bin/env python3
"""
Terminal Emulator Benchmark Suite
Comprehensive benchmarking for BossTerm vs iTerm2 and other terminals

Usage:
    python3 benchmark_suite.py [options]

Options:
    --terminal <name>    Terminal to benchmark (bossterm, iterm2, terminal, alacritty, all)
    --benchmark <name>   Specific benchmark to run (throughput, latency, unicode, rendering, all)
    --output <dir>       Output directory for results
    --runs <n>           Number of runs per test (default: 5)
    --json               Output results as JSON
    --compare            Compare results across terminals
"""

import argparse
import json
import os
import platform
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field, asdict
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any


# === Data Classes ===

@dataclass
class BenchmarkResult:
    name: str
    terminal: str
    timestamp: str
    runs: int
    metrics: Dict[str, Any] = field(default_factory=dict)
    raw_data: List[float] = field(default_factory=list)

    def add_timing(self, values: List[float], unit: str = "ms"):
        self.raw_data = values
        self.metrics = {
            "min": min(values),
            "max": max(values),
            "mean": statistics.mean(values),
            "median": statistics.median(values),
            "stdev": statistics.stdev(values) if len(values) > 1 else 0,
            "p95": sorted(values)[int(len(values) * 0.95)] if len(values) >= 20 else max(values),
            "p99": sorted(values)[int(len(values) * 0.99)] if len(values) >= 100 else max(values),
            "unit": unit
        }

    def to_dict(self) -> Dict:
        return asdict(self)


@dataclass
class BenchmarkSuite:
    terminal: str
    host: str
    os_info: str
    timestamp: str
    results: List[BenchmarkResult] = field(default_factory=list)

    def add_result(self, result: BenchmarkResult):
        self.results.append(result)

    def to_dict(self) -> Dict:
        return {
            "terminal": self.terminal,
            "host": self.host,
            "os_info": self.os_info,
            "timestamp": self.timestamp,
            "results": [r.to_dict() for r in self.results]
        }


# === Test Data Generation ===

class DataGenerator:
    """Generates test data for benchmarks"""

    @staticmethod
    def random_ascii(size_bytes: int) -> bytes:
        """Generate random printable ASCII"""
        import random
        chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return ''.join(random.choice(chars) for _ in range(size_bytes)).encode()

    @staticmethod
    def lines(count: int, line_length: int = 80) -> str:
        """Generate lines of text"""
        line = "x" * line_length
        return '\n'.join([line] * count)

    @staticmethod
    def emoji_with_variation_selectors() -> str:
        """Emoji that use variation selectors (U+FE0F)"""
        emojis = [
            "☁️", "☀️", "⭐", "❤️", "✨", "⚡", "⚠️", "✅", "❌",
            "☑️", "✔️", "➡️", "⬅️", "⬆️", "⬇️", "↗️", "↘️"
        ]
        return ''.join(emojis * 100)

    @staticmethod
    def zwj_sequences() -> str:
        """Zero-width joiner emoji sequences"""
        zwj_emojis = [
            "👨‍👩‍👧‍👦", "👨‍💻", "👩‍🔬", "🧑‍🚀", "👨‍🎨",
            "👩‍🏫", "🧑‍⚕️", "👨‍🍳", "👩‍🌾", "🧑‍🔧",
            "👨‍🏭", "👩‍💼", "🧑‍🎤", "👨‍✈️", "👩‍🚒"
        ]
        return ''.join(zwj_emojis * 50)

    @staticmethod
    def skin_tone_modifiers() -> str:
        """Emoji with skin tone modifiers"""
        base_emojis = ["👍", "👎", "👋", "✋", "🤚", "🖐️", "✌️", "🤞"]
        modifiers = ["\U0001F3FB", "\U0001F3FC", "\U0001F3FD", "\U0001F3FE", "\U0001F3FF"]
        result = []
        for emoji in base_emojis:
            for mod in modifiers:
                result.append(emoji + mod)
        return ''.join(result * 10)

    @staticmethod
    def surrogate_pairs() -> str:
        """Characters requiring UTF-16 surrogate pairs (>U+FFFF)"""
        surrogates = [
            "𝕳", "𝖊", "𝖑", "𝖑", "𝖔",  # Mathematical Bold Fraktur
            "𝒜", "𝒞", "𝒟", "𝒢", "𝒥",   # Mathematical Script
            "🎭", "🎪", "🎫", "🎬", "🎯",   # Miscellaneous Symbols
            "🀀", "🀁", "🀂", "🀃", "🀄",   # Mahjong Tiles
            "𓀀", "𓀁", "𓀂", "𓀃", "𓀄",   # Egyptian Hieroglyphs
        ]
        return ''.join(surrogates * 100)

    @staticmethod
    def cjk_characters() -> str:
        """Chinese/Japanese/Korean characters"""
        # Common CJK characters
        cjk = "你好世界中文日本語한국어漢字平仮名カタカナ"
        return cjk * 100

    @staticmethod
    def ansi_256_colors() -> str:
        """ANSI escape sequences with 256 colors"""
        result = []
        for i in range(256):
            result.append(f"\033[38;5;{i}m█\033[0m")
        return ''.join(result * 10)

    @staticmethod
    def ansi_truecolor() -> str:
        """ANSI escape sequences with 24-bit truecolor"""
        result = []
        import random
        for _ in range(1000):
            r, g, b = random.randint(0, 255), random.randint(0, 255), random.randint(0, 255)
            result.append(f"\033[38;2;{r};{g};{b}m█\033[0m")
        return ''.join(result)

    @staticmethod
    def mixed_content() -> str:
        """Mix of ASCII, Unicode, ANSI, and special characters"""
        parts = [
            "Normal ASCII text\n",
            "🎨 Emoji: 👨‍👩‍👧‍👦 family, ❤️ heart, ☀️ sun\n",
            "中文 日本語 한국어\n",
            "\033[31mRed\033[0m \033[32mGreen\033[0m \033[34mBlue\033[0m\n",
            "Mathematical: 𝕳𝖊𝖑𝖑𝖔 𝒲𝑜𝓇𝓁𝒹\n",
            "Combining: é = e + ́ (e\u0301)\n",
            "Box drawing: ┌─┬─┐ │ │ │ └─┴─┘\n",
            "Powerline:     \n",
        ]
        return ''.join(parts * 100)


# === Benchmarks ===

class ThroughputBenchmark:
    """Measures terminal throughput (MB/s)"""

    def __init__(self, runs: int = 5):
        self.runs = runs
        self.data_sizes_mb = [1, 5, 10, 25]

    def run(self, terminal: str) -> BenchmarkResult:
        result = BenchmarkResult(
            name="throughput",
            terminal=terminal,
            timestamp=datetime.now().isoformat(),
            runs=self.runs
        )

        metrics = {}
        for size_mb in self.data_sizes_mb:
            timings = []
            data = DataGenerator.random_ascii(size_mb * 1024 * 1024)

            with tempfile.NamedTemporaryFile(delete=False) as f:
                f.write(data)
                temp_file = f.name

            try:
                for _ in range(self.runs):
                    start = time.perf_counter()
                    subprocess.run(['cat', temp_file], capture_output=True)
                    end = time.perf_counter()
                    timings.append(end - start)

                throughput_mbps = [size_mb / t for t in timings]
                metrics[f"{size_mb}MB"] = {
                    "throughput_mbps_mean": statistics.mean(throughput_mbps),
                    "throughput_mbps_stdev": statistics.stdev(throughput_mbps) if len(throughput_mbps) > 1 else 0,
                    "time_seconds_mean": statistics.mean(timings),
                    "time_seconds_stdev": statistics.stdev(timings) if len(timings) > 1 else 0,
                }
            finally:
                os.unlink(temp_file)

        result.metrics = metrics
        return result


class LatencyBenchmark:
    """Measures command execution latency"""

    def __init__(self, runs: int = 100):
        self.runs = runs

    def run(self, terminal: str) -> BenchmarkResult:
        result = BenchmarkResult(
            name="latency",
            terminal=terminal,
            timestamp=datetime.now().isoformat(),
            runs=self.runs
        )

        # Echo latency
        echo_latencies = []
        for _ in range(self.runs):
            start = time.perf_counter_ns()
            subprocess.run(['echo', 'x'], capture_output=True)
            end = time.perf_counter_ns()
            echo_latencies.append((end - start) / 1_000_000)  # ns to ms

        # Printf latency (longer output)
        printf_latencies = []
        for _ in range(self.runs):
            start = time.perf_counter_ns()
            subprocess.run(['printf', '%s\n', 'x' * 80], capture_output=True)
            end = time.perf_counter_ns()
            printf_latencies.append((end - start) / 1_000_000)

        result.metrics = {
            "echo": self._calc_stats(echo_latencies),
            "printf_80chars": self._calc_stats(printf_latencies),
        }
        result.raw_data = echo_latencies

        return result

    @staticmethod
    def _calc_stats(values: List[float]) -> Dict:
        sorted_vals = sorted(values)
        return {
            "min_ms": min(values),
            "max_ms": max(values),
            "mean_ms": statistics.mean(values),
            "median_ms": statistics.median(values),
            "stdev_ms": statistics.stdev(values) if len(values) > 1 else 0,
            "p95_ms": sorted_vals[int(len(values) * 0.95)],
            "p99_ms": sorted_vals[int(len(values) * 0.99)],
        }


class UnicodeBenchmark:
    """Measures Unicode/emoji rendering performance"""

    def __init__(self, runs: int = 5):
        self.runs = runs
        self.test_cases = {
            "emoji_variation_selectors": DataGenerator.emoji_with_variation_selectors,
            "zwj_sequences": DataGenerator.zwj_sequences,
            "skin_tone_modifiers": DataGenerator.skin_tone_modifiers,
            "surrogate_pairs": DataGenerator.surrogate_pairs,
            "cjk_characters": DataGenerator.cjk_characters,
            "mixed_content": DataGenerator.mixed_content,
        }

    def run(self, terminal: str) -> BenchmarkResult:
        result = BenchmarkResult(
            name="unicode",
            terminal=terminal,
            timestamp=datetime.now().isoformat(),
            runs=self.runs
        )

        metrics = {}
        for name, generator in self.test_cases.items():
            data = generator()
            data_bytes = data.encode('utf-8')

            with tempfile.NamedTemporaryFile(delete=False, mode='wb') as f:
                f.write(data_bytes)
                temp_file = f.name

            try:
                timings = []
                for _ in range(self.runs):
                    start = time.perf_counter()
                    subprocess.run(['cat', temp_file], capture_output=True)
                    end = time.perf_counter()
                    timings.append((end - start) * 1000)  # to ms

                metrics[name] = {
                    "chars": len(data),
                    "bytes": len(data_bytes),
                    "mean_ms": statistics.mean(timings),
                    "stdev_ms": statistics.stdev(timings) if len(timings) > 1 else 0,
                    "chars_per_second": len(data) / (statistics.mean(timings) / 1000),
                }
            finally:
                os.unlink(temp_file)

        result.metrics = metrics
        return result


class ANSIBenchmark:
    """Measures ANSI escape sequence processing"""

    def __init__(self, runs: int = 5):
        self.runs = runs

    def run(self, terminal: str) -> BenchmarkResult:
        result = BenchmarkResult(
            name="ansi",
            terminal=terminal,
            timestamp=datetime.now().isoformat(),
            runs=self.runs
        )

        test_cases = {
            "256_colors": DataGenerator.ansi_256_colors(),
            "truecolor": DataGenerator.ansi_truecolor(),
        }

        metrics = {}
        for name, data in test_cases.items():
            data_bytes = data.encode('utf-8')

            with tempfile.NamedTemporaryFile(delete=False, mode='wb') as f:
                f.write(data_bytes)
                temp_file = f.name

            try:
                timings = []
                for _ in range(self.runs):
                    start = time.perf_counter()
                    subprocess.run(['cat', temp_file], capture_output=True)
                    end = time.perf_counter()
                    timings.append((end - start) * 1000)

                metrics[name] = {
                    "sequences": data.count('\033'),
                    "mean_ms": statistics.mean(timings),
                    "stdev_ms": statistics.stdev(timings) if len(timings) > 1 else 0,
                }
            finally:
                os.unlink(temp_file)

        result.metrics = metrics
        return result


class ScrollbackBenchmark:
    """Measures scrollback buffer performance"""

    def __init__(self, runs: int = 5):
        self.runs = runs
        self.line_counts = [1000, 5000, 10000, 50000]

    def run(self, terminal: str) -> BenchmarkResult:
        result = BenchmarkResult(
            name="scrollback",
            terminal=terminal,
            timestamp=datetime.now().isoformat(),
            runs=self.runs
        )

        metrics = {}
        for lines in self.line_counts:
            data = DataGenerator.lines(lines)

            with tempfile.NamedTemporaryFile(delete=False, mode='w') as f:
                f.write(data)
                temp_file = f.name

            try:
                timings = []
                for _ in range(self.runs):
                    start = time.perf_counter()
                    subprocess.run(['cat', temp_file], capture_output=True)
                    end = time.perf_counter()
                    timings.append(end - start)

                metrics[f"{lines}_lines"] = {
                    "mean_seconds": statistics.mean(timings),
                    "stdev_seconds": statistics.stdev(timings) if len(timings) > 1 else 0,
                    "lines_per_second": lines / statistics.mean(timings),
                }
            finally:
                os.unlink(temp_file)

        result.metrics = metrics
        return result


class MemoryBenchmark:
    """Measures memory usage"""

    def __init__(self, runs: int = 3):
        self.runs = runs

    def run(self, terminal: str) -> BenchmarkResult:
        result = BenchmarkResult(
            name="memory",
            terminal=terminal,
            timestamp=datetime.now().isoformat(),
            runs=self.runs
        )

        process_patterns = {
            "bossterm": ["java", "BossTerm"],
            "iterm2": ["iTerm2"],
            "terminal": ["Terminal"],
            "alacritty": ["alacritty"],
            "kitty": ["kitty"],
            "wezterm": ["wezterm"],
        }

        patterns = process_patterns.get(terminal, [terminal])

        metrics = {"baseline": {}, "after_output": {}}

        # Baseline memory
        for pattern in patterns:
            mem = self._get_process_memory(pattern)
            if mem:
                metrics["baseline"][pattern] = mem

        # After generating output
        data = DataGenerator.random_ascii(10 * 1024 * 1024)  # 10MB
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(data)
            temp_file = f.name

        try:
            subprocess.run(['cat', temp_file], capture_output=True)
            time.sleep(1)  # Allow terminal to settle

            for pattern in patterns:
                mem = self._get_process_memory(pattern)
                if mem:
                    metrics["after_output"][pattern] = mem
        finally:
            os.unlink(temp_file)

        result.metrics = metrics
        return result

    @staticmethod
    def _get_process_memory(process_name: str) -> Optional[float]:
        """Get process memory in MB (macOS/Linux)"""
        try:
            if platform.system() == "Darwin":
                result = subprocess.run(
                    ['ps', '-A', '-o', 'pid,rss,comm'],
                    capture_output=True, text=True
                )
                for line in result.stdout.strip().split('\n')[1:]:
                    parts = line.split()
                    if len(parts) >= 3:
                        rss, comm = parts[1], ' '.join(parts[2:])
                        if process_name.lower() in comm.lower():
                            return int(rss) / 1024  # KB to MB
            else:
                # Linux
                result = subprocess.run(
                    ['pgrep', '-f', process_name],
                    capture_output=True, text=True
                )
                pids = result.stdout.strip().split('\n')
                for pid in pids:
                    if pid:
                        try:
                            with open(f'/proc/{pid}/status', 'r') as f:
                                for line in f:
                                    if line.startswith('VmRSS:'):
                                        return int(line.split()[1]) / 1024  # KB to MB
                        except:
                            pass
            return None
        except Exception:
            return None


class StartupBenchmark:
    """Measures terminal startup time"""

    def __init__(self, runs: int = 3):
        self.runs = runs

    def run(self, terminal: str) -> BenchmarkResult:
        result = BenchmarkResult(
            name="startup",
            terminal=terminal,
            timestamp=datetime.now().isoformat(),
            runs=self.runs
        )

        # Note: Startup benchmarks require specific handling per terminal
        # This is a simplified version that measures launch overhead

        startup_commands = {
            "bossterm": None,  # Would need special handling
            "iterm2": ["open", "-a", "iTerm"],
            "terminal": ["open", "-a", "Terminal"],
            "alacritty": ["alacritty", "-e", "/bin/sh", "-c", "exit"],
            "kitty": ["kitty", "-e", "/bin/sh", "-c", "exit"],
            "wezterm": ["wezterm", "start", "--", "/bin/sh", "-c", "exit"],
        }

        cmd = startup_commands.get(terminal)
        if not cmd:
            result.metrics = {"error": f"Startup benchmark not implemented for {terminal}"}
            return result

        timings = []
        for _ in range(self.runs):
            start = time.perf_counter()
            try:
                proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
            end = time.perf_counter()
            timings.append(end - start)
            time.sleep(1)  # Cool down

        result.add_timing(timings, "seconds")
        return result


# === Report Generation ===

class ReportGenerator:
    """Generates benchmark reports"""

    @staticmethod
    def to_markdown(suite: BenchmarkSuite) -> str:
        """Generate Markdown report"""
        lines = [
            f"# Terminal Benchmark Report: {suite.terminal}",
            "",
            f"**Host:** {suite.host}",
            f"**OS:** {suite.os_info}",
            f"**Date:** {suite.timestamp}",
            "",
        ]

        for result in suite.results:
            lines.extend([
                f"## {result.name.title()} Benchmark",
                "",
            ])

            if isinstance(result.metrics, dict):
                for key, value in result.metrics.items():
                    if isinstance(value, dict):
                        lines.append(f"### {key}")
                        for k, v in value.items():
                            if isinstance(v, float):
                                lines.append(f"- {k}: {v:.3f}")
                            else:
                                lines.append(f"- {k}: {v}")
                    else:
                        if isinstance(value, float):
                            lines.append(f"- {key}: {value:.3f}")
                        else:
                            lines.append(f"- {key}: {value}")
            lines.append("")

        return '\n'.join(lines)

    @staticmethod
    def compare_terminals(suites: List[BenchmarkSuite]) -> str:
        """Generate comparison report"""
        lines = [
            "# Terminal Benchmark Comparison",
            "",
            f"**Date:** {datetime.now().isoformat()}",
            "",
            "## Terminals",
            "",
        ]

        for suite in suites:
            lines.append(f"- {suite.terminal}")
        lines.append("")

        # Gather all benchmark names
        benchmark_names = set()
        for suite in suites:
            for result in suite.results:
                benchmark_names.add(result.name)

        for bench_name in sorted(benchmark_names):
            lines.extend([
                f"## {bench_name.title()}",
                "",
                "| Terminal | Metric | Value |",
                "|----------|--------|-------|",
            ])

            for suite in suites:
                for result in suite.results:
                    if result.name == bench_name:
                        if isinstance(result.metrics, dict):
                            for key, value in result.metrics.items():
                                if isinstance(value, dict):
                                    for k, v in value.items():
                                        if isinstance(v, float):
                                            lines.append(f"| {suite.terminal} | {key}/{k} | {v:.3f} |")
                                else:
                                    if isinstance(value, float):
                                        lines.append(f"| {suite.terminal} | {key} | {value:.3f} |")

            lines.append("")

        return '\n'.join(lines)


# === Main ===

def detect_terminals() -> List[str]:
    """Detect available terminals"""
    available = []

    if Path("/Applications/iTerm.app").exists():
        available.append("iterm2")

    if Path("/System/Applications/Utilities/Terminal.app").exists():
        available.append("terminal")

    if shutil.which("alacritty"):
        available.append("alacritty")

    if shutil.which("kitty"):
        available.append("kitty")

    if Path("/Applications/WezTerm.app").exists() or shutil.which("wezterm"):
        available.append("wezterm")

    return available


def run_benchmarks(terminal: str, benchmarks: List[str], runs: int) -> BenchmarkSuite:
    """Run all specified benchmarks for a terminal"""
    suite = BenchmarkSuite(
        terminal=terminal,
        host=platform.node(),
        os_info=f"{platform.system()} {platform.release()} {platform.machine()}",
        timestamp=datetime.now().isoformat()
    )

    benchmark_classes = {
        "throughput": ThroughputBenchmark,
        "latency": LatencyBenchmark,
        "unicode": UnicodeBenchmark,
        "ansi": ANSIBenchmark,
        "scrollback": ScrollbackBenchmark,
        "memory": MemoryBenchmark,
        "startup": StartupBenchmark,
    }

    for bench_name in benchmarks:
        if bench_name in benchmark_classes:
            print(f"  Running {bench_name} benchmark...")
            bench_class = benchmark_classes[bench_name]
            bench = bench_class(runs=runs)
            result = bench.run(terminal)
            suite.add_result(result)
        else:
            print(f"  Unknown benchmark: {bench_name}")

    return suite


def main():
    parser = argparse.ArgumentParser(description="Terminal Emulator Benchmark Suite")
    parser.add_argument("--terminal", "-t", default="all",
                        help="Terminal to benchmark (bossterm, iterm2, terminal, alacritty, all)")
    parser.add_argument("--benchmark", "-b", default="all",
                        help="Benchmarks to run (throughput, latency, unicode, ansi, scrollback, memory, startup, all)")
    parser.add_argument("--output", "-o", default="./benchmark_results",
                        help="Output directory")
    parser.add_argument("--runs", "-r", type=int, default=5,
                        help="Number of runs per test")
    parser.add_argument("--json", action="store_true",
                        help="Output as JSON")
    parser.add_argument("--compare", action="store_true",
                        help="Generate comparison report")

    args = parser.parse_args()

    # Determine terminals
    if args.terminal == "all":
        terminals = detect_terminals()
    else:
        terminals = [t.strip() for t in args.terminal.split(",")]

    # Determine benchmarks
    all_benchmarks = ["throughput", "latency", "unicode", "ansi", "scrollback", "memory"]
    if args.benchmark == "all":
        benchmarks = all_benchmarks
    else:
        benchmarks = [b.strip() for b in args.benchmark.split(",")]

    print(f"Terminals: {terminals}")
    print(f"Benchmarks: {benchmarks}")
    print(f"Runs per test: {args.runs}")
    print()

    # Create output directory
    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Run benchmarks
    suites = []
    for terminal in terminals:
        print(f"\nBenchmarking {terminal}...")
        suite = run_benchmarks(terminal, benchmarks, args.runs)
        suites.append(suite)

        # Save individual results
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

        if args.json:
            json_file = output_dir / f"{terminal}_{timestamp}.json"
            with open(json_file, 'w') as f:
                json.dump(suite.to_dict(), f, indent=2)
            print(f"  Saved: {json_file}")
        else:
            md_file = output_dir / f"{terminal}_{timestamp}.md"
            with open(md_file, 'w') as f:
                f.write(ReportGenerator.to_markdown(suite))
            print(f"  Saved: {md_file}")

    # Generate comparison
    if args.compare and len(suites) > 1:
        print("\nGenerating comparison report...")
        comparison = ReportGenerator.compare_terminals(suites)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        comparison_file = output_dir / f"comparison_{timestamp}.md"
        with open(comparison_file, 'w') as f:
            f.write(comparison)
        print(f"Saved: {comparison_file}")

    print("\nBenchmarks complete!")


if __name__ == "__main__":
    main()
