# Casehub Engine benchmark

This is an informational baseline, not a CI performance gate. Run it again with:

```bash
python3 scripts/quill_benchmark.py \
  --quill quill-app/target/quill \
  --project /path/to/casehub/engine
```

The benchmark removes `.quill` for the measured run, but does not clear operating-system,
Maven, Gradle, or dependency caches. Existing index data is restored afterwards. Compare
results from similar hosts and cache conditions; use several runs before treating a change
as a regression.

## Baseline — 2026-09-12

- Quill: `1.0.0-SNAPSHOT`
- Project commit: `00e34b87d3271d4e272e980481fdbd5c31a34ccb` (clean worktree)
- Host: Apple arm64, 10 logical CPUs, 24 GiB RAM, macOS 26.6.2
- Cold-index time: 8.767 s (warm OS/build caches)
- First uncached observation: 30.417 s
- Peak process-tree RSS: 532.62 MiB
- SQLite size: 3.34 MiB
- MCP startup: 0.015 s
- Tool mix: `get_overview`, `search_classes`, `find_git_hotspots`,
  `list_cdi_beans`, `get_recent_changes`

| Concurrent requests | Total | Requests/s | p50 | p95 | Max | Errors |
|---:|---:|---:|---:|---:|---:|---:|
| 4 | 0.257 s | 15.56 | 135.97 ms | 257.07 ms | 257.07 ms | 0 |
| 16 | 0.752 s | 21.26 | 374.87 ms | 752.46 ms | 752.46 ms | 0 |
| 100 | 4.256 s | 23.49 | 2226.26 ms | 4134.82 ms | 4256.30 ms | 0 |

The 100-request burst is queueing-latency dominated because the server intentionally runs
four tool workers. Throughput remains stable and all requests complete without busy,
timeout, transport, or tool errors.
