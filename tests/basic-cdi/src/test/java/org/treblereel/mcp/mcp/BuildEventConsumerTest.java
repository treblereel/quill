package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.command.ProjectInitializer;
import org.treblereel.mcp.db.IndexReader;

class BuildEventConsumerTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");
    private static final Path DIRTY_RESOURCE = PROJECT_ROOT.resolve(
            "src/main/resources/quill-build-event-test.txt");

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(DIRTY_RESOURCE);
        if (!Files.exists(QUILL_DIR)) return;
        try (var paths = Files.walk(QUILL_DIR)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void registryConsumesSuccessfulBuildBeforeServingProject() throws Exception {
        assertTrue(ProjectInitializer.initialize(PROJECT_ROOT, true));
        Files.createDirectories(DIRTY_RESOURCE.getParent());
        Files.writeString(DIRTY_RESOURCE, "compiled externally");
        Path events = Files.createDirectories(QUILL_DIR.resolve("build-events"));
        writeEvent(events.resolve("maven-test.json"), System.currentTimeMillis());

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(PROJECT_ROOT);
        ProjectRegistry.Resolution resolution = registry.resolve();

        assertTrue(resolution.errors().isEmpty(), String.join("; ", resolution.errors()));
        assertFalse(resolution.projects().isEmpty());
        assertTrue(Boolean.parseBoolean(IndexReader.getMetadata(
                resolution.projects().getFirst().jdbi())
                .getOrDefault("compiled_before_index", "false")));
        assertFalse(Files.exists(events), "consumed build events directory should be removed");
    }

    @Test
    void ignoresBuildEventOlderThanStructuralChange() throws Exception {
        assertTrue(ProjectInitializer.initialize(PROJECT_ROOT, true));
        Path events = Files.createDirectories(QUILL_DIR.resolve("build-events"));
        long buildFinishedAt = System.currentTimeMillis() - 10_000;
        writeEvent(events.resolve("maven-stale.json"), buildFinishedAt);
        Files.createDirectories(DIRTY_RESOURCE.getParent());
        Files.writeString(DIRTY_RESOURCE, "edited after build");
        Files.setLastModifiedTime(DIRTY_RESOURCE,
                FileTime.fromMillis(System.currentTimeMillis()));

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(PROJECT_ROOT);
        ProjectRegistry.Resolution resolution = registry.resolve();

        assertTrue(resolution.errors().isEmpty(), String.join("; ", resolution.errors()));
        assertFalse(resolution.projects().isEmpty());
        assertFalse(Boolean.parseBoolean(IndexReader.getMetadata(
                resolution.projects().getFirst().jdbi())
                .getOrDefault("compiled_before_index", "false")),
                "an event older than a structural edit must not bless stale bytecode");
        assertFalse(Files.exists(events), "stale build events should not be retried forever");
    }

    @Test
    void ignoresMalformedBuildEventWithoutBlockingReads() throws Exception {
        assertTrue(ProjectInitializer.initialize(PROJECT_ROOT, true));
        Path events = Files.createDirectories(QUILL_DIR.resolve("build-events"));
        Files.writeString(events.resolve("invalid.json"),
                "{\"version\":1,\"buildTool\":\"maven\",\"successful\":true}");

        ProjectRegistry registry = new ProjectRegistry();
        registry.register(PROJECT_ROOT);
        ProjectRegistry.Resolution resolution = registry.resolve();

        assertTrue(resolution.errors().isEmpty(), String.join("; ", resolution.errors()));
        assertFalse(resolution.projects().isEmpty());
        assertFalse(Files.exists(events), "invalid build events should be discarded");
    }

    private static void writeEvent(Path event, long finishedAt) throws Exception {
        Files.writeString(event, "{\"version\":1,\"buildTool\":\"maven\","
                + "\"successful\":true,\"finishedAt\":" + finishedAt + "}");
    }
}
