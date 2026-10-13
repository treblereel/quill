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
        Path testClasses = module.resolve("build/classes/java/test");
        Path manifest = Files.writeString(tempDir.resolve("projects.tsv"),
                encode(module) + "\t:custom\tmain\t" + encode(javaClasses) + "\t"
                        + encode(kotlinClasses)
                        + System.lineSeparator()
                        + encode(module) + "\t:custom\ttest\t" + encode(testClasses));

        GradleProjectDiscovery.Discovery discovery =
                GradleProjectDiscovery.readManifest(manifest);

        assertTrue(discovery.complete());
        assertEquals(Set.of(module.toAbsolutePath()), Set.copyOf(discovery.moduleDirectories()));
        assertEquals(Set.of(javaClasses.toAbsolutePath(), kotlinClasses.toAbsolutePath(),
                        testClasses.toAbsolutePath()),
                Set.copyOf(discovery.classesDirectories()));
        assertEquals(module.toAbsolutePath(),
                discovery.classDirectoryOwners().get(javaClasses.toAbsolutePath()));
        assertEquals("main",
                discovery.classDirectorySourceSets().get(javaClasses.toAbsolutePath()));
        assertEquals("test",
                discovery.classDirectorySourceSets().get(testClasses.toAbsolutePath()));
        assertEquals(":custom", discovery.projectPaths().get(module.toAbsolutePath()));
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

    @Test
    void retainsCustomSetsAndEncodedSourceRoots() throws Exception {
        Path module = tempDir.resolve("module ü with spaces");
        Path output = tempDir.resolve("out/integration");
        Path roots = module.resolve("custom sources ü");
        Path manifest = Files.writeString(tempDir.resolve("custom.tsv"),
                encode(module) + "\t:service\tintegrationTest\t" + encode(output) + "\n"
                        + "@sources\t" + encode(roots) + "\t" + encode(roots) + "\n"
                        + encode(module) + "\t:service\ttestFixtures\t" + encode(module.resolve("fixtures")));
        var discovery = GradleProjectDiscovery.readManifest(manifest);
        assertTrue(discovery.complete());
        assertEquals("integrationTest", discovery.classDirectorySourceSets().get(output));
        assertEquals(module, discovery.classDirectoryOwners().get(output));
        assertEquals(java.util.List.of(roots), discovery.sourceRoots());
    }

    @Test
    void rejectsUnsafeOrEmptySourceSetNames() throws Exception {
        for (String name : java.util.List.of("", "../escape", "/absolute", ".", "..", "a/b")) {
            Path manifest = Files.writeString(tempDir.resolve("invalid.tsv"),
                    encode(tempDir) + "\t:module\t" + name + "\t" + encode(tempDir.resolve("output")));
            var discovery = GradleProjectDiscovery.readManifest(manifest);
            assertFalse(discovery.complete(), name);
            assertTrue(discovery.classesDirectories().isEmpty(), name);
        }
    }

    @Test
    void reportsSharedOutputOwnershipAsIncomplete() throws Exception {
        Path output = tempDir.resolve("shared");
        Path manifest = Files.writeString(tempDir.resolve("shared.tsv"),
                encode(tempDir) + "\t:module\tmain\t" + encode(output) + "\n"
                        + encode(tempDir) + "\t:module\tintegrationTest\t" + encode(output));
        assertFalse(GradleProjectDiscovery.readManifest(manifest).complete());
    }

    @Test
    void rejectsMalformedSourceRootRecord() throws Exception {
        Path manifest = Files.writeString(tempDir.resolve("roots.tsv"), "@sources\tnot-base64!\n");
        assertFalse(GradleProjectDiscovery.readManifest(manifest).complete());
    }

    private static String encode(Path path) {
        return Base64.getEncoder().encodeToString(
                path.toString().getBytes(StandardCharsets.UTF_8));
    }
}
