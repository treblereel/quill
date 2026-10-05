package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicitly executes the project-local stdio launcher; never invokes an AI client. */
final class McpConnectivityProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LINE = 4 * 1024 * 1024;

    private McpConnectivityProbe() {}

    static DoctorCommand.Check inspect(Path root) {
        return inspect(root, Duration.ofSeconds(30));
    }

    static DoctorCommand.Check inspect(Path root, Duration timeout) {
        Process process = null;
        String stage = "configuration";
        var readerThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quill-mcp-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            JsonNode config = JSON.readTree(root.resolve(".mcp.json").toFile())
                    .path("mcpServers").path("quill");
            if (!config.isObject() || !config.path("command").isTextual()
                    || config.path("command").asText().isBlank()
                    || !config.path("type").asText("stdio").equals("stdio")) {
                throw new IOException("Unsupported launcher");
            }
            List<String> argv = new ArrayList<>();
            argv.add(config.path("command").asText());
            JsonNode args = config.path("args");
            if (!args.isMissingNode() && !args.isArray()) throw new IOException("Invalid args");
            for (JsonNode arg : args) {
                if (!arg.isTextual()) throw new IOException("Invalid arg");
                argv.add(arg.asText());
            }
            Path workingDirectory = root;
            JsonNode cwd = config.path("cwd");
            if (!cwd.isMissingNode()) {
                if (!cwd.isTextual() || cwd.asText().isBlank()) throw new IOException("Invalid cwd");
                workingDirectory = root.resolve(cwd.asText()).toAbsolutePath().normalize();
            }
            ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDirectory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            JsonNode env = config.path("env");
            if (!env.isMissingNode() && !env.isObject()) throw new IOException("Invalid env");
            for (var entries = env.fields(); entries.hasNext();) {
                var entry = entries.next();
                if (!entry.getValue().isTextual()) throw new IOException("Invalid env value");
                builder.environment().put(entry.getKey(), entry.getValue().asText());
            }
            stage = "launch";
            process = builder.start();
            long deadline = System.nanoTime() + timeout.toNanos();
            {
                var input = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8));
                var output = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                stage = "initialize";
                send(output, 1, "initialize", JSON.readTree("""
                        {"protocolVersion":"2024-11-05","capabilities":{},
                         "clientInfo":{"name":"quill-connectivity-probe","version":"1"}}
                        """));
                JsonNode initialized = readerThread.submit(() -> response(input, 1))
                        .get(remaining(deadline), TimeUnit.NANOSECONDS);
                if (!"quill".equals(initialized.path("serverInfo").path("name").asText())) {
                    throw new IOException("Not Quill");
                }
                send(output, null, "notifications/initialized", JSON.createObjectNode());
                stage = "tools/list";
                send(output, 2, "tools/list", JSON.createObjectNode());
                JsonNode catalog = readerThread.submit(() -> response(input, 2))
                        .get(remaining(deadline), TimeUnit.NANOSECONDS);
                boolean overview = false;
                for (JsonNode tool : catalog.path("tools")) {
                    overview |= "get_overview".equals(tool.path("name").asText());
                }
                if (!overview) throw new IOException("Missing overview");
                stage = "get_overview";
                send(output, 3, "tools/call", JSON.readTree(
                        "{\"name\":\"get_overview\",\"arguments\":{}}"));
                JsonNode result = readerThread.submit(() -> response(input, 3))
                        .get(remaining(deadline), TimeUnit.NANOSECONDS);
                JsonNode payload = result.path("structuredContent");
                if (!payload.isObject()) {
                    for (JsonNode content : result.path("content")) {
                        if ("text".equals(content.path("type").asText())
                                && !content.path("text").asText().isBlank()) {
                            payload = JSON.readTree(content.path("text").asText());
                            break;
                        }
                    }
                }
                if (result.path("isError").asBoolean() || !payload.isObject()
                        || payload.has("error") || payload.has("error_code")
                        || !usableOverview(payload)) {
                    throw new IOException("Overview unavailable");
                }
                return DoctorCommand.Check.pass("mcp_connectivity",
                        "Project .mcp.json transport verified: initialize, tools/list and get_overview; "
                                + "AI client activation and Codex configuration were not checked");
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // Never expose launcher arguments, environment values, stderr or remote error text.
            return DoctorCommand.Check.error("mcp_connectivity",
                    "Project .mcp.json transport probe failed at " + stage,
                    "Check the Quill stdio launcher and indexed project; repeat the --probe-mcp check "
                            + "from the same project/workspace, or run `quill doctor --probe-mcp` inside a JVM project. "
                            + "This does not check AI client trust or activation");
        } finally {
            if (process != null) {
                var descendants = process.descendants().toList();
                descendants.forEach(ProcessHandle::destroy);
                process.destroy();
                try {
                    if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            }
            readerThread.shutdownNow();
        }
    }

    static void guidance(boolean indexOnly) {
        if (!indexOnly) {
            System.out.println("Initialization finished; client configuration alone does not prove "
                    + "live connectivity or AI client activation. Use --probe-mcp to check the .mcp.json transport. "
                    + "Trust this project in Codex/Claude and open a fresh session to discover Quill tools.");
        }
    }

    private static boolean usableOverview(JsonNode payload) {
        if (payload.path("project").isObject()) return true;
        for (JsonNode project : payload.path("projects")) {
            JsonNode data = project.path("data");
            if (!project.has("error") && data.isObject() && data.path("project").isObject()
                    && !data.has("error") && !data.has("error_code")) return true;
        }
        return false;
    }

    private static long remaining(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Timeout");
        return remaining;
    }

    private static void send(OutputStreamWriter output, Integer id, String method, JsonNode params)
            throws IOException {
        ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0");
        if (id != null) request.put("id", id);
        request.put("method", method).set("params", params);
        output.write(request + "\n");
        output.flush();
    }

    private static JsonNode response(BufferedReader input, int id) throws IOException {
        for (int messages = 0; messages < 128; messages++) {
            StringBuilder line = new StringBuilder();
            int character;
            while ((character = input.read()) != -1 && character != '\n') {
                if (line.length() >= MAX_LINE) throw new IOException("Response too large");
                line.append((char) character);
            }
            if (character == -1 && line.isEmpty()) throw new IOException("Server closed stdout");
            JsonNode response = JSON.readTree(line.toString());
            if (response == null || !"2.0".equals(response.path("jsonrpc").asText())) {
                throw new IOException("Invalid JSON-RPC");
            }
            if (response.path("id").isIntegralNumber() && response.path("id").asInt() == id) {
                if (response.has("error") || !response.has("result")) throw new IOException("RPC failure");
                return response.path("result");
            }
        }
        throw new IOException("Too many notifications");
    }
}
