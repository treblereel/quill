# Quill documentation

The root [README](../README.md) is the current user and operator reference. It covers installation,
indexing, every public CLI command, the complete MCP tool catalog, client configuration, index
freshness, supported build systems, and local builds.

Current supporting documents:

- [Release guide](releasing.md) — release artifacts, signing, notarization, checksums, and
  attestations.
- [Paired task evaluation](benchmarks/task-evaluation.md) — reproducible agent-effectiveness
  benchmark protocol.
- [CaseHub Engine benchmark](benchmarks/casehub-engine.md) and
  [Crysknife benchmark](benchmarks/crysknife.md) — dated measurement records. These describe the
  named commits and environments; they are evidence, not current performance guarantees.

The files under `superpowers/specs/` and `superpowers/plans/` are historical design and
implementation records. They intentionally preserve names, architecture decisions, schemas, and
tool counts from the date they were written. Do not use them as the current product reference.

When behavior changes, update the root README in the same change. In particular, keep its command
table aligned with picocli command definitions and its MCP list aligned with the `@Tool` methods in
`QuillTools`.
