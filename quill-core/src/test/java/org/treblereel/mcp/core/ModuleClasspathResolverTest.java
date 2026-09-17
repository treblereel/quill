package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.ModuleClasspathRecord;

class ModuleClasspathResolverTest {

    @TempDir Path tempDir;

    @Test
    void resolvesTransitiveMavenReactorVisibility() throws Exception {
        Path common = mavenModule("common", "");
        Path service = mavenModule("service", dependency("common", null));
        Path application = mavenModule("application", dependency("service", null)
                + dependency("common", "test"));

        Map<String, Integer> visible = ModuleClasspathResolver.resolve(tempDir, BuildSystem.MAVEN,
                        List.of(application, common, service)).stream()
                .filter(entry -> entry.applicationModule().equals("application"))
                .collect(Collectors.toMap(ModuleClasspathRecord::visibleModule,
                        ModuleClasspathRecord::distance));

        assertEquals(Map.of("application", 0, "service", 1, "common", 2), visible);
    }

    @Test
    void resolvesGradleProjectDependencies() throws Exception {
        Path common = gradleModule("common", "");
        Path service = gradleModule("service", "dependencies { implementation(project(\":common\")) }");
        Path application = gradleModule("application",
                "dependencies { implementation project(':service') }");

        Map<String, Integer> visible = ModuleClasspathResolver.resolve(tempDir, BuildSystem.GRADLE,
                        List.of(application, common, service)).stream()
                .filter(entry -> entry.applicationModule().equals("application"))
                .collect(Collectors.toMap(ModuleClasspathRecord::visibleModule,
                        ModuleClasspathRecord::distance));

        assertEquals(Map.of("application", 0, "service", 1, "common", 2), visible);
    }

    private Path mavenModule(String artifact, String dependencies) throws Exception {
        Path module = Files.createDirectories(tempDir.resolve(artifact));
        Files.writeString(module.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>test</groupId>
                <artifactId>%s</artifactId><version>1</version><dependencies>%s</dependencies></project>
                """.formatted(artifact, dependencies));
        return module;
    }

    private static String dependency(String artifact, String scope) {
        return "<dependency><groupId>test</groupId><artifactId>" + artifact
                + "</artifactId><version>1</version>"
                + (scope == null ? "" : "<scope>" + scope + "</scope>") + "</dependency>";
    }

    private Path gradleModule(String name, String build) throws Exception {
        Path module = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(module.resolve("build.gradle.kts"), build);
        return module;
    }
}
