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

For multiple checkouts, use repeated `--project` options or an initialized workspace.
Workspace discovery includes working copies under its configured root and discovery
depth. Worktrees elsewhere are not automatically imported; register their paths
explicitly with `--project`.

The MCP `project` selector accepts a configured name, an absolute project/source path,
or the exact `project_id` returned in response metadata. Workspace mode additionally
supports its existing relative paths and Maven coordinates. An explicit selector
matching multiple projects is rejected; omit the selector only when a tool's documented
operation intentionally queries all projects.
Absolute paths select the most specific registered project containing that path, so
an explicitly registered Maven module takes precedence over its containing reactor.

The MCP server cannot infer that an editor or agent changed its current directory.
Select the new checkout explicitly or restart its server with the appropriate path.
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
inputs. Dependency artifacts retain their existing content-based caching. Removing a
working copy retires its database readers and detaches its active source subscriptions
and queued warmup. Shared bounded worker caches may retain inactive models until normal
eviction; identity keys prevent those models from being reused by a recreated checkout.
Already resolved reads are checked again before query execution. Workspace reconciliation
discovers additions/removals without changing published snapshots in place.

Integration tests create real linked worktrees, independently build Maven/Java and
Gradle/Kotlin code with the same class names, and issue concurrent project-selected MCP
queries. Native verification also indexes these fixtures with the native executable.
