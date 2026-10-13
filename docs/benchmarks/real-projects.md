# Real-project correctness baseline

This suite checks manually selected navigation, exact method usages, impacted tests,
and change plans on real source trees. Expected owners, JVM descriptors and callers
come from source review and `javap`, not from Quill responses. It complements the
generated fixtures and agent-evaluation suite; it does not establish exhaustive
usages, impact completeness, model effectiveness, or project test outcomes.

## Reproduce

Use [the versioned manifest](cases/real-projects-v1.json). It pins two projects:

- Quill Java/Maven at `718cde5731ba0a7fb361f44c5828d87f24159344`.
- JetBrains Index MCP Plugin Kotlin/Gradle at
  `2983e24dc4ae3c464d8a8bcbe5a212cf72cd321b`.

Prepare disposable copies of those commits, compile production and test sources
using their normal builds, then index with the Quill executable under evaluation.
Do not benchmark an active MCP project's index. The first host run copied existing
compiled outputs instead of rebuilding the external project; its build provenance
is therefore weaker than a clean-checkout rebuild. SHA-256 digests of the class
files are recorded, and results describe these particular artifacts.

```bash
quill init --index-only --project /tmp/control/quill
quill init --index-only --project /tmp/control/jetbrains-index-mcp-plugin
python3 scripts/quill_real_project_baseline.py \
  --manifest docs/benchmarks/cases/real-projects-v1.json \
  --project quill-java=/tmp/control/quill \
  --project jetbrains-kotlin=/tmp/control/jetbrains-index-mcp-plugin \
  --quill /absolute/path/to/quill --samples 6 \
  --output target/benchmarks/real-projects.json
```

The runner uses existing indexes and never builds or initializes a project. Normal
MCP startup may refresh derived index state or upgrade an existing Maven extension,
so disposable copies remain necessary. It invokes no model and grants no permissions.
Git revisions and source evidence hashes must match the manifest. Git status,
evidence bytes and compiled class bytes are compared before/after the run. This
invariance check does not cover every ignored file or transient modification.

Each case runs once for its first-request observation and then five additional
times. Warm median/p95 exclude the first request; five samples are insufficient
for a general latency claim. Startup warmup may precede the first request, so this
is not a pure cold-compiler benchmark. Reports retain tool error counts.

`passed`, `failed`, and `unknown` are separate. Stale/missing freshness evidence,
pagination, incomplete traversal or test coverage, missing fields, and transport
errors cannot pass. Assertions use exact JSON types and collection projections;
negative controls require a complete response. A raw observed fact comparison is
retained separately for diagnosis even when verification is unknown. No missing
project or case disappears from the denominator. Exit code 0 requires all cases
to pass and inputs to remain unchanged.

## macOS arm64, 2026-10-12

[The compact host report](results/real-projects-v1-macos-2026-10-12.json) records
published native Quill **0.3.1**, its binary hash, the manifest hash, and all sample
statuses. Thirteen cases ran six times each. Both input snapshots remained unchanged.

| Project | Passed | Failed | Unknown |
| --- | ---: | ---: | ---: |
| Quill Java/Maven | 3 | 0 | 3 |
| JetBrains plugin Kotlin/Gradle | 3 | 0 | 4 |

The six exact-usage/test-impact cases passed. Position results lacked `_meta`, so
all five navigation cases are **unknown**, rather than confirmed successes or
failures. Their observed facts nevertheless expose useful diagnostic targets:

- Java's `isSourceFile(path.getFileName().toString())` remains ambiguous between
  the String and Path overloads.
- Kotlin's fully qualified call to the top-level `createMatcher(pattern)` selects
  the same-name private wrapper on `OptimizedSymbolSearch` instead. This project
  uses serialization with compiler 2.1.20; the pinned worker's plugin requirements
  restrict compiler semantics, so this is a fallback-navigation observation.
- The simple Java static call and two other Kotlin navigation positions match the
  manually expected declarations, but lack independent response freshness metadata.
- Both change plans include the expected primary class and test, but report an
  incomplete nested test plan; neither counts as a complete verified result.

Gradle discovery exposed a separate configuration-cache incompatibility:
`gradle.buildFinished` listeners and the discovery task's captured Project objects
are not cache compatible. Class discovery consumed its approximately 360-second
outer deadline. The subsequent classpath export failed in 13 seconds; Quill retained
a usable bytecode index but reported dependency indexing as degraded. This run is
not a healthy K2 latency baseline.

Follow-up priorities are configuration-cache-safe internal discovery, freshness
metadata on position responses, Java compiler-backed overload resolution, and
correct qualification in Kotlin fallback navigation. Rerun the same manifest after
each repair; keep the original report intact.
