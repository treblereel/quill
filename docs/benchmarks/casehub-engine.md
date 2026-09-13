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

## Worktree-cache optimization — 2026-09-12

The MCP server now shares one live Git worktree inspection between concurrent tool calls
for 500 ms and refreshes expired snapshots in the background. `get_overview` also uses
SQL aggregates and fetches only the class rows needed for its architecture-hub and
CDI-problem samples. The benchmark collected twenty sequential samples per tool after five
warmup requests.

- Project commit: `00e34b87d3271d4e272e980481fdbd5c31a34ccb` (clean worktree)
- Cold-index time: 9.450 s
- Peak process-tree RSS: 528.86 MiB
- SQLite size: 3.34 MiB
- MCP startup: 0.013 s
- Native binary size: 39.51 MB (39,509,384 bytes)

| Tool | Samples | p50 | p95 | Max | Errors |
|---|---:|---:|---:|---:|---:|
| `get_overview` | 20 | 17.70 ms | 31.84 ms | 34.59 ms | 0 |
| `search_classes` | 20 | 2.04 ms | 2.23 ms | 3.39 ms | 0 |
| `find_git_hotspots` | 20 | 9.41 ms | 10.58 ms | 13.28 ms | 0 |
| `list_cdi_beans` | 20 | 3.38 ms | 3.64 ms | 4.02 ms | 0 |
| `get_recent_changes` | 20 | 2.82 ms | 2.94 ms | 3.06 ms | 0 |

| Concurrent requests | Total | Requests/s | p50 | p95 | Max | Errors |
|---:|---:|---:|---:|---:|---:|---:|
| 4 | 0.019 s | 211.21 | 3.91 ms | 18.94 ms | 18.94 ms | 0 |
| 16 | 0.037 s | 427.94 | 20.58 ms | 37.39 ms | 37.39 ms | 0 |
| 100 | 0.221 s | 452.77 | 101.03 ms | 215.56 ms | 220.86 ms | 0 |

At 100 concurrent requests this is about 19 times the baseline throughput, while p50
and p95 latency are both about 20 times lower. Cold indexing is effectively unchanged,
as expected: this optimization targets repeated MCP reads after the index is published.

## Dependency-index cache — 2026-09-12

JFR showed that repeated Jandex parsing and type interning for 434 dependency JARs
dominated indexing CPU. Quill now serializes the combined dependency index into the
project build output and reuses it while the ordered runtime classpath and every JAR's
size and modification time remain unchanged.

| Dependency cache | Index time | Peak RSS | Cache size |
|---|---:|---:|---:|
| Miss, including cache creation | 11.079 s | 564.78 MiB | 22 MiB |
| Hit | 3.034 s | 394.94 MiB | 22 MiB |

A cache hit makes a full index refresh about 3.7 times faster than the cache-creation
run and cuts peak RSS by about 170 MiB. Maven or Gradle `clean` removes the cache with
the rest of the build output; the next Quill index recreates it. The native executable
is 39.67 MB (39,674,792 bytes) with cache read/write support included.

## Parallel dependency indexing — 2026-09-13

Cold dependency indexing now splits the ordered classpath into at most four contiguous
shards. Each shard has an independent Jandex indexer and the results are exposed as one
composite index. The cache stores length-prefixed shard indexes in one atomically published
file; shard order and classpath order remain deterministic. Parallelism is capped to avoid
turning first-time indexing into an unbounded memory spike.

Three native runs against the same 434-JAR classpath produced:

| Dependency cache | Runs | Median index time | Observed range | Peak RSS sampled | Cache size |
|---|---:|---:|---:|---:|---:|
| Miss, including cache creation | 3 | 7.45 s | 7.44–8.61 s | 522.67 MiB | 22 MiB |
| Hit | 3 | 2.97 s | 2.91–3.00 s | 392.97 MiB | 22 MiB |

Compared with the prior 11.079 s cache-miss baseline, the median first build is about
33% faster. Cache-hit latency and the 39.67 MB (39,674,792-byte) native binary size are
effectively unchanged.
