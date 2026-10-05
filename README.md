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
compiled `.class` files, builds a [Jandex](https://smallrye.io/jandex/jandex/main/index.html) index, and
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
Workspace fan-out queries use up to four shared read workers; set
`QUILL_WORKSPACE_QUERY_WORKERS` to a positive value (capped at 32) to override it.
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

The JSON and console report include the exact MCP `tools/list` count and byte size, so catalog
growth is measured alongside response latency and payload size.

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
The same gate caps the full MCP catalog at 48 KiB and the three-tool router catalog at 4 KiB;
the router must remain at most 20% of the full catalog, preventing silent context growth.

## Commands

| Command | Description |
|---------|-------------|
| `quill init` | Index compiled bytecode and install build integration and supported MCP client configuration |
| `quill init --index-only` | Build only the index; do not modify build or MCP client configuration |
| `quill init --timings` | Index and report per-phase elapsed times for diagnostics |
| `quill update` | Re-index if the project fingerprint changed |
| `quill update --force` | Rebuild the index even when the fingerprint is unchanged |
| `quill status` | Show current index status |
| `quill status --json` | Emit the status as machine-readable JSON |
| `quill doctor` | Diagnose compiled outputs, index freshness, build integration, and client setup |
| `quill doctor --json` | Emit the diagnostic report as machine-readable JSON |
| `quill client refresh` | Refresh local MCP registration and managed guidance without indexing or building |
| `quill clean` | Remove `.quill`, Quill-managed build integration, and MCP client configuration |
| `quill workspace init` | Discover and initialize all suitable repositories in a workspace |
| `quill workspace init --depth N` | Discover repositories up to the requested directory depth |
| `quill workspace init --jobs N` | Process up to N repositories concurrently (default: 4) |
| `quill workspace init --index-only` | Initialize workspace indexes without installing integration or MCP configuration |
| `quill workspace refresh` | Reconcile added/removed repositories and index missing repositories |
| `quill workspace refresh --jobs N` | Process up to N repositories concurrently (default: 4) |
| `quill workspace refresh --index-only` | Refresh indexes without installing integration or MCP configuration |
| `quill workspace status` | Show workspace configuration and per-repository readiness |
| `quill workspace status --json` | Emit workspace status as machine-readable JSON |
| `quill workspace clear` | Remove workspace metadata while preserving repository indexes |
| `quill workspace clear --repositories` | Also remove repository indexes and build integration |

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

Use `quill init --probe-mcp` or `quill workspace init --probe-mcp` to verify the installed
project/workspace `.mcp.json` stdio transport after initialization. To retry without initializing,
use `quill doctor --probe-mcp --json` in a Maven/Gradle project. This explicit opt-in **executes
the configured launcher** with its arguments, environment and working directory, performs MCP `initialize`,
`tools/list`, and `get_overview`, and terminates the subprocess (30-second total response deadline).
Only run it for a configuration you trust. It makes no model requests, runs no builds or tests,
and does not change client trust; starting Quill may refresh derived index state.
Probe failure returns a non-zero exit code even when indexing succeeded; it does not roll back init.
`--probe-mcp` cannot be combined with `--index-only`.

A successful transport probe is **not** proof that Codex/Claude activated Quill: it does not
resolve Codex TOML configuration layers, client permissions, or the current conversation's tool
catalog. Trust the project in the client and start a fresh session
(Codex's project-layer trust requirement is documented in
[Config basics](https://learn.chatgpt.com/docs/config-file/config-basic)). Actual model-driven client
adoption is a separate, explicit check via `scripts/quill_native_client_probe.py` (see its `--help`).
For cold-start read-only adoption with normal client tool availability and installed permission
settings, use `scripts/quill_cold_start_probe.py --help`. It starts fresh headless client/MCP
processes against an existing index, never installs or grants permissions, and saves only a
sanitized call sequence and outcomes. `--trust-project` supplies explicit invocation-only Codex
trust without writing global configuration; use it only after consenting to trust that project.
The map task checks overview-before-source ordering; usages additionally requires a successful
semantic query; workspace requires compact overview followed by full details for a selected
project. Missing Quill permissions, client/provider failures, and settings changes are not successes;
unrelated shell permission denials are reported separately. These checks use the client's
configured authentication and may consume model usage.

## Federated Workspaces

A workspace is a directory containing independent Git repositories that may depend on one
another. Single-project commands and MCP configuration continue to work unchanged.

Compile the repositories with their normal build commands, then initialize the workspace:

```bash
quill workspace init --project /absolute/path/to/workspace
quill workspace status --project /absolute/path/to/workspace
quill --mcp --workspace /absolute/path/to/workspace
```

`workspace init` discovers Git repositories at depth one by default; use `--depth N` for nested
checkouts. It initializes every Maven or Gradle repository that already has compiled main classes.
Unsupported and uncompiled repositories are reported and skipped. Quill never starts a Maven or
Gradle build while indexing them. `--index-only` creates indexes without installing build-result
integration or modifying repository-local client configuration.

Unless `--index-only` is used, workspace initialization creates or updates `.mcp.json` in the
workspace root and every discovered Maven or Gradle repository. Existing `quill` entries are
replaced with a workspace-aware command while unrelated MCP servers are preserved. Existing
`.codex/config.toml` files are updated in the same way. `workspace refresh` reconciles these files
for added and removed repositories, and `workspace clear` removes only entries managed for that
workspace. Use `workspace clear --repositories` when indexes and build integration should also be
removed.

Both `quill init` and `quill workspace init` offer one interactive consent question to
let Claude use Quill's current read-only MCP tools without repeated tool-approval prompts.
For scripts, pass `--allow-quill-tools` explicitly; without a terminal no permissions are
granted automatically. Use `--no-allow-quill-tools` to skip the question (it does not revoke
existing permissions). `--allow-quill-tools` cannot be combined with `--index-only`.
Workspace consent covers the workspace root and its discovered JVM repositories.

Consent adds concrete tool names derived from Quill's read-only contracts to each target's
`.claude/settings.local.json`, not global or shared permission settings. There is no wildcard
grant for future tools. Existing rules, including `ask` and `deny`, are preserved and remain
authoritative. New tools require renewed consent on a later `init`. Ownership is recorded in
`.claude/quill-permissions.json`; keep that local receipt with the settings, and do not commit
it. `quill clean` / `workspace clear` remove only permissions Quill added, preserving pre-existing
rules. Claude's workspace trust and MCP connection approval are separate client checks;
this option does not bypass them or change Codex permissions.

Manage that choice later without re-indexing, rebuilding, or removing the MCP server:

```bash
quill client permissions status --project /path/to/project --json
quill client permissions grant --project /path/to/project
quill client permissions revoke --project /path/to/project
# Apply to an initialized workspace and its recorded configured repositories:
quill client permissions status --workspace /path/to/workspace
```

Update an existing installation's client guidance and local MCP registration independently
of its index and build outputs:

```bash
quill client refresh --project /path/to/project
quill client refresh --workspace /path/to/initialized/workspace
```

Refresh preserves content outside Quill's managed instruction blocks, other MCP servers,
and existing tool permissions. It never indexes, builds, grants tool permissions, or changes
global Codex trust. Workspace refresh uses the recorded configured repositories, not discovery.
Malformed managed markers, unsupported configuration, and symlinked targets require repair
before refresh. Unmarked legacy instructions are preserved, not silently deleted.
Start a fresh client session afterwards; installed files do not attest live activation.

`grant` is explicit consent, so it does not ask another question. It requires an existing
project-local Quill MCP server and never overrides `ask`/`deny`. `revoke` removes only grants
recorded as Quill-owned; pre-existing user allows can still permit tools afterward. Both
mutations accept `--workspace` too and validate targets before writing. `status` reports
local allowed/missing/denied/ask tools, ownership and invalid files; it never writes files
or contacts an AI service. `doctor` includes the same local permission diagnosis separately
from MCP registration. Neither check attests global/managed policies, hooks, workspace trust
or a live client's effective permissions.

The MCP server reconciles added and removed repositories while it is running. A newly added
repository becomes routable immediately; run `workspace refresh` after compiling it to create its
missing index. Refresh reports `added`, `removed`, `indexed`, `skipped`, `unchanged`, and `failed`
counts and never starts a build. Removed repositories disappear from subsequent routing snapshots;
their on-disk data is not deleted.

`workspace status --json` reports whether every repository is supported, compiled, indexed,
fresh, on the current SQLite schema, and equipped with current build integration. Maven/Gradle
coordinates route requests to local providers. If multiple checkouts intentionally publish the
same coordinate, Quill preserves the ambiguity and reports each `repository:module@version`
candidate instead of silently choosing one.

Workspace MCP tools accept a repository name, relative/absolute path, or unambiguous Maven/Gradle
coordinate where a project selector is supported. The workspace-specific tools are
`list_workspace_repositories`, `get_workspace_dependencies`, `resolve_workspace_entity`,
`find_workspace_usages`, and `assess_workspace_change_risk`.

Normal `workspace clear` removes only `.quill-workspace` and preserves every repository index.
Use the explicit destructive form `workspace clear --repositories` to additionally run Quill's
normal cleanup for every supported repository, removing `.quill` and Quill-managed Maven/Gradle
build integration. Neither form deletes source code or invokes a build.

## MCP Tools

Once indexed, Quill exposes these tools via MCP:

Every tool result includes native MCP `structuredContent` and an advertised object
`outputSchema`. Quill does not duplicate the JSON payload as text content.

The default `--tools full` profile exposes the complete catalog. To reduce the initial
`tools/list` context for smaller local models, select `router`, `core`, `code`, `di`, or `git`:

```bash
quill --mcp --project /path/to/project --tools core
quill --mcp --project /path/to/project --tools core,git
quill --mcp --project /path/to/project --tools router
```

Comma-separated profiles form a union. `core` contains the general navigation, dependency,
impact, risk, and build-status tools; the specialized profiles add their respective analysis
surface. `router` is standalone and exposes only `get_overview`, `search_tools`, and
`execute_tool`; it discovers the full catalog on demand and substantially reduces the initial
tool-schema context. Tool responses and the underlying index are identical across profiles.

Choose the cheapest evidence source for the question:

- Use `rg` plus a targeted file read for an exact literal, a known path, or one concrete
  occurrence. Constructor text in one known file is usually cheaper this way.
- Use Quill for semantic symbol resolution, dependency/call graphs, implementations, DI,
  generated classes, Git aggregation, deleted history, and change risk.
- Do not query both automatically. Cross-check source only when Quill reports stale/unknown data,
  or when a consequential conclusion needs direct line-level confirmation.

The router profile returns the same policy in `search_tools.guidance`, including a query-specific
`recommended_channel` hint.

- **search_classes** — find classes by wildcard pattern; supports `limit`/`offset`
- **resolve_entities** — resolve up to 20 class names, file names, or repository paths in one
  request across the current tree and Git history, including deleted paths
- **get_project_dependencies** — list resolved Maven/Gradle artifacts visible to main or test
  code without invoking the build. Maven/Gradle integration captures separate runtime and test
  classpaths after normal user builds; until then Quill identifies transitive test visibility
  through the reactor graph and labels it as inferred
- **list_project_tree** / **search_files** — navigate the indexed project inventory with
  module, source-set, lifecycle, and dirty-worktree context
- **get_file_problems** — filter captured Maven/Gradle build diagnostics by one or more source
  paths; reports the last observed build and never starts one
- **get_worktree_status** — inspect live branch/HEAD, indexed commit, and paged dirty files with
  structural-change classification
- **get_symbol_at_position** — resolve the identifier at a one-based Java/Kotlin source position
  to indexed class/member declarations, with ambiguity and confidence reported explicitly;
  resolved declarations include a stable `symbol_id`
- **search_external_symbols** / **get_external_symbol_details** — search and inspect class/member
  declarations indexed from dependency bytecode without mixing them with application symbols
- **search_symbols** — search class, method, field, and constructor declarations by name or
  signature, with kind/language filtering and pagination. Results distinguish source-level and
  JVM names and include a stable `symbol_id`
- **get_call_hierarchy** — inspect direct or bounded-transitive method callers and callees with
  exact overload selection by signature/JVM descriptor, invocation kinds, source-line evidence,
  traversal depth, and call paths; use `scope=cross_class` or `scope=cross_package` to suppress
  lower-level call noise before pagination
- **trace_state_lifecycle** — correlate exact constructor and field-access evidence with
  persistence, dispatch, serialization, and recovery boundaries; reports candidate roles and
  explicitly does not claim control-flow ordering
- **analyze_execution_order** — reconstruct emitted call order inside one exact method and
  distinguish proven bytecode order from only likely runtime order across control flow; optional
  `before_terms` and `after_terms` check custom semantic boundaries, while CFG dominance proves
  when every indexed normal or exception-handler path to an after-call passes through a
  before-call; detected executor, future, reactive, and messaging boundaries keep invocation
  order separate from completion order

Example:

```text
analyze_execution_order(
  target="CaseFlowEngine",
  method="execute",
  before_terms="persist,save",
  after_terms="dispatch,publish")
```

The result orders application and external calls by `instruction_ordinal`, includes `source` and
`source_line`, and reports one of `proven_on_all_cfg_paths`, `proven_on_normal_completion`,
`invocation_order_proven_completion_unknown`, `likely`, or `unknown`. A proven invocation order
does not imply that an executor, reactive stream, or message publication has completed.
- **compare_design_impact** — rank existing classes as candidate hosts using risk, dependent
  classes, and impacted tests while keeping lifecycle/semantic fit as an explicit limitation
- **find_method_overrides** — find direct or transitive overriding declarations for a selected
  method or overload, with hierarchy paths and Java modifier checks
- **find_unused_classes** — find conservative dead-code candidates while excluding indexed
  references, hierarchy use, DI/framework roots, main classes, and ServiceLoader providers
- **find_unused_methods** — find uncalled private-method candidates using exact erased JVM
  descriptors while excluding annotated, native, and conventional runtime callback methods
- **find_unused_fields** — find private fields with no exact indexed bytecode reads; annotated,
  serializable, and static-final fields are excluded, while write-only candidates are opt-in
- **find_entry_points** — discover main methods, REST resources/endpoints, observers, scheduled
  methods, message consumers, annotation processors, and ServiceLoader providers
- **get_module_graph** — inspect direct Maven/Gradle project-module dependencies and transitive
  classpath visibility in either direction, with module-level class and bean counts
- **find_architecture_violations** — evaluate explicit package or module dependency boundaries
  against compiled static dependencies, with class-pair and source-line evidence
- **get_package_graph** — aggregate current static class dependencies into package-level coupling,
  with inbound/outbound filtering, relation kinds, class-pair counts, and module context
- **find_cycles** — find strongly connected components in class dependencies or direct
  project-module dependencies, including a representative closed path and internal edge kinds;
  nested classes are collapsed into their top-level owner to suppress compiler-structure noise
- **compare_index** — compare the active immutable index with a retained generation, including
  class/member, static dependency, bean, and injection-resolution deltas
- **get_build_status** — inspect build-result integration, compiled outputs, pending events,
  index freshness, and the next required action without invoking Maven or Gradle; build-required
  responses distinguish `classes_missing` from `classes_stale` and identify stale modules
- **get_build_problems** — read normalized errors captured from the last Maven or Gradle build,
  including source positions and module filtering, without starting a build
- **get_annotated_classes** — find directly annotated and meta-annotated classes by short
  annotation name or FQCN, with pagination and source/generated breakdown
- **find_annotated_symbols** — find annotated types, methods, fields, constructors, and parameters
  by short annotation name or FQCN, preserving parameter position, name, and type
- **find_framework_endpoints** — find Spring MVC and JAX-RS routes with HTTP methods, constant
  class/method paths, module/source context, and bounded direct project-call evidence
- **find_configuration_references** — find `.properties`, YAML, and persistence-unit definitions
  together with annotation-based Spring, MicroProfile, SmallRye, and JPA consumers; configuration
  values are deliberately not indexed
- **find_resource_references** — find indexed classpath resources and their programmatic consumers
  without reading or returning resource contents
- **inspect_service_descriptors** — inspect ordered `META-INF/services` providers with source-line
  evidence and warnings when provider order may affect behavior
- **find_implementations** — find direct/transitive subclasses and implementors, including
  generated occurrences grouped by module and evidence about reactor-discovery completeness
- **find_usages** — find bytecode calls, constructor calls, field access, type references,
  injection, inheritance, annotations, and ServiceLoader usages with evidence and pagination
- **find_symbol_usages** — find exact method, constructor, or field usages by declaration
  signature/JVM descriptor, including call or read/write evidence and pagination; pass a
  `symbol_id` as `target` to select an overload without repeating its name or descriptor
- **get_symbol_details** — inspect hierarchy, annotations, declared members, DI context,
  dependency metrics, implementations, occurrences, and external types for one class
- **get_context** — assemble a compact change-ready card for up to ten classes: resolution,
  symbol and DI details, coupling, usages, affected tests, risk, and index freshness
- **plan_change** — turn an intended change into an ordered, evidence-backed plan with primary
  files, dependency-review candidates, ranked tests, risk, coverage, and freshness warnings
- **verify_change** — combine live worktree state, captured build diagnostics, affected tests,
  and index freshness into `ready`, `needs_build`, `blocked`, or `partial` verification
  plus a non-executing verification plan that uses a project wrapper when usable, falls back to
  an installed build tool only when present on `PATH`, and otherwise reports the runner unavailable;
  its first recommendation compiles production and standard test sources without running tests,
  and structured `next_actions` tell the external agent how to resolve evidence gaps and re-check
- **change_session** — return a compact stateless snapshot with a stable session ID, current workflow
  phase, canonical or dirty-worktree-inferred targets, change plan, verification evidence, safe
  command recommendations, ordered next actions, and a structured verification receipt that keeps
  recommended argv separate from observed build evidence. Its evidence-only review checklist
  identifies required target, coverage, module, and worktree review without claiming human
  acknowledgement. A phase gate states whether unresolved evidence prevents progress and names the
  exact transition needed for the current phase. A factual directive selects one primary action,
  while stable action IDs let clients distinguish repeated guidance from a new requirement;
  all views preserve the same directive. Primary actions include their evidence snapshot and any
  external command needed for preparation or retry, even when the corresponding section is omitted;
  summary detail is the default, while
  full detail preserves the complete plan/verification parity contract, and `view=auto` returns
  only the plan before structural edits or verification evidence afterwards. Dirty changes with
  unresolved contract, target, module, coverage, or pagination evidence enter `review_required`;
  changes needing only build evidence enter `verification_required`. Stateless snapshots may skip
  phases when the newly observed evidence already satisfies them
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
- **get_overview** — existing full overview by default; in workspace mode use
  `view=compact` for a paginated project map (default `limit=20`, max 50, `offset=0`).
  The map includes unavailable projects, indexed framework/counts and freshness, and
  explicit `next_page_request` / per-project `details_request` calls. Counts describe
  the index, not a complete endpoint or architecture analysis. Compact mode cannot be
  combined with a project selector or `details=true`; use `project=<name>, view=full`
  for details. Pagination applies only to compact mode. Set `details=true` for diagnostic
  samples and per-dimension architecture-hub rankings. Its `capabilities` block advertises
  supported source languages, stable `symbol_id` chaining, and machine-readable errors

MCP startup instructions report the actual server mode: `workspace` for `--workspace`,
otherwise `single_project`. A repository called a "workspace" in its documentation does
not imply workspace server mode. Discover only `get_overview` first, then other tools
on demand instead of loading the entire catalog. For single-project or unknown mode,
call overview without arguments. If compact is requested on a single-project server,
follow its executable `retry_with` request for full overview; no reconfiguration or build
is needed. Read `structuredContent` even when the text content is empty.

Tool failures use a single structured envelope: `error_code`, `message`, `retryable`, and,
when the request can be refined, `retry_with`. The former free-form `error` field is not
emitted by the MCP surface.
- **list_beans** — filter beans by scope, kind, qualifier (CDI and Spring); supports
  `limit`/`offset`
- **list_injection_points** — injection resolution status for a bean
- **find_git_hotspots** — current hotspots plus a live dirty-worktree overlay; deleted
  historical paths are opt-in with `include_historical`; supports `limit`/`offset`
- **get_file_history** — paged commit history for a class or file, with author labels/emails,
  explicit returned-window identity counts (without guessing human identities), and
  indexed-history coverage
- **find_co_changed_files** — files that change together (hidden coupling)
- **get_recent_changes** — return recent commits and their paged changed-file/class details
- **list_external_dependencies** — third-party library usage
- **list_workspace_repositories** — list dynamically discovered repositories and index readiness
- **get_workspace_dependencies** — resolve declared dependencies onto providers in other local
  workspace repositories and report binary/version drift
- **resolve_workspace_entity** — resolve a class across repositories with explicit ambiguity
- **find_workspace_usages** — find cross-repository usages of an indexed class
- **assess_workspace_change_risk** — aggregate change impact across repository boundaries

Paginated responses use the same `showing`, `total`, `limit`, `offset`, `has_more`,
`truncated`, and optional `next_offset` fields.

## Connect Quill to Claude Code or Codex

Initialize the project once before connecting an MCP client. From the project directory:

```bash
cd /absolute/path/to/project
/absolute/path/to/quill init
```

Alternatively, specify the project explicitly when running Quill from another directory:

```bash
/absolute/path/to/quill init --project /absolute/path/to/project
```

Quill is a local stdio MCP server. The client starts it on demand and communicates
with it over stdin/stdout; you do not need to run a daemon. Absolute paths are
recommended because an MCP client's process working directory is not guaranteed.

For structured troubleshooting, add `--debug` to the MCP command. Quill keeps stdout
reserved for JSON-RPC, writes JSON-line events to stderr, and persists the same events in
`.quill/debug/quill-debug.jsonl` under the served project or workspace. Each instrumented
response includes a `debug.trace_id` for correlation. Set `QUILL_DEBUG=1` instead when it is
more convenient to enable diagnostics without editing MCP arguments, or use
`--debug-directory /path` to choose where the `.quill/debug` directory is created. Debug logs
contain local paths and dependency coordinates and should not be committed.

To measure real MCP usage without recording code, queries, arguments, paths, or response
contents, start the server with `--telemetry` (or set `QUILL_TELEMETRY=1`). Quill appends local
JSONL measurements to `.quill/telemetry/mcp-tools.jsonl`: tool name, profile, duration, response
byte count, success/error state, and non-content limitation flags such as stale or truncated.
Summarize a capture, or compare it with an earlier baseline, using:

```bash
python3 scripts/quill_ux_report.py .quill/telemetry/mcp-tools.jsonl
python3 scripts/quill_ux_report.py .quill/telemetry/mcp-tools.jsonl \
  --baseline /path/to/baseline.jsonl --output target/benchmarks/ux-report.json
```

Telemetry is disabled by default, never leaves the machine, rotates at 5 MiB, and is best-effort:
failure to write it never affects an MCP request.

For workspace mode, configure the client command as:

```bash
/absolute/path/to/quill --mcp --workspace /absolute/path/to/workspace
```

`--workspace` and `--project` are mutually exclusive. The same stdio process routes each request
to the selected repository or aggregates it across the workspace when the tool supports that.

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

`quill init` and `quill client refresh` create `.codex/config.toml` when missing,
or add this section to an existing file. A native launch records its executable path; development runs from a
JAR fall back to `quill` from `PATH`, keeping the generated configuration binary-only.
Other settings are preserved, repeated initialization is a no-op, and an existing
user-owned `mcp_servers.quill` section is never overwritten. A Quill-managed project entry
whose absolute launcher path no longer exists is repaired when initialization runs from a
working native executable. Initialization also refreshes versioned Quill guidance in `AGENTS.md`
and `CLAUDE.md`, preserving surrounding user content, so both clients follow the current
change-session directive, phase gate, review, and verification contract. Quill does not create a
global Codex configuration or set project trust implicitly.

`quill doctor` distinguishes outdated managed guidance from malformed marker boundaries. `client refresh` or `init`
refreshes an intact outdated block. When markers are missing, reversed, or duplicated, `init` and
`clean` preserve the entire instructions file and report the boundaries that need repair.

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
and [Codex MCP documentation](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)
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

Normal `quill init` installs a reversible build-result integration instead of Git
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
`~/.quill/cache/dependencies/<fingerprint>.idx`; it is shared between projects and
invalidated when the ordered runtime classpath or a dependency JAR's size or timestamp
changes. Set `QUILL_CACHE_DIR` to move the shared cache. Quill retains at most 16
dependency indexes and 512 MiB, pruning older entries after publishing a new one.
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
The sharded dependency Jandex index uses the same shared cache and invalidation as Maven.
On Windows it uses `gradlew.bat`; Maven projects use `mvnw.cmd`. If a wrapper is absent,
Quill falls back to `gradle` or `mvn` from `PATH`.

## Building Quill

Building Quill requires JDK 21 through 25. The Maven build rejects newer feature releases until
they are explicitly validated; released native executables do not require a local JDK to run.

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
The independently versioned Maven build extension is verified with
`./mvnw -f quill-maven-extension/pom.xml verify`; it is intentionally outside the
Quill reactor so only that artifact can be published to Maven Central.

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

## License

Quill is licensed under the [Apache License 2.0](LICENSE).
