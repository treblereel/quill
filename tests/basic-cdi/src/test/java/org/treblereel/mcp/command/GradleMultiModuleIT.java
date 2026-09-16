package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.GradleProjectDiscovery;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

@Tag("e2e")
class GradleMultiModuleIT {

    private static final Path PROJECT_FIXTURE = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("gradle-multimodule");
    private static final Path SPRING_PROJECT_FIXTURE = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("gradle-spring");

    @TempDir Path tempDir;

    @Test
    void indexesCrossModuleCdiInjection() throws Exception {
        assumeGradleAvailable();
        Path project = TestProjectCopies.copyFixture(
                PROJECT_FIXTURE, tempDir.resolve("gradle-multimodule"));
        runGradleCleanAndBuild(project);

        InitCommand command = new InitCommand();
        command.projectPath = project;
        command.indexOnly = true;
        command.call();

        Path db = ProjectIndexStore.findDbForHead(project);
        assertNotNull(db);
        var jdbi = QuillDatabase.open(db);
        assertEquals(4, IndexReader.findAllClasses(jdbi).size());
        assertTrue(IndexReader.findAllClasses(jdbi).stream()
                .allMatch(record -> record.sourceTokens() > 0));
        assertEquals(2, IndexReader.findBeans(jdbi, null).size());

        var userClass = IndexReader.findClassByName(jdbi,
                "org.treblereel.mcp.fixture.gradle.UserService").orElseThrow();
        var userBean = IndexReader.findBeanByClassId(jdbi, userClass.id()).orElseThrow();
        var injection = IndexReader.findInjectionPoints(jdbi, userBean.id()).getFirst();
        assertNotNull(injection.resolvedBeanId(), "Cross-module injection should resolve");

        assertTrue(Files.isRegularFile(project.resolve("common/build/quill-classpath.txt")));
        assertTrue(Files.isRegularFile(project.resolve("service/build/quill-classpath.txt")));
        assertTrue(Files.isRegularFile(
                project.resolve("modules/custom/build/quill-classpath.txt")));
        assertTrue(Files.isRegularFile(project.resolve("build/quill-projects.tsv")));
        assertTrue(Files.isRegularFile(project.resolve("build/quill-projects.sha256")));
    }

    @Test
    void discoversEvaluatedProjectsAndIgnoresStrayBuildOutput() throws Exception {
        assumeGradleAvailable();
        Path project = TestProjectCopies.copyFixture(
                PROJECT_FIXTURE, tempDir.resolve("gradle-multimodule"));
        runGradleClean(project);
        Path stray = Files.createDirectories(
                project.resolve("retired/build/classes/java/main"));
        Files.write(stray.resolve("Stale.class"), new byte[] {1, 2, 3});

        GradleProjectDiscovery.Discovery discovery =
                GradleProjectDiscovery.discover(project);

        assertTrue(discovery.complete());
        assertEquals(Set.of(
                        project.resolve("common").toAbsolutePath(),
                        project.resolve("service").toAbsolutePath(),
                        project.resolve("modules/custom").toAbsolutePath()),
                Set.copyOf(discovery.moduleDirectories()));
        assertTrue(discovery.classesDirectories().contains(
                project.resolve("out/custom/classes/java/main").toAbsolutePath()));
        assertFalse(discovery.classesDirectories().stream()
                .anyMatch(path -> path.startsWith(project.resolve("retired"))));
        assertFalse(Files.exists(project.resolve("common/build/quill-classpath.txt")),
                "Read-only discovery must not modify the classpath cache");

        GradleProjectDiscovery.Discovery pinned =
                GradleProjectDiscovery.discover(project.resolve("service"));
        assertEquals(Set.of(project.resolve("service").toAbsolutePath()),
                Set.copyOf(pinned.moduleDirectories()));
    }

    @Test
    void indexesSpringKotlinProject() throws Exception {
        assumeGradleAvailable();
        Path springProject = TestProjectCopies.copyFixture(
                SPRING_PROJECT_FIXTURE, tempDir.resolve("gradle-spring"));
        runGradleCleanAndBuild(springProject);

        InitCommand command = new InitCommand();
        command.projectPath = springProject;
        command.indexOnly = true;
        command.call();

        Path db = ProjectIndexStore.findDbForHead(springProject);
        assertNotNull(db);
        var jdbi = QuillDatabase.open(db);
        assertEquals("Spring", IndexReader.getMetadata(jdbi).get("framework"));
        assertEquals(2, IndexReader.findBeans(jdbi, null).size());
        assertTrue(IndexReader.findAllClasses(jdbi).stream()
                .allMatch(record -> record.sourceTokens() > 0));

        var serviceClass = IndexReader.findClassByName(jdbi,
                "org.treblereel.mcp.fixture.gradlespring.OrderService").orElseThrow();
        assertTrue(serviceClass.sourceFile().endsWith("OrderService.kt"));
        assertEquals("source", serviceClass.origin());
        assertEquals(serviceClass, IndexReader.findClassByPath(jdbi,
                "src/main/kotlin/org/treblereel/mcp/fixture/gradlespring/OrderService.kt")
                .orElseThrow());
        var serviceBean = IndexReader.findBeanByClassId(jdbi, serviceClass.id()).orElseThrow();
        assertNotNull(IndexReader.findInjectionPoints(jdbi, serviceBean.id())
                .getFirst().resolvedBeanId());
    }

    private static void assumeGradleAvailable() throws Exception {
        Process process = new ProcessBuilder(
                BuildSystem.GRADLE.command(PROJECT_FIXTURE, "--version"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0,
                "Gradle executable is required for Gradle integration tests");
    }

    private static void runGradleClean(Path project) throws Exception {
        Process process = new ProcessBuilder(BuildSystem.GRADLE.command(project, "clean", "--quiet"))
                .directory(project.toFile())
                .inheritIO()
                .start();
        assertEquals(0, process.waitFor(), "Gradle clean must succeed");
    }

    private static void runGradleCleanAndBuild(Path project) throws Exception {
        Process process = new ProcessBuilder(BuildSystem.GRADLE.command(
                project, "clean", "classes", "--quiet"))
                .directory(project.toFile())
                .inheritIO()
                .start();
        assertEquals(0, process.waitFor(), "Gradle fixture build must succeed");
    }
}
