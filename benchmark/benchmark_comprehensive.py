#!/usr/bin/env python3
"""
Comprehensive Terminal Emulator Benchmark Suite v2.0

Extended benchmarks for thorough terminal performance comparison including:
- Throughput (raw data, lines, mixed)
- Latency (echo, interactive, input processing)
- Unicode (emoji, CJK, surrogate pairs, grapheme clusters)
- ANSI/CSI sequences (colors, cursor, attributes)
- Rendering stress tests (rapid updates, large outputs)
- Real-world simulations (compiler, logs, git diff)
- Resource usage (CPU, memory over time)
- Special characters (box drawing, powerline, nerd fonts)

Usage:
    python3 benchmark_comprehensive.py [options]
"""

import argparse
import json
import os
import platform
import psutil
import random
import shutil
import statistics
import string
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass, field, asdict
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Any, Callable
from concurrent.futures import ThreadPoolExecutor
import io


# === Data Classes ===

@dataclass
class BenchmarkResult:
    name: str
    category: str
    terminal: str
    timestamp: str
    runs: int
    metrics: Dict[str, Any] = field(default_factory=dict)
    raw_data: List[float] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)

    def add_timing(self, values: List[float], unit: str = "ms"):
        self.raw_data = values
        n = len(values)
        sorted_vals = sorted(values)
        self.metrics = {
            "min": min(values),
            "max": max(values),
            "mean": statistics.mean(values),
            "median": statistics.median(values),
            "stdev": statistics.stdev(values) if n > 1 else 0,
            "p50": sorted_vals[int(n * 0.50)] if n > 0 else 0,
            "p90": sorted_vals[int(n * 0.90)] if n >= 10 else max(values),
            "p95": sorted_vals[int(n * 0.95)] if n >= 20 else max(values),
            "p99": sorted_vals[int(n * 0.99)] if n >= 100 else max(values),
            "unit": unit
        }

    def to_dict(self) -> Dict:
        return asdict(self)


@dataclass
class BenchmarkSuite:
    terminal: str
    host: str
    os_info: str
    cpu_info: str
    memory_gb: float
    timestamp: str
    results: List[BenchmarkResult] = field(default_factory=list)

    def add_result(self, result: BenchmarkResult):
        self.results.append(result)

    def to_dict(self) -> Dict:
        return {
            "terminal": self.terminal,
            "host": self.host,
            "os_info": self.os_info,
            "cpu_info": self.cpu_info,
            "memory_gb": self.memory_gb,
            "timestamp": self.timestamp,
            "results": [r.to_dict() for r in self.results]
        }


# === Enhanced Data Generators ===

class DataGenerator:
    """Comprehensive test data generators"""

    # ===== Basic Data =====
    @staticmethod
    def random_ascii(size_bytes: int) -> bytes:
        chars = string.ascii_letters + string.digits
        return ''.join(random.choice(chars) for _ in range(size_bytes)).encode()

    @staticmethod
    def random_printable(size_bytes: int) -> str:
        chars = string.printable.replace('\x0b', '').replace('\x0c', '')
        return ''.join(random.choice(chars) for _ in range(size_bytes))

    @staticmethod
    def lines(count: int, line_length: int = 80, varied: bool = False) -> str:
        if varied:
            lines = []
            for i in range(count):
                length = random.randint(20, 120) if varied else line_length
                lines.append(''.join(random.choice(string.ascii_lowercase) for _ in range(length)))
            return '\n'.join(lines)
        else:
            line = 'x' * line_length
            return '\n'.join([line] * count)

    @staticmethod
    def numbered_lines(count: int) -> str:
        return '\n'.join(f"{i:6d}: {'x' * 70}" for i in range(1, count + 1))

    # ===== Unicode Data =====
    @staticmethod
    def emoji_basic() -> str:
        """Basic emoji without modifiers"""
        emojis = "😀😁😂🤣😃😄😅😆😉😊😋😎😍😘🥰😗😙🥲😚☺️🙂🤗🤩🤔🤨😐😑😶🙄😏😣😥😮🤐😯😪😫🥱😴😌😛😜😝🤤😒😓😔😕🙃🤑😲☹️🙁😖😞😟😤😢😭😦😧😨😩🤯😬😰😱🥵🥶😳🤪😵🥴😠😡🤬😷🤒🤕🤢🤮🤧😇🥳🥺🤠🤡🤥🤫🤭🧐🤓"
        return emojis * 20

    @staticmethod
    def emoji_with_variation_selectors() -> str:
        """Emoji that use variation selectors (U+FE0F)"""
        emojis = [
            "☁️", "☀️", "⭐", "❤️", "✨", "⚡", "⚠️", "✅", "❌",
            "☑️", "✔️", "➡️", "⬅️", "⬆️", "⬇️", "↗️", "↘️", "↙️", "↖️",
            "♠️", "♣️", "♥️", "♦️", "🔴", "🟠", "🟡", "🟢", "🔵", "🟣",
            "⚪", "⚫", "🔶", "🔷", "🔸", "🔹", "▪️", "▫️", "◾", "◽"
        ]
        return ''.join(emojis * 50)

    @staticmethod
    def emoji_zwj_sequences() -> str:
        """Zero-width joiner emoji sequences"""
        zwj_emojis = [
            "👨‍👩‍👧‍👦", "👨‍👩‍👦‍👦", "👨‍👩‍👧‍👧", "👨‍👨‍👦", "👨‍👨‍👧",
            "👩‍👩‍👦", "👩‍👩‍👧", "👨‍👦", "👨‍👧", "👩‍👦", "👩‍👧",
            "👨‍💻", "👩‍💻", "🧑‍💻", "👨‍🔬", "👩‍🔬", "🧑‍🔬",
            "👨‍🎨", "👩‍🎨", "🧑‍🎨", "👨‍🚀", "👩‍🚀", "🧑‍🚀",
            "👨‍🍳", "👩‍🍳", "🧑‍🍳", "👨‍🏫", "👩‍🏫", "🧑‍🏫",
            "👨‍⚕️", "👩‍⚕️", "🧑‍⚕️", "👨‍🌾", "👩‍🌾", "🧑‍🌾",
            "🏳️‍🌈", "🏳️‍⚧️", "🏴‍☠️",
            "👁️‍🗨️", "🐻‍❄️", "😮‍💨", "😵‍💫", "❤️‍🔥", "❤️‍🩹"
        ]
        return ''.join(zwj_emojis * 30)

    @staticmethod
    def emoji_skin_tones() -> str:
        """Emoji with Fitzpatrick skin tone modifiers"""
        base = ["👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞",
                "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️", "👍",
                "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🤝",
                "🙏", "✍️", "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂"]
        modifiers = ["\U0001F3FB", "\U0001F3FC", "\U0001F3FD", "\U0001F3FE", "\U0001F3FF"]
        result = []
        for emoji in base:
            for mod in modifiers:
                result.append(emoji + mod)
        return ''.join(result * 5)

    @staticmethod
    def emoji_flags() -> str:
        """Country flag emoji (regional indicators)"""
        flags = [
            "🇺🇸", "🇬🇧", "🇨🇦", "🇦🇺", "🇩🇪", "🇫🇷", "🇮🇹", "🇪🇸", "🇯🇵", "🇰🇷",
            "🇨🇳", "🇮🇳", "🇧🇷", "🇲🇽", "🇷🇺", "🇿🇦", "🇳🇬", "🇪🇬", "🇦🇷", "🇨🇱",
            "🇸🇪", "🇳🇴", "🇩🇰", "🇫🇮", "🇳🇱", "🇧🇪", "🇨🇭", "🇦🇹", "🇵🇱", "🇺🇦"
        ]
        return ''.join(flags * 50)

    @staticmethod
    def surrogate_pairs() -> str:
        """Characters requiring UTF-16 surrogate pairs (>U+FFFF)"""
        chars = [
            # Mathematical Alphanumeric Symbols
            "𝕳", "𝖊", "𝖑", "𝖑", "𝖔", "𝕎", "𝕠", "𝕣", "𝕝", "𝕕",
            "𝒜", "𝒞", "𝒟", "𝒢", "𝒥", "𝒦", "𝒩", "𝒪", "𝒫", "𝒬",
            "𝔸", "𝔹", "ℂ", "𝔻", "𝔼", "𝔽", "𝔾", "ℍ", "𝕀", "𝕁",
            # Miscellaneous Symbols and Pictographs
            "🎭", "🎪", "🎫", "🎬", "🎯", "🎰", "🎱", "🎲", "🎳", "🎴",
            # Mahjong Tiles
            "🀀", "🀁", "🀂", "🀃", "🀄", "🀅", "🀆", "🀇", "🀈", "🀉",
            # Domino Tiles
            "🁣", "🁤", "🁥", "🁦", "🁧", "🁨", "🁩", "🁪", "🁫", "🁬",
            # Playing Cards
            "🂡", "🂢", "🂣", "🂤", "🂥", "🂦", "🂧", "🂨", "🂩", "🂪",
            # Egyptian Hieroglyphs
            "𓀀", "𓀁", "𓀂", "𓀃", "𓀄", "𓀅", "𓀆", "𓀇", "𓀈", "𓀉",
            # Musical Symbols
            "𝄞", "𝄢", "𝅗𝅥", "𝅘𝅥", "𝅘𝅥𝅮", "𝅘𝅥𝅯", "𝅘𝅥𝅰", "𝄀", "𝄁", "𝄂",
        ]
        return ''.join(chars * 50)

    @staticmethod
    def cjk_characters() -> str:
        """Chinese/Japanese/Korean characters"""
        # Common CJK Unified Ideographs
        cjk = "你好世界中文日本語한국어漢字平仮名カタカナ"
        cjk += "東京北京上海香港台北首爾新加坡"
        cjk += "天地人山川海森林花鳥風月雪雨雲星"
        cjk += "愛情友誼幸福健康平安喜樂成功"
        # Hiragana
        cjk += "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
        # Katakana
        cjk += "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"
        # Korean Hangul
        cjk += "가나다라마바사아자차카타파하"
        return cjk * 50

    @staticmethod
    def combining_characters() -> str:
        """Characters with combining diacritical marks"""
        bases = "aeiounAEIOUN"
        combiners = [
            "\u0300",  # Grave accent
            "\u0301",  # Acute accent
            "\u0302",  # Circumflex
            "\u0303",  # Tilde
            "\u0304",  # Macron
            "\u0306",  # Breve
            "\u0307",  # Dot above
            "\u0308",  # Diaeresis
            "\u030A",  # Ring above
            "\u030B",  # Double acute
            "\u030C",  # Caron
            "\u0327",  # Cedilla
            "\u0328",  # Ogonek
        ]
        result = []
        for base in bases:
            for comb in combiners:
                result.append(base + comb)
        # Multi-combining (stacked diacritics)
        result.extend(["a\u0301\u0308", "e\u0302\u0327", "o\u0303\u0304", "u\u0308\u0304"])
        return ''.join(result * 100)

    @staticmethod
    def grapheme_clusters() -> str:
        """Complex grapheme clusters"""
        clusters = [
            # Tamil
            "க்ஷ", "ஸ்ரீ",
            # Devanagari
            "क्ष", "त्र", "ज्ञ", "श्र",
            # Thai
            "กำ", "ก่", "ก้", "ก๊", "ก๋",
            # Arabic
            "لا", "لإ", "لأ", "لآ",
            # Hangul Jamo
            "각", "난",
        ]
        return ''.join(clusters * 200)

    # ===== ANSI/CSI Sequences =====
    @staticmethod
    def ansi_16_colors() -> str:
        """Standard 16 ANSI colors"""
        result = []
        for fg in range(30, 38):
            for bg in range(40, 48):
                result.append(f"\033[{fg};{bg}m█\033[0m")
        # Bright colors
        for fg in range(90, 98):
            for bg in range(100, 108):
                result.append(f"\033[{fg};{bg}m█\033[0m")
        return ''.join(result * 20)

    @staticmethod
    def ansi_256_colors() -> str:
        """256 color palette"""
        result = []
        for i in range(256):
            result.append(f"\033[38;5;{i}m█\033[0m")
        for i in range(256):
            result.append(f"\033[48;5;{i}m \033[0m")
        return ''.join(result * 10)

    @staticmethod
    def ansi_truecolor() -> str:
        """24-bit RGB truecolor"""
        result = []
        # Gradient
        for r in range(0, 256, 8):
            result.append(f"\033[38;2;{r};0;0m█\033[0m")
        for g in range(0, 256, 8):
            result.append(f"\033[38;2;0;{g};0m█\033[0m")
        for b in range(0, 256, 8):
            result.append(f"\033[38;2;0;0;{b}m█\033[0m")
        # Random colors
        for _ in range(500):
            r, g, b = random.randint(0, 255), random.randint(0, 255), random.randint(0, 255)
            result.append(f"\033[38;2;{r};{g};{b}m█\033[0m")
        return ''.join(result * 5)

    @staticmethod
    def ansi_attributes() -> str:
        """Text attributes (bold, italic, underline, etc.)"""
        attrs = [
            ("\033[1m", "Bold"),
            ("\033[2m", "Dim"),
            ("\033[3m", "Italic"),
            ("\033[4m", "Underline"),
            ("\033[5m", "Blink"),
            ("\033[7m", "Reverse"),
            ("\033[8m", "Hidden"),
            ("\033[9m", "Strikethrough"),
            ("\033[21m", "Double underline"),
            ("\033[53m", "Overline"),
            ("\033[1;3m", "Bold+Italic"),
            ("\033[1;4m", "Bold+Underline"),
            ("\033[3;4m", "Italic+Underline"),
            ("\033[1;3;4m", "Bold+Italic+Underline"),
        ]
        result = []
        for code, name in attrs:
            result.append(f"{code}{name}\033[0m ")
        return ''.join(result * 100)

    @staticmethod
    def ansi_cursor_movements() -> str:
        """Cursor movement sequences"""
        result = []
        # Move cursor up/down/left/right
        for _ in range(100):
            result.append(f"\033[{random.randint(1,5)}A")  # Up
            result.append("X")
            result.append(f"\033[{random.randint(1,5)}B")  # Down
            result.append("Y")
            result.append(f"\033[{random.randint(1,10)}C")  # Right
            result.append("Z")
            result.append(f"\033[{random.randint(1,10)}D")  # Left
            result.append("W")
        return ''.join(result)

    @staticmethod
    def ansi_scroll_regions() -> str:
        """Scroll region operations"""
        result = []
        # Set scroll region and scroll
        for i in range(50):
            result.append(f"\033[{i+1};{i+20}r")  # Set region
            result.append(f"\033[{i+10}H")  # Move cursor
            result.append(f"Line {i}\n")
            result.append("\033[S")  # Scroll up
            result.append("\033[T")  # Scroll down
        result.append("\033[r")  # Reset region
        return ''.join(result)

    # ===== Box Drawing and Special Characters =====
    @staticmethod
    def box_drawing() -> str:
        """Box drawing characters"""
        # Light box
        light = "─│┌┐└┘├┤┬┴┼"
        # Heavy box
        heavy = "━┃┏┓┗┛┣┫┳┻╋"
        # Double box
        double = "═║╔╗╚╝╠╣╦╩╬"
        # Rounded
        rounded = "╭╮╯╰"
        # Mixed
        mixed = "╒╓╔╕╖╗╘╙╚╛╜╝╞╟╠╡╢╣╤╥╦╧╨╩╪╫╬"

        result = []
        # Draw boxes
        result.append("┌────────────────┐\n")
        result.append("│  Light Box     │\n")
        result.append("├────────────────┤\n")
        result.append("│  Content here  │\n")
        result.append("└────────────────┘\n")

        result.append("┏━━━━━━━━━━━━━━━━┓\n")
        result.append("┃  Heavy Box     ┃\n")
        result.append("┣━━━━━━━━━━━━━━━━┫\n")
        result.append("┃  Content here  ┃\n")
        result.append("┗━━━━━━━━━━━━━━━━┛\n")

        result.append("╔════════════════╗\n")
        result.append("║  Double Box    ║\n")
        result.append("╠════════════════╣\n")
        result.append("║  Content here  ║\n")
        result.append("╚════════════════╝\n")

        result.append("╭────────────────╮\n")
        result.append("│  Rounded Box   │\n")
        result.append("├────────────────┤\n")
        result.append("│  Content here  │\n")
        result.append("╰────────────────╯\n")

        return ''.join(result * 50)

    @staticmethod
    def block_elements() -> str:
        """Block elements and shading"""
        blocks = "▀▁▂▃▄▅▆▇█▉▊▋▌▍▎▏▐░▒▓"
        result = []
        # Gradient
        for char in "░▒▓█":
            result.append(char * 20 + "\n")
        # Pattern
        for _ in range(20):
            result.append(''.join(random.choice(blocks) for _ in range(80)) + "\n")
        return ''.join(result * 20)

    @staticmethod
    def powerline_symbols() -> str:
        """Powerline/Nerd Font symbols"""
        powerline = [
            "", "", "", "",  # Powerline arrows
            "", "", "", "",  # Powerline rounded
            "", "", "", "",  # Git symbols
            "", "", "", "",  # Folder symbols
            "", "", "", "",  # File symbols
            "⏵", "⏸", "⏹", "⏺",  # Media controls
            "★", "☆", "●", "○",  # Shapes
            "✓", "✗", "⚡", "⚙",  # Status
        ]
        return ''.join(powerline * 100)

    @staticmethod
    def braille_patterns() -> str:
        """Braille pattern characters"""
        result = []
        # All 256 braille patterns (U+2800 to U+28FF)
        for i in range(256):
            result.append(chr(0x2800 + i))
        return ''.join(result * 20)

    @staticmethod
    def mathematical_symbols() -> str:
        """Mathematical operators and symbols"""
        math = "∀∁∂∃∄∅∆∇∈∉∊∋∌∍∎∏∐∑−∓∔∕∖∗∘∙√∛∜∝∞∟∠∡∢∣∤∥∦∧∨∩∪∫∬∭∮∯∰∱∲∳∴∵∶∷∸∹∺∻∼∽∾∿≀≁≂≃≄≅≆≇≈≉≊≋≌≍≎≏≐≑≒≓≔≕≖≗≘≙≚≛≜≝≞≟≠≡≢≣≤≥≦≧≨≩≪≫≬≭≮≯≰≱≲≳≴≵≶≷≸≹≺≻≼≽≾≿⊀⊁⊂⊃⊄⊅⊆⊇⊈⊉⊊⊋⊌⊍⊎⊏⊐⊑⊒⊓⊔⊕⊖⊗⊘⊙⊚⊛⊜⊝⊞⊟⊠⊡⊢⊣⊤⊥⊦⊧⊨⊩⊪⊫⊬⊭⊮⊯⊰⊱⊲⊳⊴⊵⊶⊷⊸⊹⊺⊻⊼⊽⊾⊿⋀⋁⋂⋃"
        return math * 30

    # ===== Real-World Simulations =====
    @staticmethod
    def compiler_output() -> str:
        """Simulated compiler output with errors/warnings"""
        files = ["main.cpp", "utils.h", "config.cpp", "network.cpp", "database.h"]
        severities = [
            ("\033[31merror\033[0m", "undeclared identifier"),
            ("\033[33mwarning\033[0m", "unused variable"),
            ("\033[34mnote\033[0m", "in expansion of macro"),
            ("\033[31merror\033[0m", "no matching function"),
            ("\033[33mwarning\033[0m", "implicit conversion"),
        ]
        result = []
        for i in range(500):
            file = random.choice(files)
            line = random.randint(1, 500)
            col = random.randint(1, 80)
            sev, msg = random.choice(severities)
            result.append(f"{file}:{line}:{col}: {sev}: {msg} '{random.choice(string.ascii_lowercase)}'\n")
            result.append(f"   {line} |     int x = undefined_var;\n")
            result.append(f"     |             ^~~~~~~~~~~~~\n")
        return ''.join(result)

    @staticmethod
    def log_output() -> str:
        """Simulated log file output"""
        levels = [
            ("\033[37m", "DEBUG"),
            ("\033[32m", "INFO"),
            ("\033[33m", "WARN"),
            ("\033[31m", "ERROR"),
            ("\033[35m", "FATAL"),
        ]
        modules = ["server", "database", "auth", "api", "cache", "queue", "worker"]
        messages = [
            "Request processed successfully",
            "Connection established",
            "Cache miss for key",
            "Retrying operation",
            "Timeout exceeded",
            "Invalid input received",
            "Resource not found",
            "Permission denied",
            "Rate limit exceeded",
            "Internal error occurred",
        ]
        result = []
        for i in range(1000):
            color, level = random.choice(levels)
            module = random.choice(modules)
            msg = random.choice(messages)
            ts = f"2024-01-{random.randint(1,31):02d} {random.randint(0,23):02d}:{random.randint(0,59):02d}:{random.randint(0,59):02d}.{random.randint(0,999):03d}"
            result.append(f"{color}[{ts}] [{level:5}] [{module:8}] {msg}\033[0m\n")
        return ''.join(result)

    @staticmethod
    def git_diff_output() -> str:
        """Simulated git diff output"""
        result = []
        for file_num in range(20):
            result.append(f"\033[1mdiff --git a/file{file_num}.py b/file{file_num}.py\033[0m\n")
            result.append(f"index abc1234..def5678 100644\n")
            result.append(f"--- a/file{file_num}.py\n")
            result.append(f"+++ b/file{file_num}.py\n")
            for hunk in range(random.randint(1, 5)):
                start = random.randint(1, 100)
                result.append(f"\033[36m@@ -{start},10 +{start},12 @@\033[0m def function_{hunk}():\n")
                for line in range(random.randint(5, 15)):
                    change = random.choice([' ', '-', '+', ' ', ' '])
                    if change == '-':
                        result.append(f"\033[31m-    old_code_line_{line} = value\033[0m\n")
                    elif change == '+':
                        result.append(f"\033[32m+    new_code_line_{line} = better_value\033[0m\n")
                    else:
                        result.append(f"     unchanged_line_{line}\n")
        return ''.join(result)

    @staticmethod
    def progress_bar_simulation() -> str:
        """Simulated progress bar updates"""
        result = []
        for task in range(10):
            for pct in range(0, 101, 2):
                filled = pct // 2
                empty = 50 - filled
                bar = f"[{'█' * filled}{'░' * empty}] {pct:3d}%"
                result.append(f"\rTask {task+1}/10: {bar}")
            result.append("\n")
        return ''.join(result)

    @staticmethod
    def htop_simulation() -> str:
        """Simulated htop-like output"""
        result = []
        # CPU bars
        for cpu in range(8):
            usage = random.randint(0, 100)
            bar_len = usage // 5
            bar = f"\033[32m{'|' * bar_len}\033[0m{' ' * (20 - bar_len)}"
            result.append(f"CPU{cpu} [{bar}] {usage:3d}%\n")
        result.append("\n")
        # Memory
        mem_used = random.randint(4000, 12000)
        mem_total = 16000
        result.append(f"Mem: {mem_used}M/{mem_total}M\n")
        result.append("\n")
        # Process list
        result.append(f"\033[7m{'PID':>7} {'USER':8} {'CPU%':>5} {'MEM%':>5} {'COMMAND':<40}\033[0m\n")
        for _ in range(50):
            pid = random.randint(1000, 99999)
            user = random.choice(["root", "user", "www-data", "postgres", "redis"])
            cpu = random.uniform(0, 100)
            mem = random.uniform(0, 20)
            cmd = random.choice(["python3", "node", "java", "nginx", "postgres", "redis-server", "chrome", "code"])
            result.append(f"{pid:>7} {user:8} {cpu:>5.1f} {mem:>5.1f} {cmd:<40}\n")
        return ''.join(result * 10)

    @staticmethod
    def vim_screen_simulation() -> str:
        """Simulated vim-like screen with syntax highlighting"""
        result = []
        # Line numbers + code
        for i in range(100):
            line_num = f"\033[33m{i+1:4d}\033[0m "
            if i % 10 == 0:
                code = f"\033[35mdef\033[0m \033[33mfunction_{i//10}\033[0m():"
            elif i % 10 == 1:
                code = f'    \033[32m"""Docstring for function"""\033[0m'
            elif i % 10 == 9:
                code = f"    \033[35mreturn\033[0m result"
            else:
                code = f"    x = \033[36m{random.randint(0, 100)}\033[0m"
            result.append(f"{line_num}{code}\n")
        # Status line
        result.append(f"\033[7m NORMAL | main.py | ln {random.randint(1,100)}, col {random.randint(1,80)} \033[0m\n")
        return ''.join(result * 5)

    @staticmethod
    def mixed_workload() -> str:
        """Mixed realistic workload"""
        parts = [
            DataGenerator.compiler_output()[:2000],
            DataGenerator.log_output()[:2000],
            DataGenerator.git_diff_output()[:2000],
            DataGenerator.vim_screen_simulation()[:2000],
            DataGenerator.box_drawing()[:1000],
            DataGenerator.emoji_basic()[:500],
            DataGenerator.cjk_characters()[:500],
        ]
        random.shuffle(parts)
        return '\n'.join(parts)


# === Benchmark Classes ===

class BaseBenchmark:
    """Base class for benchmarks"""
    name = "base"
    category = "general"

    def __init__(self, runs: int = 5):
        self.runs = runs

    def run(self, terminal: str) -> BenchmarkResult:
        raise NotImplementedError

    def _create_result(self, terminal: str) -> BenchmarkResult:
        return BenchmarkResult(
            name=self.name,
            category=self.category,
            terminal=terminal,
            timestamp=datetime.now().isoformat(),
            runs=self.runs
        )

    def _time_cat(self, data: str, runs: int = None) -> List[float]:
        """Time cat command with data"""
        runs = runs or self.runs
        timings = []

        with tempfile.NamedTemporaryFile(delete=False, mode='w', encoding='utf-8') as f:
            f.write(data)
            temp_file = f.name

        try:
            for _ in range(runs):
                start = time.perf_counter()
                subprocess.run(['cat', temp_file], capture_output=True)
                end = time.perf_counter()
                timings.append((end - start) * 1000)  # ms
        finally:
            os.unlink(temp_file)

        return timings


# === Throughput Benchmarks ===

class ThroughputRawBenchmark(BaseBenchmark):
    name = "throughput_raw"
    category = "throughput"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)
        sizes_mb = [1, 5, 10, 25, 50]
        metrics = {}

        for size in sizes_mb:
            data = DataGenerator.random_ascii(size * 1024 * 1024).decode()
            timings = self._time_cat(data)
            throughput = [size / (t / 1000) for t in timings]

            metrics[f"{size}MB"] = {
                "throughput_mbps_mean": statistics.mean(throughput),
                "throughput_mbps_stdev": statistics.stdev(throughput) if len(throughput) > 1 else 0,
                "time_ms_mean": statistics.mean(timings),
            }

        result.metrics = metrics
        return result


class ThroughputLinesBenchmark(BaseBenchmark):
    name = "throughput_lines"
    category = "throughput"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)
        line_counts = [1000, 5000, 10000, 50000, 100000]
        metrics = {}

        for count in line_counts:
            data = DataGenerator.lines(count)
            timings = self._time_cat(data)
            lines_per_sec = [count / (t / 1000) for t in timings]

            metrics[f"{count}_lines"] = {
                "lines_per_sec_mean": statistics.mean(lines_per_sec),
                "lines_per_sec_stdev": statistics.stdev(lines_per_sec) if len(lines_per_sec) > 1 else 0,
                "time_ms_mean": statistics.mean(timings),
            }

        result.metrics = metrics
        return result


class ThroughputVariedBenchmark(BaseBenchmark):
    name = "throughput_varied"
    category = "throughput"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        # Variable line lengths
        data = DataGenerator.lines(10000, varied=True)
        timings = self._time_cat(data)

        result.metrics = {
            "varied_lines_10k": {
                "time_ms_mean": statistics.mean(timings),
                "time_ms_stdev": statistics.stdev(timings) if len(timings) > 1 else 0,
            }
        }
        return result


# === Latency Benchmarks ===

class LatencyEchoBenchmark(BaseBenchmark):
    name = "latency_echo"
    category = "latency"

    def __init__(self, runs: int = 100):
        super().__init__(runs)

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        # Simple echo
        echo_times = []
        for _ in range(self.runs):
            start = time.perf_counter_ns()
            subprocess.run(['echo', 'x'], capture_output=True)
            end = time.perf_counter_ns()
            echo_times.append((end - start) / 1_000_000)

        # Printf with varying sizes
        printf_times = {}
        for size in [1, 10, 80, 200]:
            times = []
            for _ in range(self.runs):
                start = time.perf_counter_ns()
                subprocess.run(['printf', '%s', 'x' * size], capture_output=True)
                end = time.perf_counter_ns()
                times.append((end - start) / 1_000_000)
            printf_times[f"printf_{size}chars"] = self._stats(times)

        result.metrics = {
            "echo": self._stats(echo_times),
            **printf_times
        }
        result.raw_data = echo_times
        return result

    @staticmethod
    def _stats(values: List[float]) -> Dict:
        sorted_vals = sorted(values)
        n = len(values)
        return {
            "min_ms": min(values),
            "max_ms": max(values),
            "mean_ms": statistics.mean(values),
            "median_ms": statistics.median(values),
            "stdev_ms": statistics.stdev(values) if n > 1 else 0,
            "p95_ms": sorted_vals[int(n * 0.95)] if n >= 20 else max(values),
            "p99_ms": sorted_vals[int(n * 0.99)] if n >= 100 else max(values),
        }


class LatencySequentialBenchmark(BaseBenchmark):
    name = "latency_sequential"
    category = "latency"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        # Rapid sequential commands
        times = []
        for _ in range(self.runs):
            start = time.perf_counter_ns()
            for _ in range(10):
                subprocess.run(['true'], capture_output=True)
            end = time.perf_counter_ns()
            times.append((end - start) / 1_000_000)

        result.add_timing(times)
        result.metadata["description"] = "10 sequential 'true' commands"
        return result


# === Unicode Benchmarks ===

class UnicodeEmojiBenchmark(BaseBenchmark):
    name = "unicode_emoji"
    category = "unicode"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        tests = {
            "basic": DataGenerator.emoji_basic(),
            "variation_selectors": DataGenerator.emoji_with_variation_selectors(),
            "zwj_sequences": DataGenerator.emoji_zwj_sequences(),
            "skin_tones": DataGenerator.emoji_skin_tones(),
            "flags": DataGenerator.emoji_flags(),
        }

        metrics = {}
        for name, data in tests.items():
            timings = self._time_cat(data)
            metrics[name] = {
                "chars": len(data),
                "bytes": len(data.encode('utf-8')),
                "time_ms_mean": statistics.mean(timings),
                "time_ms_stdev": statistics.stdev(timings) if len(timings) > 1 else 0,
                "chars_per_sec": len(data) / (statistics.mean(timings) / 1000),
            }

        result.metrics = metrics
        return result


class UnicodeCJKBenchmark(BaseBenchmark):
    name = "unicode_cjk"
    category = "unicode"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.cjk_characters()
        timings = self._time_cat(data)

        result.metrics = {
            "cjk": {
                "chars": len(data),
                "bytes": len(data.encode('utf-8')),
                "time_ms_mean": statistics.mean(timings),
                "chars_per_sec": len(data) / (statistics.mean(timings) / 1000),
            }
        }
        return result


class UnicodeSurrogateBenchmark(BaseBenchmark):
    name = "unicode_surrogate"
    category = "unicode"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.surrogate_pairs()
        timings = self._time_cat(data)

        result.metrics = {
            "surrogate_pairs": {
                "chars": len(data),
                "bytes": len(data.encode('utf-8')),
                "time_ms_mean": statistics.mean(timings),
                "chars_per_sec": len(data) / (statistics.mean(timings) / 1000),
            }
        }
        return result


class UnicodeCombiningBenchmark(BaseBenchmark):
    name = "unicode_combining"
    category = "unicode"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        tests = {
            "combining_diacritics": DataGenerator.combining_characters(),
            "grapheme_clusters": DataGenerator.grapheme_clusters(),
        }

        metrics = {}
        for name, data in tests.items():
            timings = self._time_cat(data)
            metrics[name] = {
                "chars": len(data),
                "bytes": len(data.encode('utf-8')),
                "time_ms_mean": statistics.mean(timings),
            }

        result.metrics = metrics
        return result


# === ANSI Benchmarks ===

class ANSIColorBenchmark(BaseBenchmark):
    name = "ansi_colors"
    category = "ansi"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        tests = {
            "16_colors": DataGenerator.ansi_16_colors(),
            "256_colors": DataGenerator.ansi_256_colors(),
            "truecolor": DataGenerator.ansi_truecolor(),
        }

        metrics = {}
        for name, data in tests.items():
            timings = self._time_cat(data)
            seq_count = data.count('\033')
            metrics[name] = {
                "sequences": seq_count,
                "time_ms_mean": statistics.mean(timings),
                "sequences_per_sec": seq_count / (statistics.mean(timings) / 1000),
            }

        result.metrics = metrics
        return result


class ANSIAttributesBenchmark(BaseBenchmark):
    name = "ansi_attributes"
    category = "ansi"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.ansi_attributes()
        timings = self._time_cat(data)

        result.metrics = {
            "attributes": {
                "sequences": data.count('\033'),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class ANSICursorBenchmark(BaseBenchmark):
    name = "ansi_cursor"
    category = "ansi"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.ansi_cursor_movements()
        timings = self._time_cat(data)

        result.metrics = {
            "cursor_movements": {
                "sequences": data.count('\033'),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


# === Special Characters Benchmarks ===

class BoxDrawingBenchmark(BaseBenchmark):
    name = "box_drawing"
    category = "special"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.box_drawing()
        timings = self._time_cat(data)

        result.metrics = {
            "box_drawing": {
                "chars": len(data),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class BlockElementsBenchmark(BaseBenchmark):
    name = "block_elements"
    category = "special"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.block_elements()
        timings = self._time_cat(data)

        result.metrics = {
            "block_elements": {
                "chars": len(data),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class PowerlineBenchmark(BaseBenchmark):
    name = "powerline"
    category = "special"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.powerline_symbols()
        timings = self._time_cat(data)

        result.metrics = {
            "powerline": {
                "chars": len(data),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class BrailleBenchmark(BaseBenchmark):
    name = "braille"
    category = "special"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.braille_patterns()
        timings = self._time_cat(data)

        result.metrics = {
            "braille": {
                "chars": len(data),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class MathSymbolsBenchmark(BaseBenchmark):
    name = "math_symbols"
    category = "special"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.mathematical_symbols()
        timings = self._time_cat(data)

        result.metrics = {
            "math_symbols": {
                "chars": len(data),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


# === Real-World Simulation Benchmarks ===

class CompilerOutputBenchmark(BaseBenchmark):
    name = "simulation_compiler"
    category = "simulation"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.compiler_output()
        timings = self._time_cat(data)

        result.metrics = {
            "compiler_output": {
                "lines": data.count('\n'),
                "bytes": len(data.encode()),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class LogOutputBenchmark(BaseBenchmark):
    name = "simulation_logs"
    category = "simulation"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.log_output()
        timings = self._time_cat(data)

        result.metrics = {
            "log_output": {
                "lines": data.count('\n'),
                "bytes": len(data.encode()),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class GitDiffBenchmark(BaseBenchmark):
    name = "simulation_git_diff"
    category = "simulation"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.git_diff_output()
        timings = self._time_cat(data)

        result.metrics = {
            "git_diff": {
                "lines": data.count('\n'),
                "bytes": len(data.encode()),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class HtopSimulationBenchmark(BaseBenchmark):
    name = "simulation_htop"
    category = "simulation"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.htop_simulation()
        timings = self._time_cat(data)

        result.metrics = {
            "htop_simulation": {
                "lines": data.count('\n'),
                "bytes": len(data.encode()),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class VimSimulationBenchmark(BaseBenchmark):
    name = "simulation_vim"
    category = "simulation"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.vim_screen_simulation()
        timings = self._time_cat(data)

        result.metrics = {
            "vim_simulation": {
                "lines": data.count('\n'),
                "bytes": len(data.encode()),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


class MixedWorkloadBenchmark(BaseBenchmark):
    name = "simulation_mixed"
    category = "simulation"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        data = DataGenerator.mixed_workload()
        timings = self._time_cat(data)

        result.metrics = {
            "mixed_workload": {
                "bytes": len(data.encode()),
                "time_ms_mean": statistics.mean(timings),
            }
        }
        return result


# === Resource Usage Benchmarks ===

class MemoryBenchmark(BaseBenchmark):
    name = "memory_usage"
    category = "resources"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        process_patterns = {
            "bossterm": ["java", "BossTerm"],
            "iterm2": ["iTerm2"],
            "terminal": ["Terminal"],
            "alacritty": ["alacritty"],
            "kitty": ["kitty"],
            "wezterm": ["wezterm"],
        }

        patterns = process_patterns.get(terminal, [terminal])
        metrics = {"processes": {}}

        for pattern in patterns:
            mem = self._get_process_memory(pattern)
            if mem:
                metrics["processes"][pattern] = {"memory_mb": mem}

        result.metrics = metrics
        return result

    @staticmethod
    def _get_process_memory(process_name: str) -> Optional[float]:
        try:
            for proc in psutil.process_iter(['name', 'memory_info']):
                if process_name.lower() in proc.info['name'].lower():
                    return proc.info['memory_info'].rss / (1024 * 1024)
        except:
            pass
        return None


class CPUBenchmark(BaseBenchmark):
    name = "cpu_usage"
    category = "resources"

    def run(self, terminal: str) -> BenchmarkResult:
        result = self._create_result(terminal)

        # Measure CPU during heavy output
        data = DataGenerator.random_ascii(10 * 1024 * 1024).decode()

        with tempfile.NamedTemporaryFile(delete=False, mode='w') as f:
            f.write(data)
            temp_file = f.name

        try:
            # Sample CPU before
            cpu_before = psutil.cpu_percent(interval=0.5)

            # Run heavy output
            start = time.perf_counter()
            subprocess.run(['cat', temp_file], capture_output=True)
            end = time.perf_counter()

            # Sample CPU after
            cpu_after = psutil.cpu_percent(interval=0.5)

            result.metrics = {
                "cpu_before_percent": cpu_before,
                "cpu_after_percent": cpu_after,
                "output_time_ms": (end - start) * 1000,
            }
        finally:
            os.unlink(temp_file)

        return result


# === Report Generator ===

class ReportGenerator:
    @staticmethod
    def to_markdown(suite: BenchmarkSuite) -> str:
        lines = [
            f"# Comprehensive Terminal Benchmark Report",
            f"",
            f"## System Information",
            f"- **Terminal:** {suite.terminal}",
            f"- **Host:** {suite.host}",
            f"- **OS:** {suite.os_info}",
            f"- **CPU:** {suite.cpu_info}",
            f"- **Memory:** {suite.memory_gb:.1f} GB",
            f"- **Date:** {suite.timestamp}",
            f"",
        ]

        # Group by category
        categories = {}
        for result in suite.results:
            cat = result.category
            if cat not in categories:
                categories[cat] = []
            categories[cat].append(result)

        for category, results in sorted(categories.items()):
            lines.append(f"## {category.title()} Benchmarks")
            lines.append("")

            for result in results:
                lines.append(f"### {result.name}")
                lines.append("")

                if isinstance(result.metrics, dict):
                    for key, value in result.metrics.items():
                        if isinstance(value, dict):
                            lines.append(f"**{key}:**")
                            for k, v in value.items():
                                if isinstance(v, float):
                                    lines.append(f"  - {k}: {v:.3f}")
                                else:
                                    lines.append(f"  - {k}: {v}")
                        else:
                            if isinstance(value, float):
                                lines.append(f"- {key}: {value:.3f}")
                            else:
                                lines.append(f"- {key}: {value}")
                    lines.append("")

        return '\n'.join(lines)

    @staticmethod
    def compare_terminals(suites: List[BenchmarkSuite]) -> str:
        lines = [
            "# Comprehensive Terminal Benchmark Comparison",
            "",
            f"**Generated:** {datetime.now().isoformat()}",
            "",
            "## Terminals Compared",
            "",
        ]

        for suite in suites:
            lines.append(f"- **{suite.terminal}** ({suite.os_info})")
        lines.append("")

        # Gather all benchmark names
        all_benchmarks = {}
        for suite in suites:
            for result in suite.results:
                key = f"{result.category}/{result.name}"
                if key not in all_benchmarks:
                    all_benchmarks[key] = {}
                all_benchmarks[key][suite.terminal] = result.metrics

        # Generate comparison tables
        for bench_key in sorted(all_benchmarks.keys()):
            category, name = bench_key.split('/')
            lines.append(f"## {category.title()}: {name}")
            lines.append("")

            terminals = list(all_benchmarks[bench_key].keys())
            lines.append(f"| Metric | {' | '.join(terminals)} |")
            lines.append(f"|--------|{' | '.join(['------'] * len(terminals))}|")

            # Flatten metrics for comparison
            all_metrics = set()
            for term_data in all_benchmarks[bench_key].values():
                if isinstance(term_data, dict):
                    for key, val in term_data.items():
                        if isinstance(val, dict):
                            for subkey in val.keys():
                                all_metrics.add(f"{key}/{subkey}")
                        else:
                            all_metrics.add(key)

            for metric in sorted(all_metrics):
                values = []
                for terminal in terminals:
                    term_data = all_benchmarks[bench_key].get(terminal, {})
                    if '/' in metric:
                        main, sub = metric.split('/', 1)
                        val = term_data.get(main, {}).get(sub, "N/A")
                    else:
                        val = term_data.get(metric, "N/A")

                    if isinstance(val, float):
                        values.append(f"{val:.2f}")
                    else:
                        values.append(str(val))

                lines.append(f"| {metric} | {' | '.join(values)} |")

            lines.append("")

        return '\n'.join(lines)


# === Main ===

def get_system_info() -> tuple:
    """Get system information"""
    cpu_info = platform.processor() or "Unknown"
    try:
        if platform.system() == "Darwin":
            result = subprocess.run(['sysctl', '-n', 'machdep.cpu.brand_string'],
                                    capture_output=True, text=True)
            cpu_info = result.stdout.strip() or cpu_info
    except:
        pass

    memory_gb = psutil.virtual_memory().total / (1024 ** 3)

    return cpu_info, memory_gb


def detect_terminals() -> List[str]:
    """Detect available terminals"""
    available = []

    if Path("/Applications/iTerm.app").exists():
        available.append("iterm2")

    if Path("/System/Applications/Utilities/Terminal.app").exists():
        available.append("terminal")

    if shutil.which("alacritty") or Path("/Applications/Alacritty.app").exists():
        available.append("alacritty")

    if shutil.which("kitty") or Path("/Applications/kitty.app").exists():
        available.append("kitty")

    if Path("/Applications/WezTerm.app").exists() or shutil.which("wezterm"):
        available.append("wezterm")

    return available


def get_all_benchmarks() -> Dict[str, type]:
    """Get all benchmark classes"""
    return {
        # Throughput
        "throughput_raw": ThroughputRawBenchmark,
        "throughput_lines": ThroughputLinesBenchmark,
        "throughput_varied": ThroughputVariedBenchmark,
        # Latency
        "latency_echo": LatencyEchoBenchmark,
        "latency_sequential": LatencySequentialBenchmark,
        # Unicode
        "unicode_emoji": UnicodeEmojiBenchmark,
        "unicode_cjk": UnicodeCJKBenchmark,
        "unicode_surrogate": UnicodeSurrogateBenchmark,
        "unicode_combining": UnicodeCombiningBenchmark,
        # ANSI
        "ansi_colors": ANSIColorBenchmark,
        "ansi_attributes": ANSIAttributesBenchmark,
        "ansi_cursor": ANSICursorBenchmark,
        # Special Characters
        "box_drawing": BoxDrawingBenchmark,
        "block_elements": BlockElementsBenchmark,
        "powerline": PowerlineBenchmark,
        "braille": BrailleBenchmark,
        "math_symbols": MathSymbolsBenchmark,
        # Simulations
        "simulation_compiler": CompilerOutputBenchmark,
        "simulation_logs": LogOutputBenchmark,
        "simulation_git_diff": GitDiffBenchmark,
        "simulation_htop": HtopSimulationBenchmark,
        "simulation_vim": VimSimulationBenchmark,
        "simulation_mixed": MixedWorkloadBenchmark,
        # Resources
        "memory_usage": MemoryBenchmark,
        "cpu_usage": CPUBenchmark,
    }


def run_benchmarks(terminal: str, benchmark_names: List[str], runs: int) -> BenchmarkSuite:
    """Run specified benchmarks"""
    cpu_info, memory_gb = get_system_info()

    suite = BenchmarkSuite(
        terminal=terminal,
        host=platform.node(),
        os_info=f"{platform.system()} {platform.release()} {platform.machine()}",
        cpu_info=cpu_info,
        memory_gb=memory_gb,
        timestamp=datetime.now().isoformat()
    )

    all_benchmarks = get_all_benchmarks()

    for name in benchmark_names:
        if name in all_benchmarks:
            print(f"  Running {name}...")
            bench_class = all_benchmarks[name]
            bench = bench_class(runs=runs)
            result = bench.run(terminal)
            suite.add_result(result)
        else:
            print(f"  Unknown benchmark: {name}")

    return suite


def main():
    parser = argparse.ArgumentParser(description="Comprehensive Terminal Benchmark Suite v2.0")
    parser.add_argument("--terminal", "-t", default="all",
                        help="Terminals to benchmark (comma-separated or 'all')")
    parser.add_argument("--benchmark", "-b", default="all",
                        help="Benchmarks to run (comma-separated, category name, or 'all')")
    parser.add_argument("--output", "-o", default="./benchmark_results",
                        help="Output directory")
    parser.add_argument("--runs", "-r", type=int, default=5,
                        help="Number of runs per test")
    parser.add_argument("--json", action="store_true",
                        help="Also output JSON")
    parser.add_argument("--compare", action="store_true",
                        help="Generate comparison report")
    parser.add_argument("--list", action="store_true",
                        help="List available benchmarks")

    args = parser.parse_args()

    all_benchmarks = get_all_benchmarks()

    if args.list:
        print("Available benchmarks:")
        categories = {}
        for name, cls in all_benchmarks.items():
            cat = cls.category
            if cat not in categories:
                categories[cat] = []
            categories[cat].append(name)

        for cat, names in sorted(categories.items()):
            print(f"\n  {cat}:")
            for name in sorted(names):
                print(f"    - {name}")
        return

    # Determine terminals
    if args.terminal == "all":
        terminals = detect_terminals()
    else:
        terminals = [t.strip() for t in args.terminal.split(",")]

    # Determine benchmarks
    if args.benchmark == "all":
        benchmark_names = list(all_benchmarks.keys())
    else:
        benchmark_names = []
        for b in args.benchmark.split(","):
            b = b.strip()
            # Check if it's a category
            matching = [name for name, cls in all_benchmarks.items() if cls.category == b]
            if matching:
                benchmark_names.extend(matching)
            elif b in all_benchmarks:
                benchmark_names.append(b)
            else:
                print(f"Warning: Unknown benchmark or category: {b}")

    print(f"Terminals: {terminals}")
    print(f"Benchmarks: {len(benchmark_names)} tests")
    print(f"Runs per test: {args.runs}")
    print()

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    suites = []
    for terminal in terminals:
        print(f"\nBenchmarking {terminal}...")
        suite = run_benchmarks(terminal, benchmark_names, args.runs)
        suites.append(suite)

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

        # Save markdown
        md_file = output_dir / f"{terminal}_comprehensive_{timestamp}.md"
        with open(md_file, 'w') as f:
            f.write(ReportGenerator.to_markdown(suite))
        print(f"  Saved: {md_file}")

        # Save JSON if requested
        if args.json:
            json_file = output_dir / f"{terminal}_comprehensive_{timestamp}.json"
            with open(json_file, 'w') as f:
                json.dump(suite.to_dict(), f, indent=2)
            print(f"  Saved: {json_file}")

    # Generate comparison
    if args.compare and len(suites) > 1:
        print("\nGenerating comparison report...")
        comparison = ReportGenerator.compare_terminals(suites)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        comparison_file = output_dir / f"comparison_comprehensive_{timestamp}.md"
        with open(comparison_file, 'w') as f:
            f.write(comparison)
        print(f"Saved: {comparison_file}")

    print("\nBenchmarks complete!")


if __name__ == "__main__":
    main()
