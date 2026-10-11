package org.treblereel.mcp.mcp;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
class KotlinSemanticQueriesTest {
    @TempDir Path temp;
    @Test void unavailableWorkerKeepsFallbackAndDoesNotExecuteBuilds() throws Exception {
        var db = QuillDatabase.create(temp.resolve("index.db"));
        Path file = temp.resolve("Example.kt"); Files.writeString(file, "fun run() = 1");
        String previous = System.getProperty("quill.kotlin.semantic.worker");
        try {
            System.clearProperty("quill.kotlin.semantic.worker");
            assertEquals("disabled", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
            System.setProperty("quill.kotlin.semantic.worker", temp.resolve("missing.jar").toString());
            assertEquals("unavailable", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
            Path placeholder = temp.resolve("worker.jar"); Files.writeString(placeholder, "not a jar");
            System.setProperty("quill.kotlin.semantic.worker", placeholder.toString());
            assertEquals("module_unknown", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
            Files.writeString(temp.resolve("pom.xml"), "<project/>");
            assertEquals("classpath_missing", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
            Files.createDirectories(temp.resolve("target"));
            Files.writeString(temp.resolve("target/quill-classpath.txt"), "");
            assertEquals("worker_failed", KotlinSemanticQueries.resolve(db, temp, file, 4).path("status").asText());
            assertEquals("unavailable", KotlinSemanticQueries.resolve(db, temp, temp.resolve("script.kts"), 0).path("status").asText());
        } finally {
            if (previous == null) System.clearProperty("quill.kotlin.semantic.worker");
            else System.setProperty("quill.kotlin.semantic.worker", previous);
        }
    }
}
