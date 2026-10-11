# Incremental Kotlin analysis investigation — 2026-10-11

## Decision

Keep full module invalidation in the production CLI frontend. Investigate a separate
lazy Analysis API backend before promising incremental FIR. The current compiler
does not expose a switch which safely updates our retained snapshot after source
edits. Startup and background warmup improve when work happens; they do not reduce
the amount of frontend work required by an edit.

The first separate performance priority on this macOS host is filesystem event
delivery: the [save-to-ready benchmark](kotlin-native.md#save-to-background-ready-latency)
measured roughly 1.8–2.0 seconds, while frontend medians were 32–153 ms for its
one-function-per-file fixtures. The earlier five-function-per-file 502-source
workload measured 408–445 ms in the frontend. These workloads differ; their timings
must not be presented as a single baseline.

## Evidence from the pinned runtime

`K2Analyzer.build` creates a configuration and runs
`JvmFrontendPipelinePhase.executePhase`, storing the returned FIR files/session and
compiler Disposable as one snapshot. A changed compiler-input fingerprint disposes
that snapshot and starts this pipeline again.

Inspecting the resolved Kotlin 2.4.21 compiler with `javap` shows that
`IncrementalCompilationContext` contains a precompiled-binary package-part provider
and its file scope. It is not a changed-file update API for an existing FIR session.
The compiler's `FirDummyCompilerLazyDeclarationResolver` implements its lazy resolve
methods as no-ops. The built semantic all.jar contains neither the Analysis API nor
its low-level FIR backend. These observations describe the artifacts we ship, not
every way internal compiler APIs could be extended.

Reproduce the API inspection after packaging the worker:

```bash
javap -classpath quill-kotlin-semantic/target/quill-kotlin-semantic-0.1-SNAPSHOT-all.jar \
  org.jetbrains.kotlin.fir.session.IncrementalCompilationContext
javap -c -classpath quill-kotlin-semantic/target/quill-kotlin-semantic-0.1-SNAPSHOT-all.jar \
  org.jetbrains.kotlin.fir.resolve.transformers.FirDummyCompilerLazyDeclarationResolver
```

## Options

| Approach | Expected benefit | Work and correctness constraints |
| --- | --- | --- |
| Retain compiler configuration/environment | Avoid setup work | Configuration was about 0.5–1.5 ms in measured warm runs; it does not avoid frontend resolution. Environment/PSI caches must still observe edits. |
| Analyze changed files against compiled outputs | Reduce source scope | Existing outputs can contain old signatures, deleted declarations and stale inferred types. The incremental compilation context consumes binaries; correct fresh replacements would require extra compilation/cache machinery. |
| Reuse old FIR based only on ABI/dependency edges | Skip apparently unaffected work | A body edit can change a position's overload target without changing the enclosing function's signature. Newly added overloads can affect calls which have no old dependency edge to that file. |
| Analysis API with lazy FIR and modification tracking | Resolve queried declarations and invalidate affected state | Requires an additional pinned artifact graph, project/PSI services, Kotlin/Java declaration providers, modification events, library invalidation and symbol lifetime management. Actual latency, memory and download growth remain unmeasured. |

JetBrains documents [lazy resolution and cache invalidation](https://kotlin.github.io/analysis-api/index_md.html)
as Analysis API capabilities. Its [platform interface](https://github.com/JetBrains/kotlin/blob/master/analysis/analysis-api-platform-interface/README.md)
requires project structure, declaration providers and modification notifications;
the interface remains experimental. The same document identifies Standalone's
lifetime token with static code, so mutable, long-lived analysis needs investigation
rather than an assumption that a Standalone session already handles our edit loop.
Its [lifetime rules](https://kotlin.github.io/analysis-api/fundamentals.html) also prohibit
retaining API symbols/types outside their valid analysis scope. These upstream docs
are current documentation, not confirmation that a compatible artifact set for our
pinned compiler is already available in Maven Central.

## Correctness reference and prototype criteria

Two new `K2SnapshotTest` regressions preserve full-analysis reference behavior:

- `run(): Any = pick(1)` changes to `pick("s")`: the enclosing JVM signature stays
  the same, but navigation must switch from `(I)I` to the String overload.
- Adding `pick(String?): Boolean` changes an unedited `pick(null)` call which
  previously selected `pick(Any?): Int`. Removing the new file must restore the
  original owner and descriptor.

A prototype should keep the current worker protocol, pinned runtime preparation,
one active module, heap/timeout bounds and the existing frontend as a correctness
reference. First verify compatible artifact versions and Java 17 support; then
exercise repeated edits through a single session without generating project bytecode
or loading project compiler plugins. Compare descriptors/status/owners against fresh
full analysis after every edit, including failed analysis followed by repair.

Required cases include changed bodies with stable signatures, inferred return-type
propagation, additions/deletions and new overloads, wildcard imports, extension
resolution, shadowing, smart casts, type aliases/generics, Java-source changes,
JAR/output replacement with restored timestamps, module/source-set switches, edits
racing analysis and shutdown. Stable on-disk ABI alone is insufficient evidence.

Measure 12/102/502-source workloads, independent and dependent edits, cold queries,
changed/unchanged query median and p95, save-to-ready time, long-session RSS and heap,
runtime artifact bytes and native executable bytes. Do not promote the prototype
unless it preserves the reference results, reduces changed-source frontend work,
and documents its measured memory/download tradeoff. No production incremental
backend or speedup is claimed by this investigation.

Validation: all 13 `K2SnapshotTest` cases passed on Java 17.0.2, including the two
new overload regressions and their cached/rebuilt FIR generation checks. The last
production revision separately passed 702 unit tests, 28 integration tests and the
native semantic/startup probes described in the footprint benchmark; this final
investigation changes documentation and tests only.
