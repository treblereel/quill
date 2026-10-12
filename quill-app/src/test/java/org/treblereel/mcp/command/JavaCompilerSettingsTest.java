package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;

class JavaCompilerSettingsTest {
    @TempDir Path root;
    Properties values() throws Exception {
        Path pom = Files.writeString(root.resolve("pom.xml"), "aaaa");
        var values = new Properties(); values.setProperty("sourceSet", "main"); values.setProperty("evidence", "successful_mojo");
        values.setProperty("release", "17"); values.setProperty("argument.count", "1"); values.setProperty("argument.0", "-Avalue=with spaces");
        values.setProperty("buildInput.count", "1"); values.setProperty("buildInput.path.0", pom.toString());
        values.setProperty("buildInput.sha256.0", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pom))));
        return values;
    }
    Path save(Properties values) throws Exception {
        Path file = root.resolve("target/quill-java-main.properties"); Files.createDirectories(file.getParent());
        try (var writer = Files.newBufferedWriter(file)) { values.store(writer, "test"); } return file;
    }
    @Test void readsConfirmedMainAndSeparatelyReportsMissingTest() throws Exception {
        save(values()); var report = JavaCompilerSettings.inspect(List.of(root, root), BuildSystem.MAVEN);
        assertEquals(2, report.size()); assertEquals("captured", report.get(0).path("status").asText());
        assertTrue(report.get(0).path("compiled_settings_confirmed").asBoolean());
        assertEquals("-Avalue=with spaces", report.get(0).path("settings").path("argument").get(0).asText());
        assertEquals("missing", report.get(1).path("status").asText());
    }
    @Test void detectsContentChangesWithRestoredMetadataAndDeletion() throws Exception {
        Path file = save(values()); Path pom = root.resolve("pom.xml"); var time = Files.getLastModifiedTime(pom);
        Files.writeString(pom, "bbbb"); Files.setLastModifiedTime(pom, time);
        assertEquals("stale", JavaCompilerSettings.read(root, "main", file).path("status").asText());
        Files.delete(pom); assertEquals("stale", JavaCompilerSettings.read(root, "main", file).path("status").asText());
    }
    @Test void distinguishesConfiguredTaskAndCapturesCustomSourceSet() throws Exception {
        var values = values(); values.setProperty("evidence", "configured_task"); values.setProperty("sourceSet", "integrationTest");
        Path file = root.resolve("build/quill-java-integrationTest.properties"); Files.createDirectories(file.getParent());
        try (var writer = Files.newBufferedWriter(file)) { values.store(writer, "test"); }
        var report = JavaCompilerSettings.inspect(List.of(root), BuildSystem.GRADLE);
        var custom = Stream.of(report.get(0), report.get(1), report.get(2)).filter(node -> node.path("source_set").asText().equals("integrationTest")).findFirst().orElseThrow();
        assertEquals("configuration_only", custom.path("status").asText()); assertFalse(custom.path("compiled_settings_confirmed").asBoolean());
    }
    @TestFactory Stream<DynamicTest> malformedMetadataCannotAppearUsable() {
        return Stream.of("negative", "excessive", "incomplete", "bad_sha", "identity", "no_inputs", "unknown_evidence", "bad_count", "unicode")
                .map(label -> DynamicTest.dynamicTest(label, () -> {
                    var values = values();
                    switch (label) {
                        case "negative" -> values.setProperty("argument.count", "-1");
                        case "excessive" -> values.setProperty("argument.count", "9999999");
                        case "incomplete" -> values.remove("argument.0");
                        case "bad_sha" -> values.setProperty("buildInput.sha256.0", "xyz");
                        case "identity" -> values.setProperty("sourceSet", "../test");
                        case "no_inputs" -> values.setProperty("buildInput.count", "0");
                        case "unknown_evidence" -> values.setProperty("evidence", "anything");
                        case "bad_count" -> values.setProperty("buildInput.count", "x");
                    }
                    Path file = save(values); if (label.equals("unicode")) Files.writeString(file, "broken=\\uZZZZ");
                    var result = JavaCompilerSettings.read(root, "main", file);
                    assertEquals("invalid", result.path("status").asText()); assertFalse(result.path("usable").asBoolean());
                }));
    }
}
