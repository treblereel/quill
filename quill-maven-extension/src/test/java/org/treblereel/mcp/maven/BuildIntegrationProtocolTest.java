package org.treblereel.mcp.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildIntegrationProtocolTest {
    @TempDir Path root;
    @Test void malformedPreviousManifestIsRepaired() throws Exception {
        Files.createDirectories(root.resolve(".quill")); Files.writeString(root.resolve(".quill/build-integration.properties"), "bad=\\uZZZZ");
        BuildIntegrationProtocol.write(root);
        var values = new Properties(); try (var reader = Files.newBufferedReader(root.resolve(".quill/build-integration.properties"))) { values.load(reader); }
        assertEquals("1", values.getProperty("manifestVersion"));
    }

    @Test void writesVersionedCapabilitiesAndCompilerSchemaIndependentlyFromQuill() throws Exception {
        BuildIntegrationProtocol.write(root);
        var values = new Properties(); try (var reader = Files.newBufferedReader(root.resolve(".quill/build-integration.properties"))) { values.load(reader); }
        assertEquals("0.1.3", values.getProperty("producerVersion")); assertEquals("1", values.getProperty("manifestVersion"));
        assertEquals("3", values.getProperty("buildEventProtocol")); assertEquals("4", values.getProperty("capability.count"));
        assertEquals("absent", values.getProperty("configuration.sha256"));
        var compiler = new Properties(); BuildIntegrationProtocol.compilerSettings(compiler);
        assertEquals("1", compiler.getProperty("compilerSettingsProtocol"));
        var time = Files.getLastModifiedTime(root.resolve(".quill/build-integration.properties"));
        BuildIntegrationProtocol.write(root); assertEquals(time, Files.getLastModifiedTime(root.resolve(".quill/build-integration.properties")));
    }
}
