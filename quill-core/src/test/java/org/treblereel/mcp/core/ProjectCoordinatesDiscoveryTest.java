package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectCoordinatesDiscoveryTest {

    @TempDir Path root;

    @Test
    void resolvesMavenReactorInheritanceAndRevisionProperty() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>io.casehub</groupId><artifactId>platform</artifactId>
                  <version>${revision}</version><packaging>pom</packaging>
                  <properties><revision>3.2.1-SNAPSHOT</revision></properties>
                  <modules><module>api</module></modules>
                </project>
                """);
        Path api = Files.createDirectories(root.resolve("api"));
        Files.writeString(api.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>io.casehub</groupId><artifactId>platform</artifactId>
                    <version>${revision}</version><relativePath>../pom.xml</relativePath></parent>
                  <artifactId>platform-api</artifactId>
                </project>
                """);

        ProjectCoordinatesDiscovery.Result result =
                ProjectCoordinatesDiscovery.discover(root);

        assertTrue(result.complete(), result.diagnostics().toString());
        assertEquals(2, result.modules().size());
        assertEquals("io.casehub:platform", result.modules().get(0).ga());
        assertEquals("3.2.1-SNAPSHOT", result.modules().get(0).version());
        assertEquals("api", result.modules().get(1).module());
        assertEquals("io.casehub:platform-api", result.modules().get(1).ga());
        assertEquals("3.2.1-SNAPSHOT", result.modules().get(1).version());
    }

    @Test
    void readsOnlyStaticGradleCoordinatesWithoutExecutingGradle() throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), """
                rootProject.name = "sample"
                include(":api")
                """);
        Files.writeString(root.resolve("build.gradle.kts"), """
                group = "org.acme"
                version = "1.4.0"
                """);
        Path api = Files.createDirectories(root.resolve("api"));
        Files.writeString(api.resolve("build.gradle.kts"), "plugins { java }\n");

        ProjectCoordinatesDiscovery.Result result =
                ProjectCoordinatesDiscovery.discover(root);

        assertTrue(result.complete(), result.diagnostics().toString());
        assertEquals("org.acme:sample", result.modules().get(0).ga());
        assertEquals("org.acme:api", result.modules().get(1).ga());
        assertFalse(Files.exists(root.resolve("build")));
    }

    @Test
    void marksDynamicGradleCoordinatesIncomplete() throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'dynamic'\n");
        Files.writeString(root.resolve("build.gradle"), "group = providers.gradleProperty('group')\n");

        ProjectCoordinatesDiscovery.Result result =
                ProjectCoordinatesDiscovery.discover(root);

        assertFalse(result.complete());
        assertEquals("dynamic", result.modules().getFirst().artifact());
        assertEquals(null, result.modules().getFirst().group());
    }
}
