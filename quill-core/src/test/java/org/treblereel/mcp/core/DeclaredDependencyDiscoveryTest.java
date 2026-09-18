package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclaredDependencyDiscoveryTest {

    @TempDir Path temp;

    @Test
    void readsMavenDeclarationsIncludingReactorParentDependencies() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>root</artifactId><version>1</version>
                  <properties><logging.group>org.slf4j</logging.group></properties>
                  <dependencies><dependency><groupId>${logging.group}</groupId>
                    <artifactId>slf4j-api</artifactId><version>2</version>
                  </dependency></dependencies>
                </project>
                """);
        Files.createDirectories(temp.resolve("app"));
        Files.writeString(temp.resolve("app/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>test</groupId><artifactId>root</artifactId><version>1</version>
                    <relativePath>../pom.xml</relativePath></parent>
                  <artifactId>app</artifactId>
                  <dependencies><dependency><groupId>com.acme</groupId>
                    <artifactId>widget</artifactId><version>1</version>
                  </dependency></dependencies>
                </project>
                """);

        var result = DeclaredDependencyDiscovery.discover(
                temp, BuildSystem.MAVEN, Set.of("app"));

        assertTrue(result.complete());
        assertEquals(Set.of("com.acme:widget", "org.slf4j:slf4j-api"),
                result.dependenciesByModule().get("app"));
    }

    @Test
    void distinguishesMissingGradleSnapshotFromKnownEmptySnapshot() throws Exception {
        Files.writeString(temp.resolve("settings.gradle"), "rootProject.name='sample'");
        Files.createDirectories(temp.resolve("api/build"));
        Files.writeString(temp.resolve("api/build/quill-direct-dependencies.tsv"),
                "org.demo\tlib\truntimeClasspath\n");

        var result = DeclaredDependencyDiscovery.discover(
                temp, BuildSystem.GRADLE, Set.of("api", "worker"));

        assertFalse(result.complete());
        assertEquals(Set.of("api"), result.completeModules());
        assertEquals(Set.of("org.demo:lib"), result.dependenciesByModule().get("api"));
        assertTrue(result.dependenciesByModule().get("worker").isEmpty());
    }
}
