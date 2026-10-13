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
            if (r.path("offset").asInt() != -1 && calls.getAndIncrement() == 0) { version.incrementAndGet(); holder.get().changed(); }
            return result(r);
        }, Duration.ofHours(1))) {
            holder.set(coordinator);
            Supplier<JsonNode> input = () -> request.deepCopy().put("version", version.get());
            assertEquals(1, coordinator.query("main", input, () -> ((ObjectNode) input.get()).put("offset", -1)).path("version").asInt());
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
        Path settings = temp.resolve(".quill/kotlin-main.properties");
        request.putArray("compiler_settings_files").add(settings.toString());
        Path plugin = external.resolve("compiler plugin.jar"); Files.writeString(plugin, "before");
        request.putArray("plugin_classpath").add(plugin.toString());
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
            coordinator.query("main", () -> request, background); events.clear();
            Files.createDirectories(settings.getParent()); Files.writeString(settings, "languageVersion=2.3");
            assertNotNull(events.poll(8, TimeUnit.SECONDS));
            coordinator.query("main", () -> request, background); events.clear();
            Files.writeString(plugin, "changed"); assertNotNull(events.poll(8, TimeUnit.SECONDS));
            coordinator.query("main", () -> request, background); events.clear();
            Files.delete(settings); assertNotNull(events.poll(8, TimeUnit.SECONDS));
        }
    }
    @Test void capturedCustomRootsWatchNewSourcesAndMissingJavaOutputs() throws Exception {
        var request = request(); var events = new LinkedBlockingQueue<JsonNode>();
        Path sources = temp.resolve("relocated ü/kotlin"); Files.createDirectories(sources);
        Path futureJava = temp.resolve("relocated/java");
        Path output = temp.resolve("out/classes/java/main");
        request.put("captured_analysis_inputs", true).put("source_set", "integrationTest");
        request.putArray("source_roots").add(sources.toString());
        request.putArray("captured_javaRoot").add(futureJava.toString());
        request.putArray("optional_outputs").add(output.toString());
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) events.add(r); return result(r);
        }, Duration.ofMillis(100))) {
            Supplier<JsonNode> background = () -> request.deepCopy().put("offset", -1);
            coordinator.query("custom", () -> request, background);
            Files.writeString(sources.resolve("Added.kt"), "fun added() = 1");
            assertNotNull(events.poll(8, TimeUnit.SECONDS));
            coordinator.query("custom", () -> request, background); events.clear();
            Files.createDirectories(futureJava); Files.writeString(futureJava.resolve("Added.java"), "class Added {}");
            assertNotNull(events.poll(8, TimeUnit.SECONDS));
            coordinator.query("custom", () -> request, background); events.clear();
            Files.createDirectories(output); Files.writeString(output.resolve("Added.class"), "new bytecode");
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

    @Test void atomicSaveAndBurstReachBackgroundWithoutAForegroundQuery() throws Exception {
        var request = request(); Path source = Path.of(request.path("file").asText());
        var events = new LinkedBlockingQueue<String>();
        Supplier<JsonNode> background = () -> {
            try { return request.deepCopy().put("offset", -1).put("text", Files.readString(source)); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        };
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) events.add(r.path("text").asText());
            return result(r);
        }, Duration.ofMillis(150))) {
            coordinator.query("main", () -> request, background);
            Path staged = source.resolveSibling(".Caller.kt.save");
            Files.writeString(staged, "fun run() = true");
            Files.move(staged, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            assertEquals("fun run() = true", events.poll(8, TimeUnit.SECONDS));
            for (int i = 0; i < 5; i++) { Files.writeString(source, "fun run() = " + i); Thread.sleep(20); }
            assertEquals("fun run() = 4", events.poll(8, TimeUnit.SECONDS));
            assertNull(events.poll(400, TimeUnit.MILLISECONDS), "Burst should coalesce into one warmup");
        }
    }

    @Test void filesystemSaveDuringWarmupIsReanalyzedAndWaitingQueryUsesLatestInputs() throws Exception {
        var request = request(); Path source = Path.of(request.path("file").asText());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var readLatest = new CountDownLatch(1);
        Supplier<JsonNode> input = () -> {
            try {
                String text = Files.readString(source);
                if (text.contains("true")) readLatest.countDown();
                return request.deepCopy().put("text", text);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        };
        Supplier<JsonNode> background = () -> ((ObjectNode) input.get()).put("offset", -1);
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1 && r.path("text").asText().contains("= 2")) {
                entered.countDown(); await(release);
            }
            return JSON.createObjectNode().put("status", "resolved").put("text", r.path("text").asText());
        }, Duration.ofMillis(100))) {
            coordinator.query("main", input, background);
            Files.writeString(source, "fun run() = 2"); await(entered);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var answer = pool.submit(() -> coordinator.query("main", input, background));
                assertThrows(TimeoutException.class, () -> answer.get(100, TimeUnit.MILLISECONDS));
                Files.writeString(source, "fun run() = true"); release.countDown();
                assertEquals("fun run() = true", answer.get(8, TimeUnit.SECONDS).path("text").asText());
                await(readLatest);
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }

    @Test void symlinkedSourceTargetChangesTriggerWarmup() throws Exception {
        var request = request(); Path source = Path.of(request.path("file").asText());
        Path target = external.resolve("Caller.kt"); Files.move(source, target); Files.createSymbolicLink(source, target);
        var warmed = new CountDownLatch(1);
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) warmed.countDown(); return result(r);
        }, Duration.ofMillis(100))) {
            coordinator.query("main", () -> request, () -> request.deepCopy().put("offset", -1));
            Files.writeString(target, "fun run() = true"); await(warmed);
        }
    }

    @Test void canonicalModuleAliasesDoNotSubscribeToSharedTemporaryParent() throws Exception {
        var request = request();
        try (var coordinator = new KotlinWarmupCoordinator(this::result, Duration.ZERO)) {
            coordinator.query("main", () -> request, () -> request);
            Path canonical = temp.toRealPath();
            assertFalse(coordinator.watchedDirectories().isEmpty());
            assertTrue(coordinator.watchedDirectories().stream().allMatch(path -> path.startsWith(canonical)),
                    () -> "Unexpected subscriptions outside the module: " + coordinator.watchedDirectories());
        }
    }

    @Test void failedEventRegistrationAndMissingNativeLibraryRetainPollingAndForegroundQueries() throws Exception {
        var request = request(); Path source = Path.of(request.path("file").asText());
        for (boolean missingNative : new boolean[]{false, true}) {
            var registrations = new AtomicInteger(); var warmed = new CountDownLatch(1);
            KotlinDirectoryWatcher.Factory failing = (roots, listener, visitor) -> {
                registrations.incrementAndGet();
                if (missingNative) throw new UnsatisfiedLinkError("simulated native library unavailable");
                throw new java.io.IOException("simulated registration failure");
            };
            try (var coordinator = new KotlinWarmupCoordinator(r -> {
                if (r.path("offset").asInt() == -1) warmed.countDown(); return result(r);
            }, Duration.ofMillis(100), failing)) {
                Supplier<JsonNode> background = () -> request.deepCopy().put("offset", -1);
                assertEquals("resolved", coordinator.query("main", () -> request, background).path("status").asText());
                assertEquals("metadata_poll_100ms", coordinator.watchBackend());
                assertEquals("metadata_poll_100ms", coordinator.query("main", () -> request, background)
                        .path("input_detection").asText());
                Files.writeString(source, "fun run() = " + missingNative); await(warmed);
                assertEquals("resolved", coordinator.query("main", () -> request, background).path("status").asText());
                assertEquals(1, registrations.get(), "Unavailable event backend must not be retried on every query");
            }
        }
    }

    @Test void deletedModuleCanBeRecreatedAfterTheLibraryStopsWatchingItsRoot() throws Exception {
        var request = request(); Path source = Path.of(request.path("file").asText());
        var missing = new CountDownLatch(1); var restored = new CountDownLatch(1);
        Supplier<JsonNode> background = () -> {
            if (!Files.exists(source)) { missing.countDown(); return JSON.createObjectNode().put("status", "source_missing"); }
            return request.deepCopy().put("offset", -1);
        };
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) restored.countDown(); return result(r);
        }, Duration.ofMillis(100))) {
            coordinator.query("main", () -> request, background);
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            await(missing);
            Files.createDirectories(source.getParent()); Files.writeString(source, "fun run() = true");
            await(restored);
            assertEquals("resolved", coordinator.query("main", () -> request, background).path("status").asText());
        }
    }
    @Test void failedRegistrationRecoversInSameModuleAndWarmsCurrentInputs() throws Exception {
        var request = request(); var attempts = new AtomicInteger(); var recovered = new CountDownLatch(1);
        KotlinDirectoryWatcher.Factory factory = (roots, listener, visitor) -> {
            if (attempts.incrementAndGet() < 3) throw new java.io.IOException("transient registration failure");
            return KotlinDirectoryWatcher.DEFAULT.create(roots, listener, visitor);
        };
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (attempts.get() >= 3 && r.path("offset").asInt() == -1) recovered.countDown();
            return result(r);
        }, Duration.ofMillis(10), factory)) {
            coordinator.query("main", () -> request, () -> request.deepCopy().put("offset", -1));
            assertEquals("metadata_poll_100ms", coordinator.watchBackend());
            await(recovered); assertTrue(attempts.get() >= 3);
            assertNotEquals("metadata_poll_100ms", coordinator.watchBackend());
        }
    }
    @Test void metadataAuditDoesNotDiscardASilentSaveInAnIntervalWithAnEarlierEvent() throws Exception {
        var request = request(); Path source = Path.of(request.path("file").asText());
        var callback = new AtomicReference<io.methvin.watcher.DirectoryChangeListener>();
        var earlierWarmup = new CountDownLatch(1); var fresh = new CountDownLatch(1);
        KotlinDirectoryWatcher.Factory factory = (roots, listener, visitor) -> {
            callback.set(listener);
            return io.methvin.watcher.DirectoryWatcher.builder().paths(roots).listener(listener)
                    .fileTreeVisitor(visitor).fileHashing(false)
                    .watchService(new io.methvin.watchservice.SilentMacOSWatchService()).build();
        };
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            if (r.path("offset").asInt() == -1) {
                try {
                    if (Files.readString(source).contains("changed")) fresh.countDown();
                    else earlierWarmup.countDown();
                } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            }
            return result(r);
        }, Duration.ZERO, factory)) {
            coordinator.query("main", () -> request, () -> request.deepCopy().put("offset", -1));
            callback.get().onEvent(new io.methvin.watcher.DirectoryChangeEvent(
                    io.methvin.watcher.DirectoryChangeEvent.EventType.MODIFY, false, source, null, 1, temp));
            await(earlierWarmup);
            Files.writeString(source, "fun run() = \"changed\"");
            assertTrue(fresh.await(5, TimeUnit.SECONDS),
                    "Metadata audit must detect the save before heartbeat failure activates fallback");
        }
    }

    @Test void metadataAuditDetectsSilentProviderAndBackgroundAnalysisReadsLatestContent() throws Exception {
        var request = request(); Path source = Path.of(request.path("file").asText());
        var attempts = new AtomicInteger(); var fresh = new CountDownLatch(1);
        KotlinDirectoryWatcher.Factory factory = (roots, listener, visitor) -> {
            if (attempts.incrementAndGet() == 1)
                return io.methvin.watcher.DirectoryWatcher.builder().paths(roots).listener(listener)
                        .fileTreeVisitor(visitor).fileHashing(false)
                        .watchService(new io.methvin.watchservice.SilentMacOSWatchService()).build();
            return KotlinDirectoryWatcher.DEFAULT.create(roots, listener, visitor);
        };
        try (var coordinator = new KotlinWarmupCoordinator(r -> {
            try {
                if (r.path("offset").asInt() == -1 && Files.readString(source).contains("changed")) fresh.countDown();
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            return result(r);
        }, Duration.ofMillis(10), factory)) {
            coordinator.query("main", () -> request, () -> request.deepCopy().put("offset", -1));
            var modified = Files.getLastModifiedTime(source);
            Files.writeString(source, "fun run() = \"changed\""); Files.setLastModifiedTime(source, modified);
            await(fresh);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (attempts.get() == 1 && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(attempts.get() >= 2, "Silent event provider must be replaced without a module switch");
        }
    }

}
