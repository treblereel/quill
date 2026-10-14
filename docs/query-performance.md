# Read-query performance

Java member usages share one pinned Jdbi handle for selection, total counts and
response enrichment. Nested readers reuse that handle. Published indexes have a
four-reader pool with a five-second bounded wait; staging and writable databases
remain unpooled. Closed leases cannot affect subsequent borrowers. Returned readers
close outstanding statements and roll back unfinished transactions. Retirement
closes idle readers immediately and closes active readers when returned. Registry
resolution checks file identity so an atomic replacement at the same path starts a
new pool and graph snapshot.

Immutable generation graphs retain declared JVM members, unique/ambiguous owners,
reverse inheritance links, resolved references and deterministic dispatch evidence.
Cached dispatch payloads are serialized strings; response nodes are fresh objects.
Writable databases never retain generation snapshots. At most eight graph snapshots
and 100,000 member records are retained globally, with additional bounds on alias,
resolution, ancestry and dispatch entries. In-flight requests retain their own
snapshot when an entry is evicted. These are retention bounds, not a byte-level RSS
limit; a first graph load can still allocate more before an oversized entry is rejected.

Source coverage still enumerates live roots on each request, including generated
and ignored Java files. SHA-256 results may be reused only on explicitly supported
local Unix filesystems when device, inode identity, size, modification time and
nanosecond change time match. A restored modification time alone cannot produce a
cache hit. Other filesystems and coarse/unavailable change-time evidence use full
content reads. Concurrent misses share a content observation; files changing during
that observation are not cached as verified. File creation/deletion and symlink
boundaries are checked during live enumeration. Compiler reference ranges retain
their direct before/after SHA checks.

Compiled-output coverage discovers the layout once for a combined observation and
uses live file-tree attributes rather than repeated per-file stat calls. It remains
a timestamp observation, not proof of bytecode/source identity. Freshness checks do
not depend solely on TTLs or delivery of watcher events.

## Local diagnostics and comparisons

Set `QUILL_QUERY_METRICS=1` before starting Quill to emit content-free JSON counters
and stage timings to stderr. Diagnostics include physical connection opens, SQL
statements, source-content reads and timings for page/count lookup, dispatch,
enrichment, source/build freshness and serialization. Stage timings can overlap:
coverage includes its source/build freshness sub-stages. Instrumented query time
excludes transport and project resolution; compare it with end-to-end MCP latency.
No query text, SQL text, source paths or project names are logged by these counters.

`scripts/quill_query_benchmark.py` accepts an executable, project, exact JVM member,
page limits, warmup/sample counts and an explicit output path. It records native
end-to-end median/p95, stage counters and semantic response hashes. Outputs belong
outside the repository for private projects. Compiler generation and response
`_meta` are excluded from the comparison hash because those observations can vary.
Changing files, generation replacement, pagination and concurrent requests still
require correctness tests; a matching benchmark hash alone does not prove them.

Keep native checkpoints to isolate the effect of a change. Run measurements without
concurrent builds, on the same unchanged project index and with the same page sizes.
Small samples are exploratory; repeat final runs before treating p95 as a stable SLO.
Project measurements, file paths and project-specific baseline responses must not
be committed to the repository.
