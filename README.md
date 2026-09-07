# Quill

Pre-computed codebase intelligence for AI coding agents. Quill indexes your Java project's dependency graph (CDI and Spring DI), class structure, and git history into a local SQLite database, then serves it via [MCP](https://modelcontextprotocol.io/) tools.

## Prerequisites

- Java 21+
- Maven or Gradle; Quill prefers `mvnw`/`gradlew` and falls back to the tool on `PATH`

## Quick Start

```bash
# 1. Compilation is optional: quill init compiles when bytecode is missing.
# You can also compile explicitly first:
cd /path/to/your/project
./mvnw compile                 # Maven
# ./gradlew classes            # Gradle

# 2. Initialize the Quill index (the current directory is used automatically)
java -jar /path/to/quill-app-1.0.0-SNAPSHOT-all.jar init

# 3. Add Quill as an MCP server in your AI tool's config (.mcp.json, etc.)
```

## How It Works

Quill scans Maven `target/classes` and Gradle `build/classes/*/main` directories for
compiled `.class` files, builds a [Jandex](https://smallrye.io/jandex/) index, and
resolves CDI or Spring dependency injection directly from bytecode metadata. Quill
itself does not use or start Quarkus; the target framework is detected automatically.
The result is stored in `.quill/{commit-hash}.db` — a per-commit SQLite database that
stays consistent across branch switches.

**Important:** Quill indexes compiled bytecode, not source code. `quill init` compiles
when no bytecode exists; use `quill update --compile` when source changes must be
compiled before refreshing an existing index.

## Commands

| Command | Description |
|---------|-------------|
| `quill init` | Index a Maven or Gradle project; compile if bytecode is absent |
| `quill update` | Re-index if the project fingerprint changed |
| `quill update --compile` | Compile first, then safely refresh the index |
| `quill status` | Show current index status |
| `quill clean` | Remove indexes, refs, and Quill-managed git hook blocks |

## MCP Tools

Once indexed, Quill exposes these tools via MCP:

- **search_classes** — find classes by wildcard pattern
- **get_dependencies** — fan-in/fan-out for a class
- **assess_change_risk** — risk score based on coupling, churn, bus factor
- **get_overview** — project summary (class/bean counts, architecture hubs, problems)
- **list_beans** — filter beans by scope, kind, qualifier (CDI and Spring)
- **list_injection_points** — injection resolution status for a bean
- **find_git_hotspots** — most frequently changed files
- **find_co_changed_files** — files that change together (hidden coupling)
- **list_external_dependencies** — third-party library usage

## Per-Commit Indexing

Quill stores indexes as `.quill/{commit-hash}.db` with an LRU policy (max 5 indexes). When you switch branches, Quill serves the index matching the current HEAD. A stale warning is included in responses when the index doesn't match the exact commit.

Git hooks (post-commit, post-merge, post-checkout) are installed automatically. They
run `update --compile`, keep the previous index if compilation fails, and prefer the
absolute JAR/native launcher used during `quill init` before falling back to `quill`
from `PATH`.

### Gradle projects

Both Groovy and Kotlin DSL projects are supported, including multi-project builds.
Quill locates the root through `settings.gradle[.kts]`, runs `gradlew` when present,
and injects a temporary init script to read each Java module's `runtimeClasspath`.
It does not modify project build files or embed the Gradle Tooling API. Dependency
classpath caches are written under each module's `build/` directory and invalidated
when Gradle build files, version catalogs, wrapper properties, or `buildSrc` change.
On Windows it uses `gradlew.bat`; Maven projects use `mvnw.cmd`. If a wrapper is
absent, Quill falls back to `gradle` or `mvn` from `PATH`.

## Building Quill

```bash
./mvnw clean package
java -jar quill-app/target/quill-app-1.0.0-SNAPSHOT-all.jar --help
```

Windows PowerShell equivalents are `./mvnw.cmd clean package` and the same
`java -jar ...` command.

The build produces a thin library JAR and a runnable shaded `-all.jar` (about 28 MB).
Keeping them separate makes repeated Maven builds reproducible and avoids shading an
already shaded artifact. To use the runnable JAR as a local MCP
server, configure the command as `java`, pass `-jar` and the absolute JAR path as
arguments, and set the MCP process working directory to the indexed project. Start
it manually with `--mcp`; the current directory is registered automatically.

### Native executable

With GraalVM and `native-image` installed:

```bash
./mvnw -pl quill-app -am package -DskipTests -Pnative
./quill-app/target/quill --help
```

The native profile is independent of Quarkus and leaves the regular runnable JAR
available alongside the executable. Native executables are platform-specific; the
reference Apple Silicon build with size optimization is about 38 MiB.
On Windows the native output is `quill-app\target\quill.exe`.

### Releases

Pushing a tag such as `v1.0.0` runs the complete JVM and native test suites and
publishes a GitHub release containing `quill.jar`, Linux x86-64, macOS Apple
Silicon, and Windows x86-64 native archives, plus SHA-256 checksums. The release version is derived
from the tag and is reported consistently by both the CLI and MCP server.
