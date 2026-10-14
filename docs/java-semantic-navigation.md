# Java compiler navigation

`get_symbol_at_position` can use the project's selected JDK compiler to resolve
Java calls, constructor calls, member access and method references. The compiler's
owner and erased JVM descriptor select the corresponding indexed declaration.
`resolution_basis` distinguishes `compiler` from `lexical_fallback`;
`semantic_analysis` reports the backend, status, model generation and reuse.
For source declarations, it also reports one-based start and exclusive end coordinates;
the matched indexed symbol receives these in `location` with `basis: compiler`.
External binary declarations have no source range.
`find_symbol_usages` and `get_call_hierarchy` also attach `source_evidence` to Java
call/field groups. Compiler-selected reference expressions carry start/exclusive-end
coordinates and the selected indexed `symbol_id`. References must match the exact
caller and target descriptors and a bytecode evidence line. Java source hashes are
captured in new index generations and checked before/after compiler analysis;
changed bytes (including restored timestamps), stale indexes, old indexes without
hashes, and unavailable compiler captures retain bytecode evidence with an explicit
source-evidence status. This corroborates the indexed source snapshot; it does not
prove the compiler that produced the indexed bytecode saw those same source bytes.
Each response analyzes at most ten Java files and each file retains at most 4,096
references. Source reference coverage is explicitly incomplete: lambda synthetic
callers, initializer contexts and unsupported constructor descriptors are not guessed.

Java bytecode call groups include `dispatch`: the exact symbolic target remains in
`declared_target`, and bounded `possible_targets` use indexed ancestry and erased
override shapes. Static, special and final-method references do not expand into
override candidates. Covariant return descriptors retain distinct symbol identities.
Candidates never assert that a receiver has that runtime type or that a call executes.
For a selected override, `dispatch_context.possible_callers` separately reports
virtual/interface calls to indexed ancestor declarations. These do not increase the
exact usage/call counts; special `super` calls are excluded. This nested collection
has its own pagination and continuation request. Hierarchy coverage remains incomplete
because external nodes, generic bridges, inherited symbolic owners and proxies can
add candidates. Duplicate owner names and missing declarations report explicit statuses.
Position results and errors include index freshness metadata. A compiler-selected
declaration absent from the index reports `semantic_target_not_indexed`.

The distribution embeds the dependency-free `quill-java-semantic` worker JAR.
The native executable launches it with the JDK captured by the successful Maven
compiler execution or Gradle JavaCompile task. This requires a full JDK 17 or
newer at that captured location; it does not download another Java compiler or
substitute the host's default JDK. The worker uses public compiler APIs, disables
annotation processors and code generation, and does not execute project code.
Existing generated Java sources participate in analysis; processors must already
have produced any required sources during the project build.

## Build evidence and supported inputs

The backend reads `target/quill-java-<set>.properties` for Maven and
`build/quill-java-<set>.properties` for Gradle. It selects the most specific captured
source root containing the requested file; equally specific captures are ambiguous.
Captured main, test and custom Gradle sets have their own classpath and language
settings. Additional generated source roots are included. Source/target settings
remain source/target settings: they are not silently converted into `--release`,
which would change the available JDK API.

Successful execution evidence, the settings protocol, selected JDK and build-input
hashes are checked. Changed build inputs require rebuilding to refresh the capture.
Absent settings/classpath, configured-only Gradle evidence, unsupported compiler
arguments, non-UTF-8 sources, preview and JPMS return explicit statuses and retain
marked lexical fallback. Only compiler arguments that do not change attribution
(`-parameters`, debug/lint options, `-Werror`, processor `-A` options) are accepted;
processors remain disabled. Source traversal is restricted to the project and
limited to 5,000 Java files. The module is located through its Maven/Gradle marker;
Gradle subprojects without their own marker need further module-resolution support.

## Model lifecycle and warmup

A single worker retains one fully attributed source-set model. Sources and classpath
bytes are hashed before and after resolution. Additions/deletions, equal-size edits
with restored timestamps and changed dependency bytes invalidate the whole model.
This is full re-attribution, not incremental javac analysis. A source error does not
reuse an earlier successful compiler selection. Unsupported synthetic constructor
descriptors remain unknown; local variables do not map to indexed fields.

The worker retains the two most recently used module configurations and evicts the
least recently used compiler model when a third is selected. Changing JDK or losing
the child process restarts it. It runs with `-Xmx512m` and an
8 MiB stack. Startup and response reads each have a 25-second timeout. Timeout,
interruption, EOF or malformed transport dispose the child; a following request can
start another. Shutdown removes the temporary worker files. Heap limits do
not cap total process RSS.

Java reuses the directory-watcher and debounce coordinator used by Kotlin, with its
own worker and coordinator instance. The active module's source roots, outputs,
classpath, settings and build inputs are watched. A foreground query waits for a
running warmup and then recollects inputs. Startup prewarms only when one indexed
main Java module is an unambiguous choice; otherwise the first Java query selects
the active module. Gradle subprojects without their own build script use their indexed
module context and compiler capture; the worker retains one additional inactive model.

## Validation

The production frontend inherits the isolated experiment's overload, generic,
method-reference, constructor, Unicode, content invalidation and processor tests.
Integration tests cover worker recovery/disposal, source/target configuration,
capture freshness/protocol failures, custom Gradle sets/generated roots,
ambiguous source-set captures, symlink boundaries and real position queries with
CRLF and supplementary Unicode characters. Shared coordinator tests cover waiting
for running warmup and file-change races.

The earlier [isolated measurements](benchmarks/results/java-position-probe-macos-2026-10-12.json)
remain informational and do not measure production MCP startup/warmup. Broad
external-project acceptance, declarations beyond the supported reference nodes,
JPMS/preview, compiler plugin configurations, more than two retained module caches and
long-session memory measurements remain follow-up work.

The [macOS native integration record](benchmarks/results/java-native-integration-macos-2026-10-12.json)
contains a controlled build of the preceding `b8c34f4` commit and this integration
with GraalVM CE 25.0.1 and the same native flags. The executable grew from
69,183,272 to 69,249,432 bytes: **66,160 bytes (approximately 64.6 KiB, 0.096%)**.
The embedded worker adds no dependency JARs; its compiler comes from the project's
selected JDK. This measures the whole integration rather than just the worker JAR.

Actual native MCP requests resolved the String/Path overload correctly. In a
separately built/indexed temporary copy, a same-size edit with restored mtime
selected the Path overload in generation 2; a query 300 ms after the edit took
452 ms and reused the model built by background warmup. Restoring the source and
waiting 1.5 seconds produced generation 3 with a 38 ms query. Source content and
mtime were restored after the probe. These isolated timings demonstrate warmup
and correct invalidation; they do not guarantee latency under other workloads.

Validation on the final frontend: 32 tests each on JDK 17.0.2 and 21.0.2; the
focused JDK 25 reactor run passed 32 frontend and 150 application tests. An earlier
Kotlin semantic regression suite passed 15 tests. Native builds skipped test
execution; tests were run separately. This is not a full repository-wide suite.
