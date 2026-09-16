package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class DoctorCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void jsonReportHasStableSchemaAndActionableFailures(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        StringWriter output = new StringWriter();
        CommandLine cli = new CommandLine(new DoctorCommand());
        cli.setOut(new PrintWriter(output));

        int exitCode = cli.execute("--project", project.toString(), "--json");

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        JsonNode report = JSON.readTree(output.toString());
        assertEquals(1, report.path("schema_version").asInt());
        assertEquals(project.toAbsolutePath().normalize().toString(),
                report.path("project_root").asText());
        assertEquals("error", report.path("overall").asText());
        assertTrue(report.path("summary").path("errors").asInt() >= 2);

        Map<String, JsonNode> checks = checksById(report);
        assertEquals("error", checks.get("compiled_outputs").path("status").asText());
        assertTrue(checks.get("compiled_outputs").hasNonNull("action"));
        assertEquals("error", checks.get("index").path("status").asText());
        assertEquals("warning", checks.get("build_integration").path("status").asText());
        assertEquals("warning", checks.get("gitignore").path("status").asText());
    }

    @Test
    void textReportUsesReadableSeverityAndOverallResult(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        StringWriter output = new StringWriter();
        CommandLine cli = new CommandLine(new DoctorCommand());
        cli.setOut(new PrintWriter(output));

        int exitCode = cli.execute("--project", project.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(output.toString().contains("[ERROR] compiled_outputs:"));
        assertTrue(output.toString().contains("Action:"));
        assertTrue(output.toString().contains("Overall: ERROR"));
    }

    private static Map<String, JsonNode> checksById(JsonNode report) {
        Map<String, JsonNode> checks = new HashMap<>();
        report.path("checks").forEach(check -> checks.put(check.path("id").asText(), check));
        return checks;
    }
}
