package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Host event/lifecycle stress; compile with the app all.jar, run outside an event-blocking sandbox.
 * Arguments: rounds lifecycle-count output.json. K2 correctness is covered separately by
 * QuillKotlinWarmupBenchmark/native E2E; this probe uses a content-reading worker for rapid switches.
 */
public final class QuillKotlinWatcherStress {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger mutations = new AtomicInteger();
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        check(condition.getAsBoolean(), message);
    }
    private static void save(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent()); Files.writeString(path, text); mutations.incrementAndGet();
    }
    private static void removeTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private static ObjectNode resources() throws Exception {
        System.gc(); Thread.sleep(500);
        var node = JSON.createObjectNode().put("threads", ManagementFactory.getThreadMXBean().getThreadCount())
                .put("heap_used_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix)
            node.put("open_file_descriptors", unix.getOpenFileDescriptorCount());
        var process = new ProcessBuilder("/bin/ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
        String rss = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() == 0) node.put("rss_bytes", Long.parseLong(rss) * 1024);
        node.put("quill_threads", Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("quill-k2-")).count());
        node.put("fsevents_threads", Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("WatchService for ")).count());
        var details = node.putArray("fsevents_thread_details");
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (!thread.isAlive() || !thread.getName().startsWith("WatchService for ")) return;
            var detail = details.addObject().put("name", thread.getName()).put("state", thread.getState().name());
            var frames = detail.putArray("stack"); Arrays.stream(stack).limit(12).forEach(frame -> frames.add(frame.toString()));
        });
        return node;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected rounds lifecycle-count output.json");
        int rounds = Integer.parseInt(args[0]), lifecycle = Integer.parseInt(args[1]);
        check(rounds > 0 && rounds <= 10000 && lifecycle > 0 && lifecycle <= 10000, "Invalid counts");
        Path root = Files.createTempDirectory("quill watcher стресс ").toRealPath();
        var report = JSON.createObjectNode().put("os", System.getProperty("os.name"))
                .put("arch", System.getProperty("os.arch")).put("java", System.getProperty("java.version"))
                .put("scope", "host directory-watcher and coordinator lifecycle; content-reading test worker, not K2")
                .put("rounds", rounds).put("lifecycle_count", lifecycle)
                .put("registration_settle_ms", Long.getLong("quill.stress.registration-settle-ms", 0L))
                .put("skip_module_switches", Boolean.getBoolean("quill.stress.skip-module-switches"));
        long started = System.nanoTime();
        try {
            Path sentinel = root.resolve("src/Маркер.kt"); save(sentinel, "0000");
            var observed = new AtomicInteger(-1); var callbacks = new AtomicInteger(); var failures = new AtomicInteger();
            Runnable changed = () -> {
                callbacks.incrementAndGet();
                try { observed.set(Integer.parseInt(Files.readString(sentinel))); } catch (Exception transientSave) { }
            };
            // Initialize JNA and its callback machinery before establishing the resource baseline.
            try (var watcher = new KotlinDirectoryWatcher(KotlinDirectoryWatcher.DEFAULT, changed, failures::incrementAndGet)) {
                check(watcher.configure(Set.of(root), path -> path.startsWith(root)), "Cannot initialize watcher");
                Thread.sleep(200); save(sentinel, "0001"); await(() -> observed.get() == 1, "Initial event missing");
                report.put("backend", watcher.backend());
                check(watcher.backend().equals(System.getProperty("os.name").contains("Mac")
                        ? "directory_watcher_fsevents" : "directory_watcher_watch_service"), "Unexpected fallback");
            }
            await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive()
                    && t.getName().startsWith("quill-k2-")), "Initial watcher did not close");
            report.set("baseline", resources());
            var ready = new ArrayList<Double>();
            try (var watcher = new KotlinDirectoryWatcher(KotlinDirectoryWatcher.DEFAULT, changed, failures::incrementAndGet)) {
                check(watcher.configure(Set.of(root, sentinel.getParent()), path -> path.startsWith(root)), "Registration failed");
                Thread.sleep(200);
                for (int round = 2; round < rounds + 2; round++) {
                    Path tree = root.resolve("src/batch"), moved = root.resolve("src/renamed");
                    for (int i = 0; i < 100; i++) save(tree.resolve("nested/Source" + i + ".kt"), "fun f" + i + "() = " + round);
                    Files.move(tree, moved, StandardCopyOption.ATOMIC_MOVE); mutations.incrementAndGet();
                    removeTree(moved); mutations.incrementAndGet();
                    // Same-size/time atomic replacement tests the event path without metadata clues.
                    FileTime time = Files.getLastModifiedTime(sentinel);
                    Path swap = sentinel.resolveSibling(".swap"); save(swap, String.format("%04d", round));
                    Files.setLastModifiedTime(swap, time);
                    Files.move(swap, sentinel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    mutations.incrementAndGet();
                    int expected = round; long saved = System.nanoTime();
                    await(() -> observed.get() == expected, "Missed final state at round " + round);
                    ready.add((System.nanoTime() - saved) / 1_000_000.0);
                    if (round % 100 == 0) System.err.println("event rounds=" + round);
                }
                check(failures.get() == 0 && watcher.active(), "Watcher silently fell back during stress");
            }
            report.put("event_callbacks", callbacks.get()).put("event_failures", failures.get());
            ready.sort(Double::compareTo);
            report.put("final_state_observation_median_ms", ready.get(ready.size() / 2))
                    .put("final_state_observation_p95_ms", ready.get(Math.max(0, (int)Math.ceil(ready.size() * .95) - 1)));
            // Repeated create/replace/close checks native streams as well as executor lifecycle.
            Path other = Files.createDirectories(root.resolve("other"));
            for (int i = 0; i < lifecycle; i++) {
                try (var watcher = new KotlinDirectoryWatcher(KotlinDirectoryWatcher.DEFAULT, () -> {}, failures::incrementAndGet)) {
                    check(watcher.configure(Set.of(root), path -> path.startsWith(root)), "Lifecycle registration failed");
                    check(watcher.configure(Set.of(other), path -> path.startsWith(other)), "Root replacement failed");
                }
            }
            report.set("after_event_lifecycle", resources());
            if (!Boolean.getBoolean("quill.stress.skip-module-switches")) coordinatorStress(root, lifecycle, report);
            await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive()
                    && t.getName().startsWith("quill-k2-")), "Coordinator/watcher thread survived close");
            await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive()
                    && t.getName().startsWith("WatchService for ")), "Native FSEvents thread survived close");
            var after = resources(); report.set("after_close", after);
            var before = report.path("baseline");
            check(after.path("fsevents_threads").asLong() == 0, "Native FSEvents thread survived close");
            check(after.path("threads").asInt() <= before.path("threads").asInt() + 8, "Live thread growth exceeds bound");
            if (after.has("open_file_descriptors")) check(after.path("open_file_descriptors").asLong()
                    <= before.path("open_file_descriptors").asLong() + 8, "Open descriptor growth exceeds bound");
            check(failures.get() == 0, "Unexpected event backend failure");
            report.put("filesystem_mutations", mutations.get()).put("elapsed_seconds", (System.nanoTime()-started)/1_000_000_000.0)
                    .put("status", "passed");
            Files.writeString(Path.of(args[2]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
            System.out.println(report);
        } catch (Exception | Error failure) {
            report.put("status", "failed").put("failure", failure.toString()).put("filesystem_mutations", mutations.get());
            report.set("after_failure_close", resources());
            Files.writeString(Path.of(args[2]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
            throw failure;
        } finally { removeTree(root); }
    }

    private static void coordinatorStress(Path root, int switches, ObjectNode report) throws Exception {
        Path first = root.resolve("module-a"), second = root.resolve("module-b");
        Path a = first.resolve("src/main/kotlin/Caller.kt"), b = second.resolve("src/main/kotlin/Caller.kt");
        save(a, "initial-a"); save(b, "initial-b"); save(first.resolve("pom.xml"), "<project/>");
        save(second.resolve("build.gradle.kts"), "// fixture");
        var warmed = new LinkedBlockingQueue<String>(); var active = new AtomicReference<Path>(a);
        Supplier<JsonNode> input = () -> {
            Path source = active.get(), module = source.equals(a) ? first : second;
            var request = JSON.createObjectNode().put("module", module.toString()).put("file", source.toString())
                    .put("source_set", "main").put("offset", 1);
            request.putArray("sources").add(source.toString()); request.putArray("classpath");
            return request;
        };
        try (var coordinator = new KotlinWarmupCoordinator(request -> {
            try {
                String value = Files.readString(Path.of(request.path("file").asText()));
                if (request.path("offset").asInt() == -1) warmed.offer(value);
                return JSON.createObjectNode().put("status", "resolved").put("value", value);
            } catch (Exception error) { throw new IllegalStateException(error); }
        }, Duration.ofMillis(20))) {
            Supplier<JsonNode> background = () -> ((ObjectNode) input.get()).put("offset", -1);
            for (int i = 0; i < switches; i++) {
                Path current = i % 2 == 0 ? a : b; active.set(current); String key = current.getParent().toString();
                coordinator.query(key, input, background);
                Thread.sleep(Long.getLong("quill.stress.registration-settle-ms", 0L)); warmed.clear();
                String expected = "version-" + i;
                Path swap = current.resolveSibling(".save"); save(swap, expected);
                Files.move(swap, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                mutations.incrementAndGet();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); boolean found = false;
                while (System.nanoTime() < deadline && !found) found = expected.equals(warmed.poll(50, TimeUnit.MILLISECONDS));
                if (!found) {
                    report.put("failed_module_switch", i).put("failed_switch_backend", coordinator.watchBackend());
                    report.set("foreground_recovery", coordinator.query(key, input, background));
                }
                check(found, "Module switch missed save: " + i);
                check(coordinator.query(key, input, background).path("value").asText().equals(expected), "Stale foreground value");
                check(coordinator.watchBackend().equals(System.getProperty("os.name").contains("Mac")
                        ? "directory_watcher_fsevents" : "directory_watcher_watch_service"), "Module switched to fallback");
                // A save to the previous module must not invoke its old background supplier.
                Path previous = current.equals(a) ? b : a; save(previous, "inactive-" + i);
            }
        }
        report.put("module_switches_with_background_freshness", switches);
    }
}
