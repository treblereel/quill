# Crysknife benchmark

This benchmark validates Quill against a real multi-module annotation-processing project.
Run it again with:

```bash
python3 scripts/quill_benchmark.py \
  --quill quill-app/target/quill \
  --project /path/to/crysknife
```

The benchmark temporarily replaces `.quill` and restores it afterwards. Dependency cache
files under Maven build output are retained, so two runs distinguish cache rebuild and hit.

## Baseline — 2026-09-14

- Quill commit: `8a64629`
- Project commit: `31a6a114515dcedc325bea26b82a76aaa6d146b8`
- Project worktree: dirty; Quill indexed the stable current overlay without modifying it
- Host: Apple arm64, 10 logical CPUs, 24 GiB RAM, macOS 26.6.2
- Native binary: 39,708,008 bytes
- Measurement: five warmups, ten sequential samples per tool

| Dependency cache | Index time | Peak RSS | SQLite size |
|---|---:|---:|---:|
| Rebuild | 3.512 s | 260.48 MiB | 4.77 MiB |
| Hit | 2.160 s | 247.11 MiB | 4.77 MiB |

Cache-hit MCP latency:

| Tool | p50 | p95 | Max | Errors |
|---|---:|---:|---:|---:|
| `get_overview` | 15.23 ms | 27.30 ms | 27.30 ms | 0 |
| `search_classes` | 0.80 ms | 0.96 ms | 0.96 ms | 0 |
| `find_git_hotspots` | 8.83 ms | 28.33 ms | 28.33 ms | 0 |
| `list_beans` | 2.30 ms | 3.31 ms | 3.31 ms | 0 |
| `get_recent_changes` | 1.91 ms | 2.09 ms | 2.09 ms | 0 |

| Concurrent requests | Total | Requests/s | p50 | p95 | Max | Errors |
|---:|---:|---:|---:|---:|---:|---:|
| 4 | 0.016 s | 256.13 | 3.33 ms | 15.62 ms | 15.62 ms | 0 |
| 16 | 0.046 s | 349.75 | 23.31 ms | 45.75 ms | 45.75 ms | 0 |
| 100 | 0.200 s | 500.90 | 94.26 ms | 194.59 ms | 199.64 ms | 0 |

All measured MCP calls completed without transport, timeout, busy, or tool errors.

## Real update, overlay, and quality recheck — 2026-09-14

- Quill commit: `e92664b`
- Project commit: `31a6a114515dcedc325bea26b82a76aaa6d146b8`
- Worktree: 18 existing changes, 13 structural; source state was not modified

`quill update --compile --force` completed successfully and left the structural snapshot
current: 1,890 current source classes, 281 beans, and 696,330 source tokens. The
informational refresh took 2.817 s with 209.03 MiB peak RSS. A 100-request burst completed
at 488.15 requests/s with zero errors. During a separate forced update, one MCP process
served 312 mixed requests with zero errors, observed the atomic `index_id` switch, and
reported all 18 live worktree changes including the modified navigation test POM.

The original Crysknife quality regressions now behave as intended:

- `BeanProcessorTask` has fan-in 2 and explicitly reports the `ApplicationProcessor`
  constructor edge.
- The annotation-processor service descriptor resolves as a current high-risk resource.
- Deleted `ScopedBeanGenerator` and old `TemplatedGenerator` paths are absent from default
  hotspots and appear only with `include_historical=true`.
- The removed `TemplatedGenerator` FQCN returns current and historical candidates instead
  of an unexplained lookup failure.
- `BeanManager` fan-in is explained as 497 classes: 482 generated and 15 source, with
  edge counts reported separately.

The update-state edge case found during this run is now covered by the external-build
refresh contract: after a successful Maven or Gradle build, Quill refreshes a structurally
stale compiled snapshot even when the bytecode fingerprint is unchanged. Quill itself does
not start the build. A regression test protects the early-return path that previously left
the snapshot stale.
