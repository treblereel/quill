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
throughput measurements. This initial external-worker implementation started a JVM
for every request. The persistent-worker measurements below supersede that lifecycle.

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


## Persistent K2 worker

The persistent-worker implementation in commit `e92aa63` reuses one compiler JVM
for an MCP session. Requests use bounded, length-prefixed JSON frames and are serialized.
At that stage, analysis still rebuilt FIR from
current source files and captured classpaths on every request; analysis results were
not cached. The module-snapshot implementation below supersedes that behavior. Runtime hashes are verified on worker startup, while file attributes and
the manifest identity are checked between requests. Changed/missing runtime files,
crashes, malformed responses, timeout and cancellation discard the process. A following
request starts a fresh worker; MCP EOF/shutdown kills and reaps it.

The native executable is **67,199,992 bytes**, an increase of **16,544 bytes (0.025%)**
over the initial external-worker build. Native compilation took 83 seconds with
2.86 GB peak build RSS on the same macOS ARM64/GraalVM 25.0.1 host.

An additional native MCP probe used the compiled Kotlin Maven fixture with five
source files plus a generated caller. Each Java version had its own MCP session:
one cold query, 20 requests alternating the caller's Int/String overload, then 20
queries against unchanged source. All descriptors were verified. The child PID stayed
the same across all 41 requests and no child remained after MCP shutdown.

| Worker JVM | Cold MCP query | Changed-source median / p95 | Unchanged-source median | Worker RSS after cold / after 40 warm queries |
| --- | ---: | ---: | ---: | ---: |
| Zulu 17.0.16 | 3,036 ms | 203 / 393 ms | 220 ms | 253 / 504 MB |
| GraalVM 25.0.1 | 2,805 ms | 118 / 216 ms | 94 ms | 302 / 577 MB |

These are local smoke measurements, not latency guarantees. An earlier repetition
measured changed-source medians of 113 ms on Java 17 and 120 ms on Java 25, with
cold queries of 1,695/1,792 ms and worker RSS after 40 queries of 626/452 MB.
Another full native E2E
run on Java 17 measured a changed-source median of 357 ms and p95 of 570 ms; other
host workloads were active. The small single-source JVM unit probe measured a median
of 22 ms. These different workloads should not be compared as identical benchmarks.
A repeat of the full E2E probe measured 180 ms for changed-source queries and 99 ms
for unchanged-source queries. The reproducible E2E script now reports both changed- and unchanged-source samples
from a single MCP session, in addition to the standalone worker correctness probes.

The 512 MiB limit applies to the JVM heap, not total RSS. Keeping compiler classes,
JDK/JAR infrastructure and JIT state alive trades resident memory for warm latency.
The first semantic request still pays JVM/compiler startup. Rebuilding FIR for a full
module remains the main analysis work; this change does not claim incremental FIR
reuse or eliminate the cold query.

Validation: 664 unit tests, 28 focused integration tests, 20 native standalone semantic
cases, and the repeated native MCP probes on Java 17 and 25 passed. New cases cover
multiple framed requests, clean EOF, partial/invalid frames, oversized request/response,
malformed JSON, wrong backend, crashes, timeout, interruption, concurrent callers,
stderr flooding, changed/deleted runtime artifacts, runtime selection changes,
source edits, project switches, shutdown during a blocked read, and shutdown racing
with cold runtime preparation. Existing
single-request worker behavior and Java 17 bytecode compatibility are preserved.

## Reusing a module FIR snapshot

The worker now retains resolved FIR and its compiler environment for one active
module/source set. Different files and offsets reuse that snapshot while compiler
inputs have identical content. Responses expose `analysis_reused` and
`analysis_generation`, so the probes verify reuse independently of elapsed time.
Switching modules or source sets disposes the previous snapshot. This is whole-module
snapshot reuse; editing a compiler input still rebuilds the whole module frontend.

The fingerprint covers Kotlin sources, Java source roots, ordered classpath entries,
compiled output contents, the module/source-set identity and JDK inputs. Source bytes
are always hashed. Dependency/JDK hashes and output hashes have separate bounded
caches (2,048 and 8,192 entries), using file identity, size, modification time and Unix
change time to detect replacement and same-size edits with restored modification time.
Filesystems without Unix change time hash bytes on each request; their performance
has not been measured. These caches retain hashes, not archive bytes. Inputs are checked
before and after analysis: a detected concurrent change clears FIR and returns
`inputs_changed`, allowing metadata fallback. Unchanged compiler errors are also cached,
without retaining the failed compiler environment; repaired inputs trigger analysis.

The final native executable is **67,199,992 bytes**: no size increase over the previous
persistent-worker build. Native compilation took 49.7 seconds with 2.80 GB peak build
RSS on the same macOS ARM64/GraalVM 25.0.1 host.

The same six-source Maven fixture probe performed one cold query, 20 source edits,
and 20 unchanged queries per JVM. Every edited query produced a new FIR generation;
all unchanged queries reused the final generation. Both sessions retained one worker
PID and reaped it on MCP shutdown.

| Worker JVM | Cold MCP query | Changed-source median / p95 | Unchanged-source median | Worker RSS after cold / after 40 warm queries |
| --- | ---: | ---: | ---: | ---: |
| Zulu 17.0.16 | 3,644 ms | 173 / 266 ms | 7.3 ms | 335 / 476 MB |
| GraalVM 25.0.1 | 2,478 ms | 174 / 240 ms | 4.8 ms | 332 / 558 MB |

A separate full native E2E run measured 1,953 ms cold, 92 / 154 ms changed-source
median / p95, and 3.5 ms unchanged-source median. A small JVM unit probe measured
23.3 ms changed-source and 1.8 ms reused. These local measurements vary with workload;
they do not establish latency guarantees for large projects. The 512 MiB worker heap
limit does not bound total RSS. Cold startup, whole-module rebuilds after edits and
eviction when switching modules remain costs for subsequent optimization.

Validation passed: 675 unit tests, 28 focused integration tests, 20 native standalone
semantic cases, and native MCP reuse/lifecycle probes on Java 17 and 25. The affected
Kotlin tests and integration tests were rerun after splitting the hash caches, followed
by rebuilding and probing the final native binary. New corner cases cover different
positions/files, dependent source edits, source addition/deletion, unchanged touches,
Java roots, real class/JAR replacement with restored timestamps, classpath ordering,
module/source-set changes, JDK identity/content, symlinks and cycles, cached errors and
repair, concurrent callers, explicit disposal, and changes racing a lookup.

## Stage profiling and source fingerprint overhead

The worker protocol accepts `profile_analysis: true` to attach an
`analysis_timings_ms` object. Its `capture_before`, `dispose`, `configuration`,
`frontend`, `lookup`, `capture_after` and `total` values measure this request only.
Stages that did not execute are omitted: a reused snapshot has no configuration or
frontend timing, and a reused analysis error has no lookup timing. Detected input
races also include the work measured before returning `inputs_changed`. Normal
requests omit timings, and toggling profiling does not change snapshot identity.
The public MCP tool does not expose this benchmark flag.

Reproduce the worker-only scaling probe:

```bash
./mvnw -q -pl quill-kotlin-semantic -am package -DskipTests
python3 scripts/quill_kotlin_stage_benchmark.py \
  --java-home /path/to/jdk17 --files 10 100 500 --samples 10 \
  --output /tmp/quill-k2-stages.json
```

Each module contains the requested generated file count plus a definition and caller
file. Generated files contain five functions with explicit Int return types and
cross-file calls. Ten edits alternate an inferred Int/Boolean return in the definition
file; each query checks the dependent caller's descriptor and a new FIR generation.
Ten unchanged requests then verify reuse. Each module starts a fresh 512 MiB worker.
Worker roundtrip includes framing and response serialization, and the cold roundtrip
also includes JVM launch; stage timings exclude those costs. These are synthetic local
measurements on the same macOS host, not full MCP request measurements.

| Worker JVM | Source files | Changed roundtrip median | Frontend median | Configuration median | Unchanged roundtrip median |
| --- | ---: | ---: | ---: | ---: | ---: |
| Zulu 17.0.16 | 12 | 68 ms | 65 ms | 0.4 ms | 1.1 ms |
| Zulu 17.0.16 | 102 | 134 ms | 127 ms | 0.6 ms | 3.7 ms |
| Zulu 17.0.16 | 502 | 435 ms | 408 ms | 1.5 ms | 18.6 ms |
| GraalVM 25.0.1 | 12 | 93 ms | 88 ms | 0.5 ms | 1.0 ms |
| GraalVM 25.0.1 | 102 | 245 ms | 237 ms | 1.0 ms | 3.3 ms |
| GraalVM 25.0.1 | 502 | 473 ms | 445 ms | 1.4 ms | 19.3 ms |

Profiling exposed unnecessary metadata reads for source files: their content was
always hashed, yet the code first fetched basic attributes and Unix change time used
only for memoized binary hashes. Sources now bypass those metadata reads and still
hash their bytes before and after analysis. On Java 17, unchanged worker roundtrip
medians before/after were 2.9/1.1 ms (12 files), 5.8/3.7 ms (102), and 47.2/18.6 ms
(502). The changed-source frontend remained the dominant cost: 396 ms before and
408 ms after in the 502-file module. Independent medians and host variation prevent
attributing every timing difference to this small change.

Cold worker roundtrips were 1.6–3.3 seconds in the final probes. Configuration reuse
would save little on changed-source queries in these fixtures; avoiding whole-module
frontend work is the next substantial optimization to investigate. An initial
500-file chain with inferred return types exhausted the K2 compiler stack on Java 17;
the reported scaling workload uses explicit return types. Deep return-type inference
remains a separate compiler stress case.

Validation: 31 focused Java/Kotlin tests passed, including profiling on/off without
invalidation, per-request stage presence, invalid positions, cached errors and repair,
and racing inputs. All 180 generated-module queries across the baseline Java 17 and
final Java 17/25 probes checked descriptors and FIR generations (plus nine cold queries).
The rebuilt native executable remains 67,199,992 bytes; native MCP smoke probes on
Java 17 and 25 verified descriptor changes, FIR reuse, one worker PID per session and
worker termination on MCP shutdown. Native compilation took 67 seconds with 2.82 GB
peak build RSS.
