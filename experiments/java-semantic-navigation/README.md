# Isolated Java compiler navigation probe

This experiment resolves live Java positions through public JDK compiler APIs
(`JavacTask`, `Trees`, `Elements`, `Types`). It is outside the production reactor
and has no runtime dependencies. It does not change the native binary or add a
production Java worker by itself. Its frontend has now been promoted into
[`quill-java-semantic`](../../quill-java-semantic); see the
[production integration](../../docs/java-semantic-navigation.md).

The frontend parses and attributes sources; it never generates bytecode, runs
project static initializers, or executes annotation processors (`-proc:none`).
One attributed model is retained. Source additions, deletions, equal-size edits
with restored mtime, and classpath byte changes invalidate the whole model.
Inputs are content-checked before/after resolution; sources handed to javac are
immutable text snapshots. Classpath hashing streams JAR bytes rather than
allocating a JAR-sized byte array. This is full-model invalidation, not incremental
Java analysis. Calls are serialized and old file managers are closed on replacement.

Positions are UTF-16 offsets, matching Java strings. The resolver handles identifier
and qualified member references, method references, and constructor calls. It
returns erased JVM descriptors, including a member constructor's enclosing-instance
parameter. Anonymous/local constructors can capture synthetic parameters not
available as a reliable frontend descriptor; they explicitly remain unknown.
Local variables and parameters return `local` rather than an unrelated indexed field.
Analysis errors never reuse a previous successful symbol as a current result.

## Reproduce

```bash
JAVA_HOME=/path/to/jdk17 ./mvnw -f experiments/java-semantic-navigation/pom.xml clean test
JAVA_HOME=/path/to/jdk21 ./mvnw -f experiments/java-semantic-navigation/pom.xml clean test
python3 experiments/java-semantic-navigation/run_probe.py \
  --java-home /path/to/jdk17 --java-home /path/to/jdk21 \
  --output /tmp/java-position-probe.json
```

The default probe asks about the real String/Path overload in
`quill-core/src/main/java/org/treblereel/mcp/core/JvmSourceFiles.java`. Its expected
owner/descriptor are fixed independently of Quill. It runs 21 queries per child
JVM, requires generation 1 throughout, distinguishes first attribution from 20
reused lookups, samples process-tree RSS, and verifies clean EOF.

After a normal Quill compilation has captured core's dependency classpath, measure
the complete module separately:

```bash
python3 experiments/java-semantic-navigation/run_probe.py \
  --java-home /path/to/jdk21 --release 21 --samples 6 \
  --source-root quill-core/src/main/java \
  --classpath-file quill-core/target/quill-classpath.txt \
  --output /tmp/java-module-probe.json
```

## Validation and integration gates

On macOS arm64, all **30 cases pass on JDK 17.0.2 and 21.0.2**. They cover primitive,
reference, boxing and vararg overloads; generics and wildcard erasure; lambdas,
method references, static imports and inherited defaults; member/record constructors;
Unicode names; local shadowing; source add/delete/repair; restored timestamps;
same-size dependency JAR replacement; racing edits; concurrent lookups; processor
non-execution; malformed probe requests and EOF. No production native integration
or whole-project test result is claimed by these experiment tests.

The [host results](../../docs/benchmarks/results/java-position-probe-macos-2026-10-12.json)
include the single-file and complete 75-source, 18-classpath-entry module runs.
Repeated measurements varied: streaming-hash module runs had first-attribution
times of approximately 1.1–1.9 seconds and warm medians of 34–83 ms, with sampled
peak RSS around 265–281 MiB. These small samples are informational, not a performance
gate or a guarantee. The single-file workload must not be extrapolated to modules.

Before production integration, connect captured compiler settings, generated roots,
main/test classpaths, and the actual selected JDK. Preview and JPMS settings require
explicit support or an unavailable status. Add bounded process recovery/cancellation,
input-change warmup and module eviction, and compare with the pinned real-project
manifest through the actual MCP server. Preserve freshness metadata and distinguish
compiler-confirmed selection from fallback. Measure cold and post-edit latency,
long-session memory and native artifact growth before enabling the backend by default.
