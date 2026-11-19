package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.*;

/** Git history tools: hotspots, file history, co-changes and recent changes. */
class QuillToolsHistoryTest extends QuillToolsTestBase {

    @Test
    void resolveEntitiesSeparatesCurrentAndHistoricalPaths() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO git_file_stats(file_path, class_id, commit_count, last_modified,
                    last_author, first_commit, distinct_authors)
                VALUES (?, NULL, 4, ?, ?, ?, 2)""",
                                "src/main/java/org/acme/DeletedGenerator.java",
                                "2026-08-25T08:00:00Z",
                                "dev2",
                                "2026-08-20T08:00:00Z"));
        JsonNode result =
                JDBI_READS.resolveEntities(
                        jdbi, List.of("OrderService", "DeletedGenerator.java", "NeverExisted"));
        JsonNode current = result.get("entities").get(0);
        assertTrue(current.get("current").asBoolean());
        assertEquals("current", current.get("resolution").asText());
        JsonNode deleted = result.get("entities").get(1);
        assertFalse(deleted.get("current").asBoolean());
        assertTrue(deleted.get("historical").asBoolean());
        assertTrue(deleted.get("deleted").asBoolean());
        assertEquals(
                "src/main/java/org/acme/DeletedGenerator.java",
                deleted.get("historical_paths").get(0).get("file").asText());
        assertEquals("not_found", result.get("entities").get(2).get("resolution").asText());
    }

    @Test
    void getHotspotsReturnsMostChanged() throws Exception {
        String result = JDBI_READS.getHotspots(jdbi, 10, null).toString();
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        assertNotNull(hotspots);
        assertTrue(hotspots.size() >= 2);
        assertEquals(
                "src/main/java/org/acme/StripePaymentService.java",
                hotspots.get(0).get("file").asText());
        assertEquals(3, hotspots.get(0).get("commit_count").asInt());
    }

    @Test
    void hotspotPaginationReportsFullTotalAndTruncation() throws Exception {
        JsonNode root = JDBI_READS.getHotspots(jdbi, 1, null);

        assertEquals(1, root.path("showing").asInt());
        assertEquals(3, root.path("total").asInt());
        assertTrue(root.path("truncated").asBoolean());
    }

    @Test
    void getHotspotsFiltersBySince() throws Exception {
        String result = JDBI_READS.getHotspots(jdbi, 10, "2026-08-27").toString();
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        for (JsonNode h : hotspots) {
            assertTrue(h.get("last_modified").asText().compareTo("2026-08-27") >= 0);
        }
    }

    @Test
    void getFileHistoryReturnsCommits() throws Exception {
        String result = JDBI_READS.getFileHistory(jdbi, "StripePaymentService", 10).toString();
        JsonNode root = JSON.readTree(result);
        assertEquals("org.acme.StripePaymentService", root.get("target").asText());
        JsonNode commits = root.get("commits");
        assertNotNull(commits);
        assertEquals(3, commits.size());
        assertEquals("aaa1111", commits.get(0).get("hash").asText());
        assertEquals("dev1@test.com", commits.get(0).get("author_email").asText());
        JsonNode window = root.get("returned_window");
        assertEquals(3, window.get("commit_count").asInt());
        assertEquals(2, window.get("distinct_author_labels").asInt());
        assertEquals(3, window.get("distinct_author_emails").asInt());
        assertEquals("not_attempted", window.get("identity_resolution").asText());
        assertEquals(
                List.of("dev1", "dev2"),
                JSON.convertValue(
                        window.get("author_labels"),
                        JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(3, root.get("total_commits").asInt());
        assertTrue(root.get("history_complete").asBoolean());
        assertFalse(root.get("has_more").asBoolean());
    }

    @Test
    void getFileHistorySupportsOffsetPagination() throws Exception {
        JsonNode first = JDBI_READS.getFileHistory(jdbi, "StripePaymentService", 2, 0);
        JsonNode second = JDBI_READS.getFileHistory(jdbi, "StripePaymentService", 2, 2);

        assertEquals(2, first.get("showing").asInt());
        assertEquals(2, first.get("returned_window").get("commit_count").asInt());
        assertEquals(1, second.get("returned_window").get("commit_count").asInt());
        assertEquals(3, first.get("total_commits").asInt());
        assertEquals(2, first.get("next_offset").asInt());
        assertTrue(first.get("has_more").asBoolean());
        assertFalse(first.get("history_complete").asBoolean());
        assertEquals(1, second.get("showing").asInt());
        assertFalse(second.get("has_more").asBoolean());
        assertTrue(second.get("history_complete").asBoolean());
        assertNotEquals(
                first.get("commits").get(0).get("hash").asText(),
                second.get("commits").get(0).get("hash").asText());
    }

    @Test
    void getCoChangesFindsCorrelatedFiles() throws Exception {
        String result = JDBI_READS.getCoChanges(jdbi, "OrderService", 10).toString();
        JsonNode root = JSON.readTree(result);
        assertEquals("org.acme.OrderService", root.get("target").asText());
        JsonNode coChanges = root.get("co_changes");
        assertNotNull(coChanges);
        assertTrue(coChanges.size() >= 1);
        boolean hasStripe = false;
        for (JsonNode co : coChanges) {
            if (co.get("file").asText().contains("StripePaymentService")) {
                hasStripe = true;
                assertTrue(co.get("co_change_count").asInt() >= 1);
                assertTrue(co.get("coupling_ratio").asDouble() > 0);
            }
        }
        assertTrue(hasStripe, "OrderService should co-change with StripePaymentService");
    }

    @Test
    void getRecentChangesReturnsCommitsWithFiles() throws Exception {
        String result = JDBI_READS.getRecentChanges(jdbi, 5).toString();
        JsonNode root = JSON.readTree(result);
        JsonNode changes = root.get("recent_changes");
        assertNotNull(changes);
        assertEquals(3, changes.size());
        assertEquals("aaa1111", changes.get(0).get("commit").asText());
        JsonNode files = changes.get(0).get("files");
        assertNotNull(files);
        assertTrue(files.size() >= 1);
    }

    @Test
    void getRecentChangesPagesFilesAndSupportsCompactResponses() throws Exception {
        JsonNode first = JDBI_READS.getRecentChanges(jdbi, 5, 2, 0, false);
        JsonNode second = JDBI_READS.getRecentChanges(jdbi, 5, 2, 2, false);

        assertEquals(2, first.path("showing").asInt());
        assertEquals(6, first.path("total").asInt());
        assertEquals(2, first.path("next_offset").asInt());
        assertTrue(first.path("has_more").asBoolean());
        assertEquals(2, second.path("showing").asInt());
        assertFalse(first.path("details").asBoolean());
        JsonNode firstCommit = first.path("recent_changes").get(0);
        assertFalse(firstCommit.has("author"));
        assertFalse(firstCommit.path("files").get(0).has("class"));
    }

    @Test
    void getHotspotsSinceRecomputesCounts() throws Exception {
        String result = JDBI_READS.getHotspots(jdbi, 10, "2026-08-28").toString();
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        assertNotNull(hotspots);
        for (JsonNode h : hotspots) {
            String file = h.get("file").asText();
            int count = h.get("commit_count").asInt();
            if (file.contains("OrderService")) {
                assertEquals(
                        1,
                        count,
                        "OrderService had 1 commit on 2026-08-28, not lifetime count of 2");
            }
        }
    }

    @Test
    void getHotspotsSinceExcludesOlderCommits() throws Exception {
        String result = JDBI_READS.getHotspots(jdbi, 10, "2026-08-28").toString();
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        for (JsonNode h : hotspots) {
            assertFalse(
                    h.get("file").asText().equals("src/main/java/org/acme/PaymentService.java"),
                    "PaymentService.java only has commits before 2026-08-28 and should be excluded");
        }
    }

    @Test
    void currentHotspotsExcludeDeletedFilesButHistoryRemainsAddressableByPath() throws Exception {
        Path repository = tempDir.resolve("history-repository");
        Path current = repository.resolve("src/main/java/example/Current.java");
        Path deleted = repository.resolve("src/main/java/example/Deleted.java");
        java.nio.file.Files.createDirectories(current.getParent());
        java.nio.file.Files.writeString(current, "package example; class Current {}\n");
        java.nio.file.Files.writeString(deleted, "package example; class Deleted {}\n");

        GitAnalyzer.GitAnalysisResult analysis;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
            java.nio.file.Files.delete(deleted);
            git.rm().addFilepattern("src/main/java/example/Deleted.java").call();
            git.commit()
                    .setMessage("delete stale class")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
            analysis =
                    GitAnalyzer.analyze(
                            repository, 10, Map.of("src/main/java/example/Current.java", 1));
        }

        Jdbi historyDb = QuillDatabase.create(tempDir.resolve("history.db"));
        IndexWriter.writeIncremental(
                historyDb,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "example.Current",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "src/main/java/example/Current.java",
                                                1,
                                                false,
                                                10)))
                        .metadata(
                                Map.of(
                                        "indexed_at",
                                        "2026-09-08T00:00:00Z",
                                        "last_commit",
                                        analysis.headHash(),
                                        "project_root",
                                        repository.toString()))
                        .snapshot());
        IndexFixture.writeGitData(
                historyDb, analysis.fileStats(), analysis.commits(), analysis.commitFiles());
        historyDb.useHandle(
                handle ->
                        handle.createUpdate(
                                        """
                        INSERT INTO files(project_path, repository_path, kind, origin, lifecycle)
                        VALUES (:path, :path, 'java', 'source', 'historical')""")
                                .bind("path", "src/main/java/example/Deleted.java")
                                .execute());

        JsonNode currentOnly = JDBI_READS.getHotspots(historyDb, 10, null, false);
        assertFalse(currentOnly.path("hotspots").toString().contains("Deleted.java"));
        JsonNode overview = JDBI_READS.getOverview(historyDb);
        assertFalse(
                overview.path("git_summary")
                        .path("top_hotspots")
                        .toString()
                        .contains("Deleted.java"));

        JsonNode withHistory = JDBI_READS.getHotspots(historyDb, 10, null, true);
        assertTrue(withHistory.path("hotspots").toString().contains("Deleted.java"));
        assertTrue(withHistory.path("hotspots").toString().contains("historical"));

        JsonNode history =
                JDBI_READS.getFileHistory(historyDb, "src/main/java/example/Deleted.java", 10);
        assertEquals("historical", history.path("lifecycle").asText());
        assertEquals(2, history.path("commits").size());

        JsonNode missingClass = JDBI_READS.getRisk(historyDb, "example.Deleted");
        assertEquals("Class not found", missingClass.path("message").asText());
        assertTrue(missingClass.path("candidates").toString().contains("Deleted.java"));
        assertTrue(missingClass.path("candidates").toString().contains("historical"));
        assertEquals(1, missingClass.path("candidates").size());
        assertTrue(missingClass.path("_meta").isObject());
    }

    @Test
    void gitToolsReturnErrorWithoutGitData() throws Exception {
        Jdbi noGitJdbi = QuillDatabase.create(tempDir.resolve("nogit.db"));
        IndexWriter.writeIncremental(
                noGitJdbi,
                new IndexFixture()
                        .metadata(
                                Map.of(
                                        "indexed_at",
                                        "2026-08-26T14:30:00",
                                        "last_commit",
                                        "unknown"))
                        .snapshot());

        String result = JDBI_READS.getHotspots(noGitJdbi, 10, null).toString();
        assertTrue(result.contains("No git data available"));
        assertTrue(result.contains("git init"));

        result = JDBI_READS.getFileHistory(noGitJdbi, "SomeClass", 10).toString();
        assertTrue(result.contains("No git data available"));
    }
}
