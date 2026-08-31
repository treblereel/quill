package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

@Tag("e2e")
class McpStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");

    @BeforeEach
    void setUp() {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.run();
        assertTrue(Files.exists(QUILL_DIR.resolve("index.db")));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(QUILL_DIR)) {
            try (var walk = Files.walk(QUILL_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void mcpStdioToolsListAndCall() throws Exception {
        Path quarkusJar = resolveQuarkusJar();
        Assumptions.assumeTrue(Files.exists(quarkusJar),
                "Skipping: quarkus-run.jar not found (run 'mvn package -DskipTests' first)");

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", quarkusJar.toString(), "--mcp")
                .directory(PROJECT_ROOT.toFile())
                .redirectErrorStream(false);
        Process proc = pb.start();

        try (BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
             BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {

            sendRequest(stdin, 1, "initialize", """
                    {"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0.1"}}""");
            JsonNode initResp = readResponse(stdout, 1);
            assertNotNull(initResp.get("result"), "initialize should return a result");
            assertEquals("quill", initResp.get("result").get("serverInfo").get("name").asText());

            sendNotification(stdin, "notifications/initialized", "{}");

            sendRequest(stdin, 2, "tools/list", "{}");
            JsonNode listResp = readResponse(stdout, 2);
            JsonNode tools = listResp.get("result").get("tools");
            assertNotNull(tools, "tools/list should return tools array");

            boolean hasGetBeans = false;
            boolean hasGetDeps = false;
            boolean hasGetIps = false;
            for (JsonNode tool : tools) {
                String name = tool.get("name").asText();
                if ("get_beans".equals(name)) hasGetBeans = true;
                if ("get_dependencies".equals(name)) hasGetDeps = true;
                if ("get_injection_points".equals(name)) hasGetIps = true;
            }
            assertTrue(hasGetBeans, "Should include get_beans tool");
            assertTrue(hasGetDeps, "Should include get_dependencies tool");
            assertTrue(hasGetIps, "Should include get_injection_points tool");

            sendRequest(stdin, 3, "tools/call",
                    "{\"name\":\"get_beans\",\"arguments\":{}}");
            JsonNode callResp = readResponse(stdout, 3);
            assertNotNull(callResp.get("result"), "tools/call should return a result");
            JsonNode content = callResp.get("result").get("content");
            assertNotNull(content, "Result should have content array");
            String text = content.get(0).get("text").asText();
            JsonNode beansResult = JSON.readTree(text);
            assertTrue(beansResult.get("total").asInt() >= 3, "Should find at least 3 beans");
            assertTrue(beansResult.has("_meta"), "Response should contain _meta envelope");
        } finally {
            proc.destroyForcibly();
            proc.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private void sendRequest(BufferedWriter stdin, int id, String method, String params) throws IOException {
        String msg = String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"%s\",\"params\":%s}", id, method, params);
        stdin.write(msg);
        stdin.newLine();
        stdin.flush();
    }

    private void sendNotification(BufferedWriter stdin, String method, String params) throws IOException {
        String msg = String.format("{\"jsonrpc\":\"2.0\",\"method\":\"%s\",\"params\":%s}", method, params);
        stdin.write(msg);
        stdin.newLine();
        stdin.flush();
    }

    private JsonNode readResponse(BufferedReader stdout, int expectedId) throws IOException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String line = stdout.readLine();
            if (line == null) throw new IOException("Process stdout closed unexpectedly");
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JsonNode node = JSON.readTree(line);
                if (node.has("id") && node.get("id").asInt() == expectedId) {
                    return node;
                }
            } catch (Exception e) {
                // not valid JSON — skip (could be log output leaking to stdout)
            }
        }
        throw new IOException("Timed out waiting for response id=" + expectedId);
    }

    private Path resolveQuarkusJar() {
        return PROJECT_ROOT.getParent().getParent()
                .resolve("quill-app/target/quarkus-app/quarkus-run.jar");
    }
}
