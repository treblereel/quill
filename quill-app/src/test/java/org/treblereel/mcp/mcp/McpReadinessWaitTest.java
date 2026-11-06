package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.workspace.ProjectScope;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.scheduler.Schedulers;

class McpReadinessWaitTest {
    @Test void readinessDoesNotOccupyTheOnlyToolWorkerAndCancellationIsIndependent() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var registry = delayed(release, entered)) {
            registry.prewarmAsync();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var workers = Schedulers.newBoundedElastic(1, 10, "ready-test");
            var responses = Schedulers.newSingle("ready-response");
            try {
                var catalog = McpToolCatalog.create(new QuillTools(registry), workers, responses,
                        Duration.ofSeconds(5), McpToolProfile.parse("router"));
                var overview = catalog.stream().filter(t -> t.tool().name().equals("get_overview")).findFirst().orElseThrow();
                var request = new McpSchema.CallToolRequest("get_overview", Map.of(), Map.of());
                var cancelled = overview.callHandler().apply(null, request).toFuture();
                var waiting = overview.callHandler().apply(null, request).toFuture();
                assertEquals(2, registry.waitingRequests());
                cancelled.cancel(true);
                assertEquals(1, registry.waitingRequests());
                var search = catalog.stream().filter(t -> t.tool().name().equals("search_tools")).findFirst().orElseThrow();
                assertNotNull(search.callHandler().apply(null, new McpSchema.CallToolRequest("search_tools",
                        Map.of("query", "find_usages", "limit", 1), Map.of())).block(Duration.ofSeconds(1)));
                assertFalse(waiting.isDone());
                release.countDown();
                var result = waiting.get(3, TimeUnit.SECONDS);
                assertFalse(String.valueOf(result.structuredContent()).contains("REFRESH_PENDING"));
                assertEquals(0, registry.waitingRequests());
            } finally { release.countDown(); workers.dispose(); responses.dispose(); }
        }
    }

    @Test void timeoutDetachesOnlyTheRequestAndPreparationCanStillFinish() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var registry = delayed(release, entered)) {
            var preparation = registry.prewarmAsync();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var workers = Schedulers.newBoundedElastic(1, 10, "ready-timeout");
            var responses = Schedulers.newSingle("ready-timeout-response");
            try {
                var overview = McpToolCatalog.create(new QuillTools(registry), workers, responses, Duration.ofMillis(150), McpToolProfile.parse("router"))
                        .stream().filter(t -> t.tool().name().equals("get_overview")).findFirst().orElseThrow();
                var result = overview.callHandler().apply(null, new McpSchema.CallToolRequest("get_overview", Map.of(), Map.of()))
                        .block(Duration.ofSeconds(2));
                assertTrue(Boolean.TRUE.equals(result.isError()));
                assertTrue(String.valueOf(result.structuredContent()).contains("INDEX_REFRESH_TIMEOUT"));
                // A client that shows only text must still see why the call failed.
                String text = ((McpSchema.TextContent) result.content().getFirst()).text();
                assertTrue(text.startsWith("INDEX_REFRESH_TIMEOUT: "), text);
                assertEquals(0, registry.waitingRequests());
                assertFalse(preparation.isDone());
                release.countDown();
                preparation.get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); workers.dispose(); responses.dispose(); }
        }
    }

    private ProjectRegistry delayed(CountDownLatch release, CountDownLatch entered) {
        return new ProjectRegistry(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            return new ProjectScope.Snapshot(1, List.of());
        });
    }
}
