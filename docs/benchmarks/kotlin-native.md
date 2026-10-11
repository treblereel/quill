# Kotlin runtime footprint — 2026-10-11

## Embedded compiler (`4e53715`)

Measured on macOS arm64, GraalVM CE 25.0.1, with `-Os` and 10 build threads.
The baseline is commit `72ad849`; the embedded-compiler candidate is commit `4e53715`.
Both images were built with `./mvnw -q -pl quill-app -am package -Pnative`.
These measurements describe this host and fixtures, not other platforms or large projects.

| Artifact | Bytes | Decimal MB |
|---|---:|---:|
| Original native Quill | 49,643,864 | 49.64 |
| Native Quill with mandatory K2 | 117,424,552 | 117.42 |
| Growth | 67,780,688 | 67.78 (+136.53%) |
| Original Quill plus separate K2 worker JAR | 118,037,506 | 118.04 |

The combined distribution is 612,954 bytes smaller than the original executable plus
its 68,393,642-byte optional worker JAR. The increase is substantial for Java-only users,
but roughly preserves the total distribution size for users who already installed K2.
Native generation grew from 42.4 seconds / 2.63 GB peak RSS to 154 seconds / 4.72 GB.
The candidate contains 45.88 MB of code and a 70.25 MB image heap; the Kotlin compiler
contributes 21.37 MB of code, with another 2.43 MB from kotlin-reflect.

The frontend runs in a child copy of the same executable with a 512 MiB heap cap and
30-second timeout. It requires captured project dependencies and a project JDK, normally
through `JAVA_HOME`; it executes no project compilation or compiler plugins.

## Runtime and correctness

Six `--version` samples, excluding the first, had medians of 19.51 ms for the baseline
and 19.90 ms for the candidate. This is a startup smoke measurement, not an indexing benchmark.
Twenty small semantic cases passed, including overloads, context parameters, smart casts,
shadowing, boxing, arrays, suspend, Java sources/JDK types, annotations, nested classes,
properties, objects, invalid code and literal positions. After the first image launch,
case times ranged from 42–133 ms, with a median of 50 ms. The largest child RSS across these
probes was 135,102,464 bytes (128.84 MiB); the 512 MiB cap limits heap, not total RSS.

The compiled Kotlin fixture also exercised indexing and the complete native MCP path:
`typedNavigation(1)` was ambiguous with medium confidence when captured compiler evidence
was unavailable, and resolved to `(I)I` with high confidence when K2 was available.
Those single MCP requests took 111 ms and 275 ms respectively. This comparison uses the
same candidate executable and index, changing availability of the captured classpath.
The original image exposed missing Kotlin record reflection during native indexing;
this change fixes that separately from the compiler integration.

Reproduce these historical checks with the script from commit `4e53715` against the compiled fixture:

```bash
./mvnw -q -f tests/kotlin-maven/pom.xml compile
python3 scripts/quill_kotlin_native_e2e.py \
  --fixture tests/kotlin-maven \
  --baseline /path/to/original/quill \
  --output /tmp/quill-kotlin-native.json
```

The release workflow runs these checks for each platform. Native integration additionally
covers Kotlin indexing, Kotlin queries and MCP stdio; JVM tests cover the automatic worker
launcher, missing dependencies, test-source classpaths and compiler-error fallback.

## Alternative: compiler from the Maven cache

A Kotlin Maven build downloads its compiler dependencies; a standalone `kotlinc` command
need not be installed. Plugin dependency resolution returned the compiler and dependencies
for our fixture without compiling the project:

```bash
./mvnw -q -f tests/kotlin-maven/pom.xml \
  org.apache.maven.plugins:maven-dependency-plugin:3.9.0:resolve-plugins \
  -DincludeGroupIds=org.jetbrains.kotlin \
  -DincludeArtifactIds=kotlin-maven-plugin \
  -DoutputAbsoluteArtifactFilename=true \
  -DoutputFile=/tmp/kotlin-plugin-artifacts.txt
```

A prototype successfully ran the 28,497-byte thin Quill worker with those resolved JARs
and its Jackson dependencies. That initial experiment did not implement automatic download.
Our worker uses internal K2 2.4.21 APIs,
so arbitrary project compiler versions cannot replace that version safely. Maven resolution
must account for the effective plugin version, dependencies and custom local repository.

Bundled native K2 justifies the size when fast Kotlin queries and a single executable are
the priority. A small native Quill with an embedded thin JVM worker and automatic compiler
resolution is a credible alternative for minimizing the main download. It would still
need Java and a compatible compiler cache, and would pay JVM startup for each child process.

## Shared Maven Resolver runtime

The following implementation removes the compiler from Quill's native executable and
embeds the thin worker as a binary resource. Normal initialization prepares the pinned
compiler with Maven Resolver for Maven and Gradle projects. Queries read the validated
manifest without network access. Runtime JARs use the Maven local repository; worker and
manifest files use the shared Quill cache. Java 17 or newer is required for analysis.

Measured with the same macOS arm64 host, GraalVM CE 25.0.1, `-Os` and 10 build threads:

| Artifact | Bytes | Decimal MB |
|---|---:|---:|
| Native Quill with Resolver and thin worker | 67,183,448 | 67.18 |
| Reduction from embedded K2 | 50,241,104 | 50.24 (42.79%) |
| Growth over original native Quill | 17,539,584 | 17.54 (35.33%) |
| Resolved runtime JARs (11 artifacts, empty repository) | 68,346,015 | 68.35 |

The smaller executable does not eliminate the compiler's disk cost. A cold installation
needs about 135.53 MB for the executable and runtime JARs, plus the extracted worker,
Maven descriptors/checksums and an existing JDK. Existing compatible Maven artifacts are
reused and the runtime is shared across projects. A project using another Kotlin version
does not replace Quill's pinned 2.4.21 compiler.

Native generation took 52.8 seconds with 2.83 GB peak RSS. The image contains 25.26 MB
of code and a 41.09 MB heap. A clean HTTPS download was verified using the native
executable with an empty temporary local repository.

The native end-to-end probe passed all 20 semantic cases on JDK 17, Maven and Gradle
initialization, metadata fallback and high-confidence compiler resolution through MCP.
Warm `--version` startup had an 18.28 ms median. The 20 fresh JVM worker invocations had
a 1.287-second median; the fixture's compiler-backed MCP request took 1.835 seconds.
These are small smoke probes run alongside integration verification, not controlled
throughput measurements. Unlike the embedded compiler, this implementation pays JVM
startup for each request; a reusable worker would need a separate lifecycle design.

Validation also passed 654 unit tests and 28 integration tests. New coverage includes
transitive runtime resolution, offline misses and reuse, thread/process locking,
checksum failures, damaged manifests/artifacts, mirrors, HTTP authentication, paths with
spaces, Java-version parsing, Java 17 bytecode, initialization without compiled Kotlin,
and preserving the index when runtime preparation fails. `--index-only` and Java-only
initialization do not install the compiler.

Reproduce the current native checks:

```bash
./mvnw -q -f tests/kotlin-maven/pom.xml compile
./mvnw -q -pl quill-app -am package -Pnative -DskipTests -DskipITs=true
python3 scripts/quill_kotlin_native_e2e.py \
  --fixture tests/kotlin-maven \
  --worker-java-home /path/to/jdk17 \
  --output /tmp/quill-kotlin-resolver-native.json
```
