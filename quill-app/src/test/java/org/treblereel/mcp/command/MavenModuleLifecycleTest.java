package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.ProjectRegistry;
import org.treblereel.mcp.mcp.QuillTools;
import org.treblereel.mcp.mcp.SingleProjectScope;
import com.fasterxml.jackson.databind.ObjectMapper;

class MavenModuleLifecycleTest {

    @TempDir
    Path project;

    @Test
    void updateAddsAndRemovesReactorModulesEvenWhenRemovedOutputsRemain() throws Exception {
        writeModule("base", "example.base", "BaseType");
        writeReactor("base");

        assertTrue(ProjectInitializer.initializeDetailed(project, true).successful());
        assertIndexed("example.base.BaseType", true);
        assertMetadata("module_contexts", "1");

        writeModule("dynamic", "example.dynamic", "DynamicType");
        writeReactor("base", "dynamic");
        update();

        assertIndexed("example.base.BaseType", true);
        assertIndexed("example.dynamic.DynamicType", true);
        assertMetadata("module_contexts", "2");

        // Deliberately retain dynamic/target/classes. Reactor membership, not an orphaned output
        // directory, is authoritative for a complete Maven discovery.
        writeReactor("base");
        update();

        assertTrue(Files.isRegularFile(project.resolve(
                "dynamic/target/classes/example/dynamic/DynamicType.class")));
        assertIndexed("example.base.BaseType", true);
        assertIndexed("example.dynamic.DynamicType", false);
        assertMetadata("module_contexts", "1");
    }

    @Test
    void indexesCompiledTestsAndTheirDependenciesWithoutStartingABuild() throws Exception {
        writeModule("base", "example.base", "BaseType");
        writeTestClass("base", "example.base", "BaseTypeTest", "BaseType");
        writeReactor("base");

        assertTrue(ProjectInitializer.initializeDetailed(project, true).successful());

        Path database = ProjectIndexStore.findBestAvailableDb(project);
        var jdbi = QuillDatabase.open(database);
        var main = IndexReader.findClassByName(jdbi, "example.base.BaseType").orElseThrow();
        var test = IndexReader.findClassByName(jdbi, "example.base.BaseTypeTest").orElseThrow();
        assertEquals("main", main.sourceSet());
        assertEquals("test", test.sourceSet());
        assertTrue(IndexReader.findDependencies(jdbi, test.id(), "outbound").stream()
                .anyMatch(dependency -> dependency.toClassId() == main.id()));
        assertMetadata("compiled_main_output_count", "1");
        assertMetadata("compiled_test_output_count", "1");
        assertMetadata("compiled_test_module_count", "1");
        assertEquals("[\"base\"]", IndexReader.getMetadata(jdbi)
                .get("compiled_test_modules"));
        assertEquals("[]", IndexReader.getMetadata(jdbi)
                .get("missing_test_output_modules"));

        SingleProjectScope scope = new SingleProjectScope();
        scope.register(project);
        var impact = new ObjectMapper().readTree(new QuillTools(new ProjectRegistry(scope))
                .find_impacted_tests(List.of("example.base.BaseType"), Optional.of(true),
                        Optional.of(3), Optional.of(20), Optional.empty(), Optional.empty()));
        assertEquals(1, impact.path("total").asInt(), impact.toString());
        assertEquals("example.base.BaseTypeTest",
                impact.path("tests").get(0).path("class").asText());
        assertEquals("static_direct",
                impact.path("tests").get(0).path("evidence").get(0).asText());
        assertEquals("partial", impact.path("test_index_coverage").path("status").asText());
        assertFalse(impact.path("test_index_coverage").path("complete").asBoolean(),
                "A missing captured test classpath must keep the answer incomplete");
        assertFalse(impact.path("answer_complete").asBoolean());
        DoctorCommand.Check testIndex = DoctorCommand.inspect(project).checks().stream()
                .filter(check -> check.id().equals("test_index")).findFirst().orElseThrow();
        assertEquals(DoctorCommand.Status.WARNING, testIndex.status());
        assertTrue(testIndex.action().contains("test-compile"));
    }

    private void update() {
        UpdateCommand command = new UpdateCommand();
        command.projectPath = project;
        assertEquals(0, command.call());
    }

    private void assertIndexed(String className, boolean expected) {
        Path database = ProjectIndexStore.findBestAvailableDb(project);
        assertTrue(database != null && Files.isRegularFile(database));
        assertEquals(expected,
                IndexReader.findClassByName(QuillDatabase.open(database), className).isPresent());
    }

    private void assertMetadata(String key, String expected) {
        Path database = ProjectIndexStore.findBestAvailableDb(project);
        assertEquals(expected,
                IndexReader.getMetadata(QuillDatabase.open(database)).get(key));
    }

    private void writeReactor(String... modules) throws Exception {
        StringBuilder declarations = new StringBuilder();
        for (String module : modules) {
            declarations.append("        <module>").append(module).append("</module>\n");
        }
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>reactor</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                """ + declarations + """
                    </modules>
                </project>
                """);
    }

    private void writeModule(String module, String packageName, String className) throws Exception {
        Path directory = Files.createDirectories(project.resolve(module));
        Files.writeString(directory.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>%s</artifactId>
                    <version>1</version>
                </project>
                """.formatted(module));
        Path source = directory.resolve("src/main/java")
                .resolve(packageName.replace('.', '/')).resolve(className + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package " + packageName + "; public final class "
                + className + " {}\n");
        Path classes = Files.createDirectories(directory.resolve("target/classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString()));
        Files.writeString(directory.resolve("target/quill-classpath.txt"), "");
    }

    private void writeTestClass(String module, String packageName, String className,
            String referencedClass) throws Exception {
        Path directory = project.resolve(module);
        Path source = directory.resolve("src/test/java")
                .resolve(packageName.replace('.', '/')).resolve(className + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package " + packageName + "; public final class "
                + className + " { Object value = new " + referencedClass + "(); }\n");
        Path classes = Files.createDirectories(directory.resolve("target/test-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", directory.resolve("target/classes").toString(),
                "-d", classes.toString(), source.toString()));
        assertFalse(Files.exists(directory.resolve("target/surefire-reports")),
                "Quill must not run the test lifecycle");
    }
}
