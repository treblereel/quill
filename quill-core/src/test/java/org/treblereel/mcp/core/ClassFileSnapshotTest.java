package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileSnapshotTest {
    @TempDir Path root;

    @Test void fingerprintIsIndependentOfOutputEnumerationOrderAndIncludesEmptyOutputs() throws Exception {
        Path first = Files.createDirectories(root.resolve("first"));
        Path second = Files.createDirectories(root.resolve("second"));
        Path empty = Files.createDirectories(root.resolve("empty"));
        Files.write(first.resolve("Z.class"), new byte[] {1, 2});
        Files.write(first.resolve("A.class"), new byte[] {3, 4});
        Files.write(second.resolve("A.class"), new byte[] {5, 6});
        var snapshot = ClassFileSnapshot.capture(List.of(first, second, empty));
        assertEquals(snapshot.fingerprint(), ClassFileSnapshot.capture(List.of(empty, second, first)).fingerprint());
        assertNotEquals(snapshot.fingerprint(), ClassFileSnapshot.capture(List.of(first, second)).fingerprint());
        assertEquals(3, snapshot.entries().size());
    }

    @Test void sameSizeAndTimestampRewriteAndDeletionStillChangeContentEvidence() throws Exception {
        Path output = Files.createDirectories(root.resolve("classes"));
        Path file = Files.write(output.resolve("A.class"), new byte[] {1, 2});
        var timestamp = Files.getLastModifiedTime(file);
        var original = ClassFileSnapshot.capture(List.of(output));
        Files.write(file, new byte[] {2, 1});
        Files.setLastModifiedTime(file, timestamp);
        var rewritten = ClassFileSnapshot.capture(List.of(output));
        assertNotEquals(original.fingerprint(), rewritten.fingerprint());
        assertArrayEquals(new byte[] {1, 2}, original.entries().getFirst().bytecode());
        Files.delete(file);
        assertNotEquals(rewritten.fingerprint(), ClassFileSnapshot.capture(List.of(output)).fingerprint());
    }

    @Test void streamingFingerprintMatchesTheIndexedSnapshotAndSeesRewritesAndDeletions() throws Exception {
        Path first = Files.createDirectories(root.resolve("first/p"));
        Path second = Files.createDirectories(root.resolve("second"));
        Files.write(first.resolve("A.class"), new byte[] {1, 2});
        Files.write(second.resolve("B.class"), new byte[] {3});
        List<Path> outputs = List.of(root.resolve("first"), second);
        String indexed = ClassFileSnapshot.capture(outputs).fingerprint();
        assertEquals(indexed, ClassFileSnapshot.fingerprint(outputs));
        assertEquals(indexed, ClassFileSnapshot.fingerprint(List.of(second, root.resolve("first"))));

        Path file = first.resolve("A.class");
        var timestamp = Files.getLastModifiedTime(file);
        Files.write(file, new byte[] {2, 1});
        Files.setLastModifiedTime(file, timestamp);
        String rewritten = ClassFileSnapshot.fingerprint(outputs);
        assertNotEquals(indexed, rewritten);
        assertEquals(ClassFileSnapshot.capture(outputs).fingerprint(), rewritten);

        Files.delete(file);
        Files.write(file, new byte[] {1, 2});
        assertEquals(indexed, ClassFileSnapshot.fingerprint(outputs), "same content written again");
        Files.delete(second.resolve("B.class"));
        assertNotEquals(indexed, ClassFileSnapshot.fingerprint(outputs));
    }
}
