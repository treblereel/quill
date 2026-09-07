package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;

@Tag("e2e")
class McpStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");
    static final Path GRADLE_PROJECT = PROJECT_ROOT.getParent().resolve("gradle-basic");

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.indexOnly = true;
        cmd.run();
        assertNotNull(ProjectInitializer.findDbForHead(PROJECT_ROOT));
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
        Path appJar = resolveAppJar();
        Assumptions.assumeTrue(Files.exists(appJar),
                "Skipping: quill executable JAR not found (run 'mvn package -DskipTests' first)");

        assertToolsListAndCall(List.of("java", "-jar", appJar.toString()), PROJECT_ROOT, 3);
    }

    @Test
    void nativeMcpToolsListAndCall() throws Exception {
        Path nativeImage = resolveNativeImage();
        Assumptions.assumeTrue(Files.isExecutable(nativeImage),
                "Skipping: native image not found (build with -Pnative)");

        assertToolsListAndCall(List.of(nativeImage.toString()), PROJECT_ROOT, 3);
    }

    @Test
    void gradleProjectWorksThroughJarMcp() throws Exception {
        Path appJar = resolveAppJar();
        Assumptions.assumeTrue(Files.exists(appJar));
        prepareGradleProject();
        try {
            assertToolsListAndCall(List.of("java", "-jar", appJar.toString()), GRADLE_PROJECT, 2);
        } finally {
            deleteTree(GRADLE_PROJECT.resolve(".quill"));
        }
    }

    @Test
    void gradleProjectWorksThroughNativeMcp() throws Exception {
        Path nativeImage = resolveNativeImage();
        Assumptions.assumeTrue(Files.isExecutable(nativeImage));
        prepareGradleProject();
        try {
            assertToolsListAndCall(List.of(nativeImage.toString()), GRADLE_PROJECT, 2);
        } finally {
            deleteTree(GRADLE_PROJECT.resolve(".quill"));
        }
    }

    private void assertToolsListAndCall(
            List<String> launcher, Path projectRoot, int minimumBeans) throws Exception {
        List<String> command = new ArrayList<>(launcher);
        command.add("--mcp");
        command.add("--project");
        command.add(projectRoot.toString());

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false);
        Process proc = pb.start();

        try (BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
             BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {

            sendRequest(stdin, 1, "initialize", """
                    {"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0.1"}}""");
            JsonNode initResp = readResponse(stdout, 1);
            assertNotNull(initResp.get("result"), "initialize should return a result");
            assertEquals("quill", initResp.get("result").get("serverInfo").get("name").asText());
            assertEquals(org.treblereel.mcp.QuillTopCommand.version(),
                    initResp.get("result").get("serverInfo").get("version").asText());

            sendNotification(stdin, "notifications/initialized", "{}");

            sendRequest(stdin, 2, "tools/list", "{}");
            JsonNode listResp = readResponse(stdout, 2);
            JsonNode tools = listResp.get("result").get("tools");
            assertNotNull(tools, "tools/list should return tools array");

            boolean hasListBeans = false;
            boolean hasGetDeps = false;
            boolean hasListIps = false;
            for (JsonNode tool : tools) {
                String name = tool.get("name").asText();
                if ("list_beans".equals(name) || "list_cdi_beans".equals(name)) hasListBeans = true;
                if ("get_dependencies".equals(name)) hasGetDeps = true;
                if ("list_injection_points".equals(name)) hasListIps = true;
            }
            assertTrue(hasListBeans, "Should include list_beans or list_cdi_beans tool");
            assertTrue(hasGetDeps, "Should include get_dependencies tool");
            assertTrue(hasListIps, "Should include list_injection_points tool");

            sendRequest(stdin, 3, "tools/call",
                    "{\"name\":\"list_cdi_beans\",\"arguments\":{}}");
            JsonNode callResp = readResponse(stdout, 3);
            assertNotNull(callResp.get("result"), "tools/call should return a result");
            JsonNode content = callResp.get("result").get("content");
            assertNotNull(content, "Result should have content array");
            String text = content.get(0).get("text").asText();
            JsonNode beansResult = JSON.readTree(text);
            assertTrue(beansResult.get("total").asInt() >= minimumBeans,
                    "Should find at least " + minimumBeans + " beans");
            assertTrue(beansResult.has("_meta"), "Response should contain _meta envelope");

            sendRequest(stdin, 4, "tools/call",
                    "{\"name\":\"list_cdi_beans\",\"arguments\":{\"unexpected\":true}}");
            JsonNode invalidCall = readResponse(stdout, 4).get("result");
            assertNotNull(invalidCall, "Invalid tool input should return a tool result");
            assertTrue(invalidCall.get("isError").asBoolean());
            assertTrue(invalidCall.get("content").get(0).get("text").asText()
                    .contains("Unknown argument"));
        } finally {
            proc.destroyForcibly();
            proc.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private void prepareGradleProject() throws Exception {
        assertTrue(Files.isRegularFile(GRADLE_PROJECT.resolve("build.gradle")));
        Process probe = new ProcessBuilder(BuildSystem.GRADLE.command(GRADLE_PROJECT, "--version"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0,
                "Gradle executable is required for the Gradle integration test");
        Process clean = new ProcessBuilder(BuildSystem.GRADLE.command(
                GRADLE_PROJECT, "clean", "--quiet"))
                .directory(GRADLE_PROJECT.toFile())
                .inheritIO()
                .start();
        assertEquals(0, clean.waitFor(), "Gradle clean must succeed");
        deleteTree(GRADLE_PROJECT.resolve(".quill"));

        InitCommand command = new InitCommand();
        command.projectPath = GRADLE_PROJECT;
        command.indexOnly = true;
        command.run();
        assertNotNull(ProjectInitializer.findDbForHead(GRADLE_PROJECT));
        assertTrue(Files.isRegularFile(GRADLE_PROJECT.resolve("build/quill-classpath.txt")));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    @Test
    void nativeMcpReturnsStructuredErrors() throws Exception {
        Path nativeImage = resolveNativeImage();
        Assumptions.assumeTrue(Files.isExecutable(nativeImage),
                "Skipping: native image not found (build with -Pnative)");

        Path brokenProject = Files.createDirectories(tempDir.resolve("broken-native-project"));
        Files.createFile(brokenProject.resolve("pom.xml"));
        Path quillDir = Files.createDirectories(brokenProject.resolve(".quill"));
        Files.writeString(quillDir.resolve("nocommit.db"), "not a sqlite database");

        Process proc = new ProcessBuilder(nativeImage.toString(), "--mcp", "--project",
                brokenProject.toString())
                .directory(PROJECT_ROOT.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        try (BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
             BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            sendRequest(stdin, 1, "initialize", """
                    {"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"native-test","version":"1"}}""");
            assertNotNull(readResponse(stdout, 1).get("result"));
            sendNotification(stdin, "notifications/initialized", "{}");

            sendRequest(stdin, 2, "quill/unknown-method", "{}");
            JsonNode protocolError = readResponse(stdout, 2).get("error");
            assertNotNull(protocolError, "Unknown method must return a JSON-RPC error");
            assertTrue(protocolError.has("code"), "Error must contain code: " + protocolError);
            assertTrue(protocolError.hasNonNull("message"), "Error must contain message: " + protocolError);

            sendRequest(stdin, 3, "tools/call",
                    "{\"name\":\"get_overview\",\"arguments\":{}}");
            JsonNode toolResponse = readResponse(stdout, 3);
            assertNotNull(toolResponse.get("result"), "Broken index should be a tool result, not a transport failure");
            String text = toolResponse.get("result").get("content").get(0).get("text").asText();
            assertTrue(text.contains("broken-native-project"), text);
            assertTrue(text.contains("uninitialized"), text);
        } finally {
            proc.getOutputStream().close();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly();
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
            if (!stdout.ready()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for response id=" + expectedId, e);
                }
                continue;
            }
            String line = stdout.readLine();
            if (line == null) throw new IOException("Process stdout closed unexpectedly");
            line = line.trim();
            if (line.isEmpty()) continue;
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (Exception e) {
                throw new IOException(
                        "Non-JSON data on stdout (MCP transport violation): " + line);
            }
            if (node.has("id") && node.get("id").asInt() == expectedId) {
                return node;
            }
        }
        throw new IOException("Timed out waiting for response id=" + expectedId);
    }

    private Path resolveAppJar() throws IOException {
        Path target = PROJECT_ROOT.getParent().getParent().resolve("quill-app/target");
        if (!Files.isDirectory(target)) return target.resolve("missing-all.jar");
        try (var files = Files.list(target)) {
            return files.filter(path -> path.getFileName().toString().matches("quill-app-.+-all\\.jar"))
                    .findFirst().orElse(target.resolve("missing-all.jar"));
        }
    }

    private Path resolveNativeImage() {
        String configured = System.getProperty("native.image.path");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        return PROJECT_ROOT.getParent().getParent().resolve(
                BuildSystem.isWindows() ? "quill-app/target/quill.exe" : "quill-app/target/quill");
    }
}
