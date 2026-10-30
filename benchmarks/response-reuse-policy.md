# Quill response reuse: mandatory benchmark audit

Applies to every new Codex and Claude benchmark, including native prefetch.
Accuracy and token savings alone do not establish successful consumption of Quill.

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

Current limitation: the latest short audit detects configuration-query attempts
in Codex rollout, including client rejection. A general cross-tool, semantic
coverage audit for both clients remains to be implemented; regex counts alone
must not be presented as complete detection.
