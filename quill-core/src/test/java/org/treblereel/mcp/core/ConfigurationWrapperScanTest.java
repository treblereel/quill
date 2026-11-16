package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.ClassRecord;

/**
 * Apollo AQ-039: PortalConfig reads emergencyPublish.supported.envs through
 * RefreshableConfig#getArrayProperty, which calls getValue, which calls Environment#getProperty;
 * only direct literal calls were indexed, so the key had no reference and prefetch skipped it.
 */
class ConfigurationWrapperScanTest {
    @TempDir Path root;

    @Test
    void aKeyPassedThroughApplicationWrappersIsAConfigurationRead() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path out = Files.createDirectories(root.resolve("classes"));
        List<Path> files = new ArrayList<>();
        files.add(
                write(
                        src,
                        "org/springframework/core/env/Environment.java",
                        "package org.springframework.core.env; public interface Environment { String getProperty(String key); }"));
        files.add(
                write(
                        src,
                        "org/springframework/core/env/ConfigurableEnvironment.java",
                        "package org.springframework.core.env; public interface ConfigurableEnvironment extends Environment {}"));
        // Apollo's field is a ConfigurableEnvironment, a Spring type absent from the application
        // index.
        files.add(
                write(
                        src,
                        "example/Base.java",
                        "package example; public class Base { org.springframework.core.env.ConfigurableEnvironment environment;"
                                + " public String getValue(String key) { return environment.getProperty(key); }"
                                + " public String[] getArray(String key, String[] fallback) { try { String v = getValue(key); return v == null ? fallback : v.split(\",\"); } catch (RuntimeException e) { return fallback; } } }"));
        files.add(
                write(
                        src,
                        "example/Portal.java",
                        "package example; public class Portal extends Base { public boolean allowed() { return getArray(\"emergencyPublish.supported.envs\", new String[0]).length > 0; } }"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(
                    compiler.getTask(
                                    null,
                                    manager,
                                    null,
                                    List.of("--release", "17", "-g", "-d", out.toString()),
                                    null,
                                    manager.getJavaFileObjectsFromPaths(files))
                            .call());
        }
        ClassRecord base =
                new ClassRecord(
                        0,
                        "example.Base",
                        "CLASS",
                        "java.lang.Object",
                        List.of(),
                        "src/example/Base.java",
                        1,
                        false,
                        10,
                        null,
                        "source",
                        "current",
                        ".",
                        "main");
        ClassRecord portal =
                new ClassRecord(
                        0,
                        "example.Portal",
                        "CLASS",
                        "example.Base",
                        List.of(),
                        "src/example/Portal.java",
                        1,
                        false,
                        10,
                        null,
                        "source",
                        "current",
                        ".",
                        "main");
        var usages =
                ProgrammaticConfigurationScanner.scan(
                                ClassFileSnapshot.capture(List.of(out)),
                                Map.of("example.Base", 1, "example.Portal", 2),
                                Map.of("example.Base", base, "example.Portal", portal))
                        .configurationUsages();
        assertTrue(
                usages.stream()
                        .anyMatch(
                                usage ->
                                        usage.key().equals("emergencyPublish.supported.envs")
                                                && usage.className().equals("example.Portal")
                                                && usage.kind().equals("config_key")
                                                && usage.annotation()
                                                        .contains("example.Base#getArray")),
                usages.toString());
    }

    private static Path write(Path root, String relative, String text) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }
}
