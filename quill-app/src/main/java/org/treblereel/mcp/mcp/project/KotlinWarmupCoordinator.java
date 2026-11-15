package org.treblereel.mcp.mcp.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Watches the recently queried module while the worker can retain inactive FIR snapshots. A
 * re-analysis after an edit costs about as much as the query it prepares for, so edits re-warm
 * the worker only while someone is using it: within {@link #IDLE} of the last query. Later edits
 * leave the next query to analyze the module itself.
 */
public final class KotlinWarmupCoordinator implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Duration IDLE = Duration.ofMinutes(5);
    private final Function<JsonNode, JsonNode> worker;
    private final long debounceMillis;
    private final long idleNanos;
    private final java.util.function.LongSupplier clock;
    private final Duration healthTimeout;
    /** When the last foreground query started; null before any. */
    private Long lastQuery;
    private final KotlinDirectoryWatcher.Factory eventFactory;
    private final Object state = new Object();
    private final ReentrantLock operation = new ReentrantLock(true);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "quill-k2-warmup"); thread.setDaemon(true); return thread;
    });
    private boolean closed;
    private String active;
    private long epoch, revision;
    private Supplier<JsonNode> provider;
    private ScheduledFuture<?> pending;
    private InputsWatcher watcher;

    public KotlinWarmupCoordinator(Function<JsonNode, JsonNode> worker, Duration debounce) {
        this(worker, debounce, KotlinDirectoryWatcher.DEFAULT);
    }

    KotlinWarmupCoordinator(Function<JsonNode, JsonNode> worker, Duration debounce, KotlinDirectoryWatcher.Factory factory) {
        this(worker, debounce, factory, IDLE, System::nanoTime);
    }

    KotlinWarmupCoordinator(Function<JsonNode, JsonNode> worker, Duration debounce, KotlinDirectoryWatcher.Factory factory,
            Duration healthTimeout) {
        this(worker, debounce, factory, IDLE, System::nanoTime, healthTimeout);
    }

    KotlinWarmupCoordinator(Function<JsonNode, JsonNode> worker, Duration debounce, KotlinDirectoryWatcher.Factory factory,
            Duration idle, java.util.function.LongSupplier clock) {
        this(worker, debounce, factory, idle, clock, Duration.ofNanos(KotlinDirectoryWatcher.HEALTH_TIMEOUT_NANOS));
    }

    private KotlinWarmupCoordinator(Function<JsonNode, JsonNode> worker, Duration debounce, KotlinDirectoryWatcher.Factory factory,
            Duration idle, java.util.function.LongSupplier clock, Duration healthTimeout) {
        this.worker = worker;
        this.debounceMillis = debounce.toMillis();
        this.eventFactory = factory;
        this.idleNanos = idle.toNanos();
        this.clock = clock;
        this.healthTimeout = healthTimeout;
    }

    public boolean prewarm(String key, Supplier<JsonNode> background) {
        synchronized (state) {
            // Startup must never displace a module already selected by a real query.
            if (closed || active != null) return false;
            activate(key); provider = background;
            long owner = epoch, version = revision;
            pending = scheduler.schedule(() -> warm(owner, version), 0, TimeUnit.MILLISECONDS);
            return true;
        }
    }

    // Called with state held; both startup and foreground share the same subscriptions.
    private void activate(String key) {
        cancelPending();
        if (watcher != null) watcher.close();
        active = key; epoch++; revision = 0;
        try { watcher = new InputsWatcher(epoch); }
        catch (IOException | UnsupportedOperationException unsupported) { watcher = null; }
    }

    public JsonNode query(String key, Supplier<JsonNode> foreground, Supplier<JsonNode> background) {
        boolean acquired = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            acquired = operation.tryLock(30, TimeUnit.SECONDS);
            if (!acquired) return status("timeout");
            synchronized (state) {
                if (closed) return status("unavailable");
                if (!key.equals(active)) activate(key);
                provider = background;
                lastQuery = clock.getAsLong();
            }
            // A query takes over a queued debounce; an already running warmup holds
            // operation until it completes. Recollect inputs only after that wait.
            while (System.nanoTime() < deadline) {
                long before;
                JsonNode request = foreground.get();
                synchronized (state) {
                    if (closed) return status("unavailable");
                    cancelPending();
                    if (watcher != null) watcher.configure(request);
                    before = revision;
                }
                if (!request.has("sources")) return request;
                JsonNode result = worker.apply(request);
                synchronized (state) {
                    if (closed) return status("unavailable");
                    if (before == revision && !result.path("status").asText().equals("inputs_changed")) {
                        cancelPending();
                        return result instanceof ObjectNode node
                                ? node.deepCopy().put("input_detection", watchBackend()) : result;
                    }
                }
            }
            return status("timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return status("interrupted");
        } catch (Exception unavailable) { return status("unavailable"); }
        finally { if (acquired) operation.unlock(); }
    }

    // Also used by deterministic concurrency tests; watcher callbacks carry their epoch.
    void changed() { synchronized (state) { changed(epoch); } }
    private void changed(long expectedEpoch) {
        synchronized (state) {
            if (closed || expectedEpoch != epoch || provider == null) return;
            revision++;
            cancelPending();
            // Nobody has queried lately; the next query analyzes the edited module itself.
            if (lastQuery == null || clock.getAsLong() - lastQuery > idleNanos) return;
            long version = revision;
            pending = scheduler.schedule(() -> warm(expectedEpoch, version), debounceMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void warm(long expectedEpoch, long version) {
        boolean acquired = false;
        try {
            operation.lockInterruptibly(); acquired = true;
            Supplier<JsonNode> source;
            synchronized (state) {
                if (closed || epoch != expectedEpoch || revision != version) return;
                source = provider;
            }
            JsonNode request = source.get();
            synchronized (state) {
                if (closed || epoch != expectedEpoch || revision != version) return;
                if (watcher != null) watcher.configure(request);
            }
            if (!request.has("sources")) return;
            JsonNode result = worker.apply(request);
            if (result.path("status").asText().equals("inputs_changed")) changed(expectedEpoch);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception unavailable) { /* A foreground query retains the existing fallback. */ }
        finally { if (acquired) operation.unlock(); }
    }

    private void cancelPending() { if (pending != null) { pending.cancel(false); pending = null; } }
    @Override public void close() {
        synchronized (state) {
            if (closed) return;
            closed = true; epoch++; cancelPending();
            if (watcher != null) { watcher.close(); watcher = null; }
            provider = null;
        }
        scheduler.shutdownNow();
    }
    private static JsonNode status(String value) { return JSON.createObjectNode().put("status", value); }

    Set<Path> watchedDirectories() {
        synchronized (state) {
            if (watcher == null) return Set.of();
            return watcher.events.roots();
        }
    }

    String watchBackend() {
        synchronized (state) { return watcher == null ? "unavailable" : watcher.events.backend(); }
    }

    private final class InputsWatcher implements AutoCloseable {
        private final KotlinDirectoryWatcher events;
        private final Semaphore recoveryReady = new Semaphore(0);
        private final Thread listener;
        private volatile boolean stopped;
        private volatile Set<Path> trees = Set.of(), files = Set.of();
        private volatile Set<Path> fileAncestors = Set.of();
        private volatile boolean refresh = true;
        private String signature;
        private final long ownerEpoch;
        InputsWatcher(long epoch) throws IOException {
            ownerEpoch = epoch;
            events = new KotlinDirectoryWatcher(eventFactory, () -> {
                refresh = true; changed(ownerEpoch);
            }, () -> {
                recoveryReady.release(); refresh = true; changed(ownerEpoch);
            }, healthTimeout);
            listener = new Thread(this::listen, "quill-k2-input-watch"); listener.setDaemon(true); listener.start();
        }
        void configure(JsonNode request) {
            if (!request.has("sources")) return;
            String next = request.path("module") + ":" + request.path("source_set") + ":" + request.path("sources")
                    + ":" + request.path("optional_friend_paths") + ":" + request.path("optional_outputs") + ":" + request.path("friend_paths") + ":" + request.path("java_sources") + ":" + request.path("source_roots") + ":" + request.path("java_roots") + ":" + request.path("classpath") + ":" + request.path("captured_classpath") + ":" + request.path("compiler_settings_files") + ":" + request.path("plugin_classpath");
            if (next.equals(signature) && !refresh) return;
            refresh = false; signature = next;
            var recursive = new LinkedHashSet<Path>(); var exact = new LinkedHashSet<Path>();
            Path module = Path.of(request.path("module").asText());
            if (!request.path("captured_analysis_inputs").asBoolean()) {
                recursive.add(module.resolve("src/main"));
                if (request.path("source_set").asText().equals("test")) recursive.add(module.resolve("src/test"));
                for (String output : List.of("target/classes", "build/classes/java/main", "build/classes/kotlin/main"))
                    recursive.add(module.resolve(output));
                if (request.path("source_set").asText().equals("test")) {
                    recursive.add(module.resolve("target/test-classes"));
                    recursive.add(module.resolve("build/classes/java/test"));
                    recursive.add(module.resolve("build/classes/kotlin/test"));
                }
            }
            for (JsonNode root : request.path("captured_javaRoot")) recursive.add(Path.of(root.asText()));
            for (JsonNode source : request.path("java_sources")) exact.add(Path.of(source.asText()));
            for (JsonNode source : request.path("sources")) exact.add(Path.of(source.asText()));
            for (JsonNode root : request.path("source_roots")) recursive.add(Path.of(root.asText()));
            for (JsonNode root : request.path("java_roots")) recursive.add(Path.of(root.asText()));
            for (JsonNode friend : request.path("optional_friend_paths")) {
                Path path = Path.of(friend.asText());
                if (path.toString().endsWith(".jar")) exact.add(path); else recursive.add(path);
            }
            for (JsonNode output : request.path("optional_outputs")) recursive.add(Path.of(output.asText()));
            for (JsonNode friend : request.path("friend_paths")) recursive.add(Path.of(friend.asText()));
            for (JsonNode entry : request.path("classpath")) {
                Path path = Path.of(entry.asText());
                if (Files.isDirectory(path)) recursive.add(path); else exact.add(path);
            }
            exact.add(Path.of(request.path("captured_classpath").asText(module.resolve("target/quill-classpath.txt").toString())));
            for (JsonNode setting : request.path("compiler_settings_files")) exact.add(Path.of(setting.asText()));
            for (JsonNode plugin : request.path("plugin_classpath")) exact.add(Path.of(plugin.asText()));
            for (String marker : List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"))
                exact.add(module.resolve(marker));
            // Track real targets too, so symlinked source/output/JAR changes are visible.
            for (Path path : List.copyOf(recursive)) addReal(recursive, path);
            for (Path path : List.copyOf(exact)) addReal(exact, path);
            trees = Set.copyOf(recursive); files = Set.copyOf(exact);
            var ancestors = new HashSet<Path>();
            for (Path path : exact) {
                for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) ancestors.add(parent);
            }
            fileAncestors = Set.copyOf(ancestors);
            Set<Path> desired = new HashSet<>();
            for (Path path : recursive) {
                parents(desired, path, module);
                if (Files.isDirectory(path)) {
                    try (var paths = Files.walk(path, FileVisitOption.FOLLOW_LINKS)) {
                        var iterator = paths.filter(Files::isDirectory).iterator();
                        while (iterator.hasNext() && desired.size() < 8192) desired.add(iterator.next().toAbsolutePath().normalize());
                    } catch (IOException | java.io.UncheckedIOException ignored) { }
                }
            }
            for (Path path : exact) parents(desired, path.getParent(), module);
            Set<Path> canonical = new HashSet<>();
            for (Path path : desired) { try { canonical.add(path.toRealPath()); } catch (IOException ignored) { } }
            events.configure(canonical, this::relevant);
        }
        private void addReal(Set<Path> values, Path path) {
            // Missing future inputs still need canonical aliases under a symlinked
            // project parent (e.g. /var -> /private/var on macOS).
            for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent()) {
                try { values.add(ancestor.toRealPath().resolve(ancestor.relativize(path))); return; }
                catch (IOException ignored) { }
            }
        }
        private void parents(Set<Path> values, Path path, Path module) {
            Path canonicalModule;
            try { canonicalModule = module.toRealPath(); } catch (IOException missing) { canonicalModule = module; }
            for (Path current = path; current != null; current = current.getParent()) {
                if (Files.isDirectory(current)) values.add(current.toAbsolutePath().normalize());
                // Only ancestors inside the module are needed to catch creation of missing trees.
                if (!current.startsWith(module) && !current.startsWith(canonicalModule)) {
                    if (!Files.isDirectory(current)) continue;
                    Path parent = current.getParent();
                    if (parent != null && Files.isDirectory(parent)) values.add(parent.toAbsolutePath().normalize());
                    break;
                }
                if (current.equals(module) || current.equals(canonicalModule)) break;
            }
        }
        private boolean relevant(Path path) {
            return files.contains(path) || trees.stream().anyMatch(root -> path.startsWith(root) || root.startsWith(path))
                    || fileAncestors.contains(path);
        }
        private void listen() {
            try {
                while (!stopped) {
                    recoveryReady.tryAcquire(1, TimeUnit.SECONDS);
                    if (stopped) break;
                    events.checkHealth();
                    synchronized (state) {
                        if (stopped || closed || ownerEpoch != epoch) break;
                        if (!events.active() && events.recover()) {
                            refresh = true; changed(ownerEpoch);
                        }
                    }
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        @Override public void close() {
            stopped = true;
            listener.interrupt();
            events.close();
        }
    }
}
