# get_overview + get_risk MCP Tools Implementation Plan

**Goal:** Two new MCP tools — `get_overview` (project-wide summary for agent orientation) and `get_risk` (change risk assessment per class combining CDI graph + git signals).

**Architecture:** Pure read-side — no new tables, no new indexing. Both tools aggregate data already in `index.db` via new `IndexReader` queries. Risk scoring uses a weighted formula over quantifiable signals.

**Tech Stack:** Existing SQLite/Jackson stack, no new dependencies.

## Global Constraints

- No new dependencies
- Both tools follow existing `_meta` envelope pattern
- `get_overview` takes no required params — an agent calls it first to orient
- `get_risk` takes a class name and returns a scored breakdown, not just raw numbers

---

### Task 1: IndexReader aggregate queries

**Files:**
- Modify: `joker-core/src/main/java/org/treblereel/mcp/db/IndexReader.java`
- Test: `tests/basic-cdi/src/test/java/org/treblereel/mcp/db/IndexWriterReaderTest.java`

**Interfaces:**
- Produces: `IndexReader.countClasses(Connection)` → `int`
- Produces: `IndexReader.countBeans(Connection)` → `int`
- Produces: `IndexReader.countBeansByScope(Connection)` → `Map<String, Integer>` (scope → count)
- Produces: `IndexReader.countBeansByKind(Connection)` → `Map<String, Integer>` (kind → count)
- Produces: `IndexReader.findUnsatisfiedInjectionPoints(Connection)` → `List<InjectionPointRecord>`
- Produces: `IndexReader.findAmbiguousInjectionPoints(Connection)` → `List<InjectionPointRecord>`
- Produces: `IndexReader.findMostDependedOn(Connection, int limit)` → `List<Map.Entry<Integer, Integer>>` (classId → dependents count)
- Produces: `IndexReader.countDependents(Connection, int classId)` → `int` (inbound edge count)
- Produces: `IndexReader.countDependencies(Connection, int classId)` → `int` (outbound edge count)

SQL for most-depended-on (architectural hubs):
```sql
SELECT to_class_id, COUNT(*) as dep_count
FROM dependencies
GROUP BY to_class_id
ORDER BY dep_count DESC
LIMIT ?
```

SQL for unsatisfied injection points:
```sql
SELECT ip.*, c.class_name FROM injection_points ip
JOIN beans b ON ip.bean_id = b.id
JOIN classes c ON b.class_id = c.id
WHERE ip.resolved_bean_id IS NULL
```

- [ ] Step 1: Add `countClasses`, `countBeans` (simple SELECT COUNT)
- [ ] Step 2: Add `countBeansByScope`, `countBeansByKind` (GROUP BY queries)
- [ ] Step 3: Add `findUnsatisfiedInjectionPoints`, `findAmbiguousInjectionPoints`
- [ ] Step 4: Add `findMostDependedOn`, `countDependents`, `countDependencies`
- [ ] Step 5: Add tests for each new query
- [ ] Step 6: Commit

---

### Task 2: `get_overview` MCP tool

**Files:**
- Modify: `joker-app/src/main/java/org/treblereel/mcp/mcp/JokerTools.java`
- Modify: `tests/basic-cdi/src/test/java/org/treblereel/mcp/mcp/JokerToolsTest.java`

**Interfaces:**
- Consumes: All aggregate queries from Task 1, `findHotspots`, `hasGitData`, `getMetadata`

```java
@Tool(description = "Get a high-level overview of the project: bean counts, scope breakdown, architecture hubs, problems, and git activity. Call this first to orient before diving into specifics.")
public String get_overview()
```

No required params. Output:

```json
{
  "project": {
    "classes": 142,
    "beans": 87,
    "total_source_tokens": 284000,
    "indexed_at": "2026-08-29T10:00:00Z",
    "last_commit": "abc1234"
  },
  "beans_by_scope": {
    "@ApplicationScoped": 52,
    "@Dependent": 20,
    "@Singleton": 10,
    "@RequestScoped": 5
  },
  "beans_by_kind": {
    "CLASS": 70,
    "PRODUCER_METHOD": 10,
    "INTERCEPTOR": 5,
    "DECORATOR": 2
  },
  "architecture_hubs": [
    { "class": "org.acme.OrderService", "dependents": 12, "is_bean": true }
  ],
  "problems": {
    "unsatisfied_injection_points": [
      { "bean": "OrderService", "field": "missingDep", "type": "FooService" }
    ],
    "ambiguous_injection_points": []
  },
  "git_summary": {
    "total_commits_indexed": 500,
    "top_hotspots": [
      { "file": "OrderService.java", "commit_count": 42 }
    ]
  },
  "_meta": { ... }
}
```

If no git data: `"git_summary": null` with a note to init git.

- [ ] Step 1: Add `get_overview` tool method and `getOverview(Connection)` implementation
- [ ] Step 2: Build `project` section from metadata + counts
- [ ] Step 3: Build `beans_by_scope` and `beans_by_kind` breakdowns
- [ ] Step 4: Build `architecture_hubs` — top 5 most-depended-on classes
- [ ] Step 5: Build `problems` section — unsatisfied and ambiguous injection points
- [ ] Step 6: Build `git_summary` — commit count + top 3 hotspots (if git data available)
- [ ] Step 7: Write tests — full overview, overview without git data
- [ ] Step 8: Commit

---

### Task 3: `get_risk` MCP tool

**Files:**
- Modify: `joker-app/src/main/java/org/treblereel/mcp/mcp/JokerTools.java`
- Modify: `tests/basic-cdi/src/test/java/org/treblereel/mcp/mcp/JokerToolsTest.java`

**Interfaces:**
- Consumes: `countDependents`, `countDependencies`, `findFileStatsByClassId`, `findCoChanges` from Tasks 1-2

```java
@Tool(description = "Assess the risk of changing a specific class. Combines CDI dependency fan-in/out, git churn, author count, and coupling to produce a risk score with explanation.")
public String get_risk(
        @ToolArg(description = "Class name (short or FQCN)") String target)
```

Output:

```json
{
  "target": "org.acme.OrderService",
  "risk_score": 7.2,
  "risk_level": "HIGH",
  "signals": {
    "fan_in": { "value": 12, "weight": 3, "note": "12 classes depend on this" },
    "fan_out": { "value": 5, "weight": 1, "note": "depends on 5 classes" },
    "git_churn": { "value": 42, "weight": 2, "note": "42 commits — high change frequency" },
    "bus_factor": { "value": 1, "weight": 2, "note": "only 1 author — single point of knowledge" },
    "coupling": { "value": 8, "weight": 1, "note": "8 files frequently co-change" }
  },
  "recommendation": "High-risk change target. 12 dependents will be affected. Consider reviewing dependents with get_dependencies(target, direction='inbound'). Single author — ensure review coverage.",
  "_meta": { ... }
}
```

**Risk score formula (0-10 scale):**

```
score = clamp(0, 10,
    fan_in_score * 0.30 +
    fan_out_score * 0.10 +
    churn_score * 0.25 +
    bus_factor_score * 0.20 +
    coupling_score * 0.15
)
```

Where each sub-score is 0-10:
- `fan_in_score`: 0 if 0 dependents, 5 if 3, 8 if 8, 10 if ≥15
- `fan_out_score`: 0 if 0, 5 if 3, 10 if ≥8
- `churn_score`: 0 if ≤2 commits, 5 if 10, 8 if 30, 10 if ≥50
- `bus_factor_score`: 10 if 1 author, 5 if 2, 2 if 3, 0 if ≥4
- `coupling_score`: 0 if 0 co-changes, 5 if 3, 10 if ≥8

Risk levels: LOW (0-3), MEDIUM (3-6), HIGH (6-8), CRITICAL (8-10)

Recommendation is built from the highest-scoring signals — a readable sentence, not just numbers.

If no git data: churn, bus_factor, coupling scores default to 0 and a note says git data unavailable.

- [ ] Step 1: Add `get_risk` tool method and `getRisk(Connection, String)` implementation
- [ ] Step 2: Implement sub-score calculation functions (fan-in, churn, bus factor, etc.)
- [ ] Step 3: Implement weighted score aggregation and risk level mapping
- [ ] Step 4: Build recommendation string from top signals
- [ ] Step 5: Handle no-git-data case (git signals = 0 with note)
- [ ] Step 6: Write tests — high-risk class (many dependents + high churn), low-risk class, class without git data
- [ ] Step 7: Commit
