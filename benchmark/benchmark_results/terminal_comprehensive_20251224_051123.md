# Comprehensive Terminal Benchmark Report

## System Information
- **Terminal:** terminal
- **Host:** Shivangs-MacBook-Pro-Beast.local
- **OS:** Darwin 25.0.0 arm64
- **CPU:** Apple M3 Max
- **Memory:** 128.0 GB
- **Date:** 2025-12-24T05:11:07.357229

## Ansi Benchmarks

### ansi_colors

**16_colors:**
  - sequences: 5120
  - time_ms_mean: 3.200
  - sequences_per_sec: 1599937.504
**256_colors:**
  - sequences: 10240
  - time_ms_mean: 3.578
  - sequences_per_sec: 2862267.095
**truecolor:**
  - sequences: 5960
  - time_ms_mean: 3.392
  - sequences_per_sec: 1756852.419

### ansi_attributes

**attributes:**
  - sequences: 2800
  - time_ms_mean: 3.532

### ansi_cursor

**cursor_movements:**
  - sequences: 400
  - time_ms_mean: 3.295

## Latency Benchmarks

### latency_echo

**echo:**
  - min_ms: 3.084
  - max_ms: 3.576
  - mean_ms: 3.341
  - median_ms: 3.364
  - stdev_ms: 0.247
  - p95_ms: 3.576
  - p99_ms: 3.576
**printf_1chars:**
  - min_ms: 3.301
  - max_ms: 3.726
  - mean_ms: 3.457
  - median_ms: 3.344
  - stdev_ms: 0.234
  - p95_ms: 3.726
  - p99_ms: 3.726
**printf_10chars:**
  - min_ms: 3.110
  - max_ms: 3.680
  - mean_ms: 3.385
  - median_ms: 3.364
  - stdev_ms: 0.286
  - p95_ms: 3.680
  - p99_ms: 3.680
**printf_80chars:**
  - min_ms: 3.397
  - max_ms: 3.692
  - mean_ms: 3.564
  - median_ms: 3.602
  - stdev_ms: 0.151
  - p95_ms: 3.692
  - p99_ms: 3.692
**printf_200chars:**
  - min_ms: 3.248
  - max_ms: 3.602
  - mean_ms: 3.383
  - median_ms: 3.297
  - stdev_ms: 0.192
  - p95_ms: 3.602
  - p99_ms: 3.602

### latency_sequential

- min: 32.566
- max: 35.884
- mean: 33.812
- median: 32.985
- stdev: 1.807
- p50: 32.985
- p90: 35.884
- p95: 35.884
- p99: 35.884
- unit: ms

## Resources Benchmarks

### memory_usage

**processes:**

### cpu_usage

- cpu_before_percent: 5.900
- cpu_after_percent: 6.000
- output_time_ms: 14.318

## Simulation Benchmarks

### simulation_compiler

**compiler_output:**
  - lines: 1500
  - bytes: 63779
  - time_ms_mean: 3.436

### simulation_logs

**log_output:**
  - lines: 1000
  - bytes: 75274
  - time_ms_mean: 3.401

### simulation_git_diff

**git_diff:**
  - lines: 664
  - bytes: 20656
  - time_ms_mean: 3.617

### simulation_htop

**htop_simulation:**
  - lines: 620
  - bytes: 39340
  - time_ms_mean: 3.280

### simulation_vim

**vim_simulation:**
  - lines: 505
  - bytes: 19435
  - time_ms_mean: 3.524

### simulation_mixed

**mixed_workload:**
  - bytes: 13708
  - time_ms_mean: 3.366

## Special Benchmarks

### box_drawing

**box_drawing:**
  - chars: 19000
  - time_ms_mean: 3.229

### block_elements

**block_elements:**
  - chars: 34080
  - time_ms_mean: 3.217

### powerline

**powerline:**
  - chars: 1200
  - time_ms_mean: 3.166

### braille

**braille:**
  - chars: 5120
  - time_ms_mean: 3.145

### math_symbols

**math_symbols:**
  - chars: 5880
  - time_ms_mean: 3.240

## Throughput Benchmarks

### throughput_raw

**1MB:**
  - throughput_mbps_mean: 255.813
  - throughput_mbps_stdev: 31.593
  - time_ms_mean: 3.952
**5MB:**
  - throughput_mbps_mean: 686.350
  - throughput_mbps_stdev: 26.764
  - time_ms_mean: 7.292
**10MB:**
  - throughput_mbps_mean: 1091.564
  - throughput_mbps_stdev: 104.138
  - time_ms_mean: 9.217
**25MB:**
  - throughput_mbps_mean: 1468.563
  - throughput_mbps_stdev: 33.042
  - time_ms_mean: 17.029
**50MB:**
  - throughput_mbps_mean: 1644.296
  - throughput_mbps_stdev: 29.901
  - time_ms_mean: 30.415

### throughput_lines

**1000_lines:**
  - lines_per_sec_mean: 240494.263
  - lines_per_sec_stdev: 45338.903
  - time_ms_mean: 4.268
**5000_lines:**
  - lines_per_sec_mean: 1407278.803
  - lines_per_sec_stdev: 55363.224
  - time_ms_mean: 3.557
**10000_lines:**
  - lines_per_sec_mean: 2479093.557
  - lines_per_sec_stdev: 146543.657
  - time_ms_mean: 4.043
**50000_lines:**
  - lines_per_sec_mean: 10053310.079
  - lines_per_sec_stdev: 373604.753
  - time_ms_mean: 4.978
**100000_lines:**
  - lines_per_sec_mean: 12446831.964
  - lines_per_sec_stdev: 919996.627
  - time_ms_mean: 8.062

### throughput_varied

**varied_lines_10k:**
  - time_ms_mean: 4.554
  - time_ms_stdev: 0.579

## Unicode Benchmarks

### unicode_emoji

**basic:**
  - chars: 1880
  - bytes: 7440
  - time_ms_mean: 3.467
  - time_ms_stdev: 0.055
  - chars_per_sec: 542209.886
**variation_selectors:**
  - chars: 2950
  - bytes: 9350
  - time_ms_mean: 3.444
  - time_ms_stdev: 0.298
  - chars_per_sec: 856672.747
**zwj_sequences:**
  - chars: 4920
  - bytes: 17460
  - time_ms_mean: 3.529
  - time_ms_stdev: 0.196
  - chars_per_sec: 1394014.647
**skin_tones:**
  - chars: 2100
  - bytes: 8175
  - time_ms_mean: 3.380
  - time_ms_stdev: 0.092
  - chars_per_sec: 621260.909
**flags:**
  - chars: 3000
  - bytes: 12000
  - time_ms_mean: 3.614
  - time_ms_stdev: 0.286
  - chars_per_sec: 830121.145

### unicode_cjk

**cjk:**
  - chars: 8600
  - bytes: 25800
  - time_ms_mean: 3.563
  - chars_per_sec: 2413395.361

### unicode_surrogate

**surrogate_pairs:**
  - chars: 4900
  - bytes: 19500
  - time_ms_mean: 3.606
  - chars_per_sec: 1358924.874

### unicode_combining

**combining_diacritics:**
  - chars: 32400
  - bytes: 48800
  - time_ms_mean: 3.271
**grapheme_clusters:**
  - chars: 8600
  - bytes: 24200
  - time_ms_mean: 3.460
