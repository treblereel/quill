# Isolated Kotlin Analysis API edit probe

This experiment uses Kotlin 2.4.21 and its upstream IntelliJ SDK `251.27812.49`.
It is deliberately outside the main reactor and is never added to the production
worker or native executable. IDE bundles contain constituent modules whose POMs
refer to unpublished source artifacts, so the POM uses explicit bundles with
transitive exclusions. IntelliJ core supplies its actual runtime dependencies.

```sh
./mvnw -f experiments/kotlin-analysis-api/pom.xml compile dependency:build-classpath \
  -Dmdep.outputFile="$PWD/experiments/kotlin-analysis-api/target/classpath.txt"
python3 experiments/kotlin-analysis-api/run_probe.py \
  --java-home /path/to/jdk17 \
  --classpath-file experiments/kotlin-analysis-api/target/classpath.txt \
  --output /tmp/analysis-api-probe.json
```

The probe resolves inferred return types, retains one session, performs a same-size
edit with restored mtime, then reloads PSI and emits global out-of-block modification
events for two edits. Values crossing the analysis lifetime are strings only.
It closes both its project Disposable and global Standalone application services.
The runner compares all three edited states against fresh CLI frontend results,
uses process timeouts, and records runtime size and correctness.

On macOS arm64, Java 17.0.2 and 21.0.2 both pass initial/unchanged resolution and
terminate normally. Disk-only editing returns stale `kotlin/Int`; explicit reload
and modification events produce `KotlinIllegalArgumentExceptionWithAttachments`.
Registering the otherwise missing PSI tree-change extension point gets past reload,
but does not make the Standalone declaration provider/index mutable.

The promotion gate is blocked. Fast unchanged queries do not demonstrate cheaper
correct analysis after editing. Mutable declaration providers, module structure,
modification/lifetime handling and the complete correctness matrix must be implemented
before large-project latency comparisons would be meaningful. This is a negative
prototype result, not an enabled incremental backend.
