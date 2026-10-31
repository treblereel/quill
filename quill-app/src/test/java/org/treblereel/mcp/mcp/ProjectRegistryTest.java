package org.treblereel.mcp.mcp;

import org.treblereel.mcp.index.ProjectIndexStore;
import org.treblereel.mcp.workspace.ProjectScope;
import org.treblereel.mcp.workspace.SingleProjectScope;

import org.treblereel.mcp.db.IndexSchema;
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
    void pendingRefreshReturnsImmediatelyWithoutExposingThePreviousIndex() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("refreshing"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        createPublishedDatabase(project, "previous");
        var jobs = new java.util.ArrayDeque<Runnable>();
        ProjectRegistry registry = new ProjectRegistry(new SingleProjectScope(),
                new BuildEventConsumer(jobs::add));
        registry.register(project);
        assertEquals(1, registry.resolve().projects().size());
        Path events = Files.createDirectories(project.resolve(".quill/build-events"));
        Files.writeString(events.resolve("event.json"),
                "{\"version\":3,\"buildTool\":\"maven\",\"successful\":false,"
                        + "\"finishedAt\":" + System.currentTimeMillis()
                        + ",\"captureScope\":\"exception_chain\","
                        + "\"failureMessagesBase64\":[],\"diagnosticsBase64\":[]}");
        var tools = new QuillTools(registry);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var targets = java.util.Optional.of(List.of("One","Two","Three","Four","Five","Six"));
        var limit = java.util.Optional.of(40);
        for (int request = 0; request < 3; request++) {
            String response = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> tools.change_session(targets, "Update persistence", java.util.Optional.empty(),
                            java.util.Optional.empty(), limit, java.util.Optional.empty()));
            var pending = json.readTree(response);
            assertEquals("refresh_pending", pending.path("status").asText());
            assertEquals("build_events_queued", pending.path("reason").asText());
            assertEquals("wait", pending.path("action").asText());
            assertEquals("queued", pending.path("stage").asText());
            assertTrue(pending.path("elapsed_ms").asLong(-1) >= 0);
            assertEquals(2000, pending.path("retry_after_ms").asInt());
            assertTrue(pending.path("automatic_retry").asBoolean());
            assertTrue(pending.path("previous_generation_available").asBoolean());
            assertTrue(pending.path("retryable").asBoolean());
            assertFalse(pending.has("verification_receipt"));
            assertFalse(pending.path("verified").asBoolean());
        }
        assertEquals("refresh_pending", json.readTree(tools.verify_change(targets, limit,
                java.util.Optional.empty())).path("status").asText());
        assertEquals("refresh_pending", json.readTree(tools.search_symbols("*InvocationStore",
                java.util.Optional.of("class"), java.util.Optional.empty(), limit,
                java.util.Optional.empty(), java.util.Optional.empty())).path("status").asText());
        assertEquals("refresh_pending", json.readTree(tools.find_architecture_violations(
                java.util.Optional.of("module"), "one", List.of("two"), java.util.Optional.empty(),
                limit, java.util.Optional.empty(), java.util.Optional.empty())).path("status").asText());
        assertEquals(1, jobs.size(), "Retries must share the running refresh");
        jobs.remove().run();
        assertEquals(1, registry.resolve().projects().size());
        assertFalse(Files.exists(events));
        assertFalse(json.readTree(project.resolve(".quill/build-state.json").toFile())
                .path("successful").asBoolean());
    }

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
        assertEquals("classes_missing", issue.buildReason());
        assertEquals("maven", issue.buildSystem());
        assertFalse(issue.buildWasStarted());
        assertTrue(issue.recommendedAction().contains("quick_compile"));
        assertEquals(1, registry.cachedReadinessCount());
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
        assertEquals("classes_missing", resolution.issues().getFirst().buildReason());
        assertFalse(resolution.issues().getFirst().buildWasStarted());
    }

    @Test
    void indexedProjectWithNewerSourcesRemainsQueryableButReportsStaleClasses()
            throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("stale-project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path source = project.resolve("src/main/java/org/acme/App.java");
        Path compiled = project.resolve("target/classes/org/acme/App.class");
        Files.createDirectories(source.getParent());
        Files.createDirectories(compiled.getParent());
        Files.writeString(source, "package org.acme; public final class App {}\n");
        Files.write(compiled, new byte[] {1});
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(compiled,
                java.nio.file.attribute.FileTime.fromMillis(now - 2_000));
        Files.setLastModifiedTime(source,
                java.nio.file.attribute.FileTime.fromMillis(now + 2_000));
        createPublishedDatabase(project, "stale");
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);

        ProjectRegistry.Resolution resolution = registry.resolve();

        assertEquals(1, resolution.projects().size());
        ProjectRegistry.ProjectIssue issue = resolution.issues().getFirst();
        assertEquals("build_required", issue.code());
        assertEquals("classes_stale", issue.buildReason());
        assertEquals(List.of("."), issue.staleModules());
        String response = ProjectAvailabilityResponses.error(issue);
        assertTrue(response.contains("\"build_reason\":\"classes_stale\""));
        assertTrue(response.contains("\"stale_modules\":[\".\"]"));
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
    void closeWaitsForConcurrentResolutionAndPreventsReopening() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("concurrent-close"));
        Files.createFile(project.resolve("pom.xml"));
        createPublishedDatabase(project, "present");
        var resolving = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var closing = new java.util.concurrent.CountDownLatch(1);
        ProjectScope scope = () -> {
            resolving.countDown();
            try {
                if (!resume.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new ProjectScope.Snapshot(1, List.of(new ProjectScope.Project("present", project)));
        };
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var registry = new ProjectRegistry(scope);
        try {
            var resolved = workers.submit(() -> registry.resolve());
            assertTrue(resolving.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var closed = workers.submit(() -> {
                closing.countDown();
                registry.close();
            });
            assertTrue(closing.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> closed.get(30, java.util.concurrent.TimeUnit.MILLISECONDS));
            resume.countDown();
            var database = resolved.get(5, java.util.concurrent.TimeUnit.SECONDS).projects().getFirst().jdbi();
            closed.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, registry.cachedDatabaseCount());
            assertThrows(IllegalStateException.class, registry::resolve);
            assertEquals("present", database.withHandle(handle -> handle.createQuery(
                    "SELECT value FROM metadata WHERE key='index_id'").mapTo(String.class).one()));
        } finally {
            resume.countDown();
            workers.shutdownNow();
            registry.close();
        }
    }

    @Test
    void closingRegistryRetiresOwnersAndKeepsResolvedRequestsReadable() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("closing-project"));
        Files.createFile(project.resolve("pom.xml"));
        createPublishedDatabase(project, "present");
        try (var registry = new ProjectRegistry()) {
            registry.register(project);
            var database = registry.resolve().projects().getFirst().jdbi();
            registry.close();
            registry.close();
            assertEquals(0, registry.cachedDatabaseCount());
            assertThrows(IllegalStateException.class, registry::resolve);
            assertEquals("present", database.withHandle(handle -> handle.createQuery(
                    "SELECT value FROM metadata WHERE key='index_id'").mapTo(String.class).one()));
        }
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
    void concurrentFirstRequestsRebuildAnOutdatedSchemaInTheBackgroundWithoutRunningABuild() throws Exception {
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

        // Concurrent first requests share one background rebuild and either see the rebuilt
        // index or a retryable refresh_pending; none blocks on the initialization.
        for (var resolution : resolutions) {
            var resolved = resolution.get();
            assertTrue(resolved.projects().size() == 1 || resolved.issues().stream()
                            .allMatch(issue -> issue.code().equals("refresh_pending")),
                    resolved.issues().toString());
        }
        ProjectRegistry.Resolution ready = registry.resolve();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (ready.projects().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
            ready = registry.resolve();
        }
        assertTrue(ready.errors().isEmpty(), ready.errors().toString());
        assertEquals(1, ready.projects().size());
        Path current = org.treblereel.mcp.index.ProjectIndexStore
                .findBestAvailableDb(project);
        assertNotNull(current);
        assertEquals(IndexSchema.VERSION,
                IndexSchema.inspectVersion(current));
        assertFalse(current.equals(old), "The outdated generation must not remain active");
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
