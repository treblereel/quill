package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class ExternalSymbolQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private Jdbi jdbi;

    @BeforeEach
    void createClasspath() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path jar = temp.resolve("repository/org/demo/fixture/1.0/fixture-1.0.jar");
        Files.createDirectories(jar.getParent());
        String entryName = Fixture.class.getName().replace('.', '/') + ".class";
        try (InputStream bytecode = Fixture.class.getClassLoader().getResourceAsStream(entryName);
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            bytecode.transferTo(output);
            output.closeEntry();
        }
        Path classpath = temp.resolve("target/quill-classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, jar.toString());
        jdbi = QuillDatabase.create(temp.resolve("index.db"));
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO files(project_path, repository_path, kind, origin, lifecycle,
                                          module, source_set)
                        VALUES ('src/main/java/App.java', 'src/main/java/App.java', 'java',
                                'source', 'current', '.', 'main')
                        """).execute());
    }

    @Test
    void searchesResolvedDependencyJarClasses() throws Exception {
        var result = JSON.readTree(new ExternalSymbolQueries()
                .search(jdbi, temp, "Fixture", "class", "fixture", 20, 0));

        assertEquals(1, result.path("total").asInt());
        assertTrue(result.path("symbols").get(0).path("class_name").asText()
                .endsWith("ExternalSymbolQueriesTest$Fixture"));
        assertEquals(1, result.path("discovery").path("jar_count").asInt());
    }

    @Test
    void readsMembersOnlyForSelectedExternalClass() throws Exception {
        var result = JSON.readTree(new ExternalSymbolQueries()
                .details(jdbi, temp, "Fixture", 20, 0));

        assertTrue(result.path("class_name").asText().endsWith("$Fixture"));
        assertTrue(result.path("members").toString().contains("greet"));
        assertFalse(result.path("source_available").asBoolean());
    }

    static final class Fixture {
        String greet(String name) {
            return "hello " + name;
        }
    }
}
