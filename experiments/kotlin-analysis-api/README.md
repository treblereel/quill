# Isolated Kotlin Analysis API edit probe

This experiment uses Kotlin 2.4.21 and its upstream IntelliJ SDK `251.27812.49`.
It remains outside the main reactor and is never added to the production worker or
native executable. IDE bundles contain constituent modules whose POMs refer to
unpublished source artifacts, so the POM uses explicit bundles with transitive
exclusions. IntelliJ core supplies its actual runtime dependencies.

## Reproduce

Build the production FIR worker first (`./mvnw package -DskipTests`), then:

```sh
./mvnw -f experiments/kotlin-analysis-api/pom.xml test dependency:build-classpath \
  -DincludeScope=runtime \
  -Dmdep.outputFile="$PWD/experiments/kotlin-analysis-api/target/classpath.txt"
python3 experiments/kotlin-analysis-api/run_probe.py \
  --java-home /path/to/jdk17 \
  --classpath-file experiments/kotlin-analysis-api/target/classpath.txt \
  --output /tmp/analysis-api-probe.json
```

Repeat the runner with JDK 21. Processes have timeouts and dispose their project and
global application services. JUnit uses independent child JVMs, with file redirects
to avoid filled stdout pipes and forced cleanup on timeout. All mutations *inside*
one probe retain the same project, module and Analysis API session.

`src/test/resources/mutations.json` is shared by JUnit and the fresh-FIR comparison.
It contains 35 required states plus a disk-only negative control. The negative
control must return the old type and is excluded from the promotion gate; a failed
required case still blocks it. JUnit additionally checks that an analysis exception
cannot count as a correct result and a snapshot cannot write outside its temporary
source root. The runner compares resolved symbol names, declaration filenames and
JVM descriptors to fresh production FIR processes. FIR exposes property identity
without its type, so a separate inferred wrapper checks each property's type.
A deleted declaration must produce an explicit unresolved-reference diagnostic.

## Updating the retained session

The earlier [2026-10-11 probe](../../docs/benchmarks/results/kotlin-analysis-api-probe-jdk17-2026-10-11.json)
failed after edits. Rebinding providers alone removed its exception but still
returned old types: local Standalone VFS/PSI reload retained the original text.
Updating a Document did not fix that path; direct AST replacement also required
IDE POM services absent from the Standalone setup.

The current experiment takes a narrower, explicit approach:

1. Replace cached file views for changed/deleted sources using
   `PsiManagerImpl.fileManager.setViewProvider(file, null)` and get fresh PSI through
   a new `KotlinLocalFileSystem`. Unchanged files retain their PSI.
2. Rebuild Standalone declaration, package and annotation provider factories from
   the current files and replace their services in the MockProject container.
3. Emit a global source out-of-block modification event before the next analysis.
4. Resolve within `analyze`; only plain strings leave the analysis lifetime.

The source module has an explicit root-bounded scope that admits added files.
This bypasses the builder's fixed initial file scope. It is a single-module
experiment using IntelliJ implementation APIs, not a supported mutable Standalone
SDK integration. Provider indexes are rebuilt over all current files, and FIR
invalidation is global. There is no selective per-function incremental analysis.

## Results and remaining promotion gates

On macOS arm64, JDK 17.0.2 and 21.0.2 pass all 35 required states. Cases cover:
initial/unchanged queries; equal-size edits with restored mtime; inferred/explicit
return types; changed parameter signatures; unchanged callers with edited
cross-file dependencies; import/type aliases and alias target changes; properties;
extension functions; added/deleted/recreated/renamed source files; renamed
functions; overload selection/removal; package moves; annotations; and restoring
the original snapshot after repeated mutations.

Reports:

- [JDK 17, 2026-10-12](../../docs/benchmarks/results/kotlin-analysis-api-mutation-jdk17-2026-10-12.json)
- [JDK 21, 2026-10-12](../../docs/benchmarks/results/kotlin-analysis-api-mutation-jdk21-2026-10-12.json)

Runtime dependencies occupy 136,258,283 bytes (~130 MiB, 67 artifacts), excluding
JUnit. Small-fixture timings are exploratory: the two correctness runs can overlap,
and fresh reference timings include JVM startup. They are not large-project
performance measurements or evidence of selective FIR reuse.

The gate is now `more_validation_required`, and `production_enabled` remains false.
Before promotion we still need multiple source modules and main/test boundaries,
Java/Kotlin interop, changed external classpaths and compiler settings, generics,
nullability, inheritance, incomplete/errorful code recovery, concurrent requests,
cancellation, cache eviction, repeated session disposal and memory-growth checks.
Then benchmark large modules against the existing warmed worker and evaluate the
runtime size/dependency tradeoff.

A lifecycle limitation was found during JUnit setup: disposing all global Standalone
application services and subsequently constructing another session in the same JVM
causes a listener-registration assertion. Independent probes therefore use separate
processes, and global services are disposed once when each process finishes. A
production design would need a process-lifetime application and separately managed
project/module sessions; that lifecycle has not been validated here.
