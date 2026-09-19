package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class ProjectQueryExecutorTest {

    @TempDir Path temp;

    @Test
    void resolvesSelectedProjectsAndIsolatesQueryFailures() throws Exception {
        Path first = project("zeta");
        Path second = project("alpha");
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(first);
        registry.register(second);
        ProjectQueryExecutor executor = new ProjectQueryExecutor(registry);

        ProjectQueryExecutor.Batch batch = executor.execute((String) null, project -> {
            if (project.name().equals("zeta")) throw new IllegalStateException("broken query");
            return "{\"ok\":true}";
        });

        assertEquals(2, batch.results().size());
        assertEquals("alpha", batch.results().get(0).project());
        assertNull(batch.results().get(0).error());
        assertEquals("zeta", batch.results().get(1).project());
        assertEquals("broken query", batch.results().get(1).error());
        assertTrue(batch.results().get(0).successful());
    }

    private Path project(String name) throws Exception {
        Path root = Files.createDirectories(temp.resolve(name));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>%s</artifactId><version>1</version>
                </project>
                """.formatted(name));
        Path classes = Files.createDirectories(root.resolve("target/classes/acme"));
        Files.write(classes.resolve("Sample.class"), new byte[] {0});
        Path quill = Files.createDirectories(root.resolve(".quill"));
        String index = name + "-index";
        var database = QuillDatabase.create(quill.resolve(index + ".db"));
        database.useHandle(handle -> {
            handle.execute("INSERT INTO metadata(key, value) VALUES ('index_id', ?)", index);
            handle.execute("INSERT INTO metadata(key, value) VALUES ('indexed_at', '2026-09-18T00:00:00Z')");
            handle.execute("INSERT INTO metadata(key, value) VALUES ('last_commit', 'unknown')");
            handle.execute("INSERT INTO metadata(key, value) VALUES ('project_root', ?)",
                    root.toString());
        });
        Files.writeString(quill.resolve("refs.json"), "{\"@worktree\":\"" + index + "\"}");
        return root;
    }
}
