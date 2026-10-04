# Paired task evaluation

Latency and payload size do not prove that an agent completes engineering tasks more
accurately or with fewer tokens. Quill therefore keeps three benchmark stages separate:

1. `scripts/quill_benchmark.py` measures indexing, memory, MCP latency, concurrency, exact
   `tools/list` catalog bytes, and actual structured-response byte size.
2. `scripts/quill_agent_benchmark.py` runs the same task suite through the OpenAI Responses API
   once with Quill and once without Quill.
3. `scripts/quill_task_benchmark.py` scores the two captured runs.

The paired benchmark measures fact accuracy, completed tasks, elapsed time, actual agent
input/output tokens, model requests, tool calls, and manual verification steps. It does not use
an estimated compression factor as agent savings.

Before spending model tokens, run the deterministic Crysknife contract gate:

```bash
python3 scripts/quill_crysknife_quality.py \
  --quill quill-app/target/quill \
  --project /path/to/crysknife \
  --reindex \
  --output target/benchmarks/crysknife-quality-gate.json
```

It preserves the existing index and fails on the known constructor, service-descriptor,
historical-path, dependency-directness, or external-member regressions.

## Automated paired run

The runner starts a clean model context for every task and mode. Both modes receive the same
read-only `rg`, file-read, and Git-history functions, including ignored build outputs but excluding
`.git` and `.quill`; the Quill mode additionally receives the tools advertised by the local Quill
MCP server. Expected values are deliberately not sent to the model. The order is alternated
between tasks so one mode does not always benefit from running second.

The neutral `git_history` baseline exposes commit hash, author label, author email, timestamp,
subject, and optional changed paths. Path-limited queries use Git's full-history mode so merge
commits exposed by Quill are not silently removed by history simplification. Facts available from
Quill history must not be withheld from the baseline merely because its representation is less
structured.

Set an API key, build Quill, and run against the exact revision pinned by the suite:

```bash
export OPENAI_API_KEY=...
python3 scripts/quill_agent_benchmark.py \
  --suite benchmarks/crysknife-agent-effectiveness.json \
  --project /path/to/crysknife \
  --quill quill-app/target/quill \
  --model gpt-5.6-terra \
  --reasoning-effort medium \
  --repetitions 10
```

For the current post-quality-fixes Crysknife baseline, use
`benchmarks/crysknife-current-effectiveness.json`. The older
`crysknife-agent-effectiveness.json` remains pinned because its recorded captures are historical
evidence and must not be rescored against a different revision.

The broader UX suite is `benchmarks/casehub-engine-current-effectiveness.json`. It contains 15
revision-pinned tasks covering orientation, dependency impact, DI, runtime registration, Git
history, type hierarchy, impacted tests, framework endpoints, configuration, architecture,
dead-code candidates, pre-modification risk, module boundaries, and bytecode execution flow:

```bash
python3 scripts/quill_agent_benchmark.py \
  --suite benchmarks/casehub-engine-current-effectiveness.json \
  --project /path/to/casehub/engine \
  --quill quill-app/target/quill \
  --repetitions 10
```

The suite is pinned to commit `acc0e3f080a4b087e2d34a256e9478f36b75fbc9`. Editor configuration
files added to a local checkout make the worktree dirty; use `--allow-dirty` only for exploratory
runs and only when those changes are non-structural. Publishable comparisons must use a clean
checkout at the pinned commit.

`gpt-5.6-terra` with medium reasoning is the benchmark default, so the final two options may be
omitted. Always record overrides when comparing results produced by a different model or effort.

The runner rejects a different Git revision or dirty worktree by default. `--allow-dirty` is
available for intentional worktree experiments, but such results are not comparable to the pinned
suite without a corresponding rebaseline.

Runs alternate the first condition by task and repetition. With more than one repetition the
generated files contain a `runs` array; the scorer reports median and p95. Suites may declare
deterministic `accepted` alternatives for semantically equivalent facts such as Maven artifact ids
and repository module paths without exposing those alternatives to the model. A task may also
declare a `quill_tools` allowlist selected before the model runs. This models client-side tool
search/routing without forcing a Quill call or loading the full catalog on every turn.

Catalog discovery experiments must keep server exposure separate from client-side selection. The
default `--tool-selection suite` applies each task's `quill_tools` allowlist. Use `all` to expose
the entire selected server profile. For example, compare the normal catalog and Quill's router in
separate output directories:

```bash
python3 scripts/quill_agent_benchmark.py \
  --suite benchmarks/crysknife-current-effectiveness.json \
  --project /path/to/crysknife --quill quill-app/target/quill \
  --quill-profile default --tool-selection all \
  --output-dir target/benchmarks/full-catalog --repetitions 10

python3 scripts/quill_agent_benchmark.py \
  --suite benchmarks/crysknife-current-effectiveness.json \
  --project /path/to/crysknife --quill quill-app/target/quill \
  --quill-profile router --tool-selection all \
  --output-dir target/benchmarks/router-catalog --repetitions 10
```

Both fields are stored in every capture. Do not compare `router/all` with the default
`default/suite` and attribute the difference solely to the router: that would change server
exposure and client-side selection at the same time.

The generated `*-with-quill.json` and `*-without-quill.json` files contain the usage returned by
every Responses API call:

- `input_tokens` includes cached and uncached input;
- `cached_input_tokens` is reported separately;
- `output_tokens` includes model output accounted by the API;
- `model_requests` counts Responses API calls;
- `response_ids` and `response_models` preserve an audit trail and the resolved model version;
- `tool_catalog_count` and `tool_catalog_bytes` expose tool-discovery overhead;
- `model_rounds` records latency and token usage for every Responses API call;
- `tool_trace` records tool name/provider, latency, argument/output byte counts, and status without
  duplicating potentially sensitive tool contents;
- `time_to_first_tool_ms`, `time_to_first_quill_ms`, and
  `time_to_first_successful_quill_ms` measure how quickly the agent reaches useful indexed
  evidence rather than only measuring total task duration;
- `source_fallback_count` and `source_fallbacks` identify source searches or file reads performed
  after a Quill call, including the preceding Quill tool and non-sensitive response flags such as
  `not_found`, `stale`, `truncated`, `unknown`, or `unsupported`;
- `source_first_count` and `source_first_calls` identify source tools used before the first Quill
  call, rather than incorrectly counting them as fallbacks;
- `quill_bypassed` and `quill_bypass_count` identify tasks where Quill tools were advertised but
  the agent never invoked one;
- `requests` counts source and MCP tool calls;
- `manual_verification_steps` counts direct source-file reads.

`total_tokens = input_tokens + output_tokens`. The scorer additionally reports
`uncached_tokens = input_tokens - cached_input_tokens + output_tokens`. Tool schemas and prior tool
results are naturally included in API input usage; do not add their estimated sizes a second time.
Quill startup and MCP discovery happen before the task timer because a normal editor session keeps
the server running, but Quill tool latency during the task is included.

## Observe normal editor sessions locally

The paired runner measures controlled tasks. To find friction in ordinary Codex or Claude use,
enable Quill's content-free local telemetry in the MCP command with `--telemetry`, or set
`QUILL_TELEMETRY=1`. It writes `.quill/telemetry/mcp-tools.jsonl` and records only the tool name,
profile, duration, response size, status, and stale/truncated/confidence flags. It does not retain
arguments, paths, queries, source, or response bodies.

```bash
python3 scripts/quill_ux_report.py /path/to/project/.quill/telemetry/mcp-tools.jsonl \
  --output target/benchmarks/editor-ux.json
```

Preserve the original JSONL as the baseline before changing the tool surface. A later capture can
be compared directly with `--baseline`. Use paired task results for correctness and token claims;
use editor telemetry to identify frequent, slow, error-prone, stale, or oversized tools.

## Manual capture protocol

Use the same model, reasoning level, initial checkout, task prompt, and time/token limits for both
runs. The only intended difference is whether the Quill MCP tools are available. Start a clean
agent context for every task and record one result object:

```json
{
  "id": "bean-manager-coupling",
  "observed": {
    "total_dependents": 497,
    "source_dependents": 15,
    "generated_dependents": 482
  },
  "duration_seconds": 24.8,
  "input_tokens": 4100,
  "cached_input_tokens": 1200,
  "output_tokens": 620,
  "model_requests": 3,
  "requests": 4,
  "manual_verification_steps": 1
}
```

Store the captured tasks in two JSON documents:

```json
{
  "mode": "with_quill",
  "tasks": []
}
```

The `observed` keys must match the task's `expected` keys. Missing values are counted separately
from incorrect values. `requests` counts MCP, search, Git, and file-read operations initiated to
answer the task. A manual verification step is a direct source inspection used to confirm a tool
or search result.

## Score the pair

The neutral Crysknife agent suite is pinned to the project revision stored in
`benchmarks/crysknife-agent-effectiveness.json`. The separate
`benchmarks/crysknife-quality.json` suite checks Quill-specific response contracts and must not be
used to claim an agent advantage. Score two effectiveness captures with:

```bash
python3 scripts/quill_task_benchmark.py \
  --suite benchmarks/crysknife-agent-effectiveness.json \
  --with-quill target/benchmarks/crysknife-agent-effectiveness-with-quill.json \
  --without-quill target/benchmarks/crysknife-agent-effectiveness-without-quill.json \
  --output target/benchmarks/crysknife-comparison.json
```

Do not compare runs from different project revisions. Rebaseline expected facts explicitly when
the target project changes. For a publishable result, repeat complete paired runs 10–20 times,
alternate which condition runs first, and report median and p95 alongside correctness. A single
pair is useful for debugging the protocol, not for claiming a stable token-saving percentage.

## Deterministic change-workflow benchmark

When a model API key is unavailable, compare the scenario workflow with its equivalent atomic MCP
queries without making token or answer-accuracy claims:

```bash
python3 scripts/quill_workflow_benchmark.py \
  --project /path/to/project \
  --quill quill-app/target/quill \
  --target com.example.ChangedService
```

The legacy path calls symbol details, usages, affected tests, and change risk separately. The
workflow path calls `plan_change`, then records `verify_change` as a post-change gate. The report
contains tool-call count, wall-clock latency, exact structured-response bytes, evidence-contract
coverage, the verification verdict, blocker codes, and ordered next-action types. It deliberately
does not estimate model tokens or claim answer correctness.

To measure whether the combined stateless snapshot improves on separate planning and verification
calls, run:

```bash
python3 scripts/quill_change_session_benchmark.py \
  --project /path/to/project \
  --quill quill-app/target/quill \
  --target com.example.ChangedService
```

The report compares two MCP calls (`plan_change` plus `verify_change`) with one `change_session`
call and requires semantic parity for primary changes, dependency review, verdict, blockers,
verification commands, and next actions. It also measures the default summary response separately
and verifies that primary files, verdict, blockers, quick compilation, and next actions remain.
The phase-aware `view=auto` response is measured independently and must expose exactly one of the
plan or verification payloads with the correct actions for the current phase.

## External change-loop E2E

Validate the complete agent-facing loop on an isolated Maven fixture:

```bash
python3 scripts/quill_change_loop_e2e.py \
  --quill quill-app/target/quill
```

The fixture intentionally has neither a Maven wrapper nor a pre-existing `.gitignore`. The harness
runs the system Maven fallback to bootstrap compiled classes, initializes Quill, edits a production
source, and obtains the exact `quick_compile` argv from `change_session`. The harness introduces a
compiler error, executes that command outside Quill, inspects the failed receipt, repairs the
source, and retries the recommended command. The report requires the phase sequence `planned` →
`review_required` → `blocked` → `complete`, diagnostics-first recovery actions, compiled production
and standard test classes, no Surefire or Failsafe reports, a captured successful build event, and
removal of the build-evidence blocker. The completed snapshot must contain a verification receipt
with the
same recommended argv, successful observed evidence, completion reasons, and an explicit
`not_captured` command attestation so build results are never presented as proof of exact argv.
Because sessions are stateless evidence snapshots, phases may be skipped: `review_required` is
reported only while unresolved targets, incomplete test coverage, truncated target inference, or
truncated worktree evidence needs attention; `verification_required` means only build evidence is
missing. Review actions are ordered before external compilation recommendations.
The dirty snapshot must expose an evidence-only `review_checklist` whose required items correspond
to review blockers. A completed receipt must report zero required review items; advisory contract
and dependency checks remain visible without preventing evidence-backed completion.
Every snapshot also exposes a `phase_gate`: planned work directs the structural change, review and
failed-build phases name their unresolved evidence and required transition, and completion is
reported as satisfied with no remaining evidence requirements.
The phase `directive` must select the first ordered action as `primary_action`. Every action carries
a stable `action_id`, so repeated stateless snapshots preserve identity while a changed reason,
tool, scope, target, or other semantic action field produces a different action. Display order is
excluded from the identity.
Views project evidence without changing the phase directive. The loop inspects the primary review
action's embedded test limitations, executes its compile-only preparation command, and uses the
repair directive's diagnostics and retry command after a failed build. This checks a deterministic
client following directives; it does not measure an LLM's adoption of generated instructions.
For a separate LLM adoption experiment, pass `--guidance-file /path/to/AGENTS.md` (or `CLAUDE.md`)
to `quill_agent_benchmark.py` with `--tool-selection all`. Only the Quill mode receives the
installed managed block; captures report `installed_guidance_used` and the actual tool trace.
This experiment requires the configured API key and remains read-only: it measures tool selection
and answers, not an LLM performing edits or builds.

For a small standalone adoption probe on a freshly initialized isolated Maven fixture, run:

```bash
python3 scripts/quill_guidance_adoption.py --key-file /path/to/temporary-api-key
```

The probe consumes and removes the temporary key file, including on failure. It uses installed
`AGENTS.md`, exposes the full Quill tool catalog, and runs two read-only tasks before and after a
source edit. The harness performs fixture setup; the model cannot edit or run builds.

On October 3, 2026, one real `gpt-5.6-terra` run called `get_overview` followed by `change_session`
in both tasks, with no source-tool calls. It returned the correct phases and primary actions:
`planned` / `inspect_primary`, then `review_required` / `inspect_test_evidence`. Strict output
accuracy was 1/2: the first answer returned `verified` as the string `"false"` instead of boolean
`false`; the second answer matched all expected fields. This is evidence of tool-selection adoption
in two tasks, not comparative effectiveness or a full agent edit/build workflow. Detailed captures
are written to `target/benchmarks/quill-guidance-adoption.json` and include token counts and tool traces.

### Bounded real-agent edit/build/recovery

```bash
python3 scripts/quill_agent_change_loop.py --key-file /path/to/temporary-api-key
```

This separate probe enables two explicit action tools in an isolated Maven fixture: a single exact
source replacement in `GreetingService.java`, and external execution of the current Quill primary
directive's compile recommendation. It accepts only system Maven `test-compile` in the fixture
directory, with a maximum of four executions. No arbitrary shell or test-running command is exposed.
The existing benchmark remains read-only unless action instructions and extra tools are explicitly
provided. The temporary key file is removed even on failure.

The harness injects a compiler error immediately before the first compile, but the model performs
the requested edit and subsequent repair itself. Checks require failed/blocked then successful
compilation, the requested greeting and unchanged public signature, regenerated standard test
classes, no test reports, a final agent `change_session` call, and a completed verified receipt.
The receipt continues to declare exact command attestation `not_captured`; harness execution records
provide the separate exact argv evidence.

On October 3, 2026, one `gpt-5.6-terra` run passed the edit/compile/recovery checks using the freshly
installed `AGENTS.md`. Its trace was `get_overview` → `change_session` → source read → edit →
`change_session` → failed compile → source read → repair → successful compile → `change_session`.
Both builds used `/opt/homebrew/bin/mvn test-compile`; the final answer was
`{"phase":"complete","verified":true}` with correct JSON types. The run used 11 model requests,
108,376 aggregate input tokens and 654 output tokens. Captures are stored in
`target/benchmarks/quill-agent-change-loop.json`.

This demonstrates one constrained API-agent workflow, not native Codex/Claude client integration,
unrestricted editing safety, comparative effectiveness, or reliability across repeated runs.

### Native client integration probe

```bash
python3 scripts/quill_native_client_probe.py --client both
python3 scripts/quill_native_client_probe.py --client codex --smoke-only
```

The probe initializes a separate temporary Maven project for each installed client. It relies on
the generated `.codex/config.toml`, `.mcp.json`, `AGENTS.md`, and `CLAUDE.md`; no server transport or
guidance is injected through CLI flags. It retains normal user configuration and saved client
authentication and does not select a model. The harness does not directly write global trust or
configuration; it now detects client-side user-config changes (see the correction below). A Codex
read-only discovery failure triggers a per-invocation project-trust retry. Claude runs in `dontAsk`
mode with read/edit/Quill permissions and narrowly permitted `test-compile` commands, not unrestricted
Bash. Codex working stages use its workspace-write sandbox. Every invocation has a timeout and
its own process group, terminated on timeout before the fixture is removed. Reports are checkpointed
after each invocation. These are native headless CLI checks, not interactive desktop sessions.

An October 3 local-time run used Codex CLI 0.147.0 (ChatGPT authentication) and Claude Code 2.1.287
(Vertex authentication). Both clients performed the requested source edit and compile-only build,
then repaired a compiler error injected by the harness and rebuilt. Independent Quill snapshots
confirmed `complete` with boolean `verified=true` after each working stage; the injected failure
produced `blocked`. Standard test classes were regenerated and no Surefire/Failsafe reports existed.
Actual native MCP traces include `get_overview`, `change_session`, and `verify_change` for both
clients. Claude executed `/opt/homebrew/bin/mvn test-compile` in both stages. Codex executed
`mvn test-compile -DskipTests` and `mvn -q -DskipTests test-compile`: still compile-only, but not the
exact recommended argv. Its extra flags are an instruction-adoption deviation, not an exact-command
attestation by Quill. Captures are in `target/benchmarks/quill-native-client-standard.json`.

Read-only discovery did not pass for Codex in this environment: it reported Quill unavailable;
resource requests failed with `unknown MCP server 'quill'`. A separate normal-configuration run
also failed after an attempted per-invocation trust override. That override was later found to be
malformed; these captures do not establish failure with correctly supplied trust.
Working workspace-write stages did expose the real Quill tools without that override. The root
cause of this mode-dependent configuration/tool exposure remains unverified; do not interpret
resource-list attempts or tool mentions as successful workflow access. The read-only captures are
in `target/benchmarks/quill-native-codex-discovery.json`. Claude's normal read-only discovery called
`get_overview` and `change_session` successfully. An initial experiment that disabled user config
is retained separately and is not used to establish normal-client behavior.

These single-run results demonstrate native edit/build/recovery, but not flawless instruction
ordering or reliable discovery in every client mode. In particular, Claude queried its
`change_session` after editing, and Codex added compile flags. Repeated runs and a separate
read-only Codex configuration investigation are still needed.

### Unprompted adoption and configuration isolation

```bash
python3 scripts/quill_adoption_diagnostic.py --wire-only --output target/benchmarks/quill-adoption-wire.json
python3 scripts/quill_adoption_diagnostic.py --output target/benchmarks/quill-adoption-diagnostic.json
python3 scripts/quill_adoption_diagnostic.py --cases profile_read_only project_workspace_write claude_project --samples 2 --output target/benchmarks/quill-adoption-repeated.json
```

This diagnostic asks what to inspect before changing the fixture's greeting while preserving its
public signature. The task prompt never names Quill, its tools, or the installed instructions;
edits and builds are forbidden. Fixture preparation still performs external compilation before
the client starts. Captures include actual native initialization instructions, tool annotations,
installed guidance, and native client tool traces. No API key is needed beyond existing client
authentication. The profile case creates and removes a separate explicitly selected temporary
user profile containing only fixture trust; it never changes the main user configuration.

The initial native binary returned no server instructions and no safety annotations. The updated
server supplies concise workflow instructions, explicit query-only hints, and stronger generated
AGENTS/CLAUDE guidance requiring `change_session` before source edits or build selection. Native
serialization initially emitted empty annotation objects despite correct Java values: enabling
reflection for `ToolAnnotations` accessors fixed this separate native-image defect. The wire-only
check now requires real boolean `readOnlyHint=true`, `destructiveHint=false`, and
`openWorldHint=false` on every tool, rather than accepting absent or empty annotations.

Configuration isolation initially appeared to reproduce a distinct activation issue in Codex CLI
0.147.0. Project-only read-only runs could not call Quill with the malformed project-trust override.
Supplying
the generated transport explicitly through CLI configuration worked, including with the old
binary lacking hints. Selecting a temporary user profile with persisted fixture trust also worked
in read-only mode. Project-only workspace-write runs exposed the tools. The October 4 investigation
below retracts the suspected upstream defect: the diagnostic did not supply valid trust.
Project-local MCP requires trust according to the
[Codex documentation](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).
`quill doctor` now separates detected configuration from untested live client activation and
reports these headless alternatives; Quill does not silently grant global project trust.

Before the changes, one workspace-write Codex sample called only `get_overview`; Claude already
called `change_session`. After the guidance changes, single samples of both clients called
`get_overview` and `change_session`. These small before/after samples are not causal or statistical
proof of improvement: prompts, model variability, client configuration, instructions, and metadata
must be considered separately. Startup instructions follow the
[MCP server guidance](https://developers.openai.com/plugins/build/mcp-server); they cannot activate
a server the client never loads. Existing projects need their managed instructions refreshed with
`quill init` (or workspace initialization) and a fresh client session to consume changed metadata.

With the final native binary, all six repeated runs called `get_overview` and `change_session`
without task-level reminders: two Codex read-only runs using the trusted temporary profile, two
Codex project-only workspace-write runs, and two Claude project-only runs. All exited successfully;
the wire contract passed for both server instructions and safety hints. Captures are in
`target/benchmarks/quill-adoption-repeated.json`. This demonstrates adoption in these tested client
configurations, not a guarantee for every task. The subsequent trust-override correction resolves
the project-only read-only diagnostic failure. Local JVM verification and 68 Python unit tests
also passed at that milestone.

The repository's own setup was refreshed separately: a missing `AGENTS.md` was installed, the
managed `CLAUDE.md` block updated while preserving user text, and Claude's `alwaysLoad` plus
project server approval installed. The existing telemetry argument was preserved. The installer
did not modify another workspace or global config. However, the earlier native workspace-write
tests allowed Codex itself to persist trust for four temporary fixture paths; the prior claim of
no global config changes was incorrect. These entries were observed but not removed automatically.

### Correction: Codex inline trust overrides

The October 4 source investigation found that Codex CLI 0.147.0 splits override keys literally
on `.` in [`apply_toml_override`](https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/config/src/overrides.rs).
Consequently, `-c 'projects."/absolute/path".trust_level="trusted"'` retains quotes in the project
key instead of parsing them as TOML key quoting. A path containing dots is split further. The
diagnostic now puts the path inside the TOML value, which is parsed correctly:

```bash
codex exec --sandbox read-only -C /absolute/path \
  -c 'projects={"/absolute/path"={trust_level="trusted"}}' 'Your task'
```

This explicitly grants trust only for that invocation and does not bypass approval or sandbox
policy. The project still supplies its normal `.codex/config.toml` transport. Native writable
probes also supply this valid in-memory trust to avoid implicit persistence by Codex's
[`thread/start` handler](https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server/src/request_processors/thread_processor.rs).
That handler can persist trust when writable permissions are requested and no valid trust entry
exists; this explains the earlier misleading sandbox-mode difference and side effects.

Each Codex invocation now compares the user-config content hash before and after, recording only
`user_config_unchanged`, never configuration contents or hashes. The adoption diagnostic exits
unsuccessfully if this check detects a change. Unit tests cover dotted/spaced path keys, automatic
in-memory trust for writable probes, and config-side-effect reporting. `quill doctor` now names
the correct inline-table override and warns against quoted dotted keys. Quill still does not
silently grant persistent user trust during initialization.

With the corrected override, all four repeated Codex runs (two read-only, two workspace-write)
called `get_overview` and `change_session` without prompt reminders. Captures are in
`target/benchmarks/quill-adoption-corrected-trust.json`. A separate pair of read-only/workspace-write
runs also adopted the workflow and confirmed `user_config_unchanged=true` in both cases; see
`target/benchmarks/quill-adoption-config-invariance.json`. These runs use project-only MCP transport,
not transport overrides or a selected trust profile. The wire contract still passed. All 71 Python
unit tests and the focused JVM doctor test passed after the correction.

### Module-scoped build freshness

```bash
python3 scripts/quill_scoped_verification_e2e.py
```

The regression originally produced `needs_build` for a fresh target because the global
`CompiledOutputInspector` found stale classes in an unrelated module. `quick_compile` only
recommended the target modules, so following it could never satisfy this global freshness gate.

Maven change verification now shares an explicit `verification_scope` with the compile plan.
For a statically resolvable reactor it checks target modules and a conservative prerequisite
closure, including local parents, all dependency scopes, profiles, dependency management, plugins,
and annotation-processor artifact references. Matching artifact IDs over-approximates differing
groups/versions rather than silently dropping possible prerequisites. External/unresolved parents,
interpolated artifact IDs, unreadable models, incomplete reactors, unknown module selection and
Gradle use repository scope with a matching whole-project compile-only recommendation. This is a
static model, not execution or attestation of arbitrary build-plugin behavior. `quick_compile_modules`
distinguishes the actual compile selection from the selection used by focused test commands.

`get_build_status` still reports the entire repository. Scoped verification's `build` reports its
own compiled outputs plus `repository_status` and `repository_stale_modules`; the scope is also
retained in summary views and the verification receipt. A project warning proven to concern only
stale modules outside the scope remains visible but is marked `blocking_for_change=false` and
`scope=repository_outside_change`. Unknown/mixed warnings are not downgraded. Captured build
failure, stale index, missing/incomplete test evidence, stale/missing prerequisites, and stale
shared parent inputs remain gates; these changes do not make arbitrary successful build events
exact-command attestations.

The native probe builds a real three-module Maven fixture without tests/e2e, captures all test
classpaths, ages only the unrelated module's compiled class, and runs a target-only `-pl app -am
test-compile`. It requires global `build_required` alongside a scoped `complete`/verified receipt,
then ages the provided prerequisite and requires `needs_build`. Captures are written to
`target/benchmarks/quill-scoped-verification.json`. The initial fixture lacked an unrelated test
classpath and correctly remained partial; the probe now captures that evidence before exercising
build freshness, without weakening the test-evidence gate. Eleven JVM regression cases cover
positive isolation and the negative safeguards, in addition to the existing edit/build/recovery
wire contract and full JVM suite.

On October 4 the final native scoped probe passed, including advisory warning annotations,
standard test-class compilation, no Surefire/Failsafe reports, and stale provided-dependency
blocking. The existing native change-loop probe also passed its `planned` → `review_required` →
`blocked` → `complete` sequence and view-invariance contracts. Full `./mvnw verify`, native packaging,
and all 71 Python unit tests passed. A fresh native MCP process against this repository returned
`complete`, a satisfied gate and boolean `verified=true` for the changed workflow classes, with
scope `[".", "quill-app", "quill-core"]` and no blockers.

### Unprompted native task matrix

```bash
python3 scripts/quill_adoption_diagnostic.py \
  --cases project_read_only claude_project --samples 1 \
  --output target/benchmarks/quill-adoption-task-matrix.json
```

The diagnostic now has five independently scored read-only scenarios: locating a method,
finding production and standard-test callers, class dependencies, file history, and planning
a change. `--scenarios` selects a subset. Prompts name neither Quill nor its tools, and contain
no instruction to use installed guidance. The generated fixture has a production controller,
a service, a compiled standard-test caller, and two real Git commits. Expected answers are
fixture facts; the change-plan expectation comes from an independent live MCP snapshot.
For the primary action, its exact snapshot code, exact snapshot description, or the literal
`code — description` combination is accepted, not an arbitrary paraphrase.
The first scorer incorrectly rejected correct descriptions;
this was a rubric defect, not failed task reasoning.

Report schema version 2 distinguishes successful use of Quill (`quill_used`) from full
workflow conformance and answer correctness (`passed`, `criteria_passed`). A full pass requires
successful client completion, a successful `get_overview` as the first **Quill** call,
a successful task-specific semantic tool, exact typed answer facts, unchanged fixture inputs
and build outputs, no recorded edit calls or recognized normal shell build invocations, and
unchanged Codex user configuration. Reading instructions before Quill is not a failure;
`source_before_quill` separately records preceding source calls. This is not a full arbitrary
shell-program audit or a claim that every possible source search was classified.

Codex start/completion events are correlated by call ID to preserve invocation order, including
parallel completions. Claude `tool_use` records are correlated with `tool_result` IDs; an
unpaired call, empty result, MCP error, or embedded structured error is not successful evidence.
Router `execute_tool` calls retain the underlying semantic tool name. Catalog mentions,
resource discovery, and another server's calls cannot satisfy adoption. Normalized traces and
answers are retained, not raw client stderr or indexed source payloads. Client versions are
recorded; model defaults remain inherited, so the report does not establish a model comparison.

Unavailable clients are counted instead of disappearing from the denominator. Incremental
reports retain the planned total and remaining runs; partial completion cannot report
`all_passed=true`. A failed criterion makes the final process exit non-zero, as does unavailable
coverage or failed wire metadata. Fixture/config mutations stop the diagnostic without an
automatic rollback. Codex's user-config hash check records only a boolean. Claude global
configuration is not measured; both clients' project fixture files are checked. Derived Git
and Quill index state is excluded, while symlinks are recorded without following their targets.

`--wire-only` checks native MCP metadata without inference and makes no adoption claim. Native
cases use each installed client's existing authentication and can consume inference usage.
The default matrix has four client/config cases × five scenarios × `--samples` requests;
use the explicit two-case command above for a smaller run. No tool selection, model selection,
persistent trust, or paid request is added to ordinary `quill init`.

The adoption matrix explicitly offers Claude `Read`, `Grep`, `Glob`, and narrow read-only Git
`Bash` approvals in addition to Quill. It does not auto-approve unrestricted Bash or edits.
Codex retains the selected native sandbox. The original native workflow probe keeps its
existing permissions unless this source-read option is requested. These are bounded headless
client configurations, not a claim about unrestricted interactive sessions; fixture-only scope
is also an agent instruction, not a complete filesystem isolation boundary.

On October 4, with Codex CLI 0.147.0 and Claude Code 2.1.287, the two-client five-task
matrix observed successful task-specific Quill calls in all 10 runs without prompt reminders.
Codex passed the full rubric in 5/5 tasks. Claude omitted `get_overview` in 5/5 tasks, including
the rerun with ordinary source-search tools available. Accordingly the matrix intentionally
exits 1, with 5 full passes and 5 policy failures, rather than hiding the orientation deviation.
This is evidence of adoption in these fixtures, but not conformance to the complete startup
workflow, and it does not establish why Claude skipped orientation.

Captures are `target/benchmarks/quill-adoption-task-matrix-final.json` (before source-search
permissions were expanded) and `target/benchmarks/quill-adoption-task-matrix-source-reads.json`.
The latter retains one presentation-only answer mismatch: the exact snapshot action code
combined with its exact description. The final rubric accepts this literal combination;
the separate native `quill-adoption-action-format-regression.json` run confirms correct facts
under that rubric while still failing the missing-overview criterion. Historical captures
were not rewritten or silently rescored. Fixture snapshots remained unchanged, and all Codex
user-config invariance checks passed. Claude global configuration was not measured.

The native wire-only check passed, and all 94 Python unit tests passed, including scorer
negative cases, CLI exit behavior, unavailable coverage, mutation-stop behavior, and preventing
raw stderr from reaching reports. JVM runtime and managed instructions are unchanged in this
milestone; the existing indexed tool contract was refreshed and returned a satisfied
`complete`/verified receipt. Codex trace handling follows its
[documented JSONL event stream](https://learn.chatgpt.com/docs/non-interactive-mode).

### Native profile overhead comparison (no inference)

```bash
python3 scripts/quill_profile_benchmark.py \
  --project /absolute/path/to/indexed/project --target org.example.Service \
  --samples 3 --output /absolute/path/outside/project/profile-report.json
```

This separate benchmark compares `full`, `core`, and `router` without launching Codex/Claude,
selecting models, changing client configuration, or running builds. It requires an existing
compiled/indexed project. Starting Quill can refresh derived index state. Every sample uses a
fresh native MCP process; profile order rotates across samples. OS caches are uncontrolled and
this is **not** cold-index performance. Startup measures through initialize; lazy index loading
belongs to the first `get_overview` query, not to the handshake metric.

The benchmark records catalog tool count, compact UTF-8 JSON size, instruction bytes, startup
and request latency, request counts, and serialized MCP result bytes. These are neither raw
wire bytes nor model tokens. Each hidden router query explicitly runs uncached `search_tools`
and then `execute_tool`; discovery bytes and latency are included. The deterministic harness
knows the intended semantic tool and searches its exact name. This is one successful search,
not natural-language/model-driven discovery, which may need more attempts. A client caching
previous discoveries could instead skip that search. Actual agent discovery remains a separate test.

Orientation, symbol navigation, dependencies, and change workflow are compared across all
three profiles. History is compared across full/router; its absence from core is explicit,
not silently treated as success. Semantic payloads must be exactly equal across participating
profiles and samples, excluding only nested `_meta` provenance. Warnings, gates, completeness,
and facts remain part of equality. This proves equivalent returned evidence for these bounded
queries, not independent correctness of every fact. Missing shared coverage, failed calls,
different answers, incomplete runs, or changed fixture inputs/outputs fail the benchmark.
Partial/error reports omit remote error bodies and source payloads. Reports include Git state,
binary SHA-256, host platform, negotiated protocol, and server version.

On October 4, a three-sample run against this repository's `McpToolProfile` observed:

| Profile | Exposed tools | Catalog JSON bytes | Hidden query calls | History |
| --- | ---: | ---: | ---: | --- |
| full | 58 | 44,308 | 1 direct | available |
| core | 27 | 21,064 | 1 direct | unavailable |
| router | 3 | 1,490 | 2 including discovery | available |

All nine process runs passed semantic equality and input/output invariance. Catalog size was
approximately 52.5% smaller with core and 96.6% smaller with router than full. Router responses
also include discovery overhead, so catalog savings do not imply lower total conversation cost.
Three native samples with uncontrolled caches do not establish a meaningful latency winner.
The runtime and generated default profile remain unchanged (`full`); a default switch requires
native-agent task-quality/adoption comparisons, especially for Claude's missing orientation.
The normalized capture is `target/benchmarks/quill-profile-comparison.json` (ignored artifact).

### Native-agent task quality across MCP profiles

```bash
python3 scripts/quill_profile_adoption.py \
  --cases project_read_only claude_project --scenarios navigation change_plan \
  --samples 1 --output target/benchmarks/quill-profile-adoption-native.json
```

This opt-in comparison uses the existing unprompted adoption rubric with real headless clients.
It consumes their configured inference usage; no model or authentication is selected. Neither
ordinary init nor the generated default profile changes. `--wire-only` compiles/indexes temporary
fixtures and checks all selected profile catalogs without any native model request. It reports
wire success separately and never claims adoption. `quill_adoption_diagnostic.py --tool-profile`
also supports individual profile investigations; its default remains `full`.

Only generated temporary fixture configuration is changed. Both clients receive the selected
Quill launcher arguments; an independent native MCP connection checks safety annotations,
instructions and profile catalog before inference. Router workflow expectations come through
`search_tools` and `execute_tool`. That wire check is not enumeration of the running AI client's
catalog. Each profile/sample uses a fresh fixture and each task a fresh headless conversation;
profile order rotates across samples, but case/task order and inherited model defaults are not
randomized. This is neither a controlled causal experiment nor a model comparison.

The default selection covers five scenarios and two clients: history is deliberately excluded
from core's inference runs, with the unsupported cells retained in the report (28 supported
requests and 2 exclusions per sample). Live catalog evidence checks that exclusion. Coverage
requires exactly the planned profile/case/scenario/sample cells; duplicate or missing cells,
unavailable clients, wrong facts, failed semantic calls or startup-policy failures cannot become
a full pass. Reports distinguish answer correctness, task-specific tool adoption, orientation,
and the complete rubric. A non-zero exit for policy failures is expected evidence, not a harness
crash. Fixture or Codex-config mutations stop further runs without rollback; Claude global
configuration is not measured. Partial/exception reports retain the planned denominator and
omit raw remote error bodies, stderr and indexed source contents.

Elapsed time and provider-reported token counts retain their sample counts. Missing usage is
not zero. Codex cached-input counts and Claude cache-read/cache-creation counts are separate;
they must not be compared as equivalent fields or interpreted as billed cost. A small catalog
does not establish lower total model usage, because discovery and additional inference rounds
also contribute. The JSONL event handling follows the official
[Codex non-interactive documentation](https://learn.chatgpt.com/docs/non-interactive-mode).

On October 4, the command above completed all 12 bounded runs (one sample of navigation and
change planning per client/profile). All answers matched the independent expected facts.

| Client | Profile | Correct answers | Task-specific Quill call | Overview first | Full rubric |
| --- | --- | ---: | ---: | ---: | ---: |
| Codex | full | 2/2 | 2/2 | 2/2 | 2/2 |
| Codex | core | 2/2 | 2/2 | 2/2 | 2/2 |
| Codex | router | 2/2 | 2/2 | 2/2 | 2/2 |
| Claude | full | 2/2 | 2/2 | 0/2 | 0/2 |
| Claude | core | 2/2 | 2/2 | 1/2 | 1/2 |
| Claude | router | 2/2 | 1/2 | 2/2 | 1/2 |

Codex used the router's discovery/invocation path successfully in both tasks. Claude used it
for change planning, but answered router navigation through `get_overview` followed by `Grep`,
without a task-specific semantic query. That correct source-based answer does not satisfy this
benchmark's Quill-adoption criterion. Thus reducing the catalog can coincide with better startup
orientation but less semantic-tool use in these particular samples. It does not prove the
profile caused either behavior. The comparison intentionally exits 1 (8/12 full passes), while
wire checks, fixture invariance and every Codex user-config invariance check passed.

Captures are `target/benchmarks/quill-profile-adoption-native.json` and its profile-specific
siblings; `target/benchmarks/quill-profile-adoption-wire-final.json` separately records three native
wire checks, including core's history exclusions. No default switch is justified by two tasks
per profile/client. A next experiment should repeat the paired tasks before changing Claude
startup guidance or selecting a reduced default. Python harness tests and compile-only JVM
verification are separate checks; Quill does not index the modified Python harness sources.
All 116 Python unit tests passed; the unchanged JVM tool contracts returned a satisfied
`complete`/verified receipt after compile-only freshness verification.

### Repeated paired adoption tasks

```bash
python3 scripts/quill_profile_adoption.py \
  --cases project_read_only claude_project --scenarios navigation change_plan \
  --samples 2 --output target/benchmarks/quill-profile-adoption-repeat.json
```

On October 4, this repeat completed all 24 planned native requests, with no unavailable
clients or missing cells. Profile order was full/core/router in sample 1 and core/router/full
in sample 2. Prompts, generated guidance and runtime defaults were unchanged. The six wire
checks passed; all fixture invariance checks and all 12 Codex user-config invariance checks
passed. Claude global configuration remains outside the measured scope.

Codex passed every criterion in all 12 requests: both tasks in both samples for every profile.
Claude results separate successful semantic-tool use from the required initial overview:

| Claude profile | Navigation task call | Navigation overview | Planning task call | Planning overview | Full rubric |
| --- | ---: | ---: | ---: | ---: | ---: |
| full | 2/2 | 0/2 | 2/2 | 1/2 | 1/4 |
| core | 2/2 | 0/2 | 2/2 | 1/2 | 1/4 |
| router | 0/2 | 0/2 | 2/2 | 2/2 | 2/4 |

Both Claude router-navigation traces contained only `Grep`, with no Quill call, and their final
answers could not be parsed as the requested JSON object. This is an output-format and adoption
failure, not evidence that a parsed factual answer was wrong. The other 22 answers matched
their expected JSON facts. Router change planning successfully used overview, discovery and
hidden invocation in both Claude samples. Full/core navigation successfully used `search_symbols`
but skipped overview in all four Claude cases. Thus MCP reachability, semantic-tool adoption,
startup orientation and final-answer format are distinct concerns in these observations.

The comparison intentionally exited 1: 16/24 full-rubric passes despite complete coverage and
passing transport/safety checks. This does not establish a profile's causal effect or a general
client success rate. Two samples, inherited models, fixed case/task order and uncontrolled caches
remain limitations. The initial experiment's apparent router-orientation advantage did not hold
for repeated navigation. No default-profile switch or forced startup hook is justified here.
A useful next controlled experiment is a fixture-only Claude guidance variant, holding the
full catalog and these paired tasks fixed, measuring overview compliance separately from
semantic-tool adoption and answer correctness before changing generated production guidance.

Profile reports now add `groups.<profile:case>.by_scenario`, including planned/completed/remaining
counts, exact-cell coverage, failures versus unavailable clients, criterion counts, and separately
sampled latency/provider usage. Missing tasks stay visible even when another task passed; duplicate
cells cannot satisfy task coverage. An unavailable client cannot contribute success even if its
record also contains a contradictory positive evaluation. Existing aggregate fields are preserved.
The repeat was started before this additive reporting change; its original report contains the
raw normalized runs and aggregate groups. The table above was recomputed from those runs with
the updated `summarize`, without additional inference.

Captures are `target/benchmarks/quill-profile-adoption-repeat.json` and six profile/sample siblings
(ignored artifacts). All 119 Python unit tests passed, including three new breakdown regressions.
Compile-only JVM verification returned a satisfied `complete`/verified receipt for unchanged MCP
contracts; it does not validate the Python harness, which is covered by its own tests.

### Fixture-only Claude startup guidance comparison

```bash
python3 scripts/quill_native_guidance_comparison.py --wire-only \
  --output target/benchmarks/quill-guidance-comparison-wire.json
python3 scripts/quill_native_guidance_comparison.py --timeout 120 \
  --output target/benchmarks/quill-guidance-comparison-native.json
```

This opt-in experiment compares generated `CLAUDE.md` unchanged (`baseline`) with a short
ordered startup checklist prepended outside the managed block (`overview_first`). Only fresh
temporary fixtures are modified. Production templates, repository guidance, client settings
and default profiles are unchanged. The diagnostic rejects experimental guidance with any
client case other than Claude or any catalog other than full. User prompts remain unprompted:
neither task names Quill or its tools. Each task uses a fresh native conversation.

Both variants use the full catalog and the same navigation/change-planning tasks. The runner
checks matching hashes of the baseline Claude block, AGENTS guidance, tool catalog and annotations,
server instructions, and task prompts, plus identical client versions. Independent wire checks
validate the full profile before each child's inference; cross-variant controls are compared
after each child finishes. Missing, duplicate or wrong task cells cannot count as complete
coverage. Invariance failures stop further requests without rollback. Exception reports retain
the planned denominator and only the exception type, not remote error bodies. `--wire-only`
makes no model requests and cannot report adoption success.

On October 4, all four planned native Claude requests completed:

| Guidance | Correct answers | Successful task query | Overview first | Full rubric |
| --- | ---: | ---: | ---: | ---: |
| baseline | 2/2 | 2/2 | 0/2 | 0/2 |
| overview_first | 2/2 | 2/2 | 2/2 | 2/2 |

Baseline called `search_symbols` and `change_session` directly. The variant called `get_overview`
before those same task-specific queries. Every fixture invariance check, both full-profile wire
checks and the cross-variant controls passed. The comparison intentionally exits 1 because
baseline fails the orientation rubric; this is not a harness crash or MCP connectivity failure.
Claude global configuration invariance is not measured.

This is one sample per task/variant, with inherited models/auth, fixed task order and uncontrolled
caches. It does not establish a causal effect or a stable improvement; no production-guidance
change is justified yet. Use `--samples 2` or more to repeat with alternating variant order;
even-numbered samples run the checklist before baseline. Provider usage counts retain their own
sample counts, are not billed costs, and missing values are not zero.

Captures are the two command outputs and their variant-specific siblings under ignored
`target/benchmarks/`. All 126 Python tests passed, including fixture-only guidance, selection
guards, exact coverage, variant rotation, control drift, mutation-stop and sanitized exception
regressions. Compile-only JVM verification returned `complete`, a satisfied gate and a verified
receipt for unchanged MCP contracts; Python correctness is established by its separate tests.

### Repeated startup guidance and expanded task coverage

```bash
python3 scripts/quill_native_guidance_comparison.py --samples 2 --timeout 120 \
  --output target/benchmarks/quill-guidance-comparison-repeat.json
python3 scripts/quill_native_guidance_comparison.py --samples 1 --timeout 120 \
  --scenarios usages dependencies history \
  --output target/benchmarks/quill-guidance-comparison-expanded.json
```

The comparison now accepts `--scenarios` from the diagnostic's existing task catalog. Its
default remains navigation/change planning; selection is forwarded to both variants, included
in the report, and used for the exact planned-cell denominator. Duplicate scenarios are rejected
before reading the binary or launching clients. This does not change task prompts, fixtures,
production guidance, MCP defaults, client permissions or the definition of adoption success.

On October 4, the repeat completed eight requests across two samples, rotating variant order
from baseline/checklist to checklist/baseline. The expanded run completed six more requests,
one per variant for usages, dependencies and history. All six child wire/control checks passed,
all 14 answers matched their independent expectations, and all fixture-invariance checks passed.
Claude global configuration invariance is not measured. Both commands exited 1 because rubric
failures are deliberately propagated; neither experiment crashed or lacked task coverage.

| Tasks | Guidance | Correct answers | Successful task query | Overview first | Full rubric |
| --- | --- | ---: | ---: | ---: | ---: |
| Navigation/planning, two samples | baseline | 4/4 | 4/4 | 0/4 | 0/4 |
| Navigation/planning, two samples | overview_first | 4/4 | 4/4 | 4/4 | 4/4 |
| Usages/dependencies/history, one sample | baseline | 3/3 | 2/3 | 0/3 | 0/3 |
| Usages/dependencies/history, one sample | overview_first | 3/3 | 3/3 | 2/3 | 2/3 |

The checklist's navigation/planning result repeated, and usages/dependencies also began with
overview followed by the appropriate semantic query. History was an important exception:
baseline answered through a successful `git log` Bash call without Quill. The checklist variant
first attempted `git -C <fixture> log`, whose normalized trace records an error, then successfully
called `get_file_history`, without ever calling overview. The fallback is not evidence that the
variant preferred Quill initially. The harness allows `git log/show/status/diff` Bash prefixes,
not their `git -C` forms; this is a possible permission confound, but the sanitized trace does not
establish the shell error's cause. Do not interpret the 2/3 versus 3/3 semantic-query count as an
instruction-only effect.

The experimental prefix explicitly addresses "code navigation and change analysis" and does
not name history. That is a candidate explanation for the exception, not a demonstrated cause.
A next fixture-only variant should make Git/history coverage explicit, hold the permission
policy fixed, and distinguish initial semantic-tool selection from error-driven fallback.
Production templates and default profiles remain unchanged: tiny inherited-model samples,
fixed task order, uncontrolled caches, prepend placement and the shell-permission confound
still limit generalization. The experiment establishes observed task-specific behavior, not
a guaranteed adoption rate or causal improvement.

Reports and six child captures remain ignored under `target/benchmarks/`. All 128 Python unit
tests passed, including new selection/denominator and duplicate-rejection regressions. The
recommended compile-only JVM command passed without running tests; the unchanged JVM contracts
returned `complete`, a satisfied gate and a verified receipt. Quill does not index the changed
Python harness; its correctness is covered by the Python tests and these native experiments.

### Fixture-only Claude SessionStart experiment

```bash
python3 scripts/quill_native_guidance_comparison.py \
  --variants baseline session_start --scenarios navigation change_plan history \
  --samples 2 --timeout 120 \
  --output target/benchmarks/quill-session-hook-comparison.json
```

`--variants` preserves the original baseline/overview_first default. The new `session_start`
treatment changes only the generated fixture's `.claude/settings.json`: a five-second command
hook emits the **same** experimental checklist previously prepended to CLAUDE.md. Both instruction
files, task prompts, server instructions and the full 58-tool catalog remain identical between
these two arms. This isolates startup context injection from instruction-file changes; it does
not test Repowise's live freshness context, PostToolUse enrichment or automatic skills.

The [Claude hook reference](https://code.claude.com/docs/en/hooks#sessionstart-decision-control)
documents `hookSpecificOutput.additionalContext` for SessionStart. Its workspace-trust section
states that settings hooks run in `-p` mode; this experiment does not establish interactive
startup behavior before a user accepts workspace trust.

The helper reads only hook stdin, validates event/source/cwd, emits context and records its digest
in a harness-owned temporary ledger **outside** the measured project. It does not query MCP,
read source, build, edit project files or install global hooks. All fixture files remain covered
by the original invariance check, with no additional exclusions. Failures are silent and exit 0;
the benchmark separately fails its rubric if the expected per-request emission is absent.
Ledger evidence proves helper emission, not that Claude obeyed it. `response_events` counts all
client hook-response events, not exclusively this helper. Wire-only mode cannot attest firing.

On October 4, Claude Code 2.1.287 completed all 12 requests, rotating baseline/hook to hook/baseline.
All four wire/control checks passed; all 12 answers and fixture-invariance checks passed. Hook
context was emitted exactly once with the expected digest in all six treatment requests.

| Task, two samples | Variant | Correct answers | Task query succeeded | Overview first |
| --- | --- | ---: | ---: | ---: |
| Navigation | baseline | 2/2 | 2/2 | 0/2 |
| Navigation | session_start | 2/2 | 2/2 | 2/2 |
| Change planning | baseline | 2/2 | 2/2 | 0/2 |
| Change planning | session_start | 2/2 | 2/2 | 2/2 |
| Git history | baseline | 2/2 | 2/2 | 0/2 |
| Git history | session_start | 2/2 | 1/2 | 0/2 |

History started with Bash in every request. Both baseline queries and one hook query attempted
`git -C ... log`, recorded a shell error and then successfully used `get_file_history`; the
remaining hook query used successful `git log` without MCP. These fallbacks must not be counted
as initial Quill preference. The existing permission policy allows `git log`, not `git -C`;
the normalized traces do not establish the exact error cause. There is no evidence here that
the hook increases initial task-specific semantic adoption: navigation/planning already used
the appropriate tools without it. The observed improvement is **overview ordering**, 4/4 versus
0/4 for those tasks. History still violates that ordering despite confirmed hook emission.

The command deliberately exits 1 for rubric failures, not a crash, incomplete coverage or
transport failure. Median total request time was 8.294 s baseline and 9.621 s hook; these tiny,
task-mixed, inherited-model/cache samples are not an overhead or cost estimate. Global Claude
configuration invariance is not measured, and no production templates or defaults were changed.
The checklist still mentions navigation/change analysis rather than explicitly naming history.
Next: compare the same history-aware text in CLAUDE.md and SessionStart in a paired experiment,
separating first tool selection from failure-driven fallback before considering production hooks.

Reports and four child captures are ignored under `target/benchmarks/`. All 136 Python tests
passed, including fail-open/input guards, unchanged instruction files, selected-variant rotation,
wire-only non-attestation and missing/matching hook-emission scoring. The recommended JVM
compile-only command succeeded without running JVM tests; the unchanged JVM contracts returned
`complete`, a satisfied gate and a verified receipt. That receipt does not verify the Python helper.
