package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectCodeExpectationTest {

    @TempDir Path root;

    @Test
    void pureParentPomIsMetadataOnly() throws Exception {
        writePom(root, "pom", "");

        assertEquals(ProjectCodeExpectation.State.METADATA_ONLY,
                ProjectCodeExpectation.inspect(root, BuildSystem.MAVEN));
    }

    @Test
    void reactorWithJarModuleExpectsCompiledCode() throws Exception {
        writePom(root, "pom", "<modules><module>service</module></modules>");
        writePom(Files.createDirectories(root.resolve("service")), "jar", "");

        assertEquals(ProjectCodeExpectation.State.CODE_EXPECTED,
                ProjectCodeExpectation.inspect(root, BuildSystem.MAVEN));
    }

    @Test
    void settingsOnlyGradleWorkspaceIsMetadataOnly() throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"catalog\"");

        assertEquals(ProjectCodeExpectation.State.METADATA_ONLY,
                ProjectCodeExpectation.inspect(root, BuildSystem.GRADLE));
    }

    @Test
    void gradleSourcesTakePrecedenceOverSettingsOnlyLayout() throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'app'");
        Files.createDirectories(root.resolve("service/src/main/java/org/acme"));

        assertEquals(ProjectCodeExpectation.State.CODE_EXPECTED,
                ProjectCodeExpectation.inspect(root, BuildSystem.GRADLE));
    }

    private static void writePom(Path directory, String packaging, String body) throws Exception {
        Files.writeString(directory.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>%s</artifactId><version>1</version>
                  <packaging>%s</packaging>%s
                </project>
                """.formatted(directory.getFileName(), packaging, body));
    }
}
