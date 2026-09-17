# Quill

Pre-computed codebase intelligence for AI coding agents. Quill indexes your Java project's dependency graph (CDI and Spring DI), class structure, and git history into a local SQLite database, then serves it via [MCP](https://modelcontextprotocol.io/) tools.

## Prerequisites

- A Quill native executable for your operating system
- The JDK and Maven or Gradle required to build the indexed project

## Quick Start

```bash
# 1. Compile the project so Quill can index its bytecode:
cd /path/to/your/project
./mvnw compile                 # Maven
# ./gradlew classes            # Gradle

# 2. Initialize the Quill index (the current directory is used automatically)
/path/to/quill init

# 3. Connect Quill to Claude Code or Codex (see below)
```

## How It Works

Quill scans Maven `target/classes` and Gradle `build/classes/*/main` directories for
compiled `.class` files, builds a [Jandex](https://smallrye.io/jandex/) index, and
resolves CDI or Spring dependency injection directly from bytecode metadata. Quill
itself does not use or start Quarkus; the target framework is detected automatically.
It also recognizes project-local `META-INF/services` registrations, JPMS
`provides ... with ...` declarations, and direct `ServiceLoader.load(Service.class)`
calls. These appear in dependency results as `SERVICE_PROVIDES` and
`SERVICE_CONSUMES` edges without requiring another bytecode pass.
The result is stored as an immutable SQLite generation under `.quill/`; `refs.json`
atomically points each commit and branch at its active generation. This lets an MCP
request finish reading the previous snapshot while `quill update` publishes the next
one, including on Windows where an open SQLite file cannot be replaced safely.

**Important:** Quill indexes compiled bytecode, not source code, and never runs a Maven
or Gradle build. Build the project yourself before `quill init`. Successful builds are
recorded by the integration installed during initialization; Quill consumes that event
and refreshes the index before the next MCP response. Every MCP response reports the indexed
and current commit plus worktree freshness in `_meta`. Dirty and untracked files are
also exposed as a live overlay by `find_git_hotspots`. Changes to Java or Kotlin sources,
resources, generated sources, or Maven/Gradle build inputs mark structural answers
stale until the project is compiled and the index is refreshed; documentation-only
changes remain visible without invalidating the bytecode graph.

The stdio MCP server accepts pipelined read-only tool calls and executes up to four
concurrently. JSON-RPC responses may arrive out of order and are correlated by `id`;
response writes remain serialized so stdout always contains complete JSON messages.
`QUILL_MCP_MAX_CONCURRENCY` and `QUILL_MCP_MAX_QUEUED_PER_WORKER` can override the
defaults for constrained or unusually large local environments.
`QUILL_MCP_REQUEST_TIMEOUT` sets the per-tool timeout in seconds (default: `30`).
Successful JSON responses are capped at 256 KiB; set `QUILL_MCP_MAX_RESPONSE_BYTES`
to override the cap. Truncated responses report omitted fields and filtering guidance.
Dependency JAR indexing uses up to eight workers, capped automatically by available CPUs
and the process heap budget (roughly 256 MiB per worker). Set
`QUILL_DEPENDENCY_WORKERS` to a positive number to override that choice.
Timed-out work is cancelled and returns an explicit `Tool timed out` result; closing
the client input also cancels active workers so the stdio server can terminate cleanly.
Concurrent requests share an expensive Git worktree snapshot for 500 ms. An
expired snapshot remains available while one background refresh runs, so Git inspection
does not periodically stall MCP workers; edits appear after that refresh completes.

For an informational cold-index and MCP latency benchmark (not part of CI), run:

```bash
python3 scripts/quill_benchmark.py \
  --quill quill-app/target/quill \
  --project /path/to/large/project
```

It reports peak process-tree memory, index size, and p50/p95 latency for bursts of
4, 16, and 100 requests. Existing `.quill` data is backed up and restored by default;
machine-readable results are written below `target/benchmarks/`.
It reports actual structured-response byte sizes, without treating source coverage as token
savings. For an automated, controlled with/without-Quill Responses API comparison of accuracy,
actual and cached tokens, time, model/tool requests, and manual verification, use the paired task
evaluation described in
[`docs/benchmarks/task-evaluation.md`](docs/benchmarks/task-evaluation.md).
CI also runs a generated 400-class, 32-dependency-JAR native smoke fixture on Linux and
Windows. It verifies dependency-cache miss and hit behavior with intentionally broad time,
memory, and concurrent-request budgets to catch major regressions without depending on an
external repository or noisy microbenchmark thresholds. Native integration tests also keep
an MCP process serving reads while repeated immutable index generations are published.

## Commands

| Command | Description |
|---------|-------------|
| `quill init` | Index already-compiled Maven or Gradle bytecode and install build integration |
| `quill init --timings` | Index and report per-phase elapsed times for diagnostics |
| `quill update` | Re-index if the project fingerprint changed |
| `quill status` | Show current index status |
| `quill doctor` | Diagnose compiled outputs, index freshness, build integration, and client setup |
| `quill clean` | Remove `.quill` and build integration |

The `--timings` phases include independently measured background work such as
dependency and Git analysis. Because those phases can overlap, their durations are
not expected to add up to the reported wall-clock `total`.
Exact source token counts are cached by content under `.quill/`, so subsequent indexes
only tokenize new or changed source files. `quill clean` removes this cache together
with the index generations. Application bytecode metadata is kept in eight stable,
content-addressed Jandex shards. An update reparses only shards containing added, changed,
or removed class files while global DI resolution is recomputed for correctness.

Failed initialization reports a stable reason code such as `NO_COMPILED_CLASSES`,
`HEAD_CHANGED`, `WORKTREE_CHANGED`, or `INDEX_PUBLICATION_FAILED`. The message includes
actionable context such as expected/current commit, current dirty paths, or the SQLite destination.
Quill builds the database in a staging file and keeps the previous index unchanged if
indexing or publication fails. For `HEAD_CHANGED` or `WORKTREE_CHANGED`,
finish the concurrent checkout/build/edit and run `quill update` again.

`quill doctor` reports actionable project setup and index-health checks without building or
re-indexing the project. Warnings (for example, missing build integration) keep exit code 0;
failed requirements such as missing compiled outputs or an unreadable index return a non-zero
exit code. Use `quill doctor --json` for a stable, versioned machine-readable report.

## MCP Tools

Once indexed, Quill exposes these tools via MCP:

Every tool result includes native MCP `structuredContent` and an advertised object
`outputSchema`. Quill does not duplicate the JSON payload as text content.

- **search_classes** — find classes by wildcard pattern; supports `limit`/`offset`
- **get_annotated_classes** — find directly annotated and meta-annotated classes by short
  annotation name or FQCN, with pagination and source/generated breakdown
- **find_implementations** — find direct/transitive subclasses and implementors, including
  generated occurrences grouped by module and evidence about reactor-discovery completeness
- **find_usages** — find bytecode calls, constructor calls, field access, type references,
  injection, inheritance, annotations, and ServiceLoader usages with evidence and pagination
- **get_symbol_details** — inspect hierarchy, annotations, declared members, DI context,
  dependency metrics, implementations, occurrences, and external types for one class
- **find_impacted_tests** — rank tests by static dependency paths and Git co-change evidence,
  with explicit reporting when compiled test outputs are not indexed
- **get_type_hierarchy** — inspect paged ancestor and descendant paths, including external
  ancestors that are referenced by indexed bytecode but are not themselves indexed
- **get_dependencies** — complete dependency metrics plus an optional relation graph,
  addressable by FQCN or source path. Use `include_nodes=false` for a compact metrics-only
  response. Depth-one relations use `limit`/`offset`; deeper breadth-first traversals return
  an opaque `next_cursor`.
- **assess_change_risk** — class blast radius or file-level risk based on file
  criticality, coupling, churn, and bus factor; accepts class names and arbitrary paths
- **get_overview** — compact project summary by default; set `details=true` for diagnostic
  samples and per-dimension architecture-hub rankings
- **list_beans** — filter beans by scope, kind, qualifier (CDI and Spring); supports
  `limit`/`offset`
- **list_injection_points** — injection resolution status for a bean
- **find_git_hotspots** — current hotspots plus a live dirty-worktree overlay; deleted
  historical paths are opt-in with `include_historical`; supports `limit`/`offset`
- **get_file_history** — paged commit history for a class or file, with author labels/emails,
  explicit returned-window identity counts (without guessing human identities), and
  indexed-history coverage
- **find_co_changed_files** — files that change together (hidden coupling)
- **list_external_dependencies** — third-party library usage

Paginated responses use the same `showing`, `total`, `limit`, `offset`, `has_more`,
`truncated`, and optional `next_offset` fields.

## Connect Quill to Claude Code or Codex

Initialize the project once before connecting an MCP client:

```bash
/absolute/path/to/quill init --project /absolute/path/to/project
```

Quill is a local stdio MCP server. The client starts it on demand and communicates
with it over stdin/stdout; you do not need to run a daemon. Absolute paths are
recommended because an MCP client's process working directory is not guaranteed.

### Claude Code

Add Quill for the current project (the default `local` scope keeps the setting
private to your machine):

```bash
cd /absolute/path/to/project
claude mcp add --scope local quill -- \
  /absolute/path/to/quill --mcp --project /absolute/path/to/project
```

For a team-shared setup, add this `.mcp.json` to the project root. Put `quill` on
`PATH`, or set `QUILL_BIN` before starting Claude Code:

```json
{
  "mcpServers": {
    "quill": {
      "type": "stdio",
      "command": "${QUILL_BIN:-quill}",
      "args": ["--mcp", "--project", "${CLAUDE_PROJECT_DIR:-.}"]
    }
  }
}
```

Check the connection with `claude mcp list` or `/mcp` inside Claude Code. Claude
Code asks for approval before starting a project-scoped server for the first time.

### Codex

Add Quill to the user configuration from the command line:

```bash
codex mcp add quill -- \
  /absolute/path/to/quill --mcp --project /absolute/path/to/project
```

Alternatively, add a project-scoped entry to `.codex/config.toml`:

```toml
[mcp_servers.quill]
command = "/absolute/path/to/quill"
args = ["--mcp"]
cwd = "/absolute/path/to/project"
```

When `.codex/config.toml` already exists, `quill init` adds this section
automatically. A native launch records its executable path; development runs from a
JAR fall back to `quill` from `PATH`, keeping the generated configuration binary-only.
Other settings are preserved, repeated initialization is a no-op, and an existing
`mcp_servers.quill` section is never overwritten. Quill does not create a Codex
configuration file implicitly.

Here `cwd` lets Quill discover the project automatically, so `--project` is not
needed. Project-scoped configuration is loaded only for trusted projects. Check the
connection with `codex mcp list` or `/mcp` inside Codex.

### Windows

The same commands work in Windows PowerShell with `quill.exe`. Forward slashes keep
paths easy to copy into JSON and TOML:

```powershell
claude mcp add --scope local quill -- C:/Tools/quill.exe --mcp --project C:/src/my-project
codex mcp add quill -- C:/Tools/quill.exe --mcp --project C:/src/my-project
```

To serve more than one initialized project, repeat `--project` for each path. If a
client explicitly starts Quill in the indexed project directory, `--project` may be
omitted because Quill uses its current directory by default.

If tools do not appear, run `quill status --project /absolute/path/to/project`, use
an absolute executable path, and restart the client session after changing its MCP
configuration. See the official [Claude Code MCP documentation](https://code.claude.com/docs/en/mcp)
and [Codex MCP documentation](https://developers.openai.com/codex/mcp)
for client-specific scopes and configuration options.

## Immutable Index Generations

Quill keeps up to five immutable database generations and selects the active one via
`.quill/refs.json`. When you switch branches, Quill serves the generation matching the
current HEAD. If that generation is not ready yet after a commit or checkout, MCP and
`quill status` keep serving the current branch's previous generation, or the newest
retained generation for a new branch. Such responses explicitly report
`_meta.commit_stale=true` until `quill update` publishes the current snapshot.
Freshness is checked against both the commit and a fingerprint of
relevant tracked, deleted and untracked worktree files. `_meta.index_id` identifies the
exact generation used by a response; `_meta.structure_stale` and `_meta.stale_reasons`
explain when its static graph no longer represents the checkout. `quill status` shows
the indexed/current commit and worktree state.

Static dependency counts have explicit scope: `fan_in` and `fan_out` count unique
current classes, while `incoming_edges` and `outgoing_edges` count reference
occurrences. Responses also break counts down by `source` and `generated` origin.
Unmatched stale class outputs are classified as `orphan_output` and excluded from the
current graph. Git history remains queryable by its historical path without treating a
deleted class as current code.

Normal `quill init` installs a reversible build-success integration instead of Git
hooks. Maven uses `.mvn/extensions.xml`; Gradle gets a Quill-managed block in the root
`settings.gradle[.kts]`. A successful build writes a small atomic JSON event below
`.quill/build-events/`. Before serving the next MCP request, Quill consumes that event
and refreshes the index from the bytecode produced by the build. The integration never
starts a build itself and build failures do not replace the previous index. Use
`--index-only` to skip all project configuration changes. `quill clean` removes only
the Quill-managed integration and the entire `.quill` directory.

Build events use a strict versioned contract and are accepted only for successful Maven
or Gradle builds. If a structural worktree file was edited after the build completed,
Quill discards the stale event and keeps reporting the previous graph as stale instead
of publishing an index from out-of-date bytecode. A subsequent successful build creates
a fresh event and allows the next MCP request to refresh the index. Maven and Gradle
processes started internally for project discovery are marked so they cannot recursively
create another build event.

### Maven projects

Quill reads the reactor structure from `pom.xml` with Maven Model and recursively
follows regular and profile-defined `<module>` entries. When it starts inside a
module without `--project`, it selects the nearest containing aggregator; an
explicit `--project` keeps the requested module or reactor root. Only modules with
compiled main classes are indexed. If a raw POM cannot be read or a declared module
is unavailable, Quill falls back to scanning `target/classes` so partially checked
out and generated reactors remain usable. Maven itself is not embedded. The dependency
Jandex index is built in up to eight deterministic shards and cached at
`target/quill-dependencies.idx`; it is invalidated
when the ordered runtime classpath or a dependency JAR's size or timestamp changes.
Multi-release JARs contribute only the variant effective for Quill's Java runtime;
when multiple JARs contain the same class, the first classpath entry wins, matching
JVM class loading.

### Gradle projects

Both Groovy and Kotlin DSL projects are supported, including multi-project builds.
Java and Kotlin/JVM main sources are mapped back from compiled classes, inventoried,
and token-counted; common KSP-generated Kotlin sources are included as generated input.
Quill locates the root through `settings.gradle[.kts]`, runs `gradlew` when present,
and injects a temporary init script to export the evaluated Java project directories,
main source-set outputs, and `runtimeClasspath`. This respects dynamic settings and
custom `projectDir`/`buildDirectory` mappings while excluding stale outputs from
removed projects.
Quill does not modify project build files or embed the Gradle Tooling API. Discovery
and dependency classpath caches are written under `build/` directories and invalidated
when Gradle build files, version catalogs, wrapper properties, or `buildSrc` change.
The sharded dependency Jandex index is cached at `build/quill-dependencies.idx` with
the same classpath and JAR invalidation used for Maven.
On Windows it uses `gradlew.bat`; Maven projects use `mvnw.cmd`. If a wrapper is absent,
Quill falls back to `gradle` or `mvn` from `PATH`.

## Building Quill

Building Quill requires JDK 21 or newer.

```bash
./mvnw clean package
java -jar quill-app/target/quill-app-1.0.0-SNAPSHOT-all.jar --help
```

Windows PowerShell equivalents are `./mvnw.cmd clean package` and the same
`java -jar ...` command.

The build produces a thin library JAR and a runnable shaded `-all.jar` for development
and diagnostics. Keeping them separate makes repeated Maven builds reproducible and
avoids shading an already shaded artifact. Quill releases are distributed as native
executables, so end-user MCP configuration should point to `quill` or `quill.exe`.

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
publishes a GitHub release containing Linux x86-64/AArch64, macOS Apple Silicon/Intel,
and Windows x86-64 native archives, plus SHA-256 checksums and an SPDX SBOM. GitHub
build-provenance and SBOM attestations cover every archive. The release version is
derived from the tag and is reported consistently by both the CLI and MCP server.
See [the release guide](docs/releasing.md) for platform signing, notarization, and
download verification.
