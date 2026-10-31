package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpStartupTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void handshakeAndCatalogDoNotWaitForIndexAndDisconnectCancelsPreparation() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var registry = new ProjectRegistry(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) {
                interrupted.countDown(); Thread.currentThread().interrupt();
                throw new IllegalStateException("Preparation cancelled", stopped);
            }
            throw new AssertionError();
        });
        var serverInput = new PipedInputStream(65536);
        var clientOutput = new PipedOutputStream(serverInput);
        var clientInput = new PipedInputStream(65536);
        var serverOutput = new PipedOutputStream(clientInput);
        var failure = new AtomicReference<Throwable>();
        Thread server = Thread.startVirtualThread(() -> {
            try { McpStdioServer.start(registry, serverInput, serverOutput, McpToolProfile.parse("router")); }
            catch (Throwable error) { failure.set(error); }
        });
        try (var client = new BufferedWriter(new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8));
                var responses = new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));
                var reads = Executors.newVirtualThreadPerTaskExecutor()) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            client.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"startup-test\",\"version\":\"1\"}}}\n");
            client.flush();
            JsonNode initialized = read(responses, reads);
            assertEquals(1, initialized.path("id").asInt());
            assertEquals("quill", initialized.path("result").path("serverInfo").path("name").asText());
            client.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
            client.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n");
            client.flush();
            assertFalse(read(responses, reads).path("result").path("tools").isEmpty());
            client.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_overview\",\"arguments\":{}}}\n");
            client.flush();
            JsonNode pending = read(responses, reads).path("result").path("structuredContent");
            assertEquals("refresh_pending", pending.path("status").asText(), pending.toString());
            assertEquals("wait", pending.path("action").asText());
            assertEquals("discovering_projects", pending.path("stage").asText());
            assertTrue(pending.path("elapsed_ms").asLong() > 0, pending.toString());
            assertFalse(pending.path("previous_generation_available").asBoolean());
            JsonNode build = JSON.readTree(new QuillTools(registry).get_build_status(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));
            assertEquals("refresh_pending", build.path("status").asText(), build.toString());
            client.close();
            server.join(Duration.ofSeconds(5));
            assertFalse(server.isAlive(), "Disconnect must cancel background startup");
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertThrows(IllegalStateException.class, registry::prewarmAsync);
        } finally {
            clientOutput.close(); registry.close(); server.interrupt(); server.join(Duration.ofSeconds(5));
        }
    }

    private JsonNode read(BufferedReader reader, ExecutorService executor) throws Exception {
        var line = executor.submit(reader::readLine);
        try { return JSON.readTree(line.get(5, TimeUnit.SECONDS)); }
        finally { line.cancel(true); }
    }
}
