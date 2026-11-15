package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.ReadQueryContext;

class FileNavigationQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;

    @Test
    void listsBoundedTreeFromCurrentFiles() throws Exception {
        Jdbi jdbi = database();
        insert(jdbi, "api/src/main/java/com/acme/Api.java", "java", "current", "modified", "api");
        insert(jdbi, "api/src/main/resources/app.properties", "resource", "current", null, "api");
        insert(jdbi, "api/src/main/java/com/acme/Old.java", "java", "deleted", "deleted", "api");

        var result =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new FileNavigationQueries()
                                        .listProjectTree(readScope, "api", 2, false, 100, 0));

        assertEquals(2, result.path("depth").asInt());
        assertEquals("api/src", result.path("entries").get(0).path("path").asText());
        assertTrue(result.toString().contains("api/src/main"));
        assertFalse(result.toString().contains("Old.java"));
    }

    @Test
    void searchesCurrentAndOptionalDeletedFilesWithFilters() throws Exception {
        Jdbi jdbi = database();
        insert(jdbi, "core/src/main/java/com/acme/Order.java", "java", "current", null, "core");
        insert(
                jdbi,
                "api/src/main/java/com/acme/OrderResource.java",
                "java",
                "current",
                "modified",
                "api");
        insert(
                jdbi,
                "api/src/main/java/com/acme/OldOrder.java",
                "java",
                "deleted",
                "deleted",
                "api");

        FileNavigationQueries queries = new FileNavigationQueries();
        var current =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.searchFiles(
                                        readScope, "*Order*", "api", "java", null, null, false, 20,
                                        0));
        var all =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.searchFiles(
                                        readScope, "Order", "api", "java", null, null, true, 20,
                                        0));

        assertEquals(1, current.path("total").asInt());
        assertEquals(2, all.path("total").asInt());
        assertEquals("modified", current.path("files").get(0).path("worktree_status").asText());
    }

    @Test
    void appliesPathGlobsDirectoryAndExtensionAndRanksExactBasenameFirst() throws Exception {
        Jdbi jdbi = database();
        insert(jdbi, "api/src/main/java/com/acme/Order.java", "java", "current", null, "api");
        insert(jdbi, "api/src/test/java/com/acme/OrderTest.java", "java", "current", null, "api");
        insert(jdbi, "docs/Order.java.md", "resource", "current", null, ".");

        FileNavigationQueries queries = new FileNavigationQueries();
        var glob =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.searchFiles(
                                        readScope,
                                        "**/Order*.java",
                                        null,
                                        null,
                                        "api/src",
                                        ".java",
                                        false,
                                        20,
                                        0));
        var ranked =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.searchFiles(
                                        readScope,
                                        "Order.java",
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        20,
                                        0));

        assertEquals(2, glob.path("total").asInt());
        assertEquals("glob", glob.path("match_mode").asText());
        assertEquals(
                "api/src/main/java/com/acme/Order.java",
                ranked.path("files").get(0).path("path").asText());
        assertEquals("exact_basename", ranked.path("files").get(0).path("match").asText());
    }

    private Jdbi database() {
        return QuillDatabase.create(temp.resolve("index-" + System.nanoTime() + ".db"));
    }

    private static void insert(
            Jdbi jdbi, String path, String kind, String lifecycle, String status, String module) {
        jdbi.useHandle(
                handle ->
                        handle.createUpdate(
                                        """
                        INSERT INTO files(project_path, repository_path, kind, origin, lifecycle,
                                          worktree_status, module, source_set)
                        VALUES (:path, :path, :kind, 'source', :lifecycle, :status, :module, 'main')
                        """)
                                .bind("path", path)
                                .bind("kind", kind)
                                .bind("lifecycle", lifecycle)
                                .bind("status", status)
                                .bind("module", module)
                                .execute());
    }
}
