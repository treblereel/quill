# Joker: CDI-Aware Codebase Intelligence MCP Server

> Historical design record from 2026-08-26. Names, architecture, schemas, and tool counts below
> describe the proposal at that date; see the repository README for current Quill behavior.

**Author:** treblereel
**Status:** Draft
**Created:** 2026-08-26
**Last Updated:** 2026-08-26

---

## 1. Overview

Joker is a Quarkus-based MCP (Model Context Protocol) server that provides AI coding agents with pre-computed CDI dependency graph intelligence for Quarkus projects.

### 1.1 Problem

Existing codebase intelligence tools (e.g., Repowise) use tree-sitter for static AST analysis and cannot resolve framework-level dependency injection. In Java/Quarkus projects, CDI is the primary component wiring mechanism. Without understanding CDI, the dependency graph is incomplete: an agent cannot determine which implementation gets injected, does not see `@Produces` methods, and is blind to `@Qualifier`, `@Alternative`, and `@Priority` resolution.

Repowise's own documentation acknowledges: *"Static analysis often misses edges created by Dependency Injection or reflection."* Approximately 15% of their graph edges are known to be incorrect.

### 1.2 Solution

Use Quarkus Arc's `BeanProcessor` as a standalone library to perform full build-time CDI resolution without launching the application. This requires only compiled `.class` files (`mvn compile`). The resolved dependency graph is persisted to SQLite and served to AI agents via MCP tools over stdio.

### 1.3 Key Differentiator

Full CDI resolution — qualifiers, alternatives, priority, producers, interceptors, decorators, build profiles. This is fundamentally impossible for Python-based tools using tree-sitter AST parsing.

### 1.4 Non-Goals (MVP)

- Git analytics (hotspots, co-change, ownership)
- Code health scoring
- Documentation generation
- Semantic search / vector embeddings
- Spring Boot DI support
- Gradle projects
- Multi-module Maven projects
- Auto-update (git hooks, filesystem watchers)

---

## 2. Architecture

Joker is a single Java binary with three picocli subcommands:

```
joker init [--project <path>]    # Full indexing → .joker/index.db
joker update [--project <path>]  # Incremental update
joker mcp [--project <path>]     # stdio MCP server (spawned by agent)
```

### 2.1 Indexing Pipeline (`joker init`)

```
target/classes/*.class
        |
        v
   +-----------+
   |   Jandex   |  Scans .class files → Index (annotations, types, hierarchy)
   |   Indexer  |
   +-----+-----+
         |
         v
   +---------------+
   | BeanProcessor  |  Full CDI resolution → BeanDeployment
   |   (Arc)        |  (beans, injection points, producers, interceptors)
   +-------+-------+
           |
           v
   +----------+
   |  SQLite   |  .joker/index.db
   |  Writer   |  Tables: classes, beans, injection_points, dependencies
   +----------+
```

Steps:
1. Locate project root (CLI arg → CWD → walk up to `pom.xml`)
2. Verify `target/classes` exists; exit with code 2 if not
3. Scan all `.class` files with Jandex `Indexer` → `Index`
4. Feed `Index` into Arc `BeanProcessor.builder()` → `BeanDeployment`
5. Extract all `ClassInfo`, `BeanInfo`, `InjectionPointInfo` from the result
6. Persist to `.joker/index.db` with current git HEAD as `last_commit`

### 2.2 MCP Server (`joker mcp`)

```
AI Agent (Claude Code / Cursor)
    | stdin/stdout (JSON-RPC)
    v
+------------------------+
|  Quarkus MCP Server    |  quarkus-mcp-server-stdio
|  +------------------+  |
|  |  Tool Handlers    |  |  get_beans, get_dependencies, get_injection_points
|  |  +------------+  |  |
|  |  |   SQLite    |  |  |  Reads .joker/index.db (read-only)
|  |  |   Reader    |  |  |
|  |  +------------+  |  |
|  +------------------+  |
+------------------------+
```

Key decisions:
- `init` and `mcp` are separate processes. The MCP server only reads, never writes.
- Prerequisite: `mvn compile` has been run (`.class` files exist). Joker does not compile.
- If the index is stale (`last_commit` != current HEAD), a `stale_warning` is included in the `_meta` envelope of every response.

### 2.3 Project Root Discovery

Applied consistently across all subcommands:
1. `--project <path>` CLI argument (if provided)
2. `user.dir` (CWD)
3. Walk up the directory tree to find a directory containing `pom.xml` + `src/main/java`

---

## 3. Data Model

SQLite database at `.joker/index.db`. Four tables.

### 3.1 `classes`

All classes, interfaces, enums, records, and annotations in the project.

| Column      | Type        | Description                                    |
|-------------|-------------|------------------------------------------------|
| id          | INTEGER PK  |                                                |
| class_name  | TEXT        | FQCN: `org.acme.dto.UserDTO`                  |
| kind        | TEXT        | `CLASS`, `INTERFACE`, `ENUM`, `RECORD`, `ANNOTATION` |
| superclass  | TEXT        | FQCN of superclass                             |
| interfaces  | TEXT (JSON) | `["Serializable", "Comparable"]`               |
| source_file | TEXT        | Relative path to source file                   |
| source_line | INTEGER     | Line number of declaration                     |
| is_bean     | BOOLEAN     | Managed by CDI?                                |

### 3.2 `beans`

CDI metadata. Only for classes where `is_bean = true`.

| Column            | Type        | Description                                        |
|-------------------|-------------|----------------------------------------------------|
| id                | INTEGER PK  |                                                    |
| class_id          | INTEGER FK  | → classes                                          |
| kind              | TEXT        | `CLASS`, `PRODUCER_METHOD`, `PRODUCER_FIELD`, `SYNTHETIC`, `INTERCEPTOR`, `DECORATOR` |
| scope             | TEXT        | `@ApplicationScoped`, `@Singleton`, etc.           |
| qualifiers        | TEXT (JSON) | `["@Default", "@Premium"]`                         |
| stereotypes       | TEXT (JSON) | `["@Model"]`                                       |
| is_alternative    | BOOLEAN     | `@Alternative`                                     |
| priority          | INTEGER     | `@Priority` value (null if absent)                 |
| profiles          | TEXT (JSON) | `["dev", "test"]` or null for unconditional        |
| declaring_class_id| INTEGER FK  | → classes (for producers: owning class)            |
| member_name       | TEXT        | For producers: method/field name                   |
| bean_types        | TEXT (JSON) | `["PaymentService", "Serializable", "Object"]`     |

### 3.3 `injection_points`

All CDI injection points.

| Column           | Type        | Description                                       |
|------------------|-------------|---------------------------------------------------|
| id               | INTEGER PK  |                                                   |
| bean_id          | INTEGER FK  | → beans (bean owning this injection point)        |
| kind             | TEXT        | `FIELD`, `CONSTRUCTOR_PARAM`, `METHOD_PARAM`      |
| target_type      | TEXT        | Required type: `PaymentService`                   |
| qualifiers       | TEXT (JSON) | `["@Premium"]`                                    |
| field_name       | TEXT        | Field or parameter name                           |
| resolved_bean_id | INTEGER FK  | → beans (resolved target; null = unsatisfied)     |
| is_ambiguous     | BOOLEAN     | Multiple candidates                               |

### 3.4 `dependencies`

Denormalized dependency graph. Covers both CDI injection and structural type usage.

| Column             | Type        | Description                                    |
|--------------------|-------------|------------------------------------------------|
| from_class_id      | INTEGER FK  | → classes (consumer)                           |
| to_class_id        | INTEGER FK  | → classes (dependency)                         |
| kind               | TEXT        | `CDI_INJECT`, `FIELD_TYPE`, `METHOD_PARAM`, `RETURN_TYPE`, `EXTENDS`, `IMPLEMENTS` |
| injection_point_id | INTEGER FK  | → injection_points (only for `CDI_INJECT`)     |

### 3.5 `metadata`

Index state information.

| Column | Type    | Description                                            |
|--------|---------|--------------------------------------------------------|
| key    | TEXT PK | `indexed_at`, `project_root`, `last_commit`, `quarkus_version` |
| value  | TEXT    |                                                        |

### 3.6 Indexes

- `classes(class_name)`
- `injection_points(bean_id)`
- `injection_points(resolved_bean_id)`
- `dependencies(from_class_id)`
- `dependencies(to_class_id)`

---

## 4. MCP Tools

Three tools for MVP. All read `.joker/index.db` (read-only). Every response includes a `_meta` envelope with `indexed_at`, `last_commit`, and `stale_warning` (boolean).

### 4.1 `get_beans`

List beans with optional filtering.

**Input:**
```json
{
  "filter": {
    "class_name": "Payment*",
    "scope": "@ApplicationScoped",
    "kind": "CLASS",
    "profile": "dev",
    "qualifier": "@Premium"
  }
}
```

All filter fields are optional. Without filter — returns all beans in summary mode.

**Output:**
```json
{
  "beans": [
    {
      "class": "org.acme.payment.StripePaymentService",
      "kind": "CLASS",
      "scope": "@ApplicationScoped",
      "qualifiers": ["@Default"],
      "bean_types": ["PaymentService", "Object"],
      "profiles": null,
      "source": "src/main/java/org/acme/payment/StripePaymentService.java:15",
      "injection_points_count": 3,
      "dependents_count": 2
    }
  ],
  "total": 1,
  "_meta": { "indexed_at": "2026-08-26T14:30:00", "last_commit": "abc123", "stale_warning": false }
}
```

### 4.2 `get_dependencies`

Dependency graph for a specific bean or class.

**Input:**
```json
{
  "target": "OrderService",
  "direction": "both",
  "depth": 1
}
```

- `target`: class name (short or FQCN). Partial matching supported.
- `direction`: `"inbound"` | `"outbound"` | `"both"` (default: `"both"`)
- `depth`: graph traversal depth (default: 1)

**Output:**
```json
{
  "target": "org.acme.order.OrderService",
  "is_bean": true,
  "scope": "@ApplicationScoped",
  "depends_on": [
    {
      "class": "org.acme.payment.StripePaymentService",
      "via": "field paymentProcessor",
      "qualifiers": ["@Default"],
      "kind": "CDI_INJECT"
    },
    {
      "class": "org.acme.dto.OrderDTO",
      "via": "method createOrder() return type",
      "kind": "RETURN_TYPE"
    }
  ],
  "depended_by": [
    {
      "class": "org.acme.rest.OrderResource",
      "via": "field orderService",
      "kind": "CDI_INJECT"
    }
  ],
  "_meta": { "indexed_at": "2026-08-26T14:30:00", "last_commit": "abc123", "stale_warning": false }
}
```

With `depth: 2` — recursively expands dependencies of dependencies.

### 4.3 `get_injection_points`

Detailed injection point information for a bean.

**Input:**
```json
{
  "target": "OrderService"
}
```

**Output:**
```json
{
  "target": "org.acme.order.OrderService",
  "injection_points": [
    {
      "kind": "FIELD",
      "field": "paymentProcessor",
      "required_type": "PaymentService",
      "qualifiers": ["@Default"],
      "resolved_to": "org.acme.payment.StripePaymentService",
      "resolution": "unique",
      "all_candidates": [
        { "class": "StripePaymentService", "profiles": null, "priority": null },
        { "class": "MockPaymentService", "profiles": ["dev", "test"], "priority": null }
      ]
    },
    {
      "kind": "FIELD",
      "field": "eventBus",
      "required_type": "Event<OrderEvent>",
      "qualifiers": ["@Default"],
      "resolved_to": "built-in:Event",
      "resolution": "unique",
      "all_candidates": []
    }
  ],
  "unsatisfied": [],
  "ambiguous": [],
  "_meta": { "indexed_at": "2026-08-26T14:30:00", "last_commit": "abc123", "stale_warning": false }
}
```

Key feature: `all_candidates` shows all possible beans including profile-dependent ones. `resolution` can be `"unique"`, `"ambiguous"`, or `"unsatisfied"`.

---

## 5. CLI & Distribution

### 5.1 Subcommands

```
joker init [--project <path>]
    Full indexing. Scans target/classes via Jandex, runs BeanProcessor,
    saves to .joker/index.db.
    Prerequisite: mvn compile has been run.
    Exit codes: 0 = success, 1 = error, 2 = .class files not found.

joker update [--project <path>]
    Incremental update. Compares .class file timestamps against last
    indexing. Re-scans only changed files via Jandex, then re-runs
    BeanProcessor on the full index (partial CDI resolution is not
    supported — the full graph must be rebuilt, but Jandex scanning
    is incremental). Falls back to full init if .joker/index.db
    does not exist.

joker mcp [--project <path>] [--transport stdio|http|websocket]
    Starts MCP server. Default transport: stdio.
    Reads .joker/index.db (read-only).
    Error with hint "run joker init first" if index not found.
    Stale warning in _meta if last_commit != HEAD.

joker status [--project <path>]
    Shows index state: creation time, bean/class counts, last commit,
    staleness. Quick diagnostics.
```

### 5.2 Build System Support

**MVP: Maven only.**

Joker locates project root by finding `pom.xml` and expects compiled classes in `target/classes`. Gradle support (`build/classes`, `build.gradle`) is planned for v2.

### 5.3 MCP Client Configuration

In the target project's `.mcp.json`:

```json
{
  "mcpServers": {
    "joker": {
      "command": "java",
      "args": ["-jar", "/path/to/joker.jar", "mcp"],
      "cwd": "."
    }
  }
}
```

With GraalVM native image:
```json
{
  "mcpServers": {
    "joker": {
      "command": "/path/to/joker",
      "args": ["mcp"]
    }
  }
}
```

### 5.4 Distribution Formats

| Format           | Description                                          |
|------------------|------------------------------------------------------|
| **uber-jar**     | `java -jar joker.jar` — primary distribution         |
| **native image** | `./joker` — instant startup, ideal for MCP stdio     |

Native image is the priority for `joker mcp` because the agent spawns the process on every session. JVM warm-up (2-3 sec) is noticeable; native starts in ~50ms.

---

## 6. Key Dependencies

| Dependency                          | Purpose                          |
|-------------------------------------|----------------------------------|
| `io.smallrye:jandex`               | Annotation indexing of .class files |
| `io.quarkus.arc:arc-processor`     | Standalone CDI resolution         |
| `io.quarkiverse.mcp:quarkus-mcp-server-stdio` | MCP stdio transport    |
| `io.quarkiverse.mcp:quarkus-mcp-server-http`  | MCP HTTP transport (optional) |
| `io.quarkus:quarkus-picocli`       | CLI subcommands                   |
| SQLite JDBC driver                  | Index persistence                 |

---

## 7. Token Efficiency Tracking

Every MCP tool response includes token usage metadata in the `_meta` envelope to measure the value joker provides.

### 7.1 What We Measure

Each response reports two numbers:

- **`response_tokens`** — token count of the actual MCP response payload
- **`naive_tokens`** — estimated token count if the agent had to read the raw source files instead

The ratio `naive_tokens / response_tokens` is the **compression factor** for that request.

Example `_meta`:
```json
{
  "_meta": {
    "indexed_at": "2026-08-26T14:30:00",
    "last_commit": "abc123",
    "stale_warning": false,
    "response_tokens": 420,
    "naive_tokens": 15200,
    "compression": 36.2
  }
}
```

### 7.2 How We Count

- **`response_tokens`**: count the serialized JSON response using a tiktoken-compatible tokenizer (cl100k_base). Pre-computed at index time for static parts (bean descriptions, types); computed at query time for dynamic assembly.
- **`naive_tokens`**: sum of tokenized source files that the response covers. For `get_dependencies("OrderService")` — tokenize `OrderService.java` plus all files of direct dependencies. Stored in the `classes` table at index time (`source_tokens` column).

### 7.3 Schema Addition

Add to `classes` table:

| Column        | Type    | Description                           |
|---------------|---------|---------------------------------------|
| source_tokens | INTEGER | Token count of the source file (cl100k_base) |

Computed during `joker init` / `joker update` by reading the source file and tokenizing.

### 7.4 Aggregation (`joker status`)

`joker status` reports cumulative stats:

```
Index: .joker/index.db
  Indexed at:    2026-08-26 14:30:00
  Last commit:   abc123
  Classes:       142
  Beans:         87
  Total source tokens:  284,000
  Avg tokens/class:     2,000
```

This gives a baseline for how much context the project represents and how much joker can save per query.

---

## 8. Limitations & Future Work

### 8.1 MVP Limitations

| Limitation                   | Reason                                                        |
|------------------------------|---------------------------------------------------------------|
| **Maven only**               | Jandex looks for `.class` in `target/classes`, `pom.xml` for root detection. Gradle uses `build/classes` and `build.gradle`. |
| **Quarkus CDI only**         | BeanProcessor is Arc. Spring DI requires AnnotationsTransformer mapping. |
| **Requires `mvn compile`**   | Jandex and BeanProcessor work with `.class` files, not sources. Fundamental — will not change. |
| **CDI/DI Graph only**        | No git analytics, code health, docs.                          |
| **Single-module**            | Multi-module Maven projects require index aggregation.        |
| **No auto-update**           | No git hook / filesystem watcher. User runs `joker update` manually. |

### 8.2 Roadmap

- **v1.0** — MVP as described in this document
- **v1.1** — `joker status`, staleness warnings in MCP, post-commit hook for auto-update
- **v2.0** — Gradle support, Spring Boot DI (via AnnotationsTransformer), multi-module projects
- **v3.0** — Git layer (hotspots, co-change), additional MCP tools (`get_overview`, `get_risk`)
