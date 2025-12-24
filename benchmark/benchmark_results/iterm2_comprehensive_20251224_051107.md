# Comprehensive Terminal Benchmark Report

## System Information
- **Terminal:** iterm2
- **Host:** Shivangs-MacBook-Pro-Beast.local
- **OS:** Darwin 25.0.0 arm64
- **CPU:** Apple M3 Max
- **Memory:** 128.0 GB
- **Date:** 2025-12-24T05:10:51.269399

## Ansi Benchmarks

### ansi_colors

**16_colors:**
  - sequences: 5120
  - time_ms_mean: 2.916
  - sequences_per_sec: 1756005.547
**256_colors:**
  - sequences: 10240
  - time_ms_mean: 3.027
  - sequences_per_sec: 3383089.269
**truecolor:**
  - sequences: 5960
  - time_ms_mean: 2.894
  - sequences_per_sec: 2059769.493

### ansi_attributes

**attributes:**
  - sequences: 2800
  - time_ms_mean: 2.877

### ansi_cursor

**cursor_movements:**
  - sequences: 400
  - time_ms_mean: 2.829

## Latency Benchmarks

### latency_echo

**echo:**
  - min_ms: 3.018
  - max_ms: 3.240
  - mean_ms: 3.108
  - median_ms: 3.067
  - stdev_ms: 0.117
  - p95_ms: 3.240
  - p99_ms: 3.240
**printf_1chars:**
  - min_ms: 2.879
  - max_ms: 2.933
  - mean_ms: 2.906
  - median_ms: 2.906
  - stdev_ms: 0.027
  - p95_ms: 2.933
  - p99_ms: 2.933
**printf_10chars:**
  - min_ms: 2.843
  - max_ms: 3.180
  - mean_ms: 2.969
  - median_ms: 2.885
  - stdev_ms: 0.184
  - p95_ms: 3.180
  - p99_ms: 3.180
**printf_80chars:**
  - min_ms: 2.917
  - max_ms: 3.049
  - mean_ms: 3.001
  - median_ms: 3.038
  - stdev_ms: 0.074
  - p95_ms: 3.049
  - p99_ms: 3.049
**printf_200chars:**
  - min_ms: 2.817
  - max_ms: 2.960
  - mean_ms: 2.884
  - median_ms: 2.873
  - stdev_ms: 0.072
  - p95_ms: 2.960
  - p99_ms: 2.960

### latency_sequential

- min: 28.156
- max: 28.902
- mean: 28.535
- median: 28.548
- stdev: 0.373
- p50: 28.548
- p90: 28.902
- p95: 28.902
- p99: 28.902
- unit: ms

## Resources Benchmarks

### memory_usage

**processes:**

### cpu_usage

- cpu_before_percent: 8.900
- cpu_after_percent: 11.800
- output_time_ms: 15.779

## Simulation Benchmarks

### simulation_compiler

**compiler_output:**
  - lines: 1500
  - bytes: 63675
  - time_ms_mean: 3.896

### simulation_logs

**log_output:**
  - lines: 1000
  - bytes: 75334
  - time_ms_mean: 4.100

### simulation_git_diff

**git_diff:**
  - lines: 691
  - bytes: 21107
  - time_ms_mean: 4.154

### simulation_htop

**htop_simulation:**
  - lines: 620
  - bytes: 39350
  - time_ms_mean: 4.342

### simulation_vim

**vim_simulation:**
  - lines: 505
  - bytes: 19440
  - time_ms_mean: 4.106

### simulation_mixed

**mixed_workload:**
  - bytes: 13708
  - time_ms_mean: 4.322

## Special Benchmarks

### box_drawing

**box_drawing:**
  - chars: 19000
  - time_ms_mean: 2.876

### block_elements

**block_elements:**
  - chars: 34080
  - time_ms_mean: 2.918

### powerline

**powerline:**
  - chars: 1200
  - time_ms_mean: 2.937

### braille

**braille:**
  - chars: 5120
  - time_ms_mean: 2.851

### math_symbols

**math_symbols:**
  - chars: 5880
  - time_ms_mean: 3.133

## Throughput Benchmarks

### throughput_raw

**1MB:**
  - throughput_mbps_mean: 192.187
  - throughput_mbps_stdev: 22.634
  - time_ms_mean: 5.255
**5MB:**
  - throughput_mbps_mean: 658.870
  - throughput_mbps_stdev: 67.100
  - time_ms_mean: 7.644
**10MB:**
  - throughput_mbps_mean: 664.762
  - throughput_mbps_stdev: 81.061
  - time_ms_mean: 15.205
**25MB:**
  - throughput_mbps_mean: 1446.090
  - throughput_mbps_stdev: 66.328
  - time_ms_mean: 17.313
**50MB:**
  - throughput_mbps_mean: 1713.925
  - throughput_mbps_stdev: 136.142
  - time_ms_mean: 29.298

### throughput_lines

**1000_lines:**
  - lines_per_sec_mean: 291304.030
  - lines_per_sec_stdev: 42587.951
  - time_ms_mean: 3.484
**5000_lines:**
  - lines_per_sec_mean: 1486110.768
  - lines_per_sec_stdev: 87696.158
  - time_ms_mean: 3.372
**10000_lines:**
  - lines_per_sec_mean: 2852935.785
  - lines_per_sec_stdev: 168974.979
  - time_ms_mean: 3.514
**50000_lines:**
  - lines_per_sec_mean: 11287861.373
  - lines_per_sec_stdev: 383550.498
  - time_ms_mean: 4.433
**100000_lines:**
  - lines_per_sec_mean: 15700275.476
  - lines_per_sec_stdev: 1125100.760
  - time_ms_mean: 6.392

### throughput_varied

**varied_lines_10k:**
  - time_ms_mean: 3.891
  - time_ms_stdev: 0.685

## Unicode Benchmarks

### unicode_emoji

**basic:**
  - chars: 1880
  - bytes: 7440
  - time_ms_mean: 2.985
  - time_ms_stdev: 0.067
  - chars_per_sec: 629918.234
**variation_selectors:**
  - chars: 2950
  - bytes: 9350
  - time_ms_mean: 3.191
  - time_ms_stdev: 0.097
  - chars_per_sec: 924507.244
**zwj_sequences:**
  - chars: 4920
  - bytes: 17460
  - time_ms_mean: 2.920
  - time_ms_stdev: 0.147
  - chars_per_sec: 1684787.076
**skin_tones:**
  - chars: 2100
  - bytes: 8175
  - time_ms_mean: 3.025
  - time_ms_stdev: 0.168
  - chars_per_sec: 694237.215
**flags:**
  - chars: 3000
  - bytes: 12000
  - time_ms_mean: 2.924
  - time_ms_stdev: 0.029
  - chars_per_sec: 1025972.376

### unicode_cjk

**cjk:**
  - chars: 8600
  - bytes: 25800
  - time_ms_mean: 3.017
  - chars_per_sec: 2850802.273

### unicode_surrogate

**surrogate_pairs:**
  - chars: 4900
  - bytes: 19500
  - time_ms_mean: 2.879
  - chars_per_sec: 1701987.937

### unicode_combining

**combining_diacritics:**
  - chars: 32400
  - bytes: 48800
  - time_ms_mean: 2.927
**grapheme_clusters:**
  - chars: 8600
  - bytes: 24200
  - time_ms_mean: 2.913
