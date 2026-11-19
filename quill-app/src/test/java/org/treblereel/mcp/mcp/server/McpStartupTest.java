package org.treblereel.mcp.mcp.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.mcp.project.ProjectRegistry;
import org.treblereel.mcp.mcp.tools.QuillTools;

class McpStartupTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void handshakeAndCatalogDoNotWaitForIndexAndDisconnectCancelsPreparation() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var registry =
                new ProjectRegistry(
                        () -> {
                            entered.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException stopped) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("Preparation cancelled", stopped);
                            }
                            throw new AssertionError();
                        });
        var serverInput = new PipedInputStream(65536);
        var clientOutput = new PipedOutputStream(serverInput);
        var clientInput = new PipedInputStream(65536);
        var serverOutput = new PipedOutputStream(clientInput);
        var failure = new AtomicReference<Throwable>();
        Thread server =
                Thread.startVirtualThread(
                        () -> {
                            try {
                                McpStdioServer.start(
                                        registry,
                                        serverInput,
                                        serverOutput,
                                        McpToolProfile.parse("router"));
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        try (var client =
                        new BufferedWriter(
                                new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8));
                var responses =
                        new BufferedReader(
                                new InputStreamReader(clientInput, StandardCharsets.UTF_8));
                var reads = Executors.newVirtualThreadPerTaskExecutor()) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            client.write(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"startup-test\",\"version\":\"1\"}}}\n");
            client.flush();
            JsonNode initialized = read(responses, reads);
            assertEquals(1, initialized.path("id").asInt());
            assertEquals(
                    "quill", initialized.path("result").path("serverInfo").path("name").asText());
            client.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
            client.write(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n");
            client.flush();
            assertFalse(read(responses, reads).path("result").path("tools").isEmpty());
            client.write(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_overview\",\"arguments\":{}}}\n");
            client.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (registry.waitingRequests() == 0 && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(
                    1,
                    registry.waitingRequests(),
                    "Overview waits without occupying the transport");
            // Readiness probes race catalog requests during client startup.
            var collected =
                    reads.submit(
                            () -> {
                                var ids = new java.util.HashSet<Integer>();
                                for (int i = 0; i < 200; i++)
                                    ids.add(JSON.readTree(responses.readLine()).path("id").asInt());
                                return ids;
                            });
            for (int i = 0; i < 200; i++) {
                String method = i % 2 == 0 ? "tools/list" : "tools/call";
                String params =
                        i % 2 == 0
                                ? "{}"
                                : "{\"name\":\"search_tools\",\"arguments\":{\"query\":\"get_overview\",\"limit\":1}}";
                client.write(
                        "{\"jsonrpc\":\"2.0\",\"id\":"
                                + (100 + i)
                                + ",\"method\":\""
                                + method
                                + "\",\"params\":"
                                + params
                                + "}\n");
                client.flush();
            }
            assertEquals(
                    200,
                    collected.get(15, TimeUnit.SECONDS).size(),
                    "Every startup request needs a response");
            client.write(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3,\"reason\":\"test cancellation\"}}\n");
            client.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (registry.waitingRequests() != 0 && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(
                    0, registry.waitingRequests(), "Wire cancellation removes the waiting request");
            client.write(
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_overview\",\"arguments\":{}}}\n");
            client.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (registry.waitingRequests() == 0 && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(
                    1,
                    registry.waitingRequests(),
                    "Cancelling one request does not stop preparation");
            JsonNode build =
                    new QuillTools(registry)
                            .get_build_status(
                                    java.util.Optional.empty(),
                                    java.util.Optional.empty(),
                                    java.util.Optional.empty(),
                                    java.util.Optional.empty(),
                                    java.util.Optional.empty(),
                                    java.util.Optional.empty());
            assertEquals("refresh_pending", build.path("status").asText(), build.toString());
            client.close();
            server.join(Duration.ofSeconds(5));
            assertFalse(server.isAlive(), "Disconnect must cancel background startup");
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(0, registry.waitingRequests());
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertThrows(IllegalStateException.class, registry::prewarmAsync);
        } finally {
            clientOutput.close();
            registry.close();
            server.interrupt();
            server.join(Duration.ofSeconds(5));
        }
    }

    private JsonNode read(BufferedReader reader, ExecutorService executor) throws Exception {
        var line = executor.submit(reader::readLine);
        try {
            return JSON.readTree(line.get(5, TimeUnit.SECONDS));
        } finally {
            line.cancel(true);
        }
    }
}
