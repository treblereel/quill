package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class ProjectRegistryTest {

    @TempDir Path tempDir;

    @Test
    void registerResolvesToProjectRoot() throws IOException {
        Path project = tempDir.resolve("my-project");
        Files.createDirectories(project);
        Files.createFile(project.resolve("pom.xml"));
        Path nested = project.resolve("src/main/java");
        Files.createDirectories(nested);

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(nested);

        assertFalse(registry.isEmpty());
    }

    @Test
    void registerFallsBackOnNoPom() {
        Path noPomDir = tempDir.resolve("no-pom");
        noPomDir.toFile().mkdirs();

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(noPomDir);

        assertFalse(registry.isEmpty());
    }

    @Test
    void registerDeduplicatesNames() throws IOException {
        Path p1 = tempDir.resolve("app1/myapp");
        Path p2 = tempDir.resolve("app2/myapp");
        Files.createDirectories(p1);
        Files.createDirectories(p2);
        Files.createFile(p1.resolve("pom.xml"));
        Files.createFile(p2.resolve("pom.xml"));

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(p1);
        registry.register(p2);

        var errors = registry.uninitializedErrors();
        assertEquals(2, errors.size());
        assertNotEquals(errors.get(0), errors.get(1),
                "Two projects with same dir name should get deduplicated names");
    }

    @Test
    void resolveReportsUnreadableDatabaseInsteadOfThrowing() throws IOException {
        Path project = tempDir.resolve("broken-project");
        Path quillDir = Files.createDirectories(project.resolve(".quill"));
        Files.createFile(project.resolve("pom.xml"));
        Files.writeString(quillDir.resolve("broken.db"), "not a sqlite database");
        Files.writeString(quillDir.resolve("refs.json"), "{\"@worktree\":\"broken\"}");

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);

        ProjectRegistry.Resolution resolution = registry.resolve();
        assertTrue(resolution.projects().isEmpty());
        assertEquals(1, resolution.errors().size());
        assertTrue(resolution.errors().get(0).contains("broken-project"));
    }

    @Test
    void resolveReusesValidatedDatabaseForImmutableGeneration() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("cached-project"));
        Files.createFile(project.resolve("pom.xml"));
        createPublishedDatabase(project, "cached");

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);

        var first = registry.resolve().projects().getFirst();
        var second = registry.resolve().projects().getFirst();

        assertSame(first.jdbi(), second.jdbi());
    }

    @Test
    void synchronousPrewarmMakesDatabaseAvailableToFirstRequest() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("prewarmed-project"));
        Files.createFile(project.resolve("pom.xml"));
        createPublishedDatabase(project, "prewarmed");

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);
        registry.prewarm(Runnable::run);

        assertEquals(1, registry.resolve().projects().size());
    }

    @Test
    void preparesSqliteQueryPathBeforeServingRequests() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("request-prewarmed-project"));
        Files.createFile(project.resolve("pom.xml"));
        createPublishedDatabase(project, "request-prewarmed");

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);

        assertDoesNotThrow(registry::prepareRequestPath);
        assertEquals(1, registry.resolve().projects().size());
    }

    private static Path createPublishedDatabase(Path project, String indexId) throws IOException {
        Path quillDir = Files.createDirectories(project.resolve(".quill"));
        Path database = quillDir.resolve(indexId + ".db");
        var jdbi = QuillDatabase.create(database);
        jdbi.useHandle(handle -> {
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "index_id", indexId);
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "last_commit", "unknown");
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "project_root", project.toString());
        });
        Files.writeString(quillDir.resolve("refs.json"),
                "{\"@worktree\":\"" + indexId + "\"}");
        return database;
    }
}
