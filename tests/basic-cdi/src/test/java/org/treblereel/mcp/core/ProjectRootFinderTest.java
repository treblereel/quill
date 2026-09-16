package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectRootFinderTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitPathReturned() throws IOException {
        Path project = tempDir.resolve("my-project");
        Files.createDirectories(project);
        Files.createFile(project.resolve("pom.xml"));
        Files.createDirectories(project.resolve("src/main/java"));

        Path result = ProjectRootFinder.find(project);
        assertEquals(project, result);
    }

    @Test
    void walksUpToFindPomXml() throws IOException {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);
        Files.createFile(root.resolve("pom.xml"));
        Files.createDirectories(root.resolve("src/main/java"));
        Path subdir = root.resolve("src/main/java/com/example");
        Files.createDirectories(subdir);

        Path result = ProjectRootFinder.find(subdir);
        assertEquals(root, result);
    }

    @Test
    void throwsWhenNoPomFound() {
        Path emptyDir = tempDir.resolve("empty");
        assertThrows(IllegalArgumentException.class, () -> ProjectRootFinder.find(emptyDir));
    }

    @Test
    void nullPathUsesCwd() {
        Path result = ProjectRootFinder.find(null);
        assertNotNull(result);
    }

    @Test
    void aggregatorRootWithoutSrcMainJava() throws IOException {
        Path aggregator = tempDir.resolve("multi-module");
        Files.createDirectories(aggregator);
        Files.createFile(aggregator.resolve("pom.xml"));

        Path result = ProjectRootFinder.find(aggregator);
        assertEquals(aggregator, result);
    }

    @Test
    void findsStandaloneGradleGroovyProject() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("gradle-groovy"));
        Files.createFile(root.resolve("build.gradle"));
        Path nested = Files.createDirectories(root.resolve("src/main/java/example"));

        assertEquals(root, ProjectRootFinder.find(nested));
        assertEquals(BuildSystem.GRADLE, BuildSystem.detect(root));
    }

    @Test
    void findsStandaloneGradleKotlinProject() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("gradle-kotlin"));
        Files.createFile(root.resolve("build.gradle.kts"));

        assertEquals(root, ProjectRootFinder.find(root));
        assertEquals(BuildSystem.GRADLE, BuildSystem.detect(root));
    }

    @Test
    void gradleSubprojectResolvesToSettingsRoot() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("gradle-multi"));
        Files.createFile(root.resolve("settings.gradle"));
        Path module = Files.createDirectories(root.resolve("service/src/main/java/example"));
        Files.createFile(root.resolve("service/build.gradle"));

        assertEquals(root, ProjectRootFinder.find(module));
    }

    @Test
    void buildSystemsPreferExecutableWrappers() throws IOException {
        Path maven = Files.createDirectories(tempDir.resolve("maven-wrapper"));
        Files.createFile(maven.resolve("pom.xml"));
        Path mvnw = Files.createFile(maven.resolve("mvnw"));
        mvnw.toFile().setExecutable(true);

        Path gradle = Files.createDirectories(tempDir.resolve("gradle-wrapper"));
        Files.createFile(gradle.resolve("settings.gradle"));
        Path gradlew = Files.createFile(gradle.resolve("gradlew"));
        gradlew.toFile().setExecutable(true);

        assertEquals(List.of(mvnw.toString(), "help:effective-pom"),
                BuildSystem.MAVEN.command(maven, false, "help:effective-pom"));
        assertEquals(List.of(gradlew.toString(), "projects", "--quiet"),
                BuildSystem.GRADLE.command(gradle, false, "projects", "--quiet"));
    }

    @Test
    void windowsBuildCommandsUseBatchWrappersWithoutExecutableBit() throws IOException {
        Path maven = Files.createDirectories(tempDir.resolve("windows-maven"));
        Path mvnw = Files.createFile(maven.resolve("mvnw.cmd"));
        Path gradle = Files.createDirectories(tempDir.resolve("windows-gradle"));
        Path gradlew = Files.createFile(gradle.resolve("gradlew.bat"));

        assertEquals(List.of("cmd.exe", "/d", "/c", mvnw.toAbsolutePath().toString(),
                        "help:effective-pom"),
                BuildSystem.MAVEN.command(maven, true, "help:effective-pom"));
        assertEquals(List.of("cmd.exe", "/d", "/c", gradlew.toAbsolutePath().toString(),
                        "projects", "--quiet"),
                BuildSystem.GRADLE.command(gradle, true, "projects", "--quiet"));
    }

    @Test
    void windowsBuildCommandsFallBackToInstalledTools() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("windows-no-wrapper"));

        assertEquals(List.of("cmd.exe", "/d", "/c", "mvn", "help:effective-pom"),
                BuildSystem.MAVEN.command(project, true, "help:effective-pom"));
        assertEquals(List.of("cmd.exe", "/d", "/c", "gradle", "projects", "--quiet"),
                BuildSystem.GRADLE.command(project, true, "projects", "--quiet"));
    }
}
