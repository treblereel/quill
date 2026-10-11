package org.treblereel.mcp.core;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
class KotlinSourceDebugTest {
    private static final String SMAP = "SMAP\nCaller.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Caller.kt\nacme/CallerKt\n+ 2 Inline.kt\nacme/InlineKt\n*L\n1#1,20:1\n10#2,2:100,3\n12:106\n*S KotlinDebug\n*F\n+ 1 Caller.kt\nacme/CallerKt\n*L\n5#1:100,7\n*E\n";
    @Test void rangeBoundariesIncrementAndInheritedFileIds() {
        var mappings = KotlinSourceDebug.parseSmap(SMAP.replace("\n", "\r\n"));
        var inline = mappings.get(1);
        assertEquals(10, inline.sourceLine(100).orElseThrow()); assertEquals(10, inline.sourceLine(102).orElseThrow());
        assertEquals(11, inline.sourceLine(103).orElseThrow()); assertEquals(11, inline.sourceLine(105).orElseThrow());
        assertTrue(inline.sourceLine(99).isEmpty()); assertTrue(inline.sourceLine(106).isEmpty());
        assertEquals("Inline.kt", mappings.get(2).file());
        assertEquals("KotlinDebug", mappings.get(3).stratum());
        assertEquals(5, mappings.get(3).sourceLine(106).orElseThrow());
    }
    @Test void invalidOrIncompleteMapsNeverProducePartialLocations() {
        for (String text : List.of(SMAP.replace("SMAP", "BAD"), SMAP.replace("*E", ""),
                SMAP.replace("10#2,2:100,3", "10#9:100"), SMAP.replace("10#2,2:100,3", "10#2,0:100"),
                SMAP.replace("10#2,2:100,3", "10#2,2:2147483647,3"), SMAP.replace("+ 2 Inline.kt", "+ 1 Inline.kt")))
            assertThrows(IllegalArgumentException.class, () -> KotlinSourceDebug.parseSmap(text));
    }
    private byte[] bytecode(String smap, int version) {
        var writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "acme/CallerKt$run$1", null, "java/lang/Object", null);
        writer.visitSource("Caller.kt", smap);
        if (version != 0) {
            var annotation = writer.visitAnnotation("Lkotlin/coroutines/jvm/internal/DebugMetadata;", true);
            annotation.visit("c", "acme.CallerKt"); annotation.visit("m", "run"); annotation.visit("f", "Caller.kt");
            annotation.visit("v", version); annotation.visit("l", new int[]{4, 7}); annotation.visit("nl", new int[]{5, -1}); annotation.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    @Test void readsBothCoroutineVersionsAndPreservesUnknownVersion() {
        for (int version : List.of(1, 2, 9)) {
            var info = KotlinSourceDebug.read(bytecode(SMAP, version)).orElseThrow();
            assertEquals("acme.CallerKt$run$1", info.className());
            assertEquals(List.of(4, 7), info.coroutine().suspensionLines());
            assertEquals(List.of(5, -1), info.coroutine().nextLines());
            assertEquals(version < 3, info.coroutine().supported());
        }
    }
    @Test void malformedSmapDoesNotDiscardIndependentCoroutineEvidence() {
        var info = KotlinSourceDebug.read(bytecode("bad", 2)).orElseThrow();
        assertTrue(info.malformedSmap()); assertTrue(info.mappings().isEmpty()); assertNotNull(info.coroutine());
        assertTrue(KotlinSourceDebug.read(bytecode(null, 0)).isEmpty());
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    @Test void duplicateOwnersNeverMixDebugEvidenceAcrossModules() throws Exception {
        var main = java.nio.file.Files.createDirectories(temp.resolve("main"));
        var other = java.nio.file.Files.createDirectories(temp.resolve("other"));
        java.nio.file.Files.write(main.resolve("Caller.class"), bytecode(SMAP, 2));
        assertEquals(1, KotlinSourceDebug.scan(ClassFileSnapshot.capture(List.of(main))).size());
        java.nio.file.Files.write(other.resolve("Caller.class"), bytecode(null, 0));
        assertTrue(KotlinSourceDebug.scan(ClassFileSnapshot.capture(List.of(main, other))).isEmpty());
    }

}
