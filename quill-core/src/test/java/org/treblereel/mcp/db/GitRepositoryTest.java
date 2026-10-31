package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.GitCommitFile;
import org.treblereel.mcp.model.GitCommitRecord;
import org.treblereel.mcp.model.GitFileStats;

class GitRepositoryTest {
    private static final String SINCE = "2026-08-01T00:00:00Z";
    @TempDir Path tempDir;
    private Jdbi jdbi;
    private GitRepository repository;
    private org.jdbi.v3.core.Handle handle;
    private GitAnalyzer.GitAnalysisResult analysis;
    private int classId;

    @BeforeEach
    void indexGitHistory() throws Exception {
        jdbi = QuillDatabase.create(tempDir.resolve("index.db"));
        handle = jdbi.open();
        repository = new GitRepository(handle);
        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(List.of(new ClassRecord(0, "example.Foo", "CLASS", null,
                        List.of(), "src/Foo.java", 1, false, 10))).snapshot());
        classId = jdbi.withHandle(repositoryHandle1 -> new SymbolRepository(repositoryHandle1).findClassByName("example.Foo")).orElseThrow().id();
        Path root = tempDir.resolve("repository");
        Files.createDirectories(root.resolve("src"));
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            Files.writeString(root.resolve("src/Old.java"), "class Old {}\n");
            commit(git, "2026-01-01T00:00:00Z", "Old", Map.of("Foo", "class Foo {}"));
            commit(git, SINCE, "Zed", Map.of("Foo", "class Foo { int first; }", "Bar", "class Bar {}"));
            commit(git, SINCE, "Alice", Map.of("Foo", "class Foo { int second; }"));
            commit(git, "2026-08-02T00:00:00Z", "Bob", Map.of("Bar", "class Bar { int value; }"));
        }
        analysis = GitAnalyzer.analyze(root, 100, Map.of("src/Foo.java", classId));
        // Partial indexes may have history without a precomputed file aggregate.
        IndexFixture.writeGitData(jdbi, analysis.fileStats().stream()
                        .filter(stats -> !stats.filePath().equals("src/Bar.java")).toList(),
                analysis.commits(), analysis.commitFiles());
    }

    @org.junit.jupiter.api.AfterEach void closeHandle() { if (handle != null) handle.close(); }

    @Test
    void hotspotsAggregateTheInclusiveWindowAndSelectOneAuthorForTimestampTies() {
        var hotspots = repository.findHotspots(10, SINCE);
        assertEquals(List.of("src/Bar.java", "src/Foo.java"),
                hotspots.stream().map(GitFileStats::filePath).toList());
        GitFileStats foo = hotspots.get(1);
        assertEquals(2, foo.commitCount());
        assertEquals(2, foo.distinctAuthors());
        assertEquals(SINCE, foo.firstCommit());
        assertEquals(SINCE, foo.lastModified());
        assertEquals(classId, foo.classId());
        String expectedAuthor = analysis.commits().stream()
                .filter(commit -> commit.committedAt().equals(SINCE))
                .max(Comparator.comparing(GitCommitRecord::hash)).orElseThrow().author();
        assertEquals("Alice", expectedAuthor, "Fixture must distinguish hash ordering from MAX(author)");
        assertEquals(expectedAuthor, foo.lastAuthor());
        assertEquals(2, hotspots.getFirst().commitCount());
        assertNull(hotspots.getFirst().classId());
        assertEquals("Bob", hotspots.getFirst().lastAuthor());
        assertEquals(List.of(hotspots.getFirst()), repository.findHotspots(1, SINCE));
        assertTrue(repository.findHotspots(0, SINCE).isEmpty());
        assertTrue(repository.findHotspots(10, "2027-01-01").isEmpty());
    }

    @Test
    void repeatedFileRowsDoNotInflateCommitCounts() {
        Jdbi duplicateDb = QuillDatabase.create(tempDir.resolve("duplicates.db"));
        var files = new ArrayList<>(analysis.commitFiles().stream()
                .map(file -> new GitCommitFile(file.commitId(), null, file.filePath(), file.changeType()))
                .toList());
        files.addAll(List.copyOf(files));
        IndexFixture.writeGitData(duplicateDb, List.of(), analysis.commits(), files);
        var hotspots = duplicateDb.withHandle(repositoryHandle2 -> new GitRepository(repositoryHandle2).findHotspots(10, SINCE));
        assertEquals(List.of(2, 2), hotspots.stream().map(GitFileStats::commitCount).toList());
    }

    @Test
    void allTimeHotspotsUsePublishedAggregates() {
        var hotspots = repository.findHotspots(10, null);
        assertEquals(List.of("src/Foo.java", "src/Old.java"),
                hotspots.stream().map(GitFileStats::filePath).toList());
        assertEquals(3, hotspots.getFirst().commitCount());
        assertEquals(repository.findFileStatsByClassId(classId).orElseThrow(), hotspots.getFirst());
        assertEquals(repository.findFileStatsByPath("src\\Foo.java").orElseThrow(), hotspots.getFirst());
        assertEquals(List.of(hotspots.getFirst()), repository.findHotspots(1, null));
    }

    @Test
    void repositoryPreservesHistoryCoChangesAndCommitFilePagination() {
        assertTrue(repository.hasGitData());
        assertEquals(4, repository.countCommits());
        var history = repository.findFileHistory(classId, 10, 0);
        assertEquals(3, history.size());
        assertEquals(history, repository.findFileHistoryByPath("src\\Foo.java", 10, 0));
        assertEquals(history.subList(1, 2), repository.findFileHistory(classId, 1, 1));
        assertEquals(history.subList(1, 2), repository.findFileHistoryByPath("src/Foo.java", 1, 1));
        assertEquals(repository.findCoChanges(classId, 10),
                repository.findCoChangesByPath("src\\Foo.java", 10));
        assertEquals(2, repository.findCoChanges(classId, 10).size());
        var commits = repository.findRecentCommits(2);
        assertEquals(2, commits.size());
        var ids = commits.stream().map(GitCommitRecord::id).toList();
        int total = repository.countCommitFiles(ids);
        var files = repository.findCommitFiles(ids, total, 0).values().stream().flatMap(List::stream).toList();
        assertEquals(total, files.size());
        assertEquals(files.subList(1, 2), repository.findCommitFiles(ids, 1, 1)
                .values().stream().flatMap(List::stream).toList());
        assertEquals(repository.findCommitFiles(ids, total, 0), repository.findCommitFiles(ids, total));
        assertEquals(repository.findCommitFiles(List.of(ids.getFirst()), total).get(ids.getFirst()),
                repository.findCommitFiles(ids.getFirst()));
        assertEquals(List.of("src/Foo.java"), repository.findHistoricalPathCandidates("example.Foo", 10)
                .stream().map(GitFileStats::filePath).toList());
        assertTrue(repository.findCommitFiles(List.of(), 10).isEmpty());
        assertEquals(0, repository.countCommitFiles(List.of()));
    }

    @Test
    void hotspotQueryPlanHasNoCorrelatedSubquery() {
        AtomicReference<String> sql = new AtomicReference<>();
        jdbi.setSqlLogger(new SqlLogger() {
            @Override public void logAfterExecution(StatementContext context) {
                sql.set(context.getRawSql());
            }
        });
        jdbi.withHandle(h -> new GitRepository(h).findHotspots(10, SINCE));
        String hotspotSql = sql.get();
        var plan = jdbi.withHandle(handle -> handle.createQuery("EXPLAIN QUERY PLAN " + hotspotSql)
                .bind("since", SINCE).bind("limit", 10).mapToMap().list());
        assertFalse(plan.toString().contains("CORRELATED"), plan::toString);
    }

    @Test
    void emptyDatabaseHasNoGitEvidence() {
        var empty = QuillDatabase.create(tempDir.resolve("empty.db"));
        assertFalse(empty.withHandle(h -> new GitRepository(h).hasGitData()).booleanValue());
        assertEquals(0, empty.withHandle(h -> new GitRepository(h).countCommits()).intValue());
        assertTrue(empty.withHandle(h -> new GitRepository(h).findHotspots(10, null)).isEmpty());
        assertTrue(empty.withHandle(h -> new GitRepository(h).findHotspots(10, SINCE)).isEmpty());
        assertTrue(empty.withHandle(h -> new GitRepository(h).findFileStatsByPath("missing")).isEmpty());
    }

    private void commit(Git git, String time, String author, Map<String, String> files) throws Exception {
        for (var file : files.entrySet()) {
            Files.writeString(git.getRepository().getWorkTree().toPath().resolve("src/" + file.getKey() + ".java"),
                    file.getValue() + "\n");
        }
        git.add().addFilepattern(".").call();
        var identity = new PersonIdent(author, author.toLowerCase() + "@example.com", Instant.parse(time), ZoneOffset.UTC);
        git.commit().setMessage(author).setAuthor(identity).setCommitter(identity).setSign(false).call();
    }
}
