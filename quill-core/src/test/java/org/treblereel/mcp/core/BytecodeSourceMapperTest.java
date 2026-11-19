package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class BytecodeSourceMapperTest {

    @Test
    void mapsMovedJavaPackagesWithinTheirOwnModuleAndSourceSet(@TempDir Path project) throws Exception {
        Path main = project.resolve("module/src/main/java/moved");
        Path test = project.resolve("module/src/test/java/moved");
        Path other = project.resolve("other/src/main/java/moved");
        for (Path directory : List.of(main, test, other)) {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("Invoice.java"),
                    "/* package wrong; */\npackage org.\n acme; @Deprecated class Invoice {String value=\"package fake;\";}");
        }
        Path classes = project.resolve("module/target/classes");
        Path tests = project.resolve("module/target/test-classes");
        writeClass(classes, "org/acme/Invoice", "Invoice.java");
        writeClass(tests, "org/acme/Invoice", "Invoice.java");
        var roots = List.of(other.getParent(), test.getParent(), main.getParent());
        assertEquals(main.resolve("Invoice.java"), BytecodeSourceMapper.map(
                ClassFileSnapshot.capture(List.of(classes)), roots).get("org.acme.Invoice"));
        assertEquals(test.resolve("Invoice.java"), BytecodeSourceMapper.map(
                ClassFileSnapshot.capture(List.of(tests)), roots).get("org.acme.Invoice"));
    }

    @Test
    void leavesMovedJavaDuplicatesUnresolved(@TempDir Path project) throws Exception {
        Path root = project.resolve("src/main/java");
        for (String directory : List.of("one", "two")) {
            Files.createDirectories(root.resolve(directory));
            Files.writeString(root.resolve(directory).resolve("Invoice.java"), "package org.acme; class Invoice {}");
        }
        Path classes = project.resolve("target/classes");
        writeClass(classes, "org/acme/Invoice", "Invoice.java");
        assertFalse(BytecodeSourceMapper.map(ClassFileSnapshot.capture(List.of(classes)), List.of(root))
                .containsKey("org.acme.Invoice"));
    }

    @Test
    void retainsConventionalJavaMappingsWhenHeaderNeedsCompilerTranslation(@TempDir Path project) throws Exception {
        Path root = project.resolve("src/main/java");
        Files.createDirectories(root.resolve("org/acme"));
        Files.writeString(root.resolve("org/acme/Invoice.java"), "package org." + '\\' + "u0061cme; class Invoice {}");
        Files.writeString(root.resolve("Default.java"), "class Default {}");
        Path classes = project.resolve("target/classes");
        writeClass(classes, "org/acme/Invoice", "Invoice.java");
        writeClass(classes, "Default", "Default.java");
        var mapping = BytecodeSourceMapper.map(ClassFileSnapshot.capture(List.of(classes)), List.of(root));
        assertEquals(root.resolve("org/acme/Invoice.java"), mapping.get("org.acme.Invoice"));
        assertEquals(root.resolve("Default.java"), mapping.get("Default"));
    }

    @Test
    void doesNotMapJavaWithWrongDeclaredPackageAtConventionalPath(@TempDir Path project) throws Exception {
        Path root = project.resolve("src/main/java");
        Files.createDirectories(root.resolve("org/acme"));
        Files.writeString(root.resolve("org/acme/Invoice.java"), "package other; class Invoice {}");
        Path classes = project.resolve("target/classes");
        writeClass(classes, "org/acme/Invoice", "Invoice.java");
        assertFalse(BytecodeSourceMapper.map(ClassFileSnapshot.capture(List.of(classes)), List.of(root))
                .containsKey("org.acme.Invoice"));
    }

    @Test
    void mapsShortenedPackageLayoutAndIgnoresFakePackageInComments(@TempDir Path project) throws Exception {
        Path classes = project.resolve("build/classes/kotlin/main");
        Path root = project.resolve("src/main/kotlin");
        Files.createDirectories(root);
        Path file = root.resolve("Billing.kt");
        Files.writeString(file, "/* package wrong */\npackage org.`acme`;\nclass Invoice");
        writeClass(classes, "org/acme/Invoice", "Billing.kt");
        assertEquals(file, BytecodeSourceMapper.map(ClassFileSnapshot.capture(List.of(classes)), List.of(root))
                .get("org.acme.Invoice"));
    }

    @Test
    void doesNotMapWrongPackageEvenAtConventionalPath(@TempDir Path project) throws Exception {
        Path classes = project.resolve("target/classes");
        Path root = project.resolve("src/main/kotlin");
        Files.createDirectories(root.resolve("org/acme"));
        Files.writeString(root.resolve("org/acme/Billing.kt"), "package other\nclass Invoice");
        writeClass(classes, "org/acme/Invoice", "Billing.kt");
        assertFalse(BytecodeSourceMapper.map(ClassFileSnapshot.capture(List.of(classes)), List.of(root))
                .containsKey("org.acme.Invoice"));
    }

    @Test
    void leavesDuplicateDeclarationsUnresolved(@TempDir Path project) throws Exception {
        Path classes = project.resolve("target/classes");
        Path root = project.resolve("src/main/kotlin");
        Files.createDirectories(root.resolve("one")); Files.createDirectories(root.resolve("two"));
        Files.writeString(root.resolve("one/Billing.kt"), "package org.acme\nclass Invoice");
        Files.writeString(root.resolve("two/Billing.kt"), "package org.acme\nclass Invoice");
        writeClass(classes, "org/acme/Invoice", "Billing.kt");
        assertFalse(BytecodeSourceMapper.map(ClassFileSnapshot.capture(List.of(classes)), List.of(root))
                .containsKey("org.acme.Invoice"));
    }

    @Test
    void scopesDuplicatesToModuleAndSourceSet(@TempDir Path project) throws Exception {
        Path main = project.resolve("first/src/main/kotlin");
        Path test = project.resolve("first/src/test/kotlin");
        Path other = project.resolve("second/src/main/kotlin");
        for (Path root : List.of(main, test, other)) {
            Files.createDirectories(root);
            Files.writeString(root.resolve("Billing.kt"), "package org.acme\nclass Invoice");
        }
        Path classes = project.resolve("first/build/classes/kotlin/main");
        writeClass(classes, "org/acme/Invoice", "Billing.kt");
        var snapshot = ClassFileSnapshot.capture(List.of(classes));
        assertEquals(main.resolve("Billing.kt"), BytecodeSourceMapper.map(snapshot, List.of(other, test, main))
                .get("org.acme.Invoice"));
        assertEquals(main.resolve("Billing.kt"), BytecodeSourceMapper.source(snapshot.entries().getFirst(),
                List.of(main, main, other, test)));
        Path testClasses = project.resolve("first/target/test-classes");
        writeClass(testClasses, "org/acme/Invoice", "Billing.kt");
        assertEquals(test.resolve("Billing.kt"), BytecodeSourceMapper.map(
                ClassFileSnapshot.capture(List.of(testClasses)), List.of(main, other, test)).get("org.acme.Invoice"));
    }

    @Test
    void mapsDefaultPackageAndHandlesMissingSourceAttribute(@TempDir Path project) throws Exception {
        Path root = project.resolve("src/main/kotlin"); Files.createDirectories(root);
        Files.writeString(root.resolve("Billing.kt"), "class Invoice");
        Path classes = project.resolve("target/classes");
        writeClass(classes, "Invoice", "Billing.kt"); writeClass(classes, "Unmapped", null);
        var mapping = BytecodeSourceMapper.map(ClassFileSnapshot.capture(List.of(classes)), List.of(root));
        assertEquals(root.resolve("Billing.kt"), mapping.get("Invoice"));
        assertFalse(mapping.containsKey("Unmapped"));
    }

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

    @Test
    void neverBorrowsSourcesFromAnotherModuleOrOppositeSourceSet(@TempDir Path project) throws Exception {
        Path other = project.resolve("other/src/main/kotlin");
        Path test = project.resolve("module/src/test/kotlin");
        for (Path root : List.of(other, test)) {
            Files.createDirectories(root);
            Files.writeString(root.resolve("Billing.kt"), "package org.acme\nclass Invoice");
        }
        Path classes = project.resolve("module/target/classes");
        writeClass(classes, "org/acme/Invoice", "Billing.kt");
        var snapshot = ClassFileSnapshot.capture(List.of(classes));
        assertFalse(BytecodeSourceMapper.map(snapshot, List.of(other)).containsKey("org.acme.Invoice"));
        assertFalse(BytecodeSourceMapper.map(snapshot, List.of(test)).containsKey("org.acme.Invoice"));
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
