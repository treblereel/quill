package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
