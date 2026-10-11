package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class KotlinSemanticQueriesTest {
    @TempDir Path temp;

    @Test void missingProjectEvidenceKeepsFallbackWithoutExecutingBuilds() throws Exception {
        var db = QuillDatabase.create(temp.resolve("index.db"));
        Path file = temp.resolve("Example.kt"); Files.writeString(file, "fun run() = 1");
        assertEquals("module_unknown", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        assertEquals("classpath_missing", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
        assertFalse(Files.exists(temp.resolve("target")));
        Files.createDirectories(temp.resolve("target"));
        Files.writeString(temp.resolve("target/quill-classpath.txt"), "missing-dependency.jar");
        assertEquals("classpath_missing", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
        assertEquals("unavailable", KotlinSemanticQueries.resolve(db, temp, temp.resolve("script.kts"), 0).path("status").asText());
    }

    @Test void testSourcesUseTestClasspathAndSeeMainDeclarations() throws Exception {
        var db = QuillDatabase.create(temp.resolve("index.db"));
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path main = temp.resolve("src/main/kotlin/Main.kt");
        Path test = temp.resolve("src/test/kotlin/Test.kt");
        Files.createDirectories(main.getParent()); Files.createDirectories(test.getParent());
        Files.writeString(main, "package probe\nfun pick(x: Int) = x");
        String source = "package probe\nfun run() = pick(1)";
        Files.writeString(test, source);
        Files.createDirectories(temp.resolve("target"));
        Path stdlib = Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.writeString(temp.resolve("target/quill-test-classpath.txt"), stdlib.toString());
        var result = KotlinSemanticQueries.resolve(db, temp, test, source.lastIndexOf("pick("));
        assertEquals("resolved", result.path("status").asText(), result.toPrettyString());
        assertEquals("probe.MainKt", result.path("owner").asText());
        Files.writeString(test, "package probe\nfun run() = pick(\"bad\")");
        assertEquals("analysis_error", KotlinSemanticQueries.resolve(db, temp, test, 25).path("status").asText());
        assertFalse(Files.exists(temp.resolve("target/test-classes")));
    }

    @Test void bundledK2RunsAutomaticallyWithoutWorkerJarConfiguration() throws Exception {
        var db = QuillDatabase.create(temp.resolve("index.db"));
        Path file = temp.resolve("src/main/kotlin/Example.kt"); Files.createDirectories(file.getParent());
        String source = "package probe\nfun pick(x: Int) = x\nfun run() = pick(1)";
        Files.writeString(file, source); Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Files.createDirectories(temp.resolve("target"));
        Path stdlib = Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.writeString(temp.resolve("target/quill-classpath.txt"), stdlib.toString());
        var result = KotlinSemanticQueries.resolve(db, temp, file, source.lastIndexOf("pick("));
        assertEquals("resolved", result.path("status").asText(), result.toPrettyString());
        assertEquals("kotlin_k2_fir", result.path("backend").asText());
        assertEquals("(I)I", result.path("descriptor").asText());
        assertFalse(Files.exists(temp.resolve("target/classes")));
    }

    @Test void nativeWorkerUsesSameExecutableAndJvmWorkerUsesBundledClasspath() {
        assertEquals(List.of("/tmp/quill with spaces", "-Xmx512m", "--kotlin-semantic-worker"),
                KotlinSemanticQueries.workerCommand("/tmp/quill with spaces"));
        var command = KotlinSemanticQueries.workerCommand(null);
        assertEquals("-cp", command.get(2)); assertEquals(System.getProperty("java.class.path"), command.get(3));
        assertEquals("org.treblereel.mcp.kotlin.SemanticWorker", command.get(4));
    }
}
