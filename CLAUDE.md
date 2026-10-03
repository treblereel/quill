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
## Quill — Codebase Intelligence (MCP)

Before broad code search, dependency or impact analysis, or running a large test suite,
use ToolSearch to load the relevant Quill tools. Prefer Quill for project-wide semantic
questions: implementations, annotations, endpoints, DI, dependency graphs, affected tests,
build problems, generated code, Git history, and change risk.

Use `rg` and direct source reads for an exact literal, a known file, or one concrete
occurrence. Do not query both by default. Verify Quill results in source when the index
reports stale, partial, unknown, or unsupported evidence.
<!-- quill:managed:end -->
