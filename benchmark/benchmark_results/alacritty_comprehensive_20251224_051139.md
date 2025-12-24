# Comprehensive Terminal Benchmark Report

## System Information
- **Terminal:** alacritty
- **Host:** Shivangs-MacBook-Pro-Beast.local
- **OS:** Darwin 25.0.0 arm64
- **CPU:** Apple M3 Max
- **Memory:** 128.0 GB
- **Date:** 2025-12-24T05:11:23.168465

## Ansi Benchmarks

### ansi_colors

**16_colors:**
  - sequences: 5120
  - time_ms_mean: 3.983
  - sequences_per_sec: 1285534.874
**256_colors:**
  - sequences: 10240
  - time_ms_mean: 4.024
  - sequences_per_sec: 2545012.639
**truecolor:**
  - sequences: 5960
  - time_ms_mean: 4.410
  - sequences_per_sec: 1351614.394

### ansi_attributes

**attributes:**
  - sequences: 2800
  - time_ms_mean: 4.494

### ansi_cursor

**cursor_movements:**
  - sequences: 400
  - time_ms_mean: 3.966

## Latency Benchmarks

### latency_echo

**echo:**
  - min_ms: 3.173
  - max_ms: 3.419
  - mean_ms: 3.273
  - median_ms: 3.229
  - stdev_ms: 0.129
  - p95_ms: 3.419
  - p99_ms: 3.419
**printf_1chars:**
  - min_ms: 3.182
  - max_ms: 3.422
  - mean_ms: 3.304
  - median_ms: 3.307
  - stdev_ms: 0.120
  - p95_ms: 3.422
  - p99_ms: 3.422
**printf_10chars:**
  - min_ms: 3.073
  - max_ms: 3.470
  - mean_ms: 3.239
  - median_ms: 3.173
  - stdev_ms: 0.207
  - p95_ms: 3.470
  - p99_ms: 3.470
**printf_80chars:**
  - min_ms: 3.146
  - max_ms: 3.630
  - mean_ms: 3.445
  - median_ms: 3.559
  - stdev_ms: 0.261
  - p95_ms: 3.630
  - p99_ms: 3.630
**printf_200chars:**
  - min_ms: 3.185
  - max_ms: 3.332
  - mean_ms: 3.264
  - median_ms: 3.275
  - stdev_ms: 0.074
  - p95_ms: 3.332
  - p99_ms: 3.332

### latency_sequential

- min: 30.418
- max: 31.175
- mean: 30.869
- median: 31.015
- stdev: 0.399
- p50: 31.015
- p90: 31.175
- p95: 31.175
- p99: 31.175
- unit: ms

## Resources Benchmarks

### memory_usage

**processes:**

### cpu_usage

- cpu_before_percent: 6.900
- cpu_after_percent: 9.800
- output_time_ms: 25.918

## Simulation Benchmarks

### simulation_compiler

**compiler_output:**
  - lines: 1500
  - bytes: 63647
  - time_ms_mean: 4.401

### simulation_logs

**log_output:**
  - lines: 1000
  - bytes: 75177
  - time_ms_mean: 4.136

### simulation_git_diff

**git_diff:**
  - lines: 797
  - bytes: 24428
  - time_ms_mean: 3.868

### simulation_htop

**htop_simulation:**
  - lines: 620
  - bytes: 39340
  - time_ms_mean: 3.768

### simulation_vim

**vim_simulation:**
  - lines: 505
  - bytes: 19440
  - time_ms_mean: 3.893

### simulation_mixed

**mixed_workload:**
  - bytes: 13708
  - time_ms_mean: 4.235

## Special Benchmarks

### box_drawing

**box_drawing:**
  - chars: 19000
  - time_ms_mean: 4.185

### block_elements

**block_elements:**
  - chars: 34080
  - time_ms_mean: 4.528

### powerline

**powerline:**
  - chars: 1200
  - time_ms_mean: 4.493

### braille

**braille:**
  - chars: 5120
  - time_ms_mean: 4.290

### math_symbols

**math_symbols:**
  - chars: 5880
  - time_ms_mean: 3.876

## Throughput Benchmarks

### throughput_raw

**1MB:**
  - throughput_mbps_mean: 226.162
  - throughput_mbps_stdev: 42.810
  - time_ms_mean: 4.537
**5MB:**
  - throughput_mbps_mean: 671.737
  - throughput_mbps_stdev: 65.316
  - time_ms_mean: 7.488
**10MB:**
  - throughput_mbps_mean: 676.300
  - throughput_mbps_stdev: 66.403
  - time_ms_mean: 14.877
**25MB:**
  - throughput_mbps_mean: 1388.341
  - throughput_mbps_stdev: 92.577
  - time_ms_mean: 18.062
**50MB:**
  - throughput_mbps_mean: 1555.297
  - throughput_mbps_stdev: 56.925
  - time_ms_mean: 32.177

### throughput_lines

**1000_lines:**
  - lines_per_sec_mean: 238545.533
  - lines_per_sec_stdev: 61087.820
  - time_ms_mean: 4.418
**5000_lines:**
  - lines_per_sec_mean: 1426960.088
  - lines_per_sec_stdev: 45662.089
  - time_ms_mean: 3.506
**10000_lines:**
  - lines_per_sec_mean: 2776559.579
  - lines_per_sec_stdev: 165502.827
  - time_ms_mean: 3.610
**50000_lines:**
  - lines_per_sec_mean: 9558363.613
  - lines_per_sec_stdev: 457408.674
  - time_ms_mean: 5.239
**100000_lines:**
  - lines_per_sec_mean: 13078234.670
  - lines_per_sec_stdev: 522432.281
  - time_ms_mean: 7.654

### throughput_varied

**varied_lines_10k:**
  - time_ms_mean: 4.081
  - time_ms_stdev: 0.557

## Unicode Benchmarks

### unicode_emoji

**basic:**
  - chars: 1880
  - bytes: 7440
  - time_ms_mean: 4.235
  - time_ms_stdev: 0.068
  - chars_per_sec: 443924.050
**variation_selectors:**
  - chars: 2950
  - bytes: 9350
  - time_ms_mean: 3.762
  - time_ms_stdev: 0.215
  - chars_per_sec: 784258.745
**zwj_sequences:**
  - chars: 4920
  - bytes: 17460
  - time_ms_mean: 4.034
  - time_ms_stdev: 0.075
  - chars_per_sec: 1219733.906
**skin_tones:**
  - chars: 2100
  - bytes: 8175
  - time_ms_mean: 3.881
  - time_ms_stdev: 0.189
  - chars_per_sec: 541031.810
**flags:**
  - chars: 3000
  - bytes: 12000
  - time_ms_mean: 4.110
  - time_ms_stdev: 0.054
  - chars_per_sec: 729986.152

### unicode_cjk

**cjk:**
  - chars: 8600
  - bytes: 25800
  - time_ms_mean: 3.768
  - chars_per_sec: 2282234.984

### unicode_surrogate

**surrogate_pairs:**
  - chars: 4900
  - bytes: 19500
  - time_ms_mean: 3.837
  - chars_per_sec: 1276942.172

### unicode_combining

**combining_diacritics:**
  - chars: 32400
  - bytes: 48800
  - time_ms_mean: 3.800
**grapheme_clusters:**
  - chars: 8600
  - bytes: 24200
  - time_ms_mean: 3.880
