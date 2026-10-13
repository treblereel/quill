# Quill documentation

The root [README](../README.md) is the current user and operator reference. It covers installation,
indexing, every public CLI command, the complete MCP tool catalog, client configuration, index
freshness, supported build systems, and local builds.

Current supporting documents:

- [Real-project correctness baseline](benchmarks/real-projects.md) — pinned Java/Maven
  and Kotlin/Gradle facts, independent expectations, and explicit incomplete results.
- [Java compiler navigation probe](../experiments/java-semantic-navigation/README.md) —
  standalone JDK-backed overload resolution and its production integration gates.
- [Release guide](releasing.md) — release artifacts, signing, notarization, checksums, and
  attestations.
- [Paired task evaluation](benchmarks/task-evaluation.md) — reproducible agent-effectiveness
  benchmark protocol.
- [Kotlin native footprint](benchmarks/kotlin-native.md) — embedded and resolved K2 size, runtime checks,
  and the Maven compiler cache alternative.
- [CaseHub Engine benchmark](benchmarks/casehub-engine.md) and
  [Crysknife benchmark](benchmarks/crysknife.md) — dated measurement records. These describe the
  named commits and environments; they are evidence, not current performance guarantees.

When behavior changes, update the root README in the same change. In particular, keep its command
table aligned with picocli command definitions and its MCP list aligned with the `@Tool` methods in
`QuillTools`.

- [Java compiler navigation](java-semantic-navigation.md): captured JDK settings, worker lifecycle and supported inputs.

- [API compatibility review](api-compatibility.md): declaration deltas and baseline static impact in `compare_index`.

- [HTTP endpoint analysis](http-endpoints.md): composed Spring mappings, custom JAX-RS methods, and static-analysis limits.
