package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleProjectDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void readsEncodedModuleAndClassDirectories() throws Exception {
        Path module = tempDir.resolve("module with spaces");
        Path javaClasses = module.resolve("build/classes/java/main");
        Path kotlinClasses = module.resolve("build/classes/kotlin/main");
        Path manifest = Files.writeString(tempDir.resolve("projects.tsv"),
                encode(module) + "\t" + encode(javaClasses) + "\t" + encode(kotlinClasses)
                        + System.lineSeparator()
                        + encode(module) + "\t" + encode(javaClasses));

        GradleProjectDiscovery.Discovery discovery =
                GradleProjectDiscovery.readManifest(manifest);

        assertTrue(discovery.complete());
        assertEquals(Set.of(module.toAbsolutePath()), Set.copyOf(discovery.moduleDirectories()));
        assertEquals(Set.of(javaClasses.toAbsolutePath(), kotlinClasses.toAbsolutePath()),
                Set.copyOf(discovery.classesDirectories()));
        assertEquals(module.toAbsolutePath(),
                discovery.classDirectoryOwners().get(javaClasses.toAbsolutePath()));
    }

    @Test
    void rejectsMalformedManifestWithoutThrowing() throws Exception {
        Path manifest = Files.writeString(tempDir.resolve("projects.tsv"), "not-base64!\n");

        GradleProjectDiscovery.Discovery discovery =
                GradleProjectDiscovery.readManifest(manifest);

        assertFalse(discovery.complete());
        assertTrue(discovery.moduleDirectories().isEmpty());
        assertTrue(discovery.classesDirectories().isEmpty());
        assertTrue(discovery.classDirectoryOwners().isEmpty());
    }

    private static String encode(Path path) {
        return Base64.getEncoder().encodeToString(
                path.toString().getBytes(StandardCharsets.UTF_8));
    }
}
