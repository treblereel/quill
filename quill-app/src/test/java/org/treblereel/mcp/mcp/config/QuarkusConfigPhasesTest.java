package org.treblereel.mcp.mcp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * V41 CQ-039: asked whether a key needs a rebuild, Claude grepped pom.xml for the Quarkus version
 * and guessed the phase.
 */
class QuarkusConfigPhasesTest {
    @TempDir Path temp;

    @Test
    void aQuarkusKeyStatesItsPhaseDefaultAndExtensionFromTheExtensionsConfigModel()
            throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path jar =
                temp.resolve("repository/io/quarkus/quarkus-cache/3.40.1/quarkus-cache-3.40.1.jar");
        Files.createDirectories(jar.getParent());
        String model =
                """
                {"items":[{"path":{"property":"quarkus.cache.enabled"},"phase":"RUN_TIME","defaultValue":"true","type":"boolean"},
                 {"path":{"property":"quarkus.cache.type"},"phase":"BUILD_AND_RUN_TIME_FIXED","defaultValue":"caffeine"},
                 {"path":{"property":"quarkus.cache.caffeine.\\"cache-name\\".maximum-size"},"phase":"RUN_TIME"}]}
                """;
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(
                    new JarEntry("META-INF/quarkus-config-doc/quarkus-config-model.json"));
            output.write(model.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Files.createDirectories(temp.resolve("target"));
        Files.writeString(temp.resolve("target/quill-classpath.txt"), jar.toString());
        Jdbi jdbi = QuillDatabase.create(temp.resolve("index.db"));

        List<String> phases =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        reader ->
                                QuarkusConfigPhases.lookup(
                                        reader,
                                        temp,
                                        Set.of(
                                                "quarkus.cache.enabled",
                                                "quarkus.cache.type",
                                                "quarkus.cache.caffeine.reports.maximum-size",
                                                "quarkus.http.port")));
        assertEquals(
                List.of(
                        "quarkus.cache.caffeine.reports.maximum-size: RUN_TIME (io.quarkus:quarkus-cache:3.40.1)",
                        "quarkus.cache.enabled: RUN_TIME default=true (io.quarkus:quarkus-cache:3.40.1)",
                        "quarkus.cache.type: BUILD_AND_RUN_TIME_FIXED default=caffeine (io.quarkus:quarkus-cache:3.40.1)"),
                phases);

        // The declared configuration view, which the prefetch also uses, carries them.
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                "INSERT INTO metadata(key, value) VALUES ('project_root', ?)",
                                temp.toString()));
        String answer =
                ReadQueryContext.readWithGraph(
                                jdbi,
                                reader ->
                                        new ConfigurationReferenceQueries()
                                                .find(
                                                        reader,
                                                        "quarkus.cache.enabled",
                                                        null,
                                                        "all",
                                                        null,
                                                        12,
                                                        0,
                                                        "declared"))
                        .toString();
        assertEquals(
                "[\"quarkus.cache.enabled: RUN_TIME default=true (io.quarkus:quarkus-cache:3.40.1)\"]",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(answer)
                        .path("framework_key_phases")
                        .toString(),
                answer);
    }
}
