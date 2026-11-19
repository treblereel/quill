# Quill response reuse: mandatory benchmark audit

Applies to every new Codex and Claude benchmark, including native prefetch.
Accuracy and token savings alone do not establish successful consumption of Quill.
Full-cost accounting, independent/continuous workloads, cache and pairing rules:
[v2 accounting revision 3](measurement-policy-v3.md).

For each suspected repeat, preserve the evidence chain:

1. The original query or prefetch, index/source identity, covered facts, limits and freshness.
2. The actual model request receiving that response, before the next model action. A server response or successful hook alone does not prove delivery.
3. The subsequent attempted tool call or source read, its arguments and result. Include calls rejected by the client/schema before MCP, internal discovery and model-side failures; do not rely solely on native traces.
4. Which already supplied facts were requested again, and which requested facts were genuinely missing. Changed arguments or wording do not by themselves establish a new information need.
5. The extra provider cycles, ALL and uncached tokens, time, and the evidence supporting that attribution. Mark unknown attribution explicitly; keep prewarm separate and included in totals.

Red flags include repeated covered queries, invented arguments or views to obtain
unsupported certainty, and source reads that only reconfirm already supplied facts.
Changed inputs, stale/ambiguous/truncated evidence, a genuinely different scope or
explicit user-requested verification can justify another read; document the reason.
Do not treat unsupported runtime values as missing facts that another static query
can establish.

Reports must show repeated attempts, client-rejected attempts, redundant source
verification, justified follow-ups and unresolved cases separately for each client.
Use the result as an acceptance review gate: a new or worsened unexplained red flag
blocks automatic acceptance even if accuracy and token gates pass. An existing red
flag is not silently cleared by aggregate savings.

“Did not receive”, “ignored”, “did not understand”, “did not trust” and “needed more
evidence” are different hypotheses. State only what logs establish; model intent
is unknown unless supported by explicit evidence. Preserve failed attempts and
original answers; no repairs or rerolls to erase a flag.

## Offline implementation

Run `scripts/quill_response_reuse_audit.py SESSION... --output NEW_AUDIT_DIRECTORY`.
It reads Codex Responses HTTP/SSE/WS and Claude request/SSE captures, matches native
results to actual model inputs, and audits native prefetch. Saved sessions are
never modified; the output directory must be separate. No captured command is
executed and no provider is called.

The report distinguishes `red_flag`, `justified_followup`, `unresolved` and
`discovery`. Exact covered queries, contained complete configuration queries,
deterministic repeated errors, unsupported runtime queries and covered source
ranges have explicit checks. Dynamic arguments, ambiguous cross-tool coverage,
source overlap without a supplied body/range, changed inputs and capture gaps
require review. Neither a citation nor a successful hook proves a complete answer.

`review_required` blocks automatic clearance; it is not a correctness score or
proof that the model misunderstood Quill. Existing acceptance decisions and raw
answers are not rewritten. Callers must compare old/candidate flags separately
from accuracy/token/time gates. Do not interpret CLI exit status 0 as acceptance:
it means the audit report was produced, including unresolved/failed sessions.

Explicit human review can be supplied with `--reviews FILE`. The JSON maps finding
IDs to `classification`, nonempty `reason` and `evidence`. Original automatic
classifications remain in the report; coverage failures cannot be cleared by an
annotation. This supports user-requested verification and semantic cases that
cannot safely be decided mechanically.

Limitations: this is a conservative detector, not a general proof of natural-language
intent or arbitrary program equivalence. Literal JS calls are parsed without eval;
computed dispatch/aliases remain unresolved. Oversized/deep/malformed input cannot
be declared clean. Integral JSON numbers such as `1`/`1.0` are equivalent for
matching; booleans remain distinct. Only observed cycle costs are reported; waste
attribution stays unknown. Unknown/incomplete usage is never counted as zero.
