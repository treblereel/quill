package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodeDependencyScannerTest {

    @TempDir Path tempDir;

    @Test
    void detectsConstructorOnlyDependencyWithoutDoubleCountingInit() throws URISyntaxException {
        Path testClasses = Path.of(BytecodeDependencyScannerTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        List<BytecodeDependencyScanner.StaticDependency> dependencies =
                BytecodeDependencyScanner.scan(List.of(testClasses));

        String consumer = Consumer.class.getName();
        String constructed = Constructed.class.getName();
        var matches = dependencies.stream()
                .filter(dependency -> dependency.fromClass().equals(consumer))
                .filter(dependency -> dependency.toClass().equals(constructed))
                .filter(dependency -> dependency.kind().equals("CONSTRUCTS"))
                .toList();

        assertEquals(1, matches.size());
        assertEquals(1, matches.get(0).occurrences());
    }

    @Test
    void snapshotSupportsAllIndexingPassesAfterClassFilesDisappear() throws Exception {
        copyClass(Consumer.class);
        copyClass(Constructed.class);
        ClassFileSnapshot snapshot = ClassFileSnapshot.capture(List.of(tempDir));
        String originalFingerprint = snapshot.fingerprint();

        try (var files = Files.walk(tempDir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                Files.delete(file);
            }
        }

        assertNotNull(JandexScanner.scan(snapshot, List.of()).index()
                .getClassByName(Consumer.class.getName()));
        assertEquals(1, BytecodeDependencyScanner.scan(snapshot).stream()
                .filter(dependency -> dependency.fromClass().equals(Consumer.class.getName()))
                .filter(dependency -> dependency.toClass().equals(Constructed.class.getName()))
                .filter(dependency -> dependency.kind().equals("CONSTRUCTS"))
                .count());

        copyClass(Consumer.class);
        assertNotEquals(originalFingerprint,
                ClassFileSnapshot.capture(List.of(tempDir)).fingerprint());
    }

    private void copyClass(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        Path target = tempDir.resolve(resource.substring(1));
        Files.createDirectories(target.getParent());
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input);
            Files.copy(input, target);
        }
    }

    static final class Consumer {
        Object create() {
            return new Constructed();
        }
    }

    static final class Constructed {}
}
