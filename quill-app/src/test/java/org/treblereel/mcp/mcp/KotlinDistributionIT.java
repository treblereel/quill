package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the packaged classpath, which differs from Surefire's provided dependencies. */
class KotlinDistributionIT {
    @TempDir Path root;
    @Test void sharedSelectionHelperExecutesFromTheShadedDistribution() throws Exception {
        Path jar;
        try (var jars = Files.list(Path.of("target"))) {
            jar = jars.filter(p -> p.getFileName().toString().matches("quill-app-.*-all\\.jar"))
                    .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified())).orElseThrow();
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> mapper = loader.loadClass("com.fasterxml.jackson.databind.ObjectMapper");
            Object json = mapper.getConstructor().newInstance();
            Path kotlin = root.resolve("Picked.kt"); Files.writeString(kotlin, "fun pick() = 1");
            // Use the distribution's own Jackson classes, without a parent test classpath.
            String path = kotlin.toRealPath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
            Object selection = mapper.getMethod("readTree", String.class).invoke(json,
                    "{\"protocol\":1,\"entries\":[{\"kind\":\"kotlin\",\"file\":\"" + path + "\"}]}");
            Class<?> helper = loader.loadClass("org.treblereel.mcp.kotlin.GradleSourceSelection");
            Object result = helper.getMethod("collect", Path.class, loader.loadClass("com.fasterxml.jackson.databind.JsonNode"))
                    .invoke(null, root, selection);
            assertEquals(List.of(kotlin.toRealPath()), result.getClass().getMethod("kotlin").invoke(result));
            assertEquals(List.of(), result.getClass().getMethod("java").invoke(result));
            Object fileTree = mapper.getMethod("readTree", String.class).invoke(json,
                    "{\"protocol\":1,\"entries\":[{\"kind\":\"kotlin\",\"root\":\"" + path
                            + "\",\"gates\":[{\"includes\":[\"**/*.kt\"],\"excludes\":[]}],\"defaultExcludes\":[]}]}");
            Object treeResult = helper.getMethod("collect", Path.class, loader.loadClass("com.fasterxml.jackson.databind.JsonNode"))
                    .invoke(null, root, fileTree);
            assertEquals(List.of(kotlin.toRealPath()), treeResult.getClass().getMethod("kotlin").invoke(treeResult));
            assertNotNull(loader.loadClass("org.treblereel.mcp.kotlin.GradleSourceSelection$Invalid"));
        }
        try (var archive = new JarFile(jar.toFile())) {
            assertNotNull(archive.getJarEntry("kotlin-worker/worker.jar"));
            assertNull(archive.getJarEntry("org/jetbrains/kotlin/cli/jvm/compiler/KotlinCoreEnvironment.class"),
                    "Embedding the shared helper must not embed the compiler in Quill");
        }
    }
}
