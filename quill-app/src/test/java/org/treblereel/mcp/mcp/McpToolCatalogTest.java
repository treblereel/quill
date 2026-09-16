package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
