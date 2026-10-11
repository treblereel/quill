package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KotlinWarmupCoordinatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    @TempDir Path external;
    private ObjectNode request() throws Exception {
        Path source = temp.resolve("src/main/kotlin/Caller.kt");
        Files.createDirectories(source.getParent()); Files.writeString(source, "fun run() = 1");
        var request = JSON.createObjectNode().put("module", temp.toString()).put("source_set", "main")
                .put("file", source.toString()).put("offset", 4);
        request.putArray("sources").add(source.toString()); request.putArray("classpath");
        return request;
    }
    private JsonNode result(JsonNode request) {
        return JSON.createObjectNode().put("status", "resolved").put("version", request.path("version").asInt());
    }
    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(8, TimeUnit.SECONDS), "Expected worker operation did not run"); }
        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
    }
    @Test void startupIsAsyncAndFirstQueryWaitsThenRecollectsInputs() throws Exception {
        var request = request(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var version = new AtomicInteger(); var caller = Thread.currentThread();
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) {
                assertNotSame(caller, Thread.currentThread()); entered.countDown(); await(release);
            }
            return result(r);
        }, Duration.ofHours(1))) {
            Supplier<JsonNode> input = () -> request.deepCopy().put("version", version.get());
            assertTrue(coordinator.prewarm("main", () -> ((ObjectNode) input.get()).put("offset", -1)));
            await(entered); assertFalse(coordinator.prewarm("other", input));
            var pool = Executors.newSingleThreadExecutor();
            try {
                var answer = pool.submit(() -> coordinator.query("main", input, input));
                assertThrows(TimeoutException.class, () -> answer.get(100, TimeUnit.MILLISECONDS));
                version.set(2); release.countDown();
                assertEquals(2, answer.get(8, TimeUnit.SECONDS).path("version").asInt());
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }
    @Test void lateStartupAndClosedCoordinatorCannotDisplaceForegroundOrReadInputs() throws Exception {
        var request = request(); var calls = new AtomicInteger();
        var coordinator = new KotlinWarmupCoordinator(r -> { calls.incrementAndGet(); return result(r); }, Duration.ZERO);
        try {
            coordinator.query("chosen", () -> request, () -> request);
            Supplier<JsonNode> forbidden = () -> { fail("Startup must not read input after foreground selection/close"); return request; };
            assertFalse(coordinator.prewarm("startup", forbidden)); assertEquals(1, calls.get());
            coordinator.close(); assertFalse(coordinator.prewarm("startup", forbidden)); assertEquals(1, calls.get());
        } finally { coordinator.close(); }
    }
    @Test void failedStartupLeavesTheFirstQueryUsable() throws Exception {
        var request = request(); var entered = new CountDownLatch(1);
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) { entered.countDown(); throw new IllegalStateException("cold failure"); }
            return result(r);
        }, Duration.ZERO)) {
            assertTrue(coordinator.prewarm("main", () -> request.deepCopy().put("offset", -1))); await(entered);
            assertEquals("resolved", coordinator.query("main", () -> request, () -> request).path("status").asText());
        }
    }
    @Test void foregroundWaitsForRunningWarmupAndRecollectsCurrentInputs() throws Exception {
        var request = request(); var version = new AtomicInteger();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        Supplier<JsonNode> input = () -> request.deepCopy().put("version", version.get());
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) { entered.countDown(); await(release); }
            return result(r);
        }, Duration.ZERO)) {
            coordinator.query("main", input, () -> ((ObjectNode) input.get()).put("offset", -1));
            coordinator.changed(); await(entered);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var queried = new CountDownLatch(1);
                var answer = pool.submit(() -> { queried.countDown(); return coordinator.query("main", input,
                        () -> ((ObjectNode) input.get()).put("offset", -1)); });
                await(queried);
                assertThrows(TimeoutException.class, () -> answer.get(100, TimeUnit.MILLISECONDS));
                version.set(2); coordinator.changed(); release.countDown();
                assertEquals(2, answer.get(8, TimeUnit.SECONDS).path("version").asInt());
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }
    @Test void changesDuringForegroundAnalysisRetryInsteadOfPublishingOldResult() throws Exception {
        var request = request(); var version = new AtomicInteger(); var calls = new AtomicInteger();
        var holder = new AtomicReference<KotlinWarmupCoordinator>();
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (calls.getAndIncrement() == 0) { version.incrementAndGet(); holder.get().changed(); }
            return result(r);
        }, Duration.ofHours(1))) {
            holder.set(coordinator);
            Supplier<JsonNode> input = () -> request.deepCopy().put("version", version.get());
            assertEquals(1, coordinator.query("main", input, input).path("version").asInt());
            assertEquals(2, calls.get());
        }
    }
    @Test void burstIsCoalescedAndSwitchingModuleCancelsQueuedWarmup() throws Exception {
        var request = request(); var calls = new AtomicInteger(); var warm = new CountDownLatch(1);
        var barrier = new CountDownLatch(1);
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            calls.incrementAndGet(); if (r.path("offset").asInt() == -1) warm.countDown();
            if (r.path("barrier").asBoolean()) barrier.countDown(); return result(r);
        }, Duration.ofMillis(150))) {
            coordinator.query("main", () -> request, () -> request.deepCopy().put("offset", -1));
            for (int i = 0; i < 20; i++) coordinator.changed();
            await(warm); assertEquals(2, calls.get());
            coordinator.changed();
            coordinator.query("other", () -> request, () -> request.deepCopy().put("offset", -1));
            assertEquals(3, calls.get());
            // New module's warmup serves as a deterministic queue barrier after the debounce.
            coordinator.query("other", () -> request, () -> request.deepCopy().put("barrier", true));
            coordinator.changed(); await(barrier);
            assertEquals(5, calls.get());
        }
    }
    @Test void compilerRaceResponseRetriesAndWarmupFailureDoesNotPoisonQueries() throws Exception {
        var request = request(); var calls = new AtomicInteger(); var warmed = new CountDownLatch(1);
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) { warmed.countDown(); throw new IllegalStateException("worker failed"); }
            if (calls.getAndIncrement() == 0) return JSON.createObjectNode().put("status", "inputs_changed");
            return result(r);
        }, Duration.ZERO)) {
            assertEquals("resolved", coordinator.query("main", () -> request,
                    () -> request.deepCopy().put("offset", -1)).path("status").asText());
            coordinator.changed(); await(warmed);
            assertEquals("resolved", coordinator.query("main", () -> request,
                    () -> request.deepCopy().put("offset", -1)).path("status").asText());
        }
    }
    @Test void closeInterruptsWarmupRejectsQueriesAndIsIdempotent() throws Exception {
        var request = request(); var entered = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); stopped.countDown(); }
            }
            return result(r);
        }, Duration.ZERO);
        try {
            coordinator.query("main", () -> request, () -> request.deepCopy().put("offset", -1));
            coordinator.changed(); await(entered); coordinator.close(); await(stopped);
            assertEquals("unavailable", coordinator.query("main", () -> request, () -> request).path("status").asText());
            coordinator.changed(); coordinator.close();
        } finally { coordinator.close(); }
    }
    @Test void watcherTracksSourceEditsNewDirectoriesAndExternalClasspathFiles() throws Exception {
        var request = request(); var events = new LinkedBlockingQueue<JsonNode>();
        Path jar = temp.resolve("dependencies/library.jar"); Files.createDirectories(jar.getParent()); Files.writeString(jar, "before");
        request.withArray("classpath").add(jar.toString());
        Path captured = temp.resolve("build/quill-classpath.txt");
        request.put("captured_classpath", captured.toString());
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) events.add(r); return result(r);
        }, Duration.ofMillis(100))) {
            Supplier<JsonNode> background = () -> request.deepCopy().put("offset", -1);
            coordinator.query("main", () -> request, background);
            Files.writeString(Path.of(request.path("file").asText()), "fun run() = 2");
            assertNotNull(events.poll(8, TimeUnit.SECONDS));
            events.clear(); Path added = temp.resolve("src/main/kotlin/new/package/New.kt");
            Files.createDirectories(added.getParent()); Files.writeString(added, "fun extra() = 1");
            assertNotNull(events.poll(8, TimeUnit.SECONDS));
            // Wait for re-registration under operation before modifying the new tree again.
            coordinator.query("main", () -> request, background); events.clear();
            Files.writeString(added, "fun extra() = 2"); assertNotNull(events.poll(8, TimeUnit.SECONDS));
            coordinator.query("main", () -> request, background); events.clear();
            Files.writeString(jar, "after"); assertNotNull(events.poll(8, TimeUnit.SECONDS));
            coordinator.query("main", () -> request, background); events.clear();
            Files.delete(added); assertNotNull(events.poll(8, TimeUnit.SECONDS));
            coordinator.query("main", () -> request, background); events.clear();
            Path output = temp.resolve("target/classes/probe/Library.class");
            Files.createDirectories(output.getParent()); Files.writeString(output, "new bytecode");
            assertNotNull(events.poll(8, TimeUnit.SECONDS));
            coordinator.query("main", () -> request, background); events.clear();
            Files.createDirectories(captured.getParent()); Files.writeString(captured, jar.toString());
            assertNotNull(events.poll(8, TimeUnit.SECONDS));
        }
    }
    @Test void deletedExternalOutputRootIsWatchedForRecreationWhileClasspathIsMissing() throws Exception {
        var request = request();
        Path output = external.resolve("classes"); Files.createDirectories(output);
        Path binary = output.resolve("Library.class"); Files.writeString(binary, "before");
        request.withArray("classpath").add(output.toString());
        var missing = new CountDownLatch(1); var restored = new CountDownLatch(1);
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) restored.countDown(); return result(r);
        }, Duration.ofMillis(100))) {
            Supplier<JsonNode> background = () -> {
                if (!Files.isDirectory(output)) { missing.countDown(); return JSON.createObjectNode().put("status", "classpath_missing"); }
                return request.deepCopy().put("offset", -1);
            };
            coordinator.query("main", () -> request, background);
            Files.delete(binary); Files.delete(output); await(missing);
            Files.createDirectories(output); Files.writeString(binary, "after"); await(restored);
        }
    }
}
