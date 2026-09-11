package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BytecodeDependencyScannerTest {

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

    static final class Consumer {
        Object create() {
            return new Constructed();
        }
    }

    static final class Constructed {}
}
