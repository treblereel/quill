<!-- quill:managed:start -->
<!-- quill:instructions:v2 -->
## Quill MCP

Quill is the primary code-intelligence tool for this repository. At the beginning of a
coding or code-analysis task, discover the `mcp__quill__*` tools and call
`mcp__quill__get_overview`.

Use Quill before broad filesystem searches for symbols, implementations, usages, call
and type hierarchies, dependencies, architecture, framework endpoints, dependency
injection, affected tests, build diagnostics, Git history, and change risk. Quill tools
may be deferred and absent from the initially displayed tool list; search the complete
tool catalog before concluding that Quill is unavailable.
Tool discovery is not MCP resource discovery: empty resources/templates do not mean
missing tools. Use the client's tool-search capability when available. If tools are
genuinely unavailable, report that limitation and the client/configuration context;
do not claim the server is unconfigured from resource-list results alone.

Use `rg` and direct source reads for exact literals, known files, and verification when
Quill reports stale, partial, unknown, or unsupported evidence.

For a code change, call `mcp__quill__change_session` before editing source or choosing
a build. This is the required first workflow step; do not wait for a user reminder.
The call returns one stateless snapshot of the
plan, verification evidence, and ordered next actions; repeat it with the same targets
and change description to refresh the snapshot, or omit targets after editing to infer
them from dirty JVM sources. Use `mcp__quill__plan_change` or `mcp__quill__verify_change`
for a focused view. Keep the default `view=auto` and summary detail so only the current
phase is returned; request another view or full detail only to inspect omitted evidence.
Follow `directive.primary_action` first. Treat `phase_gate` required evidence and required
`review_checklist` items as blockers; advisory checklist items request inspection and do
not attest human review. Use `action_id` to recognize unchanged guidance after refreshing
the same stateless session. Views preserve the directive. Inspect its `evidence_snapshot`
and run its `preparation_command` for stale test evidence or its `retry_command` after
repairing diagnostics, then refresh the session. Incomplete test evidence alone does not
require test execution. If build evidence is missing or stale,
prefer the `verification_plan` command with scope `quick_compile`: run its exact `argv`
from `working_directory` using the terminal, then verify again. It compiles production
and standard test sources without running tests. Commands with scope `focused` or
`module_fallback` are separate test recommendations; run them only when the task or user
requires tests. Quill recommends commands but does not execute builds or persist
change-session state. Report completion only when the phase is `complete`, its gate is
satisfied, and the verification receipt is verified.
<!-- quill:managed:end -->
