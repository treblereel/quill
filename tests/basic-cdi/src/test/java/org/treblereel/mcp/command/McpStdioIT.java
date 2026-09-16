package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.QuillDatabase;

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
    void mcpServesStaleGenerationWhileCurrentHeadIsNotIndexed() throws Exception {
        Path appJar = resolveAppJar();
        Assumptions.assumeTrue(Files.exists(appJar));

        Path project = Files.createDirectories(tempDir.resolve("stale-project"));
        String indexedCommit;
        String branch;
        try (Git git = Git.init().setDirectory(project.toFile()).call()) {
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            git.add().addFilepattern("pom.xml").call();
            indexedCommit = git.commit().setMessage("indexed")
                    .setAuthor("Test", "test@example.com").setSign(false).call().getName();
            branch = git.getRepository().getBranch();

            Path quillDir = Files.createDirectories(project.resolve(".quill"));
            String indexId = indexedCommit + "-generation";
            var jdbi = QuillDatabase.create(quillDir.resolve(indexId + ".db"));
            jdbi.useHandle(handle -> {
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "index_id", indexId);
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "indexed_at", "2026-09-15T00:00:00Z");
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "last_commit", indexedCommit);
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "project_root", project.toString());
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "framework", "Plain");
            });
            JSON.writerWithDefaultPrettyPrinter().writeValue(
                    quillDir.resolve("refs.json").toFile(),
                    Map.of("@head:" + indexedCommit, indexId, branch, indexId));

            Files.writeString(project.resolve("README.md"), "new head\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("head moved")
                    .setAuthor("Test", "test@example.com").setSign(false).call();
        }

        Process process = new ProcessBuilder("java", "-jar", appJar.toString(),
                "--mcp", "--project", project.toString())
                .directory(project.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try (BufferedWriter input = new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream()));
                BufferedReader output = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
            sendRequest(input, 1, "initialize", """
                    {"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"stale-test","version":"1"}}""");
            assertNotNull(readResponse(output, 1).get("result"));
            sendNotification(input, "notifications/initialized", "{}");
            sendRequest(input, 2, "tools/call",
                    "{\"name\":\"get_overview\",\"arguments\":{}}");

            JsonNode overview = toolText(readResponse(output, 2));
            assertEquals("Plain", overview.path("project").path("framework").asText());
            assertTrue(overview.path("_meta").path("commit_stale").asBoolean());
            assertTrue(overview.path("_meta").path("stale_reasons").toString()
                    .contains("commit_changed_after_index"));
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void mcpStdioHandlesPipelinedToolCalls() throws Exception {
        Path appJar = resolveAppJar();
        Assumptions.assumeTrue(Files.exists(appJar),
                "Skipping: quill executable JAR not found (run 'mvn package -DskipTests' first)");

        assertPipelinedToolCalls(List.of("java", "-jar", appJar.toString()));
    }

    @Test
    void nativeMcpHandlesPipelinedToolCalls() throws Exception {
        Path nativeImage = resolveNativeImage();
        Assumptions.assumeTrue(Files.isExecutable(nativeImage),
                "Skipping: native image not found (build with -Pnative)");

        assertPipelinedToolCalls(List.of(nativeImage.toString()));
    }

    @Test
    void mcpStdioHandlesPipelinedToolCallsAcrossProjects() throws Exception {
        Path appJar = resolveAppJar();
        Assumptions.assumeTrue(Files.exists(appJar));
        prepareGradleProject();
        try {
            assertPipelinedToolCalls(
                    List.of("java", "-jar", appJar.toString()),
                    List.of(PROJECT_ROOT, GRADLE_PROJECT));
        } finally {
            deleteTree(GRADLE_PROJECT.resolve(".quill"));
        }
    }

    @Test
    void mcpStdioReturnsBusyErrorsInsteadOfDroppingBurstRequests() throws Exception {
        Path appJar = resolveAppJar();
        Assumptions.assumeTrue(Files.exists(appJar));

        assertPipelinedToolCalls(
                List.of("java", "-jar", appJar.toString()), List.of(PROJECT_ROOT), 300);
    }

    @Test
    void mcpReadsSurviveRepeatedConcurrentIndexPublications() throws Exception {
        Path appJar = resolveAppJar();
        Assumptions.assumeTrue(Files.exists(appJar));

        assertReadsSurviveRepeatedConcurrentIndexPublications(
                List.of("java", "-jar", appJar.toString()));
    }

    @Test
    void nativeMcpReadsSurviveRepeatedConcurrentIndexPublications() throws Exception {
        Path nativeImage = resolveNativeImage();
        Assumptions.assumeTrue(Files.isExecutable(nativeImage));

        assertReadsSurviveRepeatedConcurrentIndexPublications(List.of(nativeImage.toString()));
    }

    private void assertReadsSurviveRepeatedConcurrentIndexPublications(
            List<String> launcher) throws Exception {
        List<String> command = new ArrayList<>(launcher);
        command.add("--mcp");
        command.add("--project");
        command.add(PROJECT_ROOT.toString());
        Process process = new ProcessBuilder(command)
                .directory(PROJECT_ROOT.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        try (BufferedWriter input = new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream()));
                BufferedReader output = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
            sendRequest(input, 1, "initialize", """
                    {"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"update-test","version":"1"}}""");
            assertNotNull(readResponse(output, 1).get("result"));
            sendNotification(input, "notifications/initialized", "{}");

            sendRequest(input, 2, "tools/call",
                    "{\"name\":\"get_overview\",\"arguments\":{}}");
            String previousIndex = toolText(readResponse(output, 2))
                    .path("_meta").path("index_id").asText();
            Path previousDatabase = ProjectInitializer.findDbForHead(PROJECT_ROOT);

            for (int publication = 0; publication < 7; publication++) {
                Set<Integer> inFlightIds = new LinkedHashSet<>();
                int firstRequestId = 100 + publication * 24;
                for (int id = firstRequestId; id < firstRequestId + 24; id++) {
                    inFlightIds.add(id);
                    writeRequest(input, id, "tools/call",
                            "{\"name\":\"find_git_hotspots\",\"arguments\":{\"limit\":100}}");
                }
                input.flush();

                UpdateCommand update = new UpdateCommand();
                update.projectPath = PROJECT_ROOT;
                update.force = true;
                update.run();
                Path currentDatabase = ProjectInitializer.findDbForHead(PROJECT_ROOT);
                assertNotEquals(previousDatabase, currentDatabase,
                        "Every forced update must publish a new immutable generation");

                Map<Integer, JsonNode> inFlight =
                        readResponses(output, inFlightIds, 30_000);
                assertEquals(inFlightIds, inFlight.keySet());
                assertTrue(inFlight.values().stream().allMatch(response ->
                                response.has("result")
                                        && !response.path("result").path("isError").asBoolean()),
                        inFlight.toString());

                int overviewId = 10_000 + publication;
                sendRequest(input, overviewId, "tools/call",
                        "{\"name\":\"get_overview\",\"arguments\":{}}");
                String currentIndex = toolText(readResponse(output, overviewId))
                        .path("_meta").path("index_id").asText();
                assertNotEquals(previousIndex, currentIndex,
                        "The long-lived MCP process must observe the newly published generation");
                assertEquals(
                        currentDatabase.getFileName().toString().replaceFirst("\\.db$", ""),
                        currentIndex);
                previousIndex = currentIndex;
                previousDatabase = currentDatabase;
            }

            try (var files = Files.list(QUILL_DIR)) {
                List<Path> entries = files.toList();
                assertTrue(entries.stream().noneMatch(path ->
                                path.getFileName().toString().endsWith(".tmp")),
                        "No unpublished temporary database may remain after the soak test");
                assertTrue(entries.stream().filter(path ->
                                path.getFileName().toString().endsWith(".db")).count() <= 5,
                        "Immutable generation cleanup must enforce the LRU limit");
            }
            for (String indexId : ProjectInitializer.readRefs(
                    QUILL_DIR.resolve("refs.json")).values()) {
                assertTrue(Files.isRegularFile(QUILL_DIR.resolve(indexId + ".db")),
                        "Every published ref must resolve to an existing database");
            }
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
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
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stderrReader = new Thread(() -> {
            try {
                proc.getErrorStream().transferTo(stderr);
            } catch (IOException ignored) {
                // The stream is closed when the test terminates the server process.
            }
        }, "quill-test-stderr");
        stderrReader.start();

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
                if ("list_beans".equals(name)) hasListBeans = true;
                if ("get_dependencies".equals(name)) hasGetDeps = true;
                if ("list_injection_points".equals(name)) hasListIps = true;
            }
            assertTrue(hasListBeans, "Should include list_beans tool");
            assertTrue(hasGetDeps, "Should include get_dependencies tool");
            assertTrue(hasListIps, "Should include list_injection_points tool");

            sendRequest(stdin, 3, "tools/call",
                    "{\"name\":\"list_beans\",\"arguments\":{}}");
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
                    "{\"name\":\"list_beans\",\"arguments\":{\"unexpected\":true}}");
            JsonNode invalidCall = readResponse(stdout, 4).get("result");
            assertNotNull(invalidCall, "Invalid tool input should return a tool result");
            assertTrue(invalidCall.get("isError").asBoolean());
            assertTrue(invalidCall.get("content").get(0).get("text").asText()
                    .contains("Unknown argument"));
        } finally {
            proc.destroyForcibly();
            proc.waitFor(5, TimeUnit.SECONDS);
            stderrReader.join(TimeUnit.SECONDS.toMillis(5));
        }

        String stderrText = stderr.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(stderrText.contains("restricted method"), stderrText);
    }

    private void assertPipelinedToolCalls(List<String> launcher) throws Exception {
        assertPipelinedToolCalls(launcher, List.of(PROJECT_ROOT), 0);
    }

    private void assertPipelinedToolCalls(
            List<String> launcher, List<Path> projectRoots) throws Exception {
        assertPipelinedToolCalls(launcher, projectRoots, 0);
    }

    private void assertPipelinedToolCalls(
            List<String> launcher, List<Path> projectRoots, int burstRequests) throws Exception {
        List<String> command = new ArrayList<>(launcher);
        command.add("--mcp");
        for (Path projectRoot : projectRoots) {
            command.add("--project");
            command.add(projectRoot.toString());
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(PROJECT_ROOT.toFile())
                .redirectErrorStream(false);
        if (burstRequests > 0) {
            processBuilder.environment().put("QUILL_MCP_MAX_CONCURRENCY", "1");
            processBuilder.environment().put("QUILL_MCP_MAX_QUEUED_PER_WORKER", "1");
        }
        Process proc = processBuilder.start();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stderrReader = Thread.startVirtualThread(() -> {
            try {
                proc.getErrorStream().transferTo(stderr);
            } catch (IOException ignored) {
                // Process shutdown closes the stream.
            }
        });

        try (BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
             BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            sendRequest(stdin, 1, "initialize", """
                    {"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"pipeline-test","version":"1"}}""");
            assertNotNull(readResponse(stdout, 1).get("result"));
            sendNotification(stdin, "notifications/initialized", "{}");

            Map<Integer, String> requests = new LinkedHashMap<>();
            requests.put(10, "{\"name\":\"search_classes\",\"arguments\":{\"pattern\":\"*Service\",\"limit\":10}}");
            requests.put(11, "{\"name\":\"get_overview\",\"arguments\":{}}");
            requests.put(12, "{\"name\":\"get_dependencies\",\"arguments\":{\"target\":\"GreetingService\"}}");
            requests.put(13, "{\"name\":\"find_git_hotspots\",\"arguments\":{\"limit\":5}}");
            requests.put(14, "{\"name\":\"search_classes\",\"arguments\":{\"unexpected\":true}}");
            requests.put(15, "{\"name\":\"list_beans\",\"arguments\":{\"limit\":5}}");
            requests.put(16, "{\"name\":\"get_recent_changes\",\"arguments\":{\"commits\":3}}");
            requests.put(17, "{\"name\":\"search_classes\",\"arguments\":{\"pattern\":\"*\",\"limit\":5}}");
            if (projectRoots.size() > 1) {
                requests.put(18, "{\"name\":\"search_classes\",\"arguments\":{\"pattern\":\"*\",\"limit\":5,\"project\":\"basic-cdi\"}}");
                requests.put(19, "{\"name\":\"search_classes\",\"arguments\":{\"pattern\":\"*\",\"limit\":5,\"project\":\"gradle-basic\"}}");
            }
            for (int i = 0; i < burstRequests; i++) {
                requests.put(100 + i,
                        "{\"name\":\"find_git_hotspots\",\"arguments\":{\"limit\":5}}");
            }

            for (var request : requests.entrySet()) {
                writeRequest(stdin, request.getKey(), "tools/call", request.getValue());
            }
            stdin.flush();

            Map<Integer, JsonNode> responses = readResponses(
                    stdout, new LinkedHashSet<>(requests.keySet()), 30_000);
            assertEquals(requests.keySet(), responses.keySet(),
                    "Every pipelined request must receive exactly one response");
            for (int id : requests.keySet()) {
                assertNotNull(responses.get(id).get("result"), "Missing result for id=" + id);
            }
            assertTrue(responses.get(14).path("result").path("isError").asBoolean(),
                    "One invalid request must not prevent other responses");
            if (burstRequests > 0) {
                boolean busy = responses.entrySet().stream()
                        .filter(entry -> entry.getKey() >= 100)
                        .map(Map.Entry::getValue)
                        .anyMatch(response -> response.path("result").path("isError").asBoolean()
                                && response.path("result").path("content").get(0)
                                        .path("text").asText().contains("Server busy"));
                assertTrue(busy, "A saturated bounded queue must return an explicit busy error");
            }
        } finally {
            proc.destroyForcibly();
            proc.waitFor(5, TimeUnit.SECONDS);
            stderrReader.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertFalse(stderr.toString().contains("restricted method"), stderr.toString());
    }

    private void prepareGradleProject() throws Exception {
        assertTrue(Files.isRegularFile(GRADLE_PROJECT.resolve("build.gradle")));
        Process probe = new ProcessBuilder(BuildSystem.GRADLE.command(GRADLE_PROJECT, "--version"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0,
                "Gradle executable is required for the Gradle integration test");
        Process clean = new ProcessBuilder(BuildSystem.GRADLE.command(
                GRADLE_PROJECT, "clean", "classes", "--quiet"))
                .directory(GRADLE_PROJECT.toFile())
                .inheritIO()
                .start();
        assertEquals(0, clean.waitFor(), "Gradle fixture build must succeed");
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
        Files.writeString(quillDir.resolve("broken.db"), "not a sqlite database");
        Files.writeString(quillDir.resolve("refs.json"), "{\"@worktree\":\"broken\"}");

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
        writeRequest(stdin, id, method, params);
        stdin.flush();
    }

    private void writeRequest(BufferedWriter stdin, int id, String method, String params)
            throws IOException {
        String msg = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"%s\",\"params\":%s}",
                id, method, params);
        stdin.write(msg);
        stdin.newLine();
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

    private Map<Integer, JsonNode> readResponses(
            BufferedReader stdout, Set<Integer> expectedIds, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Map<Integer, JsonNode> responses = new HashMap<>();
        while (System.currentTimeMillis() < deadline && responses.size() < expectedIds.size()) {
            if (!stdout.ready()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for pipelined responses", e);
                }
                continue;
            }
            String line = stdout.readLine();
            if (line == null) throw new IOException("Process stdout closed unexpectedly");
            line = line.trim();
            if (line.isEmpty()) continue;
            JsonNode response;
            try {
                response = JSON.readTree(line);
            } catch (Exception e) {
                throw new IOException("Non-JSON data on stdout (MCP transport violation): " + line, e);
            }
            if (!response.has("id") || !response.get("id").canConvertToInt()) continue;
            int id = response.get("id").asInt();
            if (!expectedIds.contains(id)) continue;
            if (responses.putIfAbsent(id, response) != null) {
                throw new IOException("Duplicate response id=" + id);
            }
        }
        if (responses.size() != expectedIds.size()) {
            Set<Integer> missing = new LinkedHashSet<>(expectedIds);
            missing.removeAll(responses.keySet());
            throw new IOException("Timed out waiting for pipelined response ids=" + missing);
        }
        Map<Integer, JsonNode> ordered = new LinkedHashMap<>();
        for (int id : expectedIds) ordered.put(id, responses.get(id));
        return ordered;
    }

    private static JsonNode toolText(JsonNode response) throws IOException {
        return JSON.readTree(response.path("result").path("content").get(0)
                .path("text").asText());
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
