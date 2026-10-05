## Quill — Codebase Intelligence (MCP)

This project is indexed by Quill. **Always prefer Quill MCP tools over grep/find/Explore agents** for:

- **Searching classes:** `search_classes` — faster than grep, supports wildcard patterns (`*Service`, `*Strategy*`)
- **Dependency analysis:** `get_dependencies` — what a class depends on and what depends on it
- **Risk assessment:** `assess_change_risk` — class blast radius or file risk from criticality, churn, bus factor, and coupling
- **Project overview:** `get_overview` — call first to orient (class/bean counts, architecture hubs, problems)
- **Git hotspots:** `find_git_hotspots` — most frequently changed files/classes
- **Co-change analysis:** `find_co_changed_files` — files that change together (hidden coupling)
- **Beans:** `list_beans` — list/filter beans by scope, kind, qualifier (CDI and Spring)
- **Injection points:** `list_injection_points` — injection resolution status for a bean
- **External deps:** `list_external_dependencies` — third-party library usage

**Tip:** Add `"alwaysLoad": true` to the quill server in `.mcp.json` so tool schemas are loaded eagerly (no ToolSearch needed).

<!-- quill:managed:start -->
<!-- quill:instructions:v2 -->
## Quill — Codebase Intelligence (MCP)

At the beginning of a coding or code-analysis task, call `get_overview`. Before broad
code search, dependency or impact analysis, or running a large test suite, prefer Quill
for project-wide semantic
questions: implementations, annotations, endpoints, DI, dependency graphs, affected tests,
build problems, generated code, Git history, and change risk.

Use `rg` and direct source reads for an exact literal, a known file, or one concrete
occurrence. Do not query both by default. Verify Quill results in source when the index
reports stale, partial, unknown, or unsupported evidence.

For a code change, call `change_session` before editing source or choosing a build.
This is the required first workflow step, not a suggestion to wait for a user reminder.
The call returns one stateless snapshot of the plan,
verification evidence, and ordered next actions; repeat it with the same targets and
change description to refresh the snapshot, or omit targets after editing to infer them
from dirty JVM sources. Use `plan_change` or `verify_change` when only that focused view
is needed. Keep the default `view=auto` and summary detail so only the current phase is
returned; request another view or full detail only to inspect omitted evidence. When
acting on a snapshot, follow `directive.primary_action` first. Treat `phase_gate`
required evidence and required `review_checklist` items as blockers; advisory checklist
items are prompts to inspect evidence, not proof of human review. Reuse `action_id` to
recognize unchanged guidance after refreshing the same session. Views project evidence
and preserve the same directive. Inspect its `evidence_snapshot`; use its
`preparation_command` to compile stale test evidence or its `retry_command` after fixing
diagnostics, then refresh the session. Incomplete test evidence alone does not require
test execution. When build evidence is
missing or stale, prefer the
`verification_plan` command whose scope is `quick_compile`: run its exact `argv` from
`working_directory` in the external shell, then verify again. This compiles production
and standard test sources without running tests. Treat `focused` and `module_fallback` as
separate test recommendations; run them only when the task or user requires tests. Quill
recommends commands but never executes a build or persists change-session state. Report
completion only when the phase is `complete`, its gate is satisfied, and the verification
receipt is verified.
<!-- quill:managed:end -->
