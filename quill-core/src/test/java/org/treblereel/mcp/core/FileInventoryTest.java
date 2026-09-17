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
        Files.createDirectories(sourceRoot.resolve("example"));
        Files.createDirectories(generatedRoot.resolve("example"));
        Files.createDirectories(service.getParent());
        Files.writeString(sourceRoot.resolve("example/Current.java"),
                "package example; class Current {}\n");
        Files.writeString(generatedRoot.resolve("example/Generated.java"),
                "package example; class Generated {}\n");
        Files.writeString(service, "example.Processor\n");

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

    private static ClassRecord cls(int id, String name, String source) {
        return new ClassRecord(id, name, "CLASS", "java.lang.Object", List.of(),
                source, 1, false, 10);
    }
}
