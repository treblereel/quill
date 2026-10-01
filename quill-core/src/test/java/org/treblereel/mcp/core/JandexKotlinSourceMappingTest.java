package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class JandexKotlinSourceMappingTest {

    @Test
    void indexesClassWhoseKotlinFilenameDoesNotMatchClassName(
            @TempDir Path project) throws Exception {
        Path classes = project.resolve("build/classes/kotlin/main");
        Path classFile = classes.resolve("org/acme/Invoice.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, classBytes());
        Path sourceRoot = project.resolve("src/main/kotlin");
        Path source = sourceRoot.resolve("org/acme/Billing.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme\nclass Invoice\nclass Payment");

        JandexScanner.ScanResult scan = JandexScanner.scan(
                ClassFileSnapshot.capture(List.of(classes)), List.of(sourceRoot));
        var invoice = scan.classes().stream()
                .filter(value -> value.className().equals("org.acme.Invoice"))
                .findFirst().orElseThrow();

        assertEquals(source.toString(), invoice.sourceFile());
        assertTrue(invoice.sourceTokens() > 0);
    }

    private static byte[] classBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "org/acme/Invoice", null,
                "java/lang/Object", null);
        writer.visitSource("Billing.kt", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
