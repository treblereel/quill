package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class WorktreeStatusQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;

    @Test
    void reportsLiveDirtyFilesAndIndexedCommit() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        try (Git git = Git.init().setDirectory(temp.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern("pom.xml").call();
            var commit = git.commit().setMessage("initial")
                    .setAuthor("Test", "test@example.com").setSign(false).call();
            Jdbi jdbi = QuillDatabase.create(
                    Files.createDirectories(temp.resolve(".quill")).resolve("index.db"));
            jdbi.useHandle(handle -> {
                handle.createUpdate("INSERT INTO metadata(key, value) VALUES ('last_commit', :v)")
                        .bind("v", commit.getName()).execute();
                handle.createUpdate("INSERT INTO metadata(key, value) VALUES ('project_root', :v)")
                        .bind("v", temp.toString()).execute();
            });
            Files.writeString(temp.resolve("src.txt"), "dirty");

            var result = JSON.readTree(new WorktreeStatusQueries()
                    .getWorktreeStatus(jdbi, temp, "untracked", 10, 0));

            assertEquals("main", result.path("branch").asText());
            assertEquals(commit.getName(), result.path("indexed_commit").asText());
            assertTrue(result.path("dirty").asBoolean());
            assertEquals("src.txt", result.path("changes").get(0).path("path").asText());
            assertEquals("untracked", result.path("changes").get(0).path("status").asText());
        }
    }
}
