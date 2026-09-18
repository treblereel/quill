package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class McpToolCatalogTest {

    private final Scheduler workers = Schedulers.newBoundedElastic(1, 4, "timeout-test");
    private final Scheduler responses = Schedulers.newSingle("timeout-response-test");

    @AfterEach
    void disposeSchedulers() {
        workers.dispose();
        responses.dispose();
    }

    @Test
    void timedOutToolReturnsExplicitErrorAndInterruptsWorker() throws Exception {
        BlockingTools tools = new BlockingTools();
        AsyncToolSpecification specification = specification(tools, Duration.ofMillis(100));

        McpSchema.CallToolResult result = specification.callHandler()
                .apply(null, new McpSchema.CallToolRequest("block", Map.of(), Map.of()))
                .block(Duration.ofSeconds(2));

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(((McpSchema.TextContent) result.content().getFirst()).text()
                .contains("Tool timed out"));
        assertTrue(tools.interrupted.await(1, TimeUnit.SECONDS),
                "Timeout must interrupt the blocking worker");

        AsyncToolSpecification quick = specification(
                tools, Duration.ofSeconds(1), "quick");
        McpSchema.CallToolResult next = quick.callHandler()
                .apply(null, new McpSchema.CallToolRequest("quick", Map.of(), Map.of()))
                .block(Duration.ofSeconds(2));
        assertEquals("completed", ((McpSchema.TextContent) next.content().getFirst()).text(),
                "A timed-out call must release its worker slot");
    }

    @Test
    void cancellingClientSubscriptionInterruptsWorker() throws Exception {
        BlockingTools tools = new BlockingTools();
        AsyncToolSpecification specification = specification(tools, Duration.ofSeconds(30));
        Disposable request = specification.callHandler()
                .apply(null, new McpSchema.CallToolRequest("block", Map.of(), Map.of()))
                .subscribe();
        assertTrue(tools.started.await(1, TimeUnit.SECONDS));

        request.dispose();

        assertTrue(tools.interrupted.await(1, TimeUnit.SECONDS),
                "Disconnect/cancellation must interrupt the blocking worker");
    }

    @Test
    void requestTimeoutUsesPositiveSecondsAndSafeFallback() {
        Duration fallback = Duration.ofSeconds(30);

        assertEquals(Duration.ofSeconds(7),
                McpStdioServer.positiveDurationValue("7", fallback));
        assertEquals(fallback, McpStdioServer.positiveDurationValue("0", fallback));
        assertEquals(fallback, McpStdioServer.positiveDurationValue("invalid", fallback));
    }

    @Test
    void rejectsMissingRequiredArgumentWithoutInvokingTool() {
        ValidationTools tools = new ValidationTools();
        McpSchema.CallToolResult result = call(tools, "validate", Map.of("enabled", true));

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(text(result).contains("Missing required argument: target"));
        assertEquals(0, tools.invocations);
    }

    @Test
    void rejectsUnknownAndWronglyTypedArguments() {
        ValidationTools tools = new ValidationTools();

        McpSchema.CallToolResult unknown = call(tools, "validate",
                Map.of("target", "OrderService", "surprise", true));
        assertTrue(Boolean.TRUE.equals(unknown.isError()));
        assertTrue(text(unknown).contains("Unknown argument: surprise"));

        McpSchema.CallToolResult wrongBoolean = call(tools, "validate",
                Map.of("target", "OrderService", "enabled", "yes"));
        assertTrue(Boolean.TRUE.equals(wrongBoolean.isError()));
        assertTrue(text(wrongBoolean).contains("Expected boolean"));

        McpSchema.CallToolResult fractionalInteger = call(tools, "validate",
                Map.of("target", "OrderService", "limit", 2.5));
        assertTrue(Boolean.TRUE.equals(fractionalInteger.isError()));
        assertTrue(text(fractionalInteger).contains("Expected integer"));
        assertEquals(0, tools.invocations);
    }

    @Test
    void domainErrorIsMarkedAsMcpToolErrorAndNextRequestStillSucceeds() {
        ValidationTools tools = new ValidationTools();

        McpSchema.CallToolResult failed = call(tools, "validate",
                Map.of("target", "missing"));
        assertTrue(Boolean.TRUE.equals(failed.isError()));
        assertEquals("{\"error\":\"target not found\"}", text(failed));

        McpSchema.CallToolResult successful = call(tools, "validate",
                Map.of("target", "OrderService", "enabled", true, "limit", 10));
        assertTrue(!Boolean.TRUE.equals(successful.isError()));
        assertEquals("{\"target\":\"OrderService\"}", text(successful));
        assertEquals(2, tools.invocations);
    }

    @Test
    void booleanToolArgumentIsAdvertisedAsBoolean() {
        ValidationTools tools = new ValidationTools();
        AsyncToolSpecification specification = McpToolCatalog.create(
                        tools, ValidationTools.class, workers, responses, Duration.ofSeconds(1))
                .getFirst();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) specification.tool()
                .inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> enabled = (Map<String, Object>) properties.get("enabled");
        assertEquals("boolean", enabled.get("type"));
    }

    @Test
    void listToolArgumentIsAdvertisedAsStringArray() {
        AsyncToolSpecification specification = McpToolCatalog.create(
                        new QuillTools(new ProjectRegistry()), workers, responses,
                        Duration.ofSeconds(1))
                .stream().filter(candidate -> candidate.tool().name().equals("resolve_entities"))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) specification.tool()
                .inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> targets = (Map<String, Object>) properties.get("targets");
        assertEquals("array", targets.get("type"));
        assertEquals(Map.of("type", "string"), targets.get("items"));
    }

    @Test
    void structuredToolAdvertisesOutputSchemaAndReturnsJsonWithTextFallback() throws Exception {
        StructuredTools tools = new StructuredTools();
        AsyncToolSpecification specification = McpToolCatalog.create(
                        tools, StructuredTools.class, workers, responses, Duration.ofSeconds(1))
                .getFirst();

        assertEquals("object", specification.tool().outputSchema().get("type"));
        assertEquals(true, specification.tool().outputSchema().get("additionalProperties"));

        McpSchema.CallToolResult result = specification.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        "structured", Map.of("target", "OrderService"), Map.of()))
                .block(Duration.ofSeconds(2));
        assertTrue(result.content().isEmpty());
        JsonNode structured = (JsonNode) result.structuredContent();
        assertEquals("OrderService", structured.path("target").asText());

        McpSchema.CallToolResult invalid = specification.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        "structured", Map.of("unexpected", true), Map.of()))
                .block(Duration.ofSeconds(2));
        assertTrue(Boolean.TRUE.equals(invalid.isError()));
        assertEquals("Unknown argument: unexpected",
                ((JsonNode) invalid.structuredContent()).path("error").asText());
    }

    @Test
    void everyQuillToolAdvertisesStructuredObjectOutput() {
        QuillTools tools = new QuillTools(new ProjectRegistry());

        for (AsyncToolSpecification specification : McpToolCatalog.create(
                tools, workers, responses, Duration.ofSeconds(1))) {
            assertEquals("object", specification.tool().outputSchema().get("type"),
                    specification.tool().name());
        }
    }

    @Test
    void quillCatalogStaysCompact() {
        var tools = McpToolCatalog.create(
                new QuillTools(new ProjectRegistry()), workers, responses,
                Duration.ofSeconds(1));
        int characters = tools.stream()
                .mapToInt(specification -> specification.tool().description().length()
                        + specification.tool().inputSchema().toString().length())
                .sum();
        int averageCharacters = characters / tools.size();
        assertTrue(characters < 21_000 && averageCharacters < 450,
                "catalog characters: " + characters + ", average: " + averageCharacters);
    }

    @Test
    void toolProfilesExposeSmallComposableCatalogs() {
        QuillTools quill = new QuillTools(new ProjectRegistry());
        var full = McpToolCatalog.create(
                quill, workers, responses, Duration.ofSeconds(1));
        var core = McpToolCatalog.create(
                quill, workers, responses, Duration.ofSeconds(1),
                McpToolProfile.parse("core"));
        var combined = McpToolCatalog.create(
                quill, workers, responses, Duration.ofSeconds(1),
                McpToolProfile.parse("di,git"));

        assertTrue(core.size() < full.size() / 2, core::toString);
        assertTrue(core.stream().anyMatch(tool -> tool.tool().name().equals("get_overview")));
        assertTrue(core.stream().noneMatch(tool -> tool.tool().name().equals("list_beans")));
        assertTrue(combined.stream().anyMatch(tool -> tool.tool().name().equals("list_beans")));
        assertTrue(combined.stream().anyMatch(
                tool -> tool.tool().name().equals("find_git_hotspots")));
        assertThrows(IllegalArgumentException.class, () -> McpToolProfile.parse("unknown"));
    }

    @Test
    void routerProfileExposesThreeToolsAndDiscoversHiddenSchemas() {
        QuillTools quill = new QuillTools(new ProjectRegistry());
        var router = McpToolCatalog.create(
                quill, workers, responses, Duration.ofSeconds(1),
                McpToolProfile.parse("router"));

        assertEquals(Set.of("get_overview", "search_tools", "execute_tool"),
                router.stream().map(tool -> tool.tool().name())
                        .collect(java.util.stream.Collectors.toSet()));
        AsyncToolSpecification search = router.stream()
                .filter(tool -> tool.tool().name().equals("search_tools"))
                .findFirst().orElseThrow();
        McpSchema.CallToolResult result = search.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        "search_tools", Map.of("query", "implementations"), Map.of()))
                .block(Duration.ofSeconds(2));
        JsonNode structured = (JsonNode) result.structuredContent();
        assertEquals("find_implementations",
                structured.path("tools").get(0).path("name").asText());
        assertEquals("object",
                structured.path("tools").get(0).path("input_schema").path("type").asText());
    }

    @Test
    void routerValidatesDynamicToolArgumentsAndKeepsCatalogSmall() {
        QuillTools quill = new QuillTools(new ProjectRegistry());
        var full = McpToolCatalog.create(
                quill, workers, responses, Duration.ofSeconds(1));
        var router = McpToolCatalog.create(
                quill, workers, responses, Duration.ofSeconds(1),
                McpToolProfile.parse("router"));
        int fullCharacters = catalogCharacters(full);
        int routerCharacters = catalogCharacters(router);
        assertTrue(routerCharacters < fullCharacters / 5,
                () -> "router=" + routerCharacters + ", full=" + fullCharacters);

        AsyncToolSpecification execute = router.stream()
                .filter(tool -> tool.tool().name().equals("execute_tool"))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) execute.tool()
                .inputSchema().get("properties");
        assertEquals("object", ((Map<?, ?>) properties.get("arguments")).get("type"));

        McpSchema.CallToolResult result = execute.callHandler()
                .apply(null, new McpSchema.CallToolRequest("execute_tool", Map.of(
                        "name", "search_classes", "arguments", Map.of()), Map.of()))
                .block(Duration.ofSeconds(2));
        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertEquals("Missing required argument: pattern",
                ((JsonNode) result.structuredContent()).path("error").asText());
        assertThrows(IllegalArgumentException.class,
                () -> McpToolProfile.parse("router,git"));
    }

    @Test
    void omitsRepeatedDescriptionsForSelfDescribingArguments() {
        AsyncToolSpecification configuration = McpToolCatalog.create(
                        new QuillTools(new ProjectRegistry()), workers, responses,
                        Duration.ofSeconds(1)).stream()
                .filter(candidate -> candidate.tool().name()
                        .equals("find_configuration_references"))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) configuration.tool()
                .inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) properties.get("project");
        @SuppressWarnings("unchecked")
        Map<String, Object> key = (Map<String, Object>) properties.get("key");

        assertNull(project.get("description"));
        assertTrue(key.get("description").toString().contains("wildcard"));
    }

    @Test
    void implementationDiscoveryDoesNotExposeAmbiguousOccurrenceFilters() {
        AsyncToolSpecification implementations = McpToolCatalog.create(
                        new QuillTools(new ProjectRegistry()), workers, responses,
                        Duration.ofSeconds(1)).stream()
                .filter(candidate -> candidate.tool().name().equals("find_implementations"))
                .findFirst().orElseThrow();

        String schema = implementations.tool().inputSchema().toString();
        assertTrue(!schema.contains("\"module\""), schema);
        assertTrue(!schema.contains("\"source_set\""), schema);
    }

    @Test
    void unconfiguredQuillProjectIsAnMcpToolError() {
        QuillTools tools = new QuillTools(new ProjectRegistry());
        AsyncToolSpecification overview = McpToolCatalog.create(
                        tools, workers, responses, Duration.ofSeconds(1))
                .stream().filter(candidate -> candidate.tool().name().equals("get_overview"))
                .findFirst().orElseThrow();

        McpSchema.CallToolResult result = overview.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        "get_overview", Map.of(), Map.of()))
                .block(Duration.ofSeconds(2));

        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(result.content().isEmpty());
        assertTrue(((JsonNode) result.structuredContent()).path("error").asText()
                .contains("No projects configured"));
    }

    @Test
    void stdioServerStopsAndCancelsActiveToolWhenClientDisconnects() throws Exception {
        BlockingTools tools = new BlockingTools();
        PipedInputStream serverInput = new PipedInputStream();
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        ByteArrayOutputStream serverOutput = new ByteArrayOutputStream();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread server = Thread.startVirtualThread(() -> {
            try {
                McpStdioServer.start(tools, BlockingTools.class, serverInput, serverOutput,
                        Duration.ofSeconds(30));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        try (BufferedWriter client = new BufferedWriter(new OutputStreamWriter(
                clientOutput, StandardCharsets.UTF_8))) {
            client.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"disconnect-test\",\"version\":\"1\"}}}");
            client.newLine();
            client.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\","
                    + "\"params\":{}}");
            client.newLine();
            client.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"block\",\"arguments\":{}}}");
            client.newLine();
            client.flush();
            assertTrue(tools.started.await(2, TimeUnit.SECONDS));
        }

        server.join(TimeUnit.SECONDS.toMillis(2));

        assertTrue(!server.isAlive(), "Server must stop promptly after client EOF");
        assertTrue(tools.interrupted.await(1, TimeUnit.SECONDS),
                "Server shutdown must interrupt the active tool");
        assertNull(failure.get(), () -> "Server shutdown failed: " + failure.get());
    }

    private AsyncToolSpecification specification(BlockingTools tools, Duration timeout) {
        return specification(tools, timeout, "block");
    }

    private AsyncToolSpecification specification(
            BlockingTools tools, Duration timeout, String name) {
        return McpToolCatalog.create(
                        tools, BlockingTools.class, workers, responses, timeout)
                .stream()
                .filter(candidate -> candidate.tool().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private McpSchema.CallToolResult call(
            ValidationTools tools, String name, Map<String, Object> arguments) {
        AsyncToolSpecification specification = McpToolCatalog.create(
                        tools, ValidationTools.class, workers, responses, Duration.ofSeconds(1))
                .stream().filter(candidate -> candidate.tool().name().equals(name))
                .findFirst().orElseThrow();
        return specification.callHandler()
                .apply(null, new McpSchema.CallToolRequest(name, arguments, Map.of()))
                .block(Duration.ofSeconds(2));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static int catalogCharacters(java.util.List<AsyncToolSpecification> tools) {
        return tools.stream()
                .mapToInt(specification -> specification.tool().description().length()
                        + specification.tool().inputSchema().toString().length())
                .sum();
    }

    static final class BlockingTools {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);

        @Tool(description = "Blocks until cancelled")
        public String block() {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                return "completed";
            } catch (InterruptedException interruptedException) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return "cancelled";
            }
        }

        @Tool(description = "Completes immediately")
        public String quick() {
            return "completed";
        }
    }

    static final class ValidationTools {
        int invocations;

        @Tool(description = "Validates arguments")
        public String validate(
                @ToolArg(description = "Required target") String target,
                @ToolArg(description = "Optional switch") Optional<Boolean> enabled,
                @ToolArg(description = "Optional limit") Optional<Integer> limit) {
            invocations++;
            if (target.equals("missing")) return "{\"error\":\"target not found\"}";
            return "{\"target\":\"" + target + "\"}";
        }
    }

    static final class StructuredTools {
        @Tool(description = "Returns structured data", structured = true)
        public String structured(@ToolArg(description = "Required target") String target) {
            return "{\"target\":\"" + target + "\"}";
        }
    }
}
