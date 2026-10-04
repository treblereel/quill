package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class McpConnectivityProbeTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    private DoctorCommand.Check probe(String mode) throws Exception {
        Files.writeString(root.resolve(".mcp.json"), JSON.writeValueAsString(Map.of(
                "mcpServers", Map.of("quill", Map.of(
                        "command", Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "args", List.of("-cp", Path.of(FakeServer.class.getProtectionDomain()
                                .getCodeSource().getLocation().toURI()).toString(),
                                FakeServer.class.getName(), mode),
                        "env", Map.of("QUILL_PROBE_TEST_VALUE", "secret-do-not-print"))))));
        return McpConnectivityProbe.inspect(root, Duration.ofSeconds(2));
    }

    @Test void verifiesRealProtocolWithoutModel() throws Exception {
        var check = probe("ok");
        assertEquals(DoctorCommand.Status.PASS, check.status());
        assertTrue(check.message().contains("activation"));
        assertTrue(check.message().contains("Codex configuration were not checked"));
        assertEquals(DoctorCommand.Status.PASS, probe("text-overview").status());
    }

    @Test void rejectsWrongServerAndMissingTools() throws Exception {
        assertTrue(probe("wrong-server").message().contains("initialize"));
        assertEquals(DoctorCommand.Status.ERROR, probe("missing-tool").status());
    }

    @Test void rejectsToolErrorsAndRpcErrorsWithoutLeakingDetails() throws Exception {
        for (String mode : List.of("tool-error", "rpc-error", "empty-overview")) {
            var check = probe(mode);
            assertEquals(DoctorCommand.Status.ERROR, check.status());
            assertFalse(check.message().contains("secret"));
            assertFalse(check.action().contains("secret"));
        }
    }

    @Test void timesOutAndTerminatesServer() throws Exception {
        long start = System.nanoTime();
        assertEquals(DoctorCommand.Status.ERROR, probe("hang").status());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
        long pid = Long.parseLong(Files.readString(root.resolve("probe-started")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void missingConfigurationDoesNotLaunchAnything() {
        var check = McpConnectivityProbe.inspect(root);
        assertEquals(DoctorCommand.Status.ERROR, check.status());
        assertTrue(check.message().endsWith("configuration"));
    }

    @Test void malformedAndOversizedOutputFailClosed() throws Exception {
        assertEquals(DoctorCommand.Status.ERROR, probe("malformed").status());
        assertEquals(DoctorCommand.Status.ERROR, probe("oversized").status());
    }

    @Test void doctorOnlyLaunchesOnExplicitOptIn() throws Exception {
        probe("ok");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path marker = root.resolve("probe-started");
        Files.deleteIfExists(marker);
        var writer = new java.io.StringWriter();
        var cli = new CommandLine(new DoctorCommand());
        cli.setOut(new java.io.PrintWriter(writer));
        cli.execute("--project", root.toString(), "--json");
        assertFalse(Files.exists(marker));
        writer.getBuffer().setLength(0);
        cli.execute("--project", root.toString(), "--json", "--probe-mcp");
        assertTrue(Files.exists(marker));
        var checks = JSON.readTree(writer.toString()).path("checks");
        assertTrue(checks.toString().contains("mcp_connectivity"));
        assertFalse(writer.toString().contains("secret"));
    }

    @Test void honorsConfiguredWorkingDirectory() throws Exception {
        probe("ok");
        Path nested = Files.createDirectory(root.resolve("nested"));
        var config = JSON.readTree(root.resolve(".mcp.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) config.path("mcpServers").path("quill"))
                .put("cwd", "nested");
        Files.writeString(root.resolve(".mcp.json"), config.toString());
        assertEquals(DoctorCommand.Status.PASS, McpConnectivityProbe.inspect(root).status());
        assertTrue(Files.exists(nested.resolve("probe-started")));
    }

    @Test void refusesIndexOnlyBeforeAnyInitialization() {
        assertEquals(CommandLine.ExitCode.USAGE, new CommandLine(new InitCommand())
                .execute("--project", root.toString(), "--index-only", "--probe-mcp"));
        assertEquals(CommandLine.ExitCode.USAGE, new CommandLine(new WorkspaceInitCommand())
                .execute("--project", root.toString(), "--index-only", "--probe-mcp"));
        assertFalse(Files.exists(root.resolve(".quill-workspace")));
    }

    /** A subprocess fixture with no MCP library or model dependency. */
    public static class FakeServer {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            Files.writeString(Path.of("probe-started"), Long.toString(ProcessHandle.current().pid()));
            System.err.println(System.getenv("QUILL_PROBE_TEST_VALUE"));
            if (mode.equals("hang")) Thread.sleep(30_000);
            if (mode.equals("malformed")) System.out.println("not-json");
            if (mode.equals("oversized")) System.out.println("x".repeat(4 * 1024 * 1024 + 1));
            var input = new BufferedReader(new InputStreamReader(System.in));
            while (input.readLine() != null) {
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"serverInfo\":{\"name\":\""
                        + (mode.equals("wrong-server") ? "other" : "quill") + "\"}}}");
                System.out.flush();
                input.readLine(); // initialized notification
                input.readLine(); // tools/list
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":"
                        + (mode.equals("missing-tool") ? "[]" : "[{\"name\":\"get_overview\"}]") + "}}");
                System.out.flush();
                input.readLine();
                if (mode.equals("rpc-error")) {
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"message\":\"secret\"}}");
                } else if (mode.equals("text-overview")) {
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":["
                            + "{\"type\":\"text\",\"text\":\"{\\\"projects\\\":[{\\\"data\\\":{\\\"project\\\":{}}}]}\"}]}}");
                } else {
                    String payload = mode.equals("tool-error") ? "{\"error_code\":\"secret\"}"
                            : mode.equals("empty-overview") ? "{}" : "{\"project\":{}}";
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"structuredContent\":"
                            + payload + "}}");
                }
                System.out.flush();
            }
        }
    }
}
