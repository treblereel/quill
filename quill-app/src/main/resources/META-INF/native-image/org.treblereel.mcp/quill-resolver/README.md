# Maven Resolver native metadata

Captured with GraalVM CE 25.0.1's native-image agent using Quill's JVM distribution:

- Maven and Gradle initialization, followed by the 20 cases and MCP checks in
  `scripts/quill_kotlin_native_e2e.py --java-jar ... --agent-output ... --fixture tests/kotlin-maven`.
- A second `quill init` against a copied compiled Kotlin fixture with an empty
  `quill.kotlin.localRepository` and `quill.kotlin.cache`, to exercise HTTPS resolution
  from Maven Central rather than only cached artifacts.

The agent instruments Quill; the external compiler worker runs on a normal JVM.
Merge both runs into one agent output directory and copy `reachability-metadata.json`
here. Verify the resulting native executable with the same end-to-end script.
