package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import kotlin.Metadata;
import kotlinx.metadata.Flag;
import kotlinx.metadata.FlagsKt;
import kotlinx.metadata.KmClass;
import kotlinx.metadata.jvm.KotlinClassMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class JandexKotlinSourceMappingTest {

    @Test
    void mapsMultifileFacadeToFirstPartSourceDeterministically(@TempDir Path project) {
        Path first = project.resolve("src/main/kotlin/org/acme/First.kt");
        Path second = project.resolve("src/main/kotlin/org/acme/Second.kt");
        var facade = new KotlinMetadataReader.Result(
                KotlinMetadataReader.Status.PARSED,
                KotlinMetadataReader.Kind.MULTIFILE_FACADE, null, "2.4.0",
                false, false, false, false, List.of(), List.of(),
                "org/acme/Api__SecondKt,org/acme/Api__FirstKt");
        var firstPart = new KotlinMetadataReader.Result(
                KotlinMetadataReader.Status.PARSED,
                KotlinMetadataReader.Kind.MULTIFILE_PART, null, "2.4.0",
                false, false, false, false, List.of(), List.of(), "org/acme/Api");

        var mappings = JandexScanner.enrichKotlinSourceMappings(Map.of(
                        "org.acme.Api__FirstKt", first,
                        "org.acme.Api__SecondKt", second),
                Map.of("org.acme.Api", facade, "org.acme.Api__FirstKt", firstPart));

        assertEquals(first, mappings.get("org.acme.Api"));
    }

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
        var metadata = scan.kotlinMetadata().get("org.acme.Invoice");
        assertEquals(KotlinMetadataReader.Status.PARSED, metadata.status());
        assertEquals("org.acme.Invoice", metadata.kotlinName());
        assertTrue(metadata.data());
    }

    private static byte[] classBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "org/acme/Invoice", null,
                "java/lang/Object", null);
        writer.visitSource("Billing.kt", null);
        KmClass kmClass = new KmClass();
        kmClass.setName("org/acme/Invoice");
        kmClass.setFlags(FlagsKt.flagsOf(Flag.Class.IS_CLASS, Flag.Class.IS_DATA));
        writeMetadata(writer, KotlinClassMetadata.writeClass(kmClass));
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeMetadata(ClassWriter writer, Metadata metadata) {
        AnnotationVisitor annotation = writer.visitAnnotation("Lkotlin/Metadata;", true);
        annotation.visit("k", metadata.k());
        annotation.visit("mv", metadata.mv());
        writeStrings(annotation, "d1", metadata.d1());
        writeStrings(annotation, "d2", metadata.d2());
        annotation.visit("xs", metadata.xs());
        annotation.visit("pn", metadata.pn());
        annotation.visit("xi", metadata.xi());
        annotation.visitEnd();
    }

    private static void writeStrings(
            AnnotationVisitor annotation, String name, String[] values) {
        AnnotationVisitor array = annotation.visitArray(name);
        for (String value : values) array.visit(null, value);
        array.visitEnd();
    }
}
