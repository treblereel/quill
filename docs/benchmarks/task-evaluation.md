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
