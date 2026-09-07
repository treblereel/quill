package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

@Tag("e2e")
class GradleMultiModuleIT {

    private static final Path PROJECT = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("gradle-multimodule");
    private static final Path SPRING_PROJECT = Path.of(System.getProperty("user.dir"))
            .getParent().resolve("gradle-spring");

    @Test
    void indexesCrossModuleCdiInjection() throws Exception {
        assumeGradleAvailable();
        deleteTree(PROJECT.resolve(".quill"));
        runGradleClean(PROJECT);

        try {
            InitCommand command = new InitCommand();
            command.projectPath = PROJECT;
            command.indexOnly = true;
            command.run();

            Path db = ProjectInitializer.findDbForHead(PROJECT);
            assertNotNull(db);
            var jdbi = QuillDatabase.open(db);
            assertEquals(3, IndexReader.findAllClasses(jdbi).size());
            assertTrue(IndexReader.findAllClasses(jdbi).stream()
                    .allMatch(record -> record.sourceTokens() > 0));
            assertEquals(2, IndexReader.findBeans(jdbi, null).size());

            var userClass = IndexReader.findClassByName(jdbi,
                    "org.treblereel.mcp.fixture.gradle.UserService").orElseThrow();
            var userBean = IndexReader.findBeanByClassId(jdbi, userClass.id()).orElseThrow();
            var injection = IndexReader.findInjectionPoints(jdbi, userBean.id()).getFirst();
            assertNotNull(injection.resolvedBeanId(), "Cross-module injection should resolve");

            assertTrue(Files.isRegularFile(PROJECT.resolve("common/build/quill-classpath.txt")));
            assertTrue(Files.isRegularFile(PROJECT.resolve("service/build/quill-classpath.txt")));
        } finally {
            deleteTree(PROJECT.resolve(".quill"));
        }
    }

    @Test
    void indexesSpringKotlinDslProject() throws Exception {
        assumeGradleAvailable();
        deleteTree(SPRING_PROJECT.resolve(".quill"));
        runGradleClean(SPRING_PROJECT);

        try {
            InitCommand command = new InitCommand();
            command.projectPath = SPRING_PROJECT;
            command.indexOnly = true;
            command.run();

            Path db = ProjectInitializer.findDbForHead(SPRING_PROJECT);
            assertNotNull(db);
            var jdbi = QuillDatabase.open(db);
            assertEquals("Spring", IndexReader.getMetadata(jdbi).get("framework"));
            assertEquals(2, IndexReader.findBeans(jdbi, null).size());
            assertTrue(IndexReader.findAllClasses(jdbi).stream()
                    .allMatch(record -> record.sourceTokens() > 0));

            var serviceClass = IndexReader.findClassByName(jdbi,
                    "org.treblereel.mcp.fixture.gradlespring.OrderService").orElseThrow();
            var serviceBean = IndexReader.findBeanByClassId(jdbi, serviceClass.id()).orElseThrow();
            assertNotNull(IndexReader.findInjectionPoints(jdbi, serviceBean.id())
                    .getFirst().resolvedBeanId());
        } finally {
            deleteTree(SPRING_PROJECT.resolve(".quill"));
        }
    }

    private static void assumeGradleAvailable() throws Exception {
        Process process = new ProcessBuilder(BuildSystem.GRADLE.command(PROJECT, "--version"))
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

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }
}
