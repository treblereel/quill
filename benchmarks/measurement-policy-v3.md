# Native CLI measurement: v2 accounting revision 3

Applies to future Codex/Claude comparisons. This changes accounting and planning,
not the question text, independent oracle, native outputs or model permissions.
Historical acquisitions, grades and acceptance decisions remain immutable.

## Separate workloads

Report each client and workload separately, without pooling ratios:

- `independent`: fresh conversation and one MCP per question; count full startup.
- `linked`: one continuous conversation/MCP across all steps; retain real history,
  prefetch state and invalidation. No reset/warmup per step or future-step hints.
- `patch`: mutation plus actual validation. Keep toolchain/permission failures
  visible; do not mix read-only savings with build-environment repair.

The primary comparison is the ratio of summed complete costs on identical
case/client/repeat/prompt keys. Never average per-case efficiency coefficients.
Also retain all-attempt execution totals, including failed/incorrect attempts.
Do not silently replace failed pairs or rerun the worse arm.

## What to capture and charge

Capture raw model requests/responses, provider response IDs/usage, CLI cumulative
counters, session identity and prompt boundaries, MCP initialize count, native
hook delivery, tool attempts/results and clocks. Reconcile the full usage before
acceptance. Preserve internal provider messages, ToolSearch, failed requests and
Codex prewarm. Missing usage is unknown, never free.

Primary totals include prewarm, all model requests and output. Split diagnostic
stages into prewarm, first model request of each question and continuations;
never subtract them from the main result. CLI/MCP readiness belongs in wall time.
Compile/index preparation is a separate measured field, or unknown if not saved.

Show ALL, cached input, uncached input and output. Show new/cache-write/cache-read
separately where the provider exposes them (Claude); unsupported splits stay null.
Show provider/CLI USD estimates with their basis, never label tokens as dollars
or an estimate as a billing statement. Codex subscription cost can be unknown.

Serialized prefix component byte counts/hashes help locate changing instructions,
tools, user/developer messages, tool results and history. They are **not token
counts**. Hook facts may share a message with instructions/question text; do not
invent an exact split or attribute cache misses from a changed hash alone.

For every continuation preserve actions of the previous response, current actions
and the shared response-reuse audit classification. Distinguish catalog discovery,
Quill queries, source search/read and build/other work. Only an explicit review
with delivered fact/range evidence can promote an overlap to redundant checking.
Cycle costs are observed costs, not proven waste.

## Accuracy and useful results

Keep unchanged final-answer factual grades, format and patch correctness separate.
Report accuracy alongside `total ALL / correct final answers`, charging all failed
attempts in the numerator. Zero correct answers means an undefined efficiency,
not zero cost. Unknown usage or grades blocks the metric and automatic acceptance.

Report whether a correctly graded final answer took exactly one model request and
no tool call. Earlier intermediate text has not been graded: do not claim to know
the earliest correct answer or repair an earlier wrong response using a later one.
Per-question provider costs in linked sessions exclude prewarm **only in that
diagnostic view**; session and aggregate totals always retain it.

## Fair pairing and cache policy

Freeze source/build/index identities, model/effort/version, native binary, client
configuration, permissions and oracle/scoring version before acquisition. Source
arm has no Quill hooks, managed instructions, index access or helper leakage.
Use the same question/order/history per pair. Preserve native automatic behavior;
no forced tools, runner hints or automatic answer repairs.

Pre-register repeat count and seed. Randomize pair order and counterbalance AB/BA
across repeats. Keep all attempts; don't stop/repeat based on the preferred result.
Report paired repeat totals and their spread separately; linked turns are correlated,
not independent samples. One repeat or a historical baseline is diagnostic evidence,
not a confirmed causal improvement.

Provider cache is uncontrolled by default. A fresh session or zero cache reads does
not establish a cold cache. A claimed cold/warm experiment requires independently
documented cache controls and provider usage evidence; otherwise label observed
reads/writes only. Do not add warming or cache-busting text to one arm's questions.

## Offline commands and future runner contract

```sh
python3 scripts/quill_measurement_report.py \
  --summary benchmarks/results/cryostat-full-native-current-v4-quill-only/summary.json \
  --reuse-summary benchmarks/results/cryostat-full-native-current-v4-quill-only/reuse-audit/summary.json \
  --output benchmarks/analysis/measurement-accounting-v3

python3 scripts/quill_measurement_report.py \
  --plan benchmarks/results/cryostat-full-native-current-v4-quill-only/protocol.json \
  --repeats 3 --seed 20261101 \
  --output /tmp/quill-paired-plan-review
```

The plan is a non-executable schedule preview, **not permission to acquire it**.
A future acquisition runner must freeze/validate the `measurement_contract`,
schedule and all inputs at preflight, preserve prompt-boundary timestamps and
session identities, then run the normal native client. Existing frozen runners
are deliberately not patched. Paid runs require a separate user instruction.

The report refuses to overwrite saved results. Historical source totals remain
available when raw sessions are not locally restored, but detailed stages, prefix
and first-request statistics remain unknown. Full/raw totals must reconcile.

Acceptance requires accuracy nondecrease, ALL and uncached nonincrease, complete
evidence, and response-reuse review. This descriptive offline report cannot grant
acceptance, rewrite old grades, or establish 4x by removing startup/failed cases.
