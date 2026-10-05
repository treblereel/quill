package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class BytecodeSourceMapperTest {

    @Test
    void usesSourceFileAttributeWhenKotlinFilenameDiffersFromClassName(
            @TempDir Path project) throws Exception {
        Path classes = project.resolve("build/classes/kotlin/main");
        Path classFile = classes.resolve("org/acme/Invoice.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, classBytes("org/acme/Invoice", "Billing.kt"));
        Path sourceRoot = project.resolve("src/main/kotlin");
        Path source = sourceRoot.resolve("org/acme/Billing.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme\nclass Invoice\nclass Payment");

        var result = BytecodeSourceMapper.map(
                ClassFileSnapshot.capture(List.of(classes)), List.of(sourceRoot));

        assertEquals(source, result.get("org.acme.Invoice"));
    }

    @Test
    void mapsTopLevelFacadeAndNestedClassToTheirKotlinFile(
            @TempDir Path project) throws Exception {
        Path classes = project.resolve("build/classes/kotlin/main");
        Path sourceRoot = project.resolve("src/main/kotlin");
        Path source = sourceRoot.resolve("org/acme/Billing.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme\nfun charge() = Unit");
        writeClass(classes, "org/acme/BillingKt", "Billing.kt");
        writeClass(classes, "org/acme/Order$Companion", "Billing.kt");

        var result = BytecodeSourceMapper.map(
                ClassFileSnapshot.capture(List.of(classes)), List.of(sourceRoot));

        assertEquals(source, result.get("org.acme.BillingKt"));
        assertEquals(source, result.get("org.acme.Order$Companion"));
    }

    private static void writeClass(
            Path classes, String internalName, String sourceFile) throws Exception {
        Path output = classes.resolve(internalName + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, classBytes(internalName, sourceFile));
    }

    private static byte[] classBytes(String internalName, String sourceFile) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        writer.visitSource(sourceFile, null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
