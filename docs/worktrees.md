# Git worktrees

Each working copy owns its `.quill` indexes, build events, source state and compiler
input identity. Git objects and repository identity can be shared; branches, staged
changes, compiled outputs and compiler models are selected from the requested checkout.
Quill does not run a project build merely because a worktree is discovered.

## Select the working copy

Launch a server explicitly for the checkout being edited:

```bash
quill init --project /path/to/checkout
quill --mcp --project /path/to/checkout
```

A client can keep the same server and pass the absolute working-copy/JVM project root
in each project-scoped tool request. The copy need not have been configured at startup:

```json
{"project": "/path/to/feature-checkout", "pattern": "MessageHandler"}
```

For example, these are arguments for `search_symbols`. Pass `project` on the initial
`get_overview` and subsequent calls too. The client or agent supplies the path; the
server cannot infer the caller's current editor directory. Managed AGENTS.md/CLAUDE.md
instructions installed by `quill init` describe this convention. Existing configuration
files do not need to be rewritten when selecting a different copy.

Quill canonicalizes the path and opens that copy's own existing index. A missing,
unavailable or foreign index produces an error for the requested copy, never a fallback
to the server's original project. Initialize each copy with `quill init` before querying.
An absolute source-file path can also locate its containing indexed project. Nested
indexed projects and linked worktrees take precedence over their configured parent.
An explicit project directory containing Maven/Gradle build metadata selects that
project itself, even if its index is not yet available.

Without `project` (or with a blank value), the configured default behavior is unchanged:
one project is queried directly; repeated `--project` options or workspace mode keep
their existing aggregation. Request-only contexts never enter that default scope or
workspace inventory. Their database/readiness contexts are cached for up to 32 recent
roots; eviction retires readers and subscriptions without removing on-disk indexes.
Parallel requests select their contexts independently; there is no mutable active project.

Configured names and exact `project_id` selectors remain supported. Workspace mode also
supports its existing relative paths and Maven coordinates. Those selectors address the
configured scope; use an absolute path for a copy outside it. An ambiguous configured
selector is rejected. Workspace discovery still only scans its configured root/depth;
request routing does not automatically discover every Git worktree.

Compare `_meta.project_root` with the working copy being edited.

## Identity and index ownership

Responses expose `project_id`, `worktree_id`, `repository_id` (for Git projects),
`project_root` and `worktree_root`. IDs describe physical local storage, using canonical
paths and filesystem directory identity; they are not portable clone identifiers.
Branch changes and commits do not change the identity. Moving or recreating a checkout
requires a new index. Symlink aliases address the same physical project.

A nested independent JVM project has its own project identity within the same worktree.
Submodules have their own Git repository identity. Detached and unborn HEADs are valid;
broken Git pointers and unreadable repository state are reported as unavailable or
unknown, rather than as verified clean state.

New indexes record their owner. Copying `.quill` between working copies cannot make
the copied generations queryable, and copied build events cannot automatically repair
a foreign index. Run `quill init` in the destination to create its own index. Pre-identity
indexes are accepted only when their recorded absolute root resolves to the requested
root; reindex them to obtain the stronger filesystem identity checks. No SQLite schema
upgrade is required for the additional metadata.

## Builds, caches and removal

Build each worktree separately. Outputs within the working copy are checked against
live build/source freshness. External output directories, including symlinks to shared
directories, have `ownership_unknown`; timestamp freshness alone cannot establish
which checkout produced them. Prefer separate output directories inside each checkout.

Java and Kotlin compiler cache keys include the project/worktree identity and compiler
inputs. Each language keeps source subscriptions and independent debounce state for up
to two recently queried module/source-set contexts, including contexts in different
worktrees. Editing either retained context schedules background analysis. Selecting a
third context evicts the least recently selected subscription and cancels its queued
warmup; background activity does not promote it in the LRU. Returning to an evicted
context recreates its subscriptions. A request waits for a running worker operation,
then recollects its own inputs; worker operations remain serialized per language.
An inactive background warmup does not switch the worker away from the most recently
queried JDK/K2 version. Copies requiring another runtime are analyzed on their next
foreground request instead.
Dependency artifacts retain their existing content-based caching. Removing a
working copy retires its database readers and detaches its active source subscriptions
and queued warmup. Shared bounded worker caches may retain inactive models until normal
eviction; identity keys prevent those models from being reused by a recreated checkout.
Already resolved reads are checked again before query execution. Workspace reconciliation
discovers additions/removals without changing published snapshots in place.

Integration tests create real linked worktrees, independently build Maven/Java and
Gradle/Kotlin code with the same class names, and issue concurrent project-selected MCP
queries. Native verification also indexes these fixtures with the native executable.
