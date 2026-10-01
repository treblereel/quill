package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

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

    @Test
    void mapsDuplicateKotlinClassesToSourcesInTheirOwningModules(
            @TempDir Path project) throws Exception {
        Path firstModule = project.resolve("app-one");
        Path secondModule = project.resolve("app-two");
        Path first = firstModule.resolve("build/classes/kotlin/main");
        Path second = secondModule.resolve("build/classes/kotlin/main");
        writeClass(first, "org/acme/Invoice", "Billing.kt");
        writeClass(second, "org/acme/Invoice", "Billing.kt");
        Path firstSource = writeSource(firstModule, "one");
        Path secondSource = writeSource(secondModule, "two");

        ClassFileSnapshot snapshot = ClassFileSnapshot.capture(List.of(first, second));
        var occurrences = ClassOccurrenceScanner.scan(project, snapshot,
                Map.of(first, firstModule, second, secondModule),
                Map.of(first, "main", second, "main"),
                Map.of("org.acme.Invoice", 7), List.of(
                        firstModule.resolve("src/main/kotlin"),
                        secondModule.resolve("src/main/kotlin")));

        assertEquals(List.of(
                        project.relativize(firstSource).toString(),
                        project.relativize(secondSource).toString()),
                occurrences.stream().map(value -> value.sourceFile()).toList());
    }

    private static Path writeSource(Path module, String marker) throws Exception {
        Path source = module.resolve("src/main/kotlin/org/acme/Billing.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme\nclass Invoice // " + marker);
        return source;
    }

    private static void writeClass(
            Path classes, String internalName, String sourceFile) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        writer.visitSource(sourceFile, null);
        writer.visitEnd();
        Path output = classes.resolve(internalName + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
