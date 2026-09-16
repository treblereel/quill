package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class DoctorCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    private static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");

    @AfterEach
    void cleanup() throws Exception {
        if (!Files.exists(QUILL_DIR)) return;
        try (var walk = Files.walk(QUILL_DIR)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void validIndexHasNoDoctorErrors() throws Exception {
        ProjectInitializer.InitializationResult initialization =
                ProjectInitializer.initializeDetailed(PROJECT_ROOT, true);
        assertTrue(initialization.successful(), initialization.diagnostic());
        StringWriter output = new StringWriter();
        CommandLine cli = new CommandLine(new DoctorCommand());
        cli.setOut(new PrintWriter(output));

        int exitCode = cli.execute("--project", PROJECT_ROOT.toString(), "--json");

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        JsonNode report = JSON.readTree(output.toString());
        assertEquals(0, report.path("summary").path("errors").asInt());
        assertEquals("pass", check(report, "compiled_outputs").path("status").asText());
        assertEquals("pass", check(report, "index").path("status").asText());
        assertEquals("pass", check(report, "project_fingerprint").path("status").asText());
        assertEquals("pass", check(report, "dependency_index").path("status").asText());
    }

    private static JsonNode check(JsonNode report, String id) {
        for (JsonNode check : report.path("checks")) {
            if (id.equals(check.path("id").asText())) return check;
        }
        throw new AssertionError("Missing doctor check: " + id);
    }
}
