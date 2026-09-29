package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectLayoutTest {

    @Test
    void discoversMavenMainAndTestOutputsAndTestSources(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path main = classOutput(project.resolve("target/classes"), "Main.class");
        Path test = classOutput(project.resolve("target/test-classes"), "MainTest.class");
        Path testSources = Files.createDirectories(project.resolve("src/test/java"));

        ProjectLayout.ClassesDiscovery discovery =
                ProjectLayout.discoverClassesDirs(project, false);

        assertEquals(List.of(main, test), discovery.classesDirectories());
        assertEquals(List.of("main", "test"), discovery.outputs().stream()
                .map(ProjectLayout.CompiledOutput::sourceSet).toList());
        assertEquals(List.of(main), ProjectInitializer.findMainClassesDirs(project));
        assertEquals(List.of(testSources.toAbsolutePath().normalize()),
                ProjectLayout.findSourceRoots(discovery.moduleDirectories()));
    }

    @Test
    void detectsTestSourcesNewerThanCompiledTests(@TempDir Path project) throws Exception {
        Path module = Files.createDirectories(project.resolve("module"));
        Path testOutput = classOutput(module.resolve("target/test-classes"), "MainTest.class");
        Path source = module.resolve("src/test/java/MainTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class MainTest {}");
        Files.setLastModifiedTime(testOutput.resolve("MainTest.class"),
                FileTime.from(Instant.now().minusSeconds(30)));
        Files.setLastModifiedTime(source, FileTime.from(Instant.now()));

        assertEquals(List.of(module.toAbsolutePath().normalize()),
                ProjectLayout.staleTestOutputModules(List.of(
                        new ProjectLayout.CompiledOutput(testOutput, module, "test"))));
    }

    @Test
    void stateFingerprintIncludesCapturedTestClasspath(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path main = classOutput(project.resolve("target/classes"), "Main.class");
        Path test = classOutput(project.resolve("target/test-classes"), "MainTest.class");
        Files.writeString(project.resolve("target/quill-classpath.txt"), "");
        Path testClasspath = project.resolve("target/quill-test-classpath.txt");
        Files.writeString(testClasspath, "");
        String before = ProjectLayout.computeStateFingerprint(project, List.of(main, test));

        Path testDependency = Files.write(project.resolve("test-library.jar"), new byte[] {1, 2, 3});
        Files.writeString(testClasspath, testDependency.toString());

        assertNotEquals(before,
                ProjectLayout.computeStateFingerprint(project, List.of(main, test)));
    }

    @Test
    void expectsTestOutputOnlyWhenTestSourcesExist(@TempDir Path module) throws Exception {
        assertFalse(ProjectLayout.hasTestSources(module));
        Path source = module.resolve("src/test/kotlin/example/FeatureTest.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class FeatureTest");
        assertTrue(ProjectLayout.hasTestSources(module));
    }

    private static Path classOutput(Path directory, String className) throws Exception {
        Path normalized = Files.createDirectories(directory).toAbsolutePath().normalize();
        Files.write(normalized.resolve(className), new byte[] {0});
        return normalized;
    }
}
