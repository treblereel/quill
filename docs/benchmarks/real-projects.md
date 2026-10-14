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
  returned `semantic_analysis.status=runtime_missing`: this index-only control run
  had not prepared the released worker's runtime. This is a metadata/lexical
  fallback observation, not a K2 correctness result. The project also uses
  serialization with compiler 2.1.20, which needs separate plugin compatibility
  checks after runtime preparation.
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

## Configuration-cache repair

Internal Gradle discovery now passes `--no-configuration-cache` for its temporary
export task. The project's properties and regular build invocation remain unchanged.
The corrected JVM discovery on the same disposable Kotlin project completed in
**12.562 seconds**, with one module and four output directories, and returned
`complete=true`. This measures discovery/classpath export, not native indexing or
K2 frontend latency; do not compare it directly with the full 379.534-second index.

Validation passed two `GradleProjectDiscoveryTest` cases and all four
`GradleMultiModuleIT` cases. The new integration regression first builds a reactor
with configuration cache enabled and problems configured to fail, then verifies
both read-only discovery and classpath export, cached reuse, a custom output path,
unchanged bytecode, and unchanged cache preferences. Its temporary project path
contains spaces and Unicode. Focused verification disables the aggregate coverage
gate; this is not a fresh full-reactor coverage result.

## Clean-build rerun on 0.4.0 — macOS arm64, 2026-10-13

Disposable copies of the same pinned commits were rebuilt from source. Quill used
`./mvnw clean test-compile -DskipTests`; the JetBrains plugin used
`./gradlew --no-daemon --no-configuration-cache clean classes testClasses`.
Full initialization installed the Maven build integration and prepared the K2
runtime; Quill was rebuilt and reindexed after Maven compiler capture. The released
0.4.0 macOS arm64 archive matched its published SHA-256 file. Six samples were
collected for each of the 13 original cases. Source, compiled outputs, and Git
status remained unchanged during each measurement.

| Binary | Java/Maven passed / failed / unknown | Kotlin/Gradle passed / failed / unknown |
| --- | --- | --- |
| [Published 0.4.0](results/real-projects-v1-macos-2026-10-13-v0.4.0.json) | 5 / 0 / 1 | 5 / 1 / 1 |
| [Native candidate with qualified fallback fix](results/real-projects-v1-macos-2026-10-13-qualified-fallback.json) | 5 / 0 / 1 | 6 / 0 / 1 |

The published Kotlin binary resolves the fully qualified
`com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.createMatcher(pattern)`
to the same-name method in the current class. Its semantic backend explicitly
reports `compiler_plugins_disabled`: the project uses Kotlin 2.1.20 with
serialization, while the bundled K2 worker is 2.4.21. This is a lexical-fallback
error, not a failed K2 attribution. The candidate uses the explicit package in
source text to select the indexed top-level function. A focused test also checks
that an ordinary receiver call retains its existing fallback behavior.

Both remaining `unknown` cases are change plans. Their expected primary classes
and tests appear in the response, but the nested affected-test list exceeds the
tool's 100-result limit and is correctly marked incomplete. They are not counted
as passes. The candidate's benchmark exit status therefore remains nonzero even
though all independently checkable facts pass. Complete paginated change-plan
evidence remains follow-up work.

## Kotlin 2.1.20 worker check — macOS arm64, 2026-10-13

The next JVM candidate embeds a separate thin 2.1.20 worker. On the same disposable
JetBrains Gradle project, `quill init` read `compilerVersion=2.1.20-release-217`
from the captured Kotlin task settings and prepared the 2.1.20 runtime. With
`allowPlugins=true` in the disposable project's `.quill/kotlin-main.properties`,
the first `get_symbol_at_position` for `isExcludedPath(path)` returned
`semantic_analysis.status=resolved`, `compiler_version=2.1.20`, and the expected
`(Ljava/lang/String;)Z` descriptor. This confirms that the serialization plugin
and project compiler can run through FIR together. The first request took 11.4 s;
startup and frontend costs are included, so this is not a warm latency estimate.

A one-sample run of the pinned manifest returned Java 5/0/1 and Kotlin 6/0/1
passed/failed/unknown, with source, bytecode and Git status unchanged. The two
unknown change plans remain limited by the 100-result affected-test page. This
single-sample check establishes correctness, not a latency comparison with the
six-sample native runs above.

The macOS arm64 native candidate was built with `./mvnw -pl quill-app -am
-DskipTests package -Pnative`. Its executable is 69,497,560 bytes, 16,528 bytes
larger than the preceding single-worker candidate from the same checkout. Native
`quill init` prepared K2 2.1.20, and a native position query with the serialization
plugin enabled returned `semantic_analysis.status=resolved` and the same descriptor.

[Six samples per case](results/real-projects-v1-macos-2026-10-13-k2-2.1.20-native.json)
returned Java 5/0/1 and Kotlin 6/0/1 passed/failed/unknown; both input snapshots
remained unchanged. The first Kotlin position request took 13.4 s including startup,
while its five subsequent requests had a 199 ms median and 213 ms p95. This
measures one project and host with compiler plugins enabled, not a general K2
latency guarantee. Both unknown change plans still exceed the affected-test page.

## Affected-test continuation follow-up

The next candidate adds `test_plan.next_page_request` to `plan_change` when its nested
affected-test list has another page. The request names `find_impacted_tests` with the
same targets, traversal settings, and the next offset. `change_session` preserves this
request in both summary and full plan views. The real-project runner now follows all
pages before scoring a plan, checking index identity, target identity, total count,
coverage, monotonic offsets, and duplicate test identities. A failed or changed page
remains `unknown`; earlier published reports are unchanged. A new host run is needed
before claiming the two real-project change plans pass.
