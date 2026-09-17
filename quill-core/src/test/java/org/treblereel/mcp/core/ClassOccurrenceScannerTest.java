package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassOccurrenceScannerTest {

    @Test
    void preservesDuplicateFqcnAcrossModuleOutputs(@TempDir Path project) throws Exception {
        Path first = project.resolve("app-one/target/classes");
        Path second = project.resolve("app-two/target/classes");
        Path classPath = Path.of("org/acme/GeneratedRegistry.class");
        Files.createDirectories(first.resolve(classPath).getParent());
        Files.createDirectories(second.resolve(classPath).getParent());
        Files.write(first.resolve(classPath), new byte[]{1});
        Files.write(second.resolve(classPath), new byte[]{2});
        Path handwrittenSource = project.resolve(
                "app-one/src/main/java/org/acme/GeneratedRegistry.java");
        Files.createDirectories(handwrittenSource.getParent());
        Files.writeString(handwrittenSource, "package org.acme; class GeneratedRegistry {}");
        Path generatedSource = project.resolve(
                "app-two/target/generated-sources/annotations/org/acme/GeneratedRegistry.java");
        Files.createDirectories(generatedSource.getParent());
        Files.writeString(generatedSource, "package org.acme; class GeneratedRegistry {}");

        ClassFileSnapshot snapshot = ClassFileSnapshot.capture(List.of(first, second));
        var occurrences = ClassOccurrenceScanner.scan(project, snapshot,
                Map.of(first, project.resolve("app-one"), second, project.resolve("app-two")),
                Map.of("org.acme.GeneratedRegistry", 7));

        assertEquals(2, occurrences.size());
        assertEquals(List.of("app-one", "app-two"),
                occurrences.stream().map(value -> value.module()).toList());
        assertEquals("source", occurrences.get(0).origin());
        assertEquals("generated", occurrences.get(1).origin());
        assertEquals(
                "app-two/target/generated-sources/annotations/org/acme/GeneratedRegistry.java",
                occurrences.get(1).sourceFile());
        assertEquals(7, occurrences.get(1).classId());
    }
}
