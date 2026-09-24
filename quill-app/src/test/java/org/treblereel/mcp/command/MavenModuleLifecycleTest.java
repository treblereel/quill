package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

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
}
