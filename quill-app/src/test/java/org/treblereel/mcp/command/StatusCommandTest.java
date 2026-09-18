package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import picocli.CommandLine;

class StatusCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void jsonReportsUnavailableIndexWithStableErrorCode() throws Exception {
        Files.createFile(project.resolve("pom.xml"));

        Captured result = executeJson();

        assertEquals(CommandLine.ExitCode.SOFTWARE, result.exitCode());
        JsonNode json = JSON.readTree(result.stdout());
        assertFalse(json.path("indexed").asBoolean());
        assertEquals("missing", json.path("health").asText());
        assertEquals("INDEX_MISSING", json.path("error").path("code").asText());
        assertTrue(json.path("index").isNull());
    }

    @Test
    void jsonDistinguishesPendingRefreshFromMissingIndex() throws Exception {
        Files.createFile(project.resolve("pom.xml"));
        Path events = Files.createDirectories(project.resolve(".quill/build-events"));
        Files.writeString(events.resolve("maven-event.json"), "{}");

        Captured result = executeJson();

        JsonNode json = JSON.readTree(result.stdout());
        assertEquals("refresh_pending", json.path("health").asText());
        assertEquals("INDEX_REFRESH_PENDING", json.path("error").path("code").asText());
        assertTrue(json.path("error").path("message").asText().contains("1 build event"));
    }

    @Test
    void jsonDistinguishesIncompatibleIndex() throws Exception {
        Files.createFile(project.resolve("pom.xml"));
        Path quill = Files.createDirectories(project.resolve(".quill"));
        Path database = quill.resolve("old.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 1");
        }

        Captured result = executeJson();

        JsonNode json = JSON.readTree(result.stdout());
        assertEquals("incompatible", json.path("health").asText());
        assertEquals("INDEX_INCOMPATIBLE", json.path("error").path("code").asText());
    }

    @Test
    void jsonReportsCanonicalIndexFreshnessAndStatistics() throws Exception {
        Files.createFile(project.resolve("pom.xml"));
        Path quillDir = Files.createDirectories(project.resolve(".quill"));
        Path database = quillDir.resolve("status-index.db");
        var jdbi = QuillDatabase.create(database);
        jdbi.useHandle(handle -> {
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "index_id", "status-index");
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "indexed_at", "2026-09-16T00:00:00Z");
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "last_commit", "unknown");
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "project_root", project.toString());
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "framework", "CDI");
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "dependency_index", "complete");
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "dependency_index_detail", "indexed 3 jars");
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "database_write_mode", "fresh");
        });
        Files.writeString(quillDir.resolve("refs.json"), "{\"@worktree\":\"status-index\"}");

        Captured result = executeJson();

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        JsonNode json = JSON.readTree(result.stdout());
        assertTrue(json.path("indexed").asBoolean());
        assertEquals("status-index", json.path("index").path("generation").asText());
        assertEquals("CDI", json.path("index").path("framework").asText());
        assertEquals("complete", json.path("dependency_index").path("status").asText());
        assertEquals(0, json.path("statistics").path("classes").asInt());
        assertTrue(json.path("freshness").path("stale_reasons").isArray());
        assertTrue(json.path("recovery").isNull());
    }

    private Captured executeJson() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = new CommandLine(new StatusCommand()).execute(
                    "--project", project.toString(), "--json");
            return new Captured(exitCode, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Captured(int exitCode, String stdout, String stderr) {}
}
