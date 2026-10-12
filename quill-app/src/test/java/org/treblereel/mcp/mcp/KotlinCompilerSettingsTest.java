package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KotlinCompilerSettingsTest {
    @TempDir Path root;
    void write(Path path, String text) throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path, text); }
    @Test void capturesSourceSetSpecificOptionsAndExplicitOverrideWithoutBuildExecution() throws Exception {
        write(root.resolve("build/quill-kotlin-main.properties"), "languageVersion=2.3\napiVersion=2.2\njvmTarget=17\nargument.count=1\nargument.0=-Xcontext-parameters\npluginClasspath.count=1\npluginClasspath.0=plugins/with spaces.jar\n");
        write(root.resolve(".quill/kotlin-main.properties"), "allowPlugins=true\ncompilerVersion=2.4.21\nlanguageVersion=2.4\n");
        var request = new ObjectMapper().createObjectNode(); KotlinCompilerSettings.apply(root, root.resolve("build"), "main", request);
        assertEquals("2.4", request.path("languageVersion").asText()); assertEquals("2.2", request.path("apiVersion").asText());
        assertEquals("17", request.path("jvmTarget").asText()); assertTrue(request.path("allow_compiler_plugins").asBoolean());
        assertEquals(root.resolve("plugins/with spaces.jar").toString(), request.path("plugin_classpath").get(0).asText());
        assertEquals("-Xcontext-parameters", request.path("compiler_arguments").get(0).asText());
        var test = new ObjectMapper().createObjectNode(); KotlinCompilerSettings.apply(root, root.resolve("build"), "test", test);
        assertFalse(test.has("languageVersion")); assertTrue(test.path("plugin_classpath").isEmpty());
    }
    @Test void buildEditsWithRestoredMtimeInvalidateCapturedCompilerSettings() throws Exception {
        Path pom = root.resolve("pom.xml"); write(pom, "before"); var time = Files.getLastModifiedTime(pom);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pom)));
        var values = new Properties(); values.setProperty("languageVersion", "2.3"); values.setProperty("buildInput.count", "1");
        values.setProperty("buildInput.path.0", pom.toString()); values.setProperty("buildInput.sha256.0", hash);
        Files.createDirectories(root.resolve("target")); try(var writer = Files.newBufferedWriter(root.resolve("target/quill-kotlin-main.properties"))) { values.store(writer, null); }
        var initial = new ObjectMapper().createObjectNode(); KotlinCompilerSettings.apply(root, root.resolve("target"), "main", initial);
        assertFalse(initial.has("compiler_configuration_error"));
        assertTrue(java.util.stream.StreamSupport.stream(initial.path("compiler_settings_fingerprints").spliterator(), false)
                .anyMatch(value -> value.path("path").asText().equals(pom.toString()) && value.path("sha256").asText().equals(hash)),
                "Worker must verify captured build hashes again to detect an edit after parent validation");
        Files.writeString(pom, "edited"); Files.setLastModifiedTime(pom, time);
        var changed = new ObjectMapper().createObjectNode(); KotlinCompilerSettings.apply(root, root.resolve("target"), "main", changed);
        assertEquals("compiler_settings_stale", changed.path("compiler_configuration_error").asText());
    }
    @Test void malformedCountsAndMissingValuesCannotBeSilentlyIgnored() throws Exception {
        for (String text : new String[]{"argument.count=-1", "argument.count=1025", "argument.count=1", "argument.count=bad"}) {
            write(root.resolve("target/quill-kotlin-main.properties"), text);
            assertThrows(Exception.class, () -> KotlinCompilerSettings.apply(root, root.resolve("target"), "main", new ObjectMapper().createObjectNode()));
        }
    }
    @Test void implicitLanguageLevelFollowsProjectCompilerAndExplicitLevelTakesPrecedence() throws Exception {
        write(root.resolve("target/quill-kotlin-main.properties"), "compilerVersion=2.3.21\n");
        var request = new ObjectMapper().createObjectNode(); KotlinCompilerSettings.apply(root, root.resolve("target"), "main", request);
        assertEquals("2.3", request.path("languageVersion").asText());
        write(root.resolve(".quill/kotlin-main.properties"), "languageVersion=2.2\n");
        var overridden = new ObjectMapper().createObjectNode(); KotlinCompilerSettings.apply(root, root.resolve("target"), "main", overridden);
        assertEquals("2.2", overridden.path("languageVersion").asText());
    }
}
