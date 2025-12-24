# Comprehensive Terminal Benchmark Comparison

**Generated:** 2025-12-24T05:11:39.018378

## Terminals Compared

- **bossterm** (Darwin 25.0.0 arm64)
- **iterm2** (Darwin 25.0.0 arm64)
- **terminal** (Darwin 25.0.0 arm64)
- **alacritty** (Darwin 25.0.0 arm64)

## Ansi: ansi_attributes

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| attributes/sequences | 2800 | 2800 | 2800 | 2800 |
| attributes/time_ms_mean | 2.98 | 2.88 | 3.53 | 4.49 |

## Ansi: ansi_colors

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| 16_colors/sequences | 5120 | 5120 | 5120 | 5120 |
| 16_colors/sequences_per_sec | 1473292.97 | 1756005.55 | 1599937.50 | 1285534.87 |
| 16_colors/time_ms_mean | 3.48 | 2.92 | 3.20 | 3.98 |
| 256_colors/sequences | 10240 | 10240 | 10240 | 10240 |
| 256_colors/sequences_per_sec | 3007145.77 | 3383089.27 | 2862267.10 | 2545012.64 |
| 256_colors/time_ms_mean | 3.41 | 3.03 | 3.58 | 4.02 |
| truecolor/sequences | 5960 | 5960 | 5960 | 5960 |
| truecolor/sequences_per_sec | 1895900.44 | 2059769.49 | 1756852.42 | 1351614.39 |
| truecolor/time_ms_mean | 3.14 | 2.89 | 3.39 | 4.41 |

## Ansi: ansi_cursor

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| cursor_movements/sequences | 400 | 400 | 400 | 400 |
| cursor_movements/time_ms_mean | 2.89 | 2.83 | 3.29 | 3.97 |

## Latency: latency_echo

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| echo/max_ms | 3.26 | 3.24 | 3.58 | 3.42 |
| echo/mean_ms | 3.05 | 3.11 | 3.34 | 3.27 |
| echo/median_ms | 3.02 | 3.07 | 3.36 | 3.23 |
| echo/min_ms | 2.88 | 3.02 | 3.08 | 3.17 |
| echo/p95_ms | 3.26 | 3.24 | 3.58 | 3.42 |
| echo/p99_ms | 3.26 | 3.24 | 3.58 | 3.42 |
| echo/stdev_ms | 0.19 | 0.12 | 0.25 | 0.13 |
| printf_10chars/max_ms | 4.44 | 3.18 | 3.68 | 3.47 |
| printf_10chars/mean_ms | 3.39 | 2.97 | 3.38 | 3.24 |
| printf_10chars/median_ms | 2.87 | 2.88 | 3.36 | 3.17 |
| printf_10chars/min_ms | 2.86 | 2.84 | 3.11 | 3.07 |
| printf_10chars/p95_ms | 4.44 | 3.18 | 3.68 | 3.47 |
| printf_10chars/p99_ms | 4.44 | 3.18 | 3.68 | 3.47 |
| printf_10chars/stdev_ms | 0.91 | 0.18 | 0.29 | 0.21 |
| printf_1chars/max_ms | 3.03 | 2.93 | 3.73 | 3.42 |
| printf_1chars/mean_ms | 2.96 | 2.91 | 3.46 | 3.30 |
| printf_1chars/median_ms | 3.00 | 2.91 | 3.34 | 3.31 |
| printf_1chars/min_ms | 2.85 | 2.88 | 3.30 | 3.18 |
| printf_1chars/p95_ms | 3.03 | 2.93 | 3.73 | 3.42 |
| printf_1chars/p99_ms | 3.03 | 2.93 | 3.73 | 3.42 |
| printf_1chars/stdev_ms | 0.10 | 0.03 | 0.23 | 0.12 |
| printf_200chars/max_ms | 3.84 | 2.96 | 3.60 | 3.33 |
| printf_200chars/mean_ms | 3.61 | 2.88 | 3.38 | 3.26 |
| printf_200chars/median_ms | 3.71 | 2.87 | 3.30 | 3.27 |
| printf_200chars/min_ms | 3.27 | 2.82 | 3.25 | 3.19 |
| printf_200chars/p95_ms | 3.84 | 2.96 | 3.60 | 3.33 |
| printf_200chars/p99_ms | 3.84 | 2.96 | 3.60 | 3.33 |
| printf_200chars/stdev_ms | 0.30 | 0.07 | 0.19 | 0.07 |
| printf_80chars/max_ms | 4.62 | 3.05 | 3.69 | 3.63 |
| printf_80chars/mean_ms | 3.61 | 3.00 | 3.56 | 3.44 |
| printf_80chars/median_ms | 3.29 | 3.04 | 3.60 | 3.56 |
| printf_80chars/min_ms | 2.91 | 2.92 | 3.40 | 3.15 |
| printf_80chars/p95_ms | 4.62 | 3.05 | 3.69 | 3.63 |
| printf_80chars/p99_ms | 4.62 | 3.05 | 3.69 | 3.63 |
| printf_80chars/stdev_ms | 0.90 | 0.07 | 0.15 | 0.26 |

## Latency: latency_sequential

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| max | 38.94 | 28.90 | 35.88 | 31.18 |
| mean | 34.00 | 28.54 | 33.81 | 30.87 |
| median | 33.10 | 28.55 | 32.99 | 31.01 |
| min | 29.97 | 28.16 | 32.57 | 30.42 |
| p50 | 33.10 | 28.55 | 32.99 | 31.01 |
| p90 | 38.94 | 28.90 | 35.88 | 31.18 |
| p95 | 38.94 | 28.90 | 35.88 | 31.18 |
| p99 | 38.94 | 28.90 | 35.88 | 31.18 |
| stdev | 4.55 | 0.37 | 1.81 | 0.40 |
| unit | ms | ms | ms | ms |

## Resources: cpu_usage

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| cpu_after_percent | 19.90 | 11.80 | 6.00 | 9.80 |
| cpu_before_percent | 38.00 | 8.90 | 5.90 | 6.90 |
| output_time_ms | 21.84 | 15.78 | 14.32 | 25.92 |

## Resources: memory_usage

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| processes/BossTerm | {'memory_mb': 1534.25} | N/A | N/A | N/A |
| processes/java | {'memory_mb': 619.65625} | N/A | N/A | N/A |

## Simulation: simulation_compiler

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| compiler_output/bytes | 63774 | 63675 | 63779 | 63647 |
| compiler_output/lines | 1500 | 1500 | 1500 | 1500 |
| compiler_output/time_ms_mean | 3.16 | 3.90 | 3.44 | 4.40 |

## Simulation: simulation_git_diff

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| git_diff/bytes | 18778 | 21107 | 20656 | 24428 |
| git_diff/lines | 626 | 691 | 664 | 797 |
| git_diff/time_ms_mean | 3.09 | 4.15 | 3.62 | 3.87 |

## Simulation: simulation_htop

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| htop_simulation/bytes | 39340 | 39350 | 39340 | 39340 |
| htop_simulation/lines | 620 | 620 | 620 | 620 |
| htop_simulation/time_ms_mean | 3.06 | 4.34 | 3.28 | 3.77 |

## Simulation: simulation_logs

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| log_output/bytes | 75316 | 75334 | 75274 | 75177 |
| log_output/lines | 1000 | 1000 | 1000 | 1000 |
| log_output/time_ms_mean | 3.07 | 4.10 | 3.40 | 4.14 |

## Simulation: simulation_mixed

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| mixed_workload/bytes | 13708 | 13708 | 13708 | 13708 |
| mixed_workload/time_ms_mean | 2.98 | 4.32 | 3.37 | 4.24 |

## Simulation: simulation_vim

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| vim_simulation/bytes | 19425 | 19440 | 19435 | 19440 |
| vim_simulation/lines | 505 | 505 | 505 | 505 |
| vim_simulation/time_ms_mean | 2.82 | 4.11 | 3.52 | 3.89 |

## Special: block_elements

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| block_elements/chars | 34080 | 34080 | 34080 | 34080 |
| block_elements/time_ms_mean | 3.84 | 2.92 | 3.22 | 4.53 |

## Special: box_drawing

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| box_drawing/chars | 19000 | 19000 | 19000 | 19000 |
| box_drawing/time_ms_mean | 3.39 | 2.88 | 3.23 | 4.18 |

## Special: braille

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| braille/chars | 5120 | 5120 | 5120 | 5120 |
| braille/time_ms_mean | 3.31 | 2.85 | 3.14 | 4.29 |

## Special: math_symbols

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| math_symbols/chars | 5880 | 5880 | 5880 | 5880 |
| math_symbols/time_ms_mean | 3.23 | 3.13 | 3.24 | 3.88 |

## Special: powerline

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| powerline/chars | 1200 | 1200 | 1200 | 1200 |
| powerline/time_ms_mean | 3.92 | 2.94 | 3.17 | 4.49 |

## Throughput: throughput_lines

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| 100000_lines/lines_per_sec_mean | 13444396.03 | 15700275.48 | 12446831.96 | 13078234.67 |
| 100000_lines/lines_per_sec_stdev | 937767.11 | 1125100.76 | 919996.63 | 522432.28 |
| 100000_lines/time_ms_mean | 7.46 | 6.39 | 8.06 | 7.65 |
| 10000_lines/lines_per_sec_mean | 2342664.36 | 2852935.79 | 2479093.56 | 2776559.58 |
| 10000_lines/lines_per_sec_stdev | 127953.83 | 168974.98 | 146543.66 | 165502.83 |
| 10000_lines/time_ms_mean | 4.28 | 3.51 | 4.04 | 3.61 |
| 1000_lines/lines_per_sec_mean | 238811.83 | 291304.03 | 240494.26 | 238545.53 |
| 1000_lines/lines_per_sec_stdev | 52251.12 | 42587.95 | 45338.90 | 61087.82 |
| 1000_lines/time_ms_mean | 4.34 | 3.48 | 4.27 | 4.42 |
| 50000_lines/lines_per_sec_mean | 9303909.48 | 11287861.37 | 10053310.08 | 9558363.61 |
| 50000_lines/lines_per_sec_stdev | 654505.42 | 383550.50 | 373604.75 | 457408.67 |
| 50000_lines/time_ms_mean | 5.39 | 4.43 | 4.98 | 5.24 |
| 5000_lines/lines_per_sec_mean | 1231813.40 | 1486110.77 | 1407278.80 | 1426960.09 |
| 5000_lines/lines_per_sec_stdev | 192912.05 | 87696.16 | 55363.22 | 45662.09 |
| 5000_lines/time_ms_mean | 4.13 | 3.37 | 3.56 | 3.51 |

## Throughput: throughput_raw

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| 10MB/throughput_mbps_mean | 1308.48 | 664.76 | 1091.56 | 676.30 |
| 10MB/throughput_mbps_stdev | 131.73 | 81.06 | 104.14 | 66.40 |
| 10MB/time_ms_mean | 7.69 | 15.21 | 9.22 | 14.88 |
| 1MB/throughput_mbps_mean | 354.05 | 192.19 | 255.81 | 226.16 |
| 1MB/throughput_mbps_stdev | 64.30 | 22.63 | 31.59 | 42.81 |
| 1MB/time_ms_mean | 2.89 | 5.26 | 3.95 | 4.54 |
| 25MB/throughput_mbps_mean | 1645.77 | 1446.09 | 1468.56 | 1388.34 |
| 25MB/throughput_mbps_stdev | 72.07 | 66.33 | 33.04 | 92.58 |
| 25MB/time_ms_mean | 15.21 | 17.31 | 17.03 | 18.06 |
| 50MB/throughput_mbps_mean | 1703.76 | 1713.93 | 1644.30 | 1555.30 |
| 50MB/throughput_mbps_stdev | 193.28 | 136.14 | 29.90 | 56.92 |
| 50MB/time_ms_mean | 29.59 | 29.30 | 30.41 | 32.18 |
| 5MB/throughput_mbps_mean | 1092.48 | 658.87 | 686.35 | 671.74 |
| 5MB/throughput_mbps_stdev | 144.07 | 67.10 | 26.76 | 65.32 |
| 5MB/time_ms_mean | 4.63 | 7.64 | 7.29 | 7.49 |

## Throughput: throughput_varied

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| varied_lines_10k/time_ms_mean | 3.89 | 3.89 | 4.55 | 4.08 |
| varied_lines_10k/time_ms_stdev | 0.63 | 0.68 | 0.58 | 0.56 |

## Unicode: unicode_cjk

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| cjk/bytes | 25800 | 25800 | 25800 | 25800 |
| cjk/chars | 8600 | 8600 | 8600 | 8600 |
| cjk/chars_per_sec | 2255441.92 | 2850802.27 | 2413395.36 | 2282234.98 |
| cjk/time_ms_mean | 3.81 | 3.02 | 3.56 | 3.77 |

## Unicode: unicode_combining

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| combining_diacritics/bytes | 48800 | 48800 | 48800 | 48800 |
| combining_diacritics/chars | 32400 | 32400 | 32400 | 32400 |
| combining_diacritics/time_ms_mean | 3.32 | 2.93 | 3.27 | 3.80 |
| grapheme_clusters/bytes | 24200 | 24200 | 24200 | 24200 |
| grapheme_clusters/chars | 8600 | 8600 | 8600 | 8600 |
| grapheme_clusters/time_ms_mean | 3.35 | 2.91 | 3.46 | 3.88 |

## Unicode: unicode_emoji

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| basic/bytes | 7440 | 7440 | 7440 | 7440 |
| basic/chars | 1880 | 1880 | 1880 | 1880 |
| basic/chars_per_sec | 530185.21 | 629918.23 | 542209.89 | 443924.05 |
| basic/time_ms_mean | 3.55 | 2.98 | 3.47 | 4.23 |
| basic/time_ms_stdev | 0.07 | 0.07 | 0.05 | 0.07 |
| flags/bytes | 12000 | 12000 | 12000 | 12000 |
| flags/chars | 3000 | 3000 | 3000 | 3000 |
| flags/chars_per_sec | 878552.34 | 1025972.38 | 830121.14 | 729986.15 |
| flags/time_ms_mean | 3.41 | 2.92 | 3.61 | 4.11 |
| flags/time_ms_stdev | 0.24 | 0.03 | 0.29 | 0.05 |
| skin_tones/bytes | 8175 | 8175 | 8175 | 8175 |
| skin_tones/chars | 2100 | 2100 | 2100 | 2100 |
| skin_tones/chars_per_sec | 572066.38 | 694237.22 | 621260.91 | 541031.81 |
| skin_tones/time_ms_mean | 3.67 | 3.02 | 3.38 | 3.88 |
| skin_tones/time_ms_stdev | 0.49 | 0.17 | 0.09 | 0.19 |
| variation_selectors/bytes | 9350 | 9350 | 9350 | 9350 |
| variation_selectors/chars | 2950 | 2950 | 2950 | 2950 |
| variation_selectors/chars_per_sec | 864268.28 | 924507.24 | 856672.75 | 784258.75 |
| variation_selectors/time_ms_mean | 3.41 | 3.19 | 3.44 | 3.76 |
| variation_selectors/time_ms_stdev | 0.10 | 0.10 | 0.30 | 0.22 |
| zwj_sequences/bytes | 17460 | 17460 | 17460 | 17460 |
| zwj_sequences/chars | 4920 | 4920 | 4920 | 4920 |
| zwj_sequences/chars_per_sec | 1317685.49 | 1684787.08 | 1394014.65 | 1219733.91 |
| zwj_sequences/time_ms_mean | 3.73 | 2.92 | 3.53 | 4.03 |
| zwj_sequences/time_ms_stdev | 0.30 | 0.15 | 0.20 | 0.07 |

## Unicode: unicode_surrogate

| Metric | bossterm | iterm2 | terminal | alacritty |
|--------|------ | ------ | ------ | ------|
| surrogate_pairs/bytes | 19500 | 19500 | 19500 | 19500 |
| surrogate_pairs/chars | 4900 | 4900 | 4900 | 4900 |
| surrogate_pairs/chars_per_sec | 1499496.29 | 1701987.94 | 1358924.87 | 1276942.17 |
| surrogate_pairs/time_ms_mean | 3.27 | 2.88 | 3.61 | 3.84 |
