# Git Intelligence Implementation Plan

> Historical implementation plan from 2026-08-29. It preserves the intended work at that date;
> see the repository README for the current Quill commands and MCP tools.

**Goal:** Add git history analysis (hotspots, co-changes, file ownership, recent changes) to joker, stored in SQLite for fast MCP queries, with git hooks for automatic index updates.

**Architecture:** JGit reads git history during `joker init`/`update`, persists aggregated stats to new tables in `index.db`. Four new MCP tools expose git intelligence. Git hooks (`post-commit`, `post-merge`) trigger background `joker update`.

**Tech Stack:** JGit (`org.eclipse.jgit`), existing SQLite/Jackson stack

**Spec:** `docs/superpowers/specs/2026-08-26-joker-design.md` (roadmap items v1.1 + v3.0)

## Global Constraints

- Java 25, Quarkus 3.39.1, Maven
- JGit is the only new dependency (~5MB)
- Git tables live in the same `index.db` — single database, no separate files
- `GitAnalyzer` goes in `joker-core` alongside `BeanResolver`
- Hook scripts must be POSIX-compatible (sh, not bash)
- Hooks run `joker update` in background (`&`) to not block developer workflow
- All git MCP tools follow the existing `_meta` envelope pattern

---

### Task 1: Add JGit dependency and GitAnalyzer skeleton

**Files:**
- Modify: `joker-core/pom.xml`
- Create: `joker-core/src/main/java/org/treblereel/mcp/core/GitAnalyzer.java`
- Create: `joker-core/src/main/java/org/treblereel/mcp/model/GitFileStats.java`
- Create: `joker-core/src/main/java/org/treblereel/mcp/model/GitCommitRecord.java`
- Create: `joker-core/src/main/java/org/treblereel/mcp/model/GitCommitFile.java`
- Test: `tests/basic-cdi/src/test/java/org/treblereel/mcp/core/GitAnalyzerTest.java`

**Interfaces:**
- Produces: `GitAnalyzer.analyze(Path projectRoot, int maxCommits)` → `GitAnalysisResult(List<GitFileStats>, List<GitCommitRecord>, List<GitCommitFile>)`
- Produces: `GitFileStats(String filePath, Integer classId, int commitCount, String lastModified, String lastAuthor, String firstCommit, int distinctAuthors)`
- Produces: `GitCommitRecord(int id, String hash, String shortHash, String author, String authorEmail, String committedAt, String message)`
- Produces: `GitCommitFile(int commitId, Integer classId, String filePath, String changeType)`

Add to `joker-core/pom.xml`:
```xml
<dependency>
    <groupId>org.eclipse.jgit</groupId>
    <artifactId>org.eclipse.jgit</artifactId>
    <version>7.2.0.202503040940-r</version>
</dependency>
```

`GitAnalyzer.analyze()`:
- Opens git repo with `FileRepositoryBuilder`
- Walks commit history with `RevWalk` (limited to `maxCommits`, default 500)
- For each commit, uses `DiffFormatter` to get changed files
- Aggregates per-file stats: commit count, last modified, last author, distinct authors
- Matches file paths to `classes.source_file` where possible → sets `classId`
- Returns records ready for SQL insertion

- [ ] Step 1: Add JGit dependency to `joker-core/pom.xml`
- [ ] Step 2: Create model records (`GitFileStats`, `GitCommitRecord`, `GitCommitFile`)
- [ ] Step 3: Write failing test — `GitAnalyzerTest.analyzeBasicCdiProject()` asserts non-empty results from the test fixture (basic-cdi has a git history since it's inside the joker repo)
- [ ] Step 4: Implement `GitAnalyzer.analyze()` using JGit `RevWalk` + `DiffFormatter`
- [ ] Step 5: Run tests, verify pass
- [ ] Step 6: Commit

---

### Task 2: Add git tables to SQLite schema + writer/reader

**Files:**
- Modify: `joker-core/src/main/java/org/treblereel/mcp/db/JokerDatabase.java`
- Modify: `joker-core/src/main/java/org/treblereel/mcp/db/IndexWriter.java`
- Modify: `joker-core/src/main/java/org/treblereel/mcp/db/IndexReader.java`
- Test: `tests/basic-cdi/src/test/java/org/treblereel/mcp/db/IndexWriterReaderTest.java` (add git-related tests)

**Interfaces:**
- Consumes: `GitFileStats`, `GitCommitRecord`, `GitCommitFile` from Task 1
- Produces: `IndexWriter.writeGitData(Connection, List<GitFileStats>, List<GitCommitRecord>, List<GitCommitFile>)`
- Produces: `IndexReader.findHotspots(Connection, int limit, String since)` → `List<GitFileStats>`
- Produces: `IndexReader.findFileHistory(Connection, int classId, int limit)` → `List<GitCommitRecord>`
- Produces: `IndexReader.findCoChanges(Connection, int classId, int limit)` → `List<CoChangeRecord>`
- Produces: `IndexReader.findRecentChanges(Connection, int limit)` → `List<GitCommitRecord>` (with joined file list)

New tables in `JokerDatabase.create()`:

```sql
CREATE TABLE IF NOT EXISTS git_file_stats (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path TEXT NOT NULL,
    class_id INTEGER REFERENCES classes(id),
    commit_count INTEGER NOT NULL DEFAULT 0,
    last_modified TEXT,
    last_author TEXT,
    first_commit TEXT,
    distinct_authors INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS git_commits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hash TEXT NOT NULL UNIQUE,
    short_hash TEXT NOT NULL,
    author TEXT NOT NULL,
    author_email TEXT,
    committed_at TEXT NOT NULL,
    message TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS git_commit_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    commit_id INTEGER NOT NULL REFERENCES git_commits(id),
    class_id INTEGER REFERENCES classes(id),
    file_path TEXT NOT NULL,
    change_type TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_gfs_class ON git_file_stats(class_id);
CREATE INDEX IF NOT EXISTS idx_gfs_count ON git_file_stats(commit_count DESC);
CREATE INDEX IF NOT EXISTS idx_gcf_commit ON git_commit_files(commit_id);
CREATE INDEX IF NOT EXISTS idx_gcf_class ON git_commit_files(class_id);
CREATE INDEX IF NOT EXISTS idx_gc_date ON git_commits(committed_at DESC);
```

Co-change query (SQL):
```sql
SELECT gcf2.file_path, gcf2.class_id, COUNT(*) as co_count
FROM git_commit_files gcf1
JOIN git_commit_files gcf2 ON gcf1.commit_id = gcf2.commit_id
    AND gcf1.file_path != gcf2.file_path
WHERE gcf1.class_id = ?
GROUP BY gcf2.file_path
ORDER BY co_count DESC
LIMIT ?
```

- [ ] Step 1: Add three new CREATE TABLE statements to `JokerDatabase.create()`
- [ ] Step 2: Add `writeGitData()` to `IndexWriter` with batch inserts
- [ ] Step 3: Add `findHotspots()`, `findFileHistory()`, `findCoChanges()`, `findRecentChanges()` to `IndexReader`
- [ ] Step 4: Write tests for round-trip write/read of git data
- [ ] Step 5: Run tests, verify pass
- [ ] Step 6: Commit

---

### Task 3: Wire GitAnalyzer into InitCommand

**Files:**
- Modify: `joker-app/src/main/java/org/treblereel/mcp/command/InitCommand.java`
- Modify: `joker-app/src/main/java/org/treblereel/mcp/command/UpdateCommand.java`

**Interfaces:**
- Consumes: `GitAnalyzer.analyze()` from Task 1
- Consumes: `IndexWriter.writeGitData()` from Task 2

Add git analysis step after CDI resolution in `InitCommand.run()`:

```java
System.out.println("Analyzing git history...");
GitAnalyzer.GitAnalysisResult gitResult = GitAnalyzer.analyze(root, 500);
System.out.println("Processed " + gitResult.commits().size() + " commits, "
        + gitResult.fileStats().size() + " files with history.");
```

After `IndexWriter.write(...)`, call:
```java
IndexWriter.writeGitData(conn, gitResult.fileStats(), gitResult.commits(), gitResult.commitFiles());
```

`GitAnalyzer` needs the `classNameToSqliteId` map to link file paths to class IDs. Pass the classes list so it can match `source_file` → `class_id`.

Same for `UpdateCommand` — git analysis runs on every update.

Remove the manual `resolveGitHead()` method from `InitCommand` — JGit gives us HEAD hash directly.

- [ ] Step 1: Wire `GitAnalyzer.analyze()` into `InitCommand.run()` after CDI resolution
- [ ] Step 2: Pass class-to-id mapping so git files link to `classes.id`
- [ ] Step 3: Wire same into `UpdateCommand`
- [ ] Step 4: Replace `resolveGitHead()` with JGit-based HEAD resolution
- [ ] Step 5: Verify `joker init` on basic-cdi test project produces git tables with data
- [ ] Step 6: Commit

---

### Task 4: Four new MCP tools

**Files:**
- Modify: `joker-app/src/main/java/org/treblereel/mcp/mcp/JokerTools.java`
- Modify: `tests/basic-cdi/src/test/java/org/treblereel/mcp/mcp/JokerToolsTest.java`

**Interfaces:**
- Consumes: `IndexReader.findHotspots()`, `findFileHistory()`, `findCoChanges()`, `findRecentChanges()` from Task 2

#### 4a. `get_hotspots`

```java
@Tool(description = "Get most frequently changed files/classes by git commit count. Helps identify volatile areas of the codebase.")
public String get_hotspots(
        @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit,
        @ToolArg(description = "Only commits after this date, ISO format YYYY-MM-DD") Optional<String> since)
```

Output:
```json
{
  "hotspots": [
    {
      "file": "src/main/java/org/acme/OrderService.java",
      "class": "org.acme.OrderService",
      "is_bean": true,
      "commit_count": 42,
      "distinct_authors": 3,
      "last_modified": "2026-08-28T10:00:00Z",
      "last_author": "treblereel"
    }
  ],
  "total": 10,
  "_meta": { ... }
}
```

#### 4b. `get_file_history`

```java
@Tool(description = "Get git commit history for a specific class or file")
public String get_file_history(
        @ToolArg(description = "Class name (short or FQCN)") String target,
        @ToolArg(description = "Max commits to return (default: 10)") Optional<Integer> limit)
```

Output:
```json
{
  "target": "org.acme.OrderService",
  "file": "src/main/java/org/acme/OrderService.java",
  "commits": [
    {
      "hash": "abc1234",
      "author": "treblereel",
      "date": "2026-08-28T10:00:00Z",
      "message": "fix: order validation edge case"
    }
  ],
  "total_commits": 42,
  "_meta": { ... }
}
```

#### 4c. `get_co_changes`

```java
@Tool(description = "Find files that frequently change together with a given class. Reveals hidden coupling not visible in the dependency graph.")
public String get_co_changes(
        @ToolArg(description = "Class name (short or FQCN)") String target,
        @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit)
```

Output:
```json
{
  "target": "org.acme.OrderService",
  "co_changes": [
    {
      "file": "src/main/java/org/acme/OrderDTO.java",
      "class": "org.acme.OrderDTO",
      "co_change_count": 28,
      "coupling_ratio": 0.67
    }
  ],
  "_meta": { ... }
}
```

`coupling_ratio` = co-change count / target's total commit count. High ratio (>0.5) means strong coupling.

#### 4d. `get_recent_changes`

```java
@Tool(description = "Get recently changed beans/classes from git history. Useful for understanding what was recently modified.")
public String get_recent_changes(
        @ToolArg(description = "Number of recent commits to inspect (default: 10)") Optional<Integer> commits)
```

Output:
```json
{
  "recent_changes": [
    {
      "commit": "abc1234",
      "author": "treblereel",
      "date": "2026-08-28T10:00:00Z",
      "message": "fix: order validation",
      "files": [
        { "file": "OrderService.java", "class": "org.acme.OrderService", "is_bean": true, "change_type": "MODIFY" }
      ]
    }
  ],
  "_meta": { ... }
}
```

- [ ] Step 1: Add `get_hotspots` tool + test
- [ ] Step 2: Add `get_file_history` tool + test
- [ ] Step 3: Add `get_co_changes` tool + test
- [ ] Step 4: Add `get_recent_changes` tool + test
- [ ] Step 5: Run all tests, verify pass
- [ ] Step 6: Commit

---

### Task 5: Git hooks — `post-commit` and `post-merge`

**Files:**
- Modify: `joker-app/src/main/java/org/treblereel/mcp/command/InitCommand.java`
- Create: `joker-core/src/main/java/org/treblereel/mcp/core/GitHookInstaller.java`
- Test: `tests/basic-cdi/src/test/java/org/treblereel/mcp/core/GitHookInstallerTest.java`

**Interfaces:**
- Produces: `GitHookInstaller.install(Path projectRoot)` — installs post-commit and post-merge hooks
- Produces: `GitHookInstaller.uninstall(Path projectRoot)` — removes joker hooks

Hook script template (POSIX sh, non-blocking):

```sh
#!/bin/sh
# joker: auto-update CDI index after commit
# Remove this file or run 'joker init --no-hooks' to disable
if command -v joker >/dev/null 2>&1; then
    joker update --project "$(git rev-parse --show-toplevel)" >/dev/null 2>&1 &
elif [ -f "$(git rev-parse --show-toplevel)/.joker/joker.jar" ]; then
    java -jar "$(git rev-parse --show-toplevel)/.joker/joker.jar" update >/dev/null 2>&1 &
fi
```

Rules:
- If `.git/hooks/post-commit` already exists, append joker block wrapped in `# --- joker-start ---` / `# --- joker-end ---` markers
- If hook already has joker markers, replace the block (idempotent)
- `joker init` calls `GitHookInstaller.install()` by default
- `joker init --no-hooks` skips hook installation
- Hooks run `joker update` in background (`&`) — never blocks the developer

- [ ] Step 1: Create `GitHookInstaller` with `install()` and `uninstall()` methods
- [ ] Step 2: Write tests — install creates hooks, install is idempotent, respects existing hooks
- [ ] Step 3: Wire into `InitCommand` — call `GitHookInstaller.install()` after `ensureGitignore()`
- [ ] Step 4: Add `--no-hooks` option to `InitCommand`
- [ ] Step 5: Run tests, verify pass
- [ ] Step 6: Commit

---

### Task 6: Integration test — git tools e2e

**Files:**
- Modify: `tests/basic-cdi/src/test/java/org/treblereel/mcp/command/McpStdioIT.java`

**Interfaces:**
- Consumes: MCP tools from Task 4

Add to `McpStdioIT`:
- Verify `tools/list` now returns 7 tools (3 CDI + 4 git)
- Call `get_hotspots` → verify returns results
- Call `get_recent_changes` → verify returns commits with files

- [ ] Step 1: Update tool count assertion in `mcpStdioToolsListAndCall`
- [ ] Step 2: Add `get_hotspots` call and assert non-empty response
- [ ] Step 3: Add `get_recent_changes` call and assert commits present
- [ ] Step 4: Run `mvn verify -DskipITs=false`, verify pass
- [ ] Step 5: Commit
