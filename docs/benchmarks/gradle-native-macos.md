# Packaged Gradle/native validation — 2026-10-12

The macOS arm64 native build supports declarative Gradle Kotlin/Java source filters,
file-tree and individual-file task overrides, source-set snapshot reuse and background
warmup. This run also corrected distribution packaging and discovery of task source roots.

The shared selector is a 10,139-byte classifier JAR containing three
Java classes. The application consumes it without the compiler's transitive dependencies.
The thin K2 worker also contains the selector. A distribution integration test executes
the helper using an isolated classloader and confirms the compiler is absent from the
shaded application. Keeping dependency classes out of application output directories
preserves their module ownership in Quill's own index.

| Native artifact | Bytes | Decimal MB |
|---|---:|---:|
| Previous Java-worker image | 69,249,432 | 69.25 |
| Current validated image | 69,481,032 | 69.48 |
| Growth | 231,600 | 0.2316 (+0.33%) |

The baseline SHA matches the historical Java-native report, with code equivalent to
`ddb9a4a`. It predates the subsequent API/HTTP and Gradle work; this delta includes those
changes and cannot attribute size to source filters alone. The candidate was built from
`626c523` plus the runtime patch whose paths and SHA are recorded in the JSON report.
GraalVM CE 25.0.1 used `-Os`; the final reactor build took approximately 73 seconds and
native-image reported 2.84 GB peak RSS while building.

The small Gradle fixtures measured 86.8–100.1 MiB RSS for the native
server and 218.5–254.0 MiB for its K2 child across post-request
snapshots. The Java probe on Quill records both Java and Kotlin children when startup
K2 warmup is active. These are process snapshots, not heap sizes or long-session peaks.
The small executable delta is justified by the added analysis coverage; worker memory
remains a larger cost than the increase in executable size.

Validation covered 1039 unit tests and 11 integration tests before native findings,
then 44 focused unit tests and 10 integration tests after the final fixes. The final
image passed 25 Kotlin language cases with JDK 25 and another 25 with JDK 17, metadata
fallback, prepared K2, startup/background reuse and clean MCP EOF shutdown. Gradle
fixtures made 18 native MCP requests across configured roots, relocated file trees and
individual-file overrides; another six requests checked the packaged JVM JAR.
They verify `main ↔ integrationTest` reuse, newly selected files, ignored invalid files,
Unicode paths and worker shutdown. Predicate guards, source removal, restored mtime,
pattern intersections, case sensitivity, limits, path escapes and symlink cycles are
covered by the focused Java/JUnit suites.

The live compiler can identify newly added declarations before the bytecode index has
them. Such requests return `semantic_target_not_indexed` and a compiler descriptor;
rebuilding and refreshing the index provides an indexed selected symbol. Predicate
filters are evaluated by Gradle at capture time; guarded source changes request a new
capture. Inputs outside captured roots and arbitrary external predicate state require
manual recapture.

On the Maven Kotlin fixture, repeated-query medians were
5.06 ms (JDK 25) and
5.60 ms (JDK 17).
The Java real-project probe had a 43.70 ms repeated-query median.
The temporary Java mutation fixture changes overload selection while restoring file size
and mtime, verifies a newer reused generation after background work, then restores the
source. Its delays are allowances, not measurements of exact warmup readiness. These
small probes establish correctness and reuse, not a broad performance guarantee.

Raw results, artifact hashes, memory snapshots and the verified change-session receipt
are in [the JSON report](results/gradle-native-macos-2026-10-12.json).

Reproduce after compiling/indexing the fixtures:

```bash
./mvnw -f tests/kotlin-maven/pom.xml -DskipTests package
./mvnw -pl quill-app -am package -DskipTests -Pnative -DskipITs=true
python3 scripts/quill_kotlin_native_e2e.py --fixture tests/kotlin-maven --output /tmp/kotlin.json
python3 scripts/quill_gradle_native_e2e.py --output /tmp/gradle.json
python3 scripts/quill_gradle_native_e2e.py --task-override --output /tmp/override.json
python3 scripts/quill_gradle_native_e2e.py --task-override --explicit-file --output /tmp/explicit.json
python3 scripts/quill_gradle_native_e2e.py --java-jar quill-app/target/quill-app-0.4.0-SNAPSHOT-all.jar --task-override --explicit-file --output /tmp/jvm.json
python3 scripts/quill_java_native_e2e.py --project "$PWD" --output /tmp/java.json
```

Use `--worker-java-home` for the Kotlin JDK 17 run. The Java mutation check uses
`--exercise-edit --project <compiled-and-indexed-temporary-Quill-project>`; the script
refuses to edit a project outside the system temporary locations and restores content
and timestamps in `finally`. The run here used a temporary archive of `b8c34f4`.
