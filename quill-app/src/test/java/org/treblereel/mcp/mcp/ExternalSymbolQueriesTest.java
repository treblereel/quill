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
                .search(jdbi, temp, "Fixture", "class", null, "fixture",
                        "contains", false, 20, 0));

        assertEquals(1, result.path("total").asInt());
        assertTrue(result.path("symbols").get(0).path("class_name").asText()
                .endsWith("ExternalSymbolQueriesTest$Fixture"));
        assertEquals(1, result.path("discovery").path("jar_count").asInt());
    }

    @Test
    void searchesMembersWithinAnExplicitExternalClass() throws Exception {
        var result = JSON.readTree(new ExternalSymbolQueries()
                .search(jdbi, temp, "greet", "method", "Fixture", null,
                        "exact", false, 20, 0));

        assertEquals(1, result.path("total").asInt());
        assertEquals("method", result.path("symbols").get(0).path("kind").asText());
        assertEquals("greet", result.path("symbols").get(0).path("name").asText());
        assertEquals(1, result.path("discovery").path("classes_inspected").asInt());
    }

    @Test
    void rejectsUnboundedMemberSearch() throws Exception {
        var result = JSON.readTree(new ExternalSymbolQueries()
                .search(jdbi, temp, "greet", "method", null, null,
                        "contains", false, 20, 0));

        assertTrue(result.path("error").asText().contains("requires class_name"));
    }

    @Test
    void exactClassNameDoesNotInspectNestedClasses() throws Exception {
        appendJar("2.0", Fixture.Inner.class);

        var result = JSON.readTree(new ExternalSymbolQueries().search(jdbi, temp,
                "nestedOnly", "method", Fixture.class.getName(), null,
                "contains", false, 20, 0));

        assertEquals(0, result.path("total").asInt());
        assertEquals(1, result.path("discovery").path("classes_inspected").asInt());
    }

    @Test
    void groupsTheSameClassAcrossDependencyVersions() throws Exception {
        appendJar("2.0", Fixture.class);

        var result = JSON.readTree(new ExternalSymbolQueries()
                .search(jdbi, temp, "Fixture", "class", null, "fixture",
                        "contains", true, 20, 0));

        assertEquals(1, result.path("total").asInt());
        assertEquals(2, result.path("symbols").get(0).path("version_count").asInt());
        assertEquals(2, result.path("symbols").get(0).path("occurrences").size());
    }

    @Test
    void compactClassSearchOmitsJarPaths() throws Exception {
        var result = JSON.readTree(new ExternalSymbolQueries().search(jdbi, temp,
                "Fixture", "class", null, null, "exact", false, 20, 0));

        var symbol = result.path("symbols").get(0);
        assertEquals("exact", result.path("match_mode").asText());
        assertEquals(1, symbol.path("artifacts").size());
        assertTrue(symbol.path("occurrences").isMissingNode());
        assertFalse(result.toString().contains(temp.toString()));
    }

    @Test
    void validatesMatchMode() throws Exception {
        var result = JSON.readTree(new ExternalSymbolQueries().search(jdbi, temp,
                "Fixture", "class", null, null, "fuzzy", false, 20, 0));

        assertTrue(result.path("error").asText().contains("match_mode"));
    }

    @Test
    void shortNameAmbiguityReturnsCandidatesInsteadOfChoosingArbitrarily() throws Exception {
        appendJar("2.0", Other.Fixture.class);

        var result = JSON.readTree(new ExternalSymbolQueries()
                .details(jdbi, temp, "Fixture", 20, 0));

        assertTrue(result.path("error").asText().contains("ambiguous"));
        assertEquals(2, result.path("candidates").size());
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

        static final class Inner {
            void nestedOnly() {}
        }
    }

    static final class Other {
        static final class Fixture {}
    }

    private void appendJar(String version, Class<?> type) throws Exception {
        Path jar = temp.resolve("repository/org/demo/fixture/" + version
                + "/fixture-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        String entryName = type.getName().replace('.', '/') + ".class";
        try (InputStream bytecode = type.getClassLoader().getResourceAsStream(entryName);
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            bytecode.transferTo(output);
            output.closeEntry();
        }
        Path classpath = temp.resolve("target/quill-classpath.txt");
        Files.writeString(classpath, java.io.File.pathSeparator + jar,
                java.nio.file.StandardOpenOption.APPEND);
    }
}
