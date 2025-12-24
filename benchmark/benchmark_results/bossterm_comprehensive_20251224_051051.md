# Comprehensive Terminal Benchmark Report

## System Information
- **Terminal:** bossterm
- **Host:** Shivangs-MacBook-Pro-Beast.local
- **OS:** Darwin 25.0.0 arm64
- **CPU:** Apple M3 Max
- **Memory:** 128.0 GB
- **Date:** 2025-12-24T05:10:35.670370

## Ansi Benchmarks

### ansi_colors

**16_colors:**
  - sequences: 5120
  - time_ms_mean: 3.475
  - sequences_per_sec: 1473292.965
**256_colors:**
  - sequences: 10240
  - time_ms_mean: 3.405
  - sequences_per_sec: 3007145.775
**truecolor:**
  - sequences: 5960
  - time_ms_mean: 3.144
  - sequences_per_sec: 1895900.437

### ansi_attributes

**attributes:**
  - sequences: 2800
  - time_ms_mean: 2.979

### ansi_cursor

**cursor_movements:**
  - sequences: 400
  - time_ms_mean: 2.893

## Latency Benchmarks

### latency_echo

**echo:**
  - min_ms: 2.882
  - max_ms: 3.263
  - mean_ms: 3.054
  - median_ms: 3.016
  - stdev_ms: 0.193
  - p95_ms: 3.263
  - p99_ms: 3.263
**printf_1chars:**
  - min_ms: 2.851
  - max_ms: 3.031
  - mean_ms: 2.960
  - median_ms: 2.998
  - stdev_ms: 0.096
  - p95_ms: 3.031
  - p99_ms: 3.031
**printf_10chars:**
  - min_ms: 2.861
  - max_ms: 4.437
  - mean_ms: 3.389
  - median_ms: 2.870
  - stdev_ms: 0.907
  - p95_ms: 4.437
  - p99_ms: 4.437
**printf_80chars:**
  - min_ms: 2.906
  - max_ms: 4.619
  - mean_ms: 3.606
  - median_ms: 3.292
  - stdev_ms: 0.898
  - p95_ms: 4.619
  - p99_ms: 4.619
**printf_200chars:**
  - min_ms: 3.273
  - max_ms: 3.839
  - mean_ms: 3.605
  - median_ms: 3.705
  - stdev_ms: 0.296
  - p95_ms: 3.839
  - p99_ms: 3.839

### latency_sequential

- min: 29.973
- max: 38.939
- mean: 34.003
- median: 33.097
- stdev: 4.551
- p50: 33.097
- p90: 38.939
- p95: 38.939
- p99: 38.939
- unit: ms

## Resources Benchmarks

### memory_usage

**processes:**
  - java: {'memory_mb': 619.65625}
  - BossTerm: {'memory_mb': 1534.25}

### cpu_usage

- cpu_before_percent: 38.000
- cpu_after_percent: 19.900
- output_time_ms: 21.836

## Simulation Benchmarks

### simulation_compiler

**compiler_output:**
  - lines: 1500
  - bytes: 63774
  - time_ms_mean: 3.157

### simulation_logs

**log_output:**
  - lines: 1000
  - bytes: 75316
  - time_ms_mean: 3.071

### simulation_git_diff

**git_diff:**
  - lines: 626
  - bytes: 18778
  - time_ms_mean: 3.092

### simulation_htop

**htop_simulation:**
  - lines: 620
  - bytes: 39340
  - time_ms_mean: 3.059

### simulation_vim

**vim_simulation:**
  - lines: 505
  - bytes: 19425
  - time_ms_mean: 2.823

### simulation_mixed

**mixed_workload:**
  - bytes: 13708
  - time_ms_mean: 2.985

## Special Benchmarks

### box_drawing

**box_drawing:**
  - chars: 19000
  - time_ms_mean: 3.393

### block_elements

**block_elements:**
  - chars: 34080
  - time_ms_mean: 3.837

### powerline

**powerline:**
  - chars: 1200
  - time_ms_mean: 3.923

### braille

**braille:**
  - chars: 5120
  - time_ms_mean: 3.306

### math_symbols

**math_symbols:**
  - chars: 5880
  - time_ms_mean: 3.227

## Throughput Benchmarks

### throughput_raw

**1MB:**
  - throughput_mbps_mean: 354.053
  - throughput_mbps_stdev: 64.296
  - time_ms_mean: 2.892
**5MB:**
  - throughput_mbps_mean: 1092.484
  - throughput_mbps_stdev: 144.071
  - time_ms_mean: 4.632
**10MB:**
  - throughput_mbps_mean: 1308.476
  - throughput_mbps_stdev: 131.732
  - time_ms_mean: 7.692
**25MB:**
  - throughput_mbps_mean: 1645.767
  - throughput_mbps_stdev: 72.071
  - time_ms_mean: 15.210
**50MB:**
  - throughput_mbps_mean: 1703.765
  - throughput_mbps_stdev: 193.280
  - time_ms_mean: 29.587

### throughput_lines

**1000_lines:**
  - lines_per_sec_mean: 238811.828
  - lines_per_sec_stdev: 52251.117
  - time_ms_mean: 4.340
**5000_lines:**
  - lines_per_sec_mean: 1231813.403
  - lines_per_sec_stdev: 192912.050
  - time_ms_mean: 4.132
**10000_lines:**
  - lines_per_sec_mean: 2342664.358
  - lines_per_sec_stdev: 127953.835
  - time_ms_mean: 4.277
**50000_lines:**
  - lines_per_sec_mean: 9303909.480
  - lines_per_sec_stdev: 654505.422
  - time_ms_mean: 5.393
**100000_lines:**
  - lines_per_sec_mean: 13444396.033
  - lines_per_sec_stdev: 937767.109
  - time_ms_mean: 7.463

### throughput_varied

**varied_lines_10k:**
  - time_ms_mean: 3.892
  - time_ms_stdev: 0.629

## Unicode Benchmarks

### unicode_emoji

**basic:**
  - chars: 1880
  - bytes: 7440
  - time_ms_mean: 3.546
  - time_ms_stdev: 0.070
  - chars_per_sec: 530185.211
**variation_selectors:**
  - chars: 2950
  - bytes: 9350
  - time_ms_mean: 3.413
  - time_ms_stdev: 0.102
  - chars_per_sec: 864268.276
**zwj_sequences:**
  - chars: 4920
  - bytes: 17460
  - time_ms_mean: 3.734
  - time_ms_stdev: 0.301
  - chars_per_sec: 1317685.493
**skin_tones:**
  - chars: 2100
  - bytes: 8175
  - time_ms_mean: 3.671
  - time_ms_stdev: 0.492
  - chars_per_sec: 572066.379
**flags:**
  - chars: 3000
  - bytes: 12000
  - time_ms_mean: 3.415
  - time_ms_stdev: 0.235
  - chars_per_sec: 878552.340

### unicode_cjk

**cjk:**
  - chars: 8600
  - bytes: 25800
  - time_ms_mean: 3.813
  - chars_per_sec: 2255441.916

### unicode_surrogate

**surrogate_pairs:**
  - chars: 4900
  - bytes: 19500
  - time_ms_mean: 3.268
  - chars_per_sec: 1499496.288

### unicode_combining

**combining_diacritics:**
  - chars: 32400
  - bytes: 48800
  - time_ms_mean: 3.322
**grapheme_clusters:**
  - chars: 8600
  - bytes: 24200
  - time_ms_mean: 3.353
