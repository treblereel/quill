package org.treblereel.mcp.maven;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Capability declaration is separate from the unchanged v3 build-event wire format. */
final class BuildIntegrationProtocol {
    static String version() {
        var values = new Properties();
        try (var input = BuildIntegrationProtocol.class.getResourceAsStream("/quill-extension.properties")) {
            if (input != null) values.load(input);
        } catch (IOException ignored) { }
        return values.getProperty("version", "unknown");
    }
    static void compilerSettings(Properties values) {
        values.setProperty("compilerSettingsProtocol", "1");
        values.setProperty("producerVersion", version());
    }
    static void write(Path root) throws IOException {
        var values = new Properties();
        values.setProperty("manifestVersion", "1");
        values.setProperty("buildTool", "maven");
        values.setProperty("producer", "quill-maven-extension");
        values.setProperty("producerVersion", version());
        values.setProperty("buildEventProtocol", "3");
        values.setProperty("compilerSettingsProtocol", "1");
        JavaCompilerCapture.put(values, "capability", List.of("build_events", "runtime_classpath", "kotlin_compiler_settings", "java_compiler_settings"));
        Path configuration = root.resolve(".mvn/extensions.xml").toAbsolutePath().normalize();
        values.setProperty("configuration.path", configuration.toString());
        values.setProperty("configuration.sha256", JavaCompilerCapture.hash(configuration));
        KotlinCompilerCapture.write(root.resolve(".quill/build-integration.properties"), values);
    }
}
