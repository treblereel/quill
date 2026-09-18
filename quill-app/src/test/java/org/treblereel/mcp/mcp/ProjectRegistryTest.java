package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.ToolProvider;
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
        ProjectRegistry.ProjectEntry entry = registry.resolve().projects().stream()
                .findFirst().orElse(null);
        assertNull(entry, "An unindexed project must not be exposed as queryable");
        ProjectRegistry.Resolution resolution = registry.resolve();
        assertTrue(resolution.errors().getFirst().contains("my-project"));
        ProjectRegistry.ProjectIssue issue = resolution.issues().getFirst();
        assertEquals("build_required", issue.code());
        assertEquals("maven", issue.buildSystem());
        assertFalse(issue.buildWasStarted());
        assertTrue(issue.recommendedAction().contains("Decide whether"));
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
    void indexedProjectWithoutCompiledOutputsRemainsQueryableButReportsBuildRequired()
            throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("cleaned-project"));
        Files.createFile(project.resolve("pom.xml"));
        createPublishedDatabase(project, "cleaned");
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);

        ProjectRegistry.Resolution resolution = registry.resolve();

        assertEquals(1, resolution.projects().size());
        assertTrue(resolution.errors().isEmpty());
        assertEquals("build_required", resolution.issues().getFirst().code());
        assertFalse(resolution.issues().getFirst().buildWasStarted());
    }

    @Test
    void pureParentPomIsReportedAsMetadataOnly() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("parent"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>parent</artifactId><version>1</version>
                  <packaging>pom</packaging>
                </project>
                """);
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);

        ProjectRegistry.Resolution resolution = registry.resolve();

        assertTrue(resolution.projects().isEmpty());
        ProjectRegistry.ProjectIssue issue = resolution.issues().getFirst();
        assertEquals("metadata_only", issue.code());
        assertFalse(issue.buildWasStarted());
    }

    @Test
    void pomAggregatorWithCodeModuleStillRequiresBuild() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("reactor"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>reactor</artifactId><version>1</version>
                  <packaging>pom</packaging><modules><module>service</module></modules>
                </project>
                """);
        Path service = Files.createDirectories(project.resolve("service"));
        Files.writeString(service.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.acme</groupId><artifactId>reactor</artifactId>
                    <version>1</version></parent><artifactId>service</artifactId>
                </project>
                """);
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);

        assertEquals("build_required", registry.resolve().issues().getFirst().code());
    }

    @Test
    void publishedGenerationReplacesTheCachedDatabase() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("updated-project"));
        Files.createFile(project.resolve("pom.xml"));
        createPublishedDatabase(project, "first");
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);

        var first = registry.resolve().projects().getFirst().jdbi();
        createPublishedDatabase(project, "second");
        var second = registry.resolve().projects().getFirst().jdbi();

        assertNotSame(first, second);
        assertEquals(1, registry.cachedDatabaseCount(),
                "An obsolete immutable generation must not remain cached");
    }

    @Test
    void removedProjectEvictsItsDatabaseWithoutInvalidatingInFlightReference()
            throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("removed-project"));
        Files.createFile(project.resolve("pom.xml"));
        createPublishedDatabase(project, "present");
        AtomicReference<ProjectScope.Snapshot> current = new AtomicReference<>(
                new ProjectScope.Snapshot(1,
                        List.of(new ProjectScope.Project("removed-project", project))));
        ProjectRegistry registry = new ProjectRegistry(current::get);

        var inFlight = registry.resolve().projects().getFirst().jdbi();
        current.set(new ProjectScope.Snapshot(2, List.of()));
        assertTrue(registry.resolve().projects().isEmpty());

        assertEquals(0, registry.cachedDatabaseCount());
        assertEquals("present", inFlight.withHandle(handle -> handle.createQuery(
                        "SELECT value FROM metadata WHERE key = 'index_id'")
                .mapTo(String.class).one()));
    }

    @Test
    void selectorAvoidsOpeningUnselectedProjectIndexes() throws IOException {
        Path selected = Files.createDirectories(tempDir.resolve("selected"));
        Files.createFile(selected.resolve("pom.xml"));
        createPublishedDatabase(selected, "selected");
        Path broken = Files.createDirectories(tempDir.resolve("broken"));
        Files.createFile(broken.resolve("pom.xml"));
        Path brokenQuill = Files.createDirectories(broken.resolve(".quill"));
        Files.writeString(brokenQuill.resolve("bad.db"), "broken");
        Files.writeString(brokenQuill.resolve("refs.json"), "{\"@worktree\":\"bad\"}");
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(selected);
        registry.register(broken);

        ProjectRegistry.Resolution resolution = registry.resolve("selected");

        assertEquals(1, resolution.projects().size());
        assertTrue(resolution.errors().isEmpty(),
                "An explicitly unselected broken repository must not affect the request");
        assertEquals(1, registry.cachedDatabaseCount());
    }

    @Test
    void singleRegisteredProjectPreservesItsNameAndCanonicalRoot() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("single-project"));
        Files.createFile(project.resolve("pom.xml"));
        createPublishedDatabase(project, "single");

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project.resolve("."));

        ProjectRegistry.Resolution resolution = registry.resolve();
        assertTrue(resolution.errors().isEmpty());
        assertEquals(1, resolution.projects().size());
        assertEquals("single-project", resolution.projects().getFirst().name());
        assertEquals(project.toAbsolutePath().normalize(),
                resolution.projects().getFirst().root());
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
    void concurrentFirstRequestsRebuildAnOutdatedSchemaWithoutRunningABuild() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("outdated-project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>outdated</artifactId><version>1</version>
                </project>
                """);
        Path source = project.resolve("src/main/java/org/acme/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme; public final class App {}\n");
        Path classes = Files.createDirectories(project.resolve("target/classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString()));
        Path old = createPublishedDatabase(project, "outdated");
        QuillDatabase.openWritable(old).useHandle(handle ->
                handle.execute("PRAGMA user_version = 7"));

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);
        var executor = Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<ProjectRegistry.Resolution>> resolutions;
        try {
            resolutions = executor.invokeAll(java.util.stream.IntStream.range(0, 8)
                    .<java.util.concurrent.Callable<ProjectRegistry.Resolution>>mapToObj(
                            ignored -> registry::resolve)
                    .toList());
        } finally {
            executor.shutdownNow();
        }

        for (var resolution : resolutions) {
            assertTrue(resolution.get().errors().isEmpty(),
                    resolution.get().errors().toString());
            assertEquals(1, resolution.get().projects().size());
        }
        Path current = org.treblereel.mcp.command.ProjectIndexStore
                .findBestAvailableDb(project);
        assertNotNull(current);
        assertEquals(QuillDatabase.currentSchemaVersion(),
                QuillDatabase.inspectSchemaVersion(current));
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(project.resolve(".mvn/extensions.xml")));
        assertFalse(Files.exists(project.resolve("CLAUDE.md")));
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
