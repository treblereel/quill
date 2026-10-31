package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.GitFileStats;

class FileInventoryTest {

    @TempDir Path tempDir;

    @Test
    void classifiesCurrentGeneratedHistoricalAndOrphanEntries() throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Path generatedRoot = tempDir.resolve("target/generated-sources/annotations");
        Path service = tempDir.resolve(
                "src/main/resources/META-INF/services/javax.annotation.processing.Processor");
        Path testResource = tempDir.resolve("src/test/resources/fixtures/order.json");
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(generatedRoot.resolve("example"));
        Files.createDirectories(service.getParent());
        Files.createDirectories(testResource.getParent());
        Files.writeString(sourceRoot.resolve("example/Current.java"),
                "package example; class Current {}\n");
        Files.writeString(generatedRoot.resolve("example/Generated.java"),
                "package example; class Generated {}\n");
        Files.writeString(service, "example.Processor\n");
        Files.writeString(testResource, "{}\n");

        List<ClassRecord> classes = List.of(
                cls(1, "example.Current", sourceRoot.resolve("example/Current.java").toString()),
                cls(2, "example.Generated", generatedRoot.resolve("example/Generated.java").toString()),
                cls(3, "example.StaleOutput", null));
        List<GitFileStats> history = List.of(new GitFileStats(1,
                "src/main/java/example/Deleted.java", null, 2, "2026-01-02T00:00:00Z",
                "Test", "2026-01-01T00:00:00Z", 1));

        FileInventory.Result result = FileInventory.build(tempDir, List.of(tempDir),
                List.of(sourceRoot, generatedRoot), classes, history,
                WorktreeInspector.Snapshot.empty());

        assertEquals("source", result.classes().get(0).origin());
        assertEquals("generated", result.classes().get(1).origin());
        assertEquals(".", result.classes().get(0).module());
        assertEquals("main", result.classes().get(0).sourceSet());
        assertEquals("main", result.classes().get(1).sourceSet());
        assertEquals("orphan_output", result.classes().get(2).origin());
        assertNull(result.classes().get(2).fileId());
        assertEquals("historical", result.files().stream()
                .filter(file -> file.projectPath().endsWith("Deleted.java"))
                .findFirst().orElseThrow().lifecycle());
        assertEquals("service_descriptor", result.files().stream()
                .filter(file -> file.projectPath().contains("META-INF/services"))
                .findFirst().orElseThrow().kind());
        assertEquals("test", result.files().stream()
                .filter(file -> file.projectPath().equals("src/test/resources/fixtures/order.json"))
                .findFirst().orElseThrow().sourceSet());
    }

    @Test
    void identifiesNestedModuleAndSourceSet() throws Exception {
        Path module = tempDir.resolve("applications/navigation");
        Path sourceRoot = module.resolve("src/main/java");
        Path source = sourceRoot.resolve("example/Navigation.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; class Navigation {}\n");

        FileInventory.Result result = FileInventory.build(tempDir, List.of(module),
                List.of(sourceRoot), List.of(cls(1, "example.Navigation", source.toString())),
                List.of(), WorktreeInspector.Snapshot.empty());

        assertEquals("applications/navigation", result.classes().getFirst().module());
        assertEquals("main", result.classes().getFirst().sourceSet());
        assertEquals("applications/navigation", result.files().getFirst().module());
        assertEquals("main", result.files().getFirst().sourceSet());
    }

    @Test
    void infersKotlinSourceForNestedClass() throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/kotlin");
        Path source = sourceRoot.resolve("example/Outer.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example\nclass Outer { class Nested }");

        FileInventory.Result result = FileInventory.build(tempDir, List.of(tempDir),
                List.of(sourceRoot), List.of(cls(1, "example.Outer$Nested", null)), List.of(),
                WorktreeInspector.Snapshot.empty());

        assertEquals("src/main/kotlin/example/Outer.kt", result.classes().getFirst().sourceFile());
        assertEquals("kotlin", result.files().getFirst().kind());
    }

    @Test
    void compiledOwnershipOverridesPathConventionAndKeepsConflictsUnknown() throws Exception {
        Path source = tempDir.resolve("shared sources/Probe.java");
        Files.createDirectories(source.getParent()); Files.writeString(source, "class Probe {}");
        var main = new org.treblereel.mcp.model.ClassOccurrenceRecord(1, 1, "Probe", "service",
                "integrationTest", "out/integration", "out/integration/Probe.class",
                "shared sources/Probe.java", "source");
        var result = FileInventory.build(tempDir, List.of(tempDir), List.of(source.getParent()),
                List.of(cls(1, "Probe", source.toString())), List.of(), WorktreeInspector.Snapshot.empty(), List.of(main));
        assertEquals("service", result.files().getFirst().module());
        assertEquals("integrationTest", result.files().getFirst().sourceSet());
        assertEquals("integrationTest", result.classes().getFirst().sourceSet());
        var conflicting = new org.treblereel.mcp.model.ClassOccurrenceRecord(2, 1, "Probe", "service",
                "main", "out/main", "out/main/Probe.class", "shared sources/Probe.java", "source");
        result = FileInventory.build(tempDir, List.of(tempDir), List.of(source.getParent()),
                List.of(cls(1, "Probe", source.toString())), List.of(), WorktreeInspector.Snapshot.empty(), List.of(main, conflicting));
        assertNull(result.files().getFirst().sourceSet());
        assertNull(result.classes().getFirst().sourceSet());
    }

    @Test
    void prefersCurrentSourceAfterModuleMove() throws Exception {
        String oldPath = "cbr-jpa/src/integration-test/java/example/MovedTest.java";
        String currentPath = "cbr-recovery-jpa/src/integration-test/java/example/MovedTest.java";
        Path current = tempDir.resolve(currentPath);
        Files.createDirectories(current.getParent());
        Files.writeString(current, "package example; class MovedTest {}\n");
        var history = List.of(new GitFileStats(1, oldPath, null, 2,
                "2026-01-02T00:00:00Z", "Test", "2026-01-01T00:00:00Z", 1));

        for (String source : java.util.Arrays.asList(null, oldPath, currentPath)) {
            var result = FileInventory.build(tempDir,
                    List.of(tempDir.resolve("cbr-jpa"), tempDir.resolve("cbr-recovery-jpa")),
                    List.of(current.getParent().getParent()),
                    List.of(cls(1, "example.MovedTest", source)), history,
                    WorktreeInspector.Snapshot.empty());
            var selected = result.classes().getFirst();
            assertEquals(currentPath, selected.sourceFile(), "original source: " + source);
            assertEquals("current", selected.lifecycle());
            assertEquals("cbr-recovery-jpa", selected.module());
            assertEquals("integration-test", selected.sourceSet());
            assertEquals("historical", result.files().stream()
                    .filter(file -> file.projectPath().equals(oldPath))
                    .findFirst().orElseThrow().lifecycle());
        }
    }

    @Test
    void retainsHistoricalSourceWhenNoCurrentSourceExists() {
        String path = "old/src/test/java/example/Removed.java";
        var history = List.of(new GitFileStats(1, path, null, 2,
                "2026-01-02T00:00:00Z", "Test", "2026-01-01T00:00:00Z", 1));
        var result = FileInventory.build(tempDir, List.of(tempDir.resolve("old")), List.of(),
                List.of(cls(1, "example.Removed", null)), history,
                WorktreeInspector.Snapshot.empty());
        assertEquals(path, result.classes().getFirst().sourceFile());
        assertEquals("historical", result.classes().getFirst().lifecycle());
    }

    private static ClassRecord cls(int id, String name, String source) {
        return new ClassRecord(id, name, "CLASS", "java.lang.Object", List.of(),
                source, 1, false, 10);
    }
}
