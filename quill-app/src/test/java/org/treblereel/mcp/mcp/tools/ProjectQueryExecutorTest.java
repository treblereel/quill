package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.project.ProjectRegistry;

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

        ProjectQueryExecutor.Batch batch =
                executor.execute(
                        (String) null,
                        (project, context) -> {
                            if (project.name().equals("zeta"))
                                throw new IllegalStateException("broken query");
                            return Json.MAPPER.createObjectNode().put("ok", true);
                        });

        assertEquals(2, batch.results().size());
        assertEquals("alpha", batch.results().get(0).project());
        assertNull(batch.results().get(0).error());
        assertEquals("zeta", batch.results().get(1).project());
        assertEquals("broken query", batch.results().get(1).error());
        assertTrue(batch.results().get(0).successful());
    }

    @Test
    void executesIndependentProjectQueriesConcurrently() throws Exception {
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project("zeta"));
        registry.register(project("alpha"));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(2);

        ProjectQueryExecutor.Batch batch =
                new ProjectQueryExecutor(registry)
                        .execute(
                                (String) null,
                                (project, context) -> {
                                    assertTrue(Thread.currentThread().isVirtual());
                                    int current = active.incrementAndGet();
                                    maximum.accumulateAndGet(current, Math::max);
                                    try {
                                        barrier.await(2, TimeUnit.SECONDS);
                                        return Json.MAPPER
                                                .createObjectNode()
                                                .put("project", project.name());
                                    } catch (Exception failure) {
                                        throw new IllegalStateException(failure);
                                    } finally {
                                        active.decrementAndGet();
                                    }
                                });

        assertEquals(2, batch.results().size());
        assertEquals(2, maximum.get());
        assertEquals("alpha", batch.results().get(0).project());
    }

    @Test
    void nestedFanOutDoesNotWaitForAnAdditionalAdmissionSlot() throws Exception {
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project("alpha"));
        registry.register(project("zeta"));
        var projects = registry.resolve().projects();
        var executor = new ProjectQueryExecutor(registry);
        int parallel = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        var barrier = new CyclicBarrier(parallel);
        try (registry;
                var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending =
                    new java.util.ArrayList<
                            java.util.concurrent.Future<ProjectQueryExecutor.QueryResult>>();
            try {
                for (int i = 0; i < parallel; i++)
                    pending.add(
                            requests.submit(
                                    () ->
                                            executor.executeProject(
                                                    projects.getFirst(),
                                                    (parent, context) -> {
                                                        try {
                                                            barrier.await(2, TimeUnit.SECONDS);
                                                        } catch (Exception failure) {
                                                            throw new IllegalStateException(
                                                                    failure);
                                                        }
                                                        Thread owner = Thread.currentThread();
                                                        var nested =
                                                                executor.executeProjects(
                                                                        projects,
                                                                        (child, reader) -> {
                                                                            assertEquals(
                                                                                    owner,
                                                                                    Thread
                                                                                            .currentThread());
                                                                            return Json.MAPPER
                                                                                    .createObjectNode()
                                                                                    .put(
                                                                                            "index_id",
                                                                                            reader.index
                                                                                                    .getMetadata()
                                                                                                    .get(
                                                                                                            "index_id"));
                                                                        },
                                                                        true);
                                                        assertTrue(
                                                                nested.stream()
                                                                        .allMatch(
                                                                                ProjectQueryExecutor
                                                                                                .QueryResult
                                                                                        ::successful));
                                                        assertEquals(
                                                                "alpha-index",
                                                                context.index
                                                                        .getMetadata()
                                                                        .get("index_id"));
                                                        return Json.MAPPER
                                                                .createObjectNode()
                                                                .put("status", "ok");
                                                    })));
                for (var task : pending) {
                    var result = task.get(3, TimeUnit.SECONDS);
                    assertTrue(result.successful(), result.error());
                    assertEquals("ok", result.json().path("status").asText());
                }
            } finally {
                pending.forEach(task -> task.cancel(true));
            }
        }
    }

    @Test
    void onlyGraphOperationsPrepareTypeEvidence() throws Exception {
        try (var registry = new ProjectRegistry()) {
            registry.register(project("selected"));
            var entry = registry.resolve().projects().getFirst();
            var inventories = new AtomicInteger();
            entry.jdbi()
                    .setSqlLogger(
                            new org.jdbi.v3.core.statement.SqlLogger() {
                                @Override
                                public void logBeforeExecution(
                                        org.jdbi.v3.core.statement.StatementContext context) {
                                    if (context.getRawSql()
                                            .startsWith("SELECT * FROM classes WHERE lifecycle"))
                                        inventories.incrementAndGet();
                                }
                            });
            var executor = new ProjectQueryExecutor(registry);
            assertTrue(
                    executor.execute(
                                    null,
                                    (p, context) -> {
                                        assertTrue(!context.hasGraph());
                                        return Json.MAPPER
                                                .createObjectNode()
                                                .put(
                                                        "index_id",
                                                        context.index
                                                                .getMetadata()
                                                                .get("index_id"));
                                    })
                            .results()
                            .getFirst()
                            .successful());
            assertEquals(0, inventories.get());
            assertTrue(
                    executor.execute(
                                    null,
                                    (p, context) -> {
                                        assertTrue(context.hasGraph());
                                        return Json.MAPPER
                                                .createObjectNode()
                                                .put(
                                                        "index_id",
                                                        context.index
                                                                .getMetadata()
                                                                .get("index_id"));
                                    },
                                    true)
                            .results()
                            .getFirst()
                            .successful());
            assertEquals(1, inventories.get());
        }
    }

    private Path project(String name) throws Exception {
        Path root = Files.createDirectories(temp.resolve(name));
        Files.writeString(
                root.resolve("pom.xml"),
                """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>%s</artifactId><version>1</version>
                </project>
                """
                        .formatted(name));
        Path classes = Files.createDirectories(root.resolve("target/classes/acme"));
        Files.write(classes.resolve("Sample.class"), new byte[] {0});
        Path quill = Files.createDirectories(root.resolve(".quill"));
        String index = name + "-index";
        var database = QuillDatabase.create(quill.resolve(index + ".db"));
        database.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO metadata(key, value) VALUES ('index_id', ?)", index);
                    handle.execute(
                            "INSERT INTO metadata(key, value) VALUES ('indexed_at', '2026-09-18T00:00:00Z')");
                    handle.execute(
                            "INSERT INTO metadata(key, value) VALUES ('last_commit', 'unknown')");
                    handle.execute(
                            "INSERT INTO metadata(key, value) VALUES ('project_root', ?)",
                            root.toString());
                });
        Files.writeString(quill.resolve("refs.json"), "{\"@worktree\":\"" + index + "\"}");
        return root;
    }
}
