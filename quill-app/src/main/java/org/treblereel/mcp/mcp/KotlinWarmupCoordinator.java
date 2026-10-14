package org.treblereel.mcp.mcp;

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

/** Keeps subscriptions for two recently queried compiler contexts; worker operations remain serialized. */
final class KotlinWarmupCoordinator implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CONTEXTS = 2;
    private final Function<JsonNode, JsonNode> worker;
    private final long debounceMillis;
    private final KotlinDirectoryWatcher.Factory eventFactory;
    private final Object state = new Object();
    private final ReentrantLock operation = new ReentrantLock(true);
    private final LinkedHashMap<String, Context> contexts = new LinkedHashMap<>(MAX_CONTEXTS, .75f, true);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "quill-k2-warmup"); thread.setDaemon(true); return thread;
    });
    private boolean closed, foregroundSelected;
    private String active;

    private final class Context {
        final String key;
        long revision;
        boolean retired;
        String runtime;
        Supplier<JsonNode> provider;
        ScheduledFuture<?> pending;
        InputsWatcher watcher;
        Context(String key) { this.key = key; }
    }

    KotlinWarmupCoordinator(Function<JsonNode, JsonNode> worker, Duration debounce) {
        this(worker, debounce, KotlinDirectoryWatcher.DEFAULT);
    }

    KotlinWarmupCoordinator(Function<JsonNode, JsonNode> worker, Duration debounce, KotlinDirectoryWatcher.Factory factory) {
        this.worker = worker;
        this.debounceMillis = debounce.toMillis();
        this.eventFactory = factory;
    }

    boolean prewarm(String key, Supplier<JsonNode> background) {
        synchronized (state) {
            // A late startup task must never displace contexts selected by real queries.
            if (closed || foregroundSelected || !contexts.isEmpty()) return false;
            Context context = activate(key);
            context.provider = background;
            schedule(context, 0);
            return true;
        }
    }

    // Called with state held. Only foreground selection changes LRU order.
    private Context activate(String key) {
        Context context = contexts.get(key);
        if (context == null) {
            while (contexts.size() >= MAX_CONTEXTS) {
                var oldest = contexts.entrySet().iterator();
                Context evicted = oldest.next().getValue(); oldest.remove(); retire(evicted);
            }
            context = new Context(key);
            contexts.put(key, context);
            try { context.watcher = new InputsWatcher(context); }
            catch (IOException | UnsupportedOperationException unsupported) { context.watcher = null; }
        }
        active = key;
        return context;
    }

    private boolean current(Context context) {
        return !closed && !context.retired;
    }

    JsonNode query(String key, Supplier<JsonNode> foreground, Supplier<JsonNode> background) {
        boolean acquired = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            acquired = operation.tryLock(30, TimeUnit.SECONDS);
            if (!acquired) return status("timeout");
            Context context;
            synchronized (state) {
                if (closed) return status("unavailable");
                foregroundSelected = true;
                context = activate(key);
                context.provider = background;
            }
            // Running warmups hold operation. Recollect inputs only after that wait;
            // a foreground query takes over its own queued debounce, preserving other contexts.
            while (System.nanoTime() < deadline) {
                long before;
                JsonNode request = foreground.get();
                synchronized (state) {
                    if (!current(context)) return status(closed ? "unavailable" : "inputs_changed");
                    cancelPending(context);
                    if (context.watcher != null) context.watcher.configure(request);
                    context.runtime = runtime(request);
                    before = context.revision;
                }
                if (!request.has("sources")) return request;
                JsonNode result = worker.apply(request);
                if (request.has("project_id") && request.has("project_root")) {
                    try {
                        if (!org.treblereel.mcp.core.ProjectIdentity.resolve(Path.of(request.path("project_root").asText()))
                                .projectId().equals(request.path("project_id").asText())) return status("inputs_changed");
                    } catch (IOException unavailable) { return status("inputs_changed"); }
                }
                synchronized (state) {
                    if (!current(context)) return status(closed ? "unavailable" : "inputs_changed");
                    if (before == context.revision && !result.path("status").asText().equals("inputs_changed")) {
                        cancelPending(context);
                        return result instanceof ObjectNode node
                                ? node.deepCopy().put("input_detection", watchBackend(context)) : result;
                    }
                }
            }
            return status("timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return status("interrupted");
        } catch (Exception unavailable) { return status("unavailable"); }
        finally { if (acquired) operation.unlock(); }
    }

    // Deterministic test hooks. Lookup does not promote background activity in the LRU.
    void changed() { synchronized (state) { changed(find(active)); } }
    void changed(String key) { synchronized (state) { changed(find(key)); } }
    private Context find(String key) {
        for (Context context : contexts.values()) if (context.key.equals(key)) return context;
        return null;
    }
    private void changed(Context context) {
        synchronized (state) {
            if (context == null || !current(context) || context.provider == null) return;
            context.revision++;
            schedule(context, debounceMillis);
        }
    }

    private void schedule(Context context, long delay) {
        cancelPending(context);
        long version = context.revision;
        context.pending = scheduler.schedule(() -> warm(context, version), delay, TimeUnit.MILLISECONDS);
    }

    private void warm(Context context, long version) {
        boolean acquired = false;
        try {
            operation.lockInterruptibly(); acquired = true;
            Supplier<JsonNode> source;
            synchronized (state) {
                if (!current(context) || context.revision != version) return;
                source = context.provider;
            }
            JsonNode request = source.get();
            synchronized (state) {
                if (!current(context) || context.revision != version) return;
                if (context.watcher != null) context.watcher.configure(request);
            }
            if (!request.has("sources")) return;
            synchronized (state) {
                if (!current(context) || context.revision != version) return;
                context.runtime = runtime(request);
                Context selected = find(active);
                // A background edit must not restart a worker using the foreground's JDK/K2.
                if (selected != null && selected != context && selected.runtime != null
                        && context.runtime != null && !selected.runtime.equals(context.runtime)) return;
            }
            JsonNode result = worker.apply(request);
            if (result.path("status").asText().equals("inputs_changed")) changed(context);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception unavailable) { /* A foreground query retains the existing fallback. */ }
        finally { if (acquired) operation.unlock(); }
    }

    private static String runtime(JsonNode request) {
        if (request.hasNonNull("jdk_home")) return "javac:" + request.path("jdk_home").asText();
        if (request.hasNonNull("compilerVersion")) return "k2:" + request.path("compilerVersion").asText();
        return null;
    }

    private void cancelPending(Context context) {
        if (context.pending != null) { context.pending.cancel(false); context.pending = null; }
    }
    private void retire(Context context) {
        context.retired = true;
        cancelPending(context);
        if (context.watcher != null) { context.watcher.close(); context.watcher = null; }
        context.provider = null;
    }
    /** Detaches all this root's contexts without interrupting a running worker operation. */
    void retireRoot(Path root) {
        String prefix = org.treblereel.mcp.core.ProjectIdentity.canonical(root) + ":";
        synchronized (state) {
            contexts.entrySet().removeIf(entry -> {
                if (!entry.getKey().startsWith(prefix)) return false;
                retire(entry.getValue()); return true;
            });
            if (find(active) == null) active = null;
        }
    }
    @Override public void close() {
        synchronized (state) {
            if (closed) return;
            closed = true;
            contexts.values().forEach(this::retire); contexts.clear(); active = null;
        }
        scheduler.shutdownNow();
    }
    private static ObjectNode status(String value) { return JSON.createObjectNode().put("status", value); }

    Set<Path> watchedDirectories() {
        synchronized (state) {
            Set<Path> directories = new LinkedHashSet<>();
            for (Context context : contexts.values()) if (context.watcher != null) {
                var watcher = context.watcher;
                directories.addAll(watcher.events.active() ? watcher.events.roots() : watcher.polling.directories());
            }
            return Set.copyOf(directories);
        }
    }

    int contextCount() { synchronized (state) { return contexts.size(); } }
    String watchBackend() { synchronized (state) { return watchBackend(find(active)); } }
    private String watchBackend(Context context) {
        return context == null || context.watcher == null ? "unavailable" : context.watcher.events.backend();
    }

    private final class InputsWatcher implements AutoCloseable {
        private final KotlinPollingWatcher polling = new KotlinPollingWatcher();
        private final KotlinDirectoryWatcher events;
        private final Semaphore fallbackReady = new Semaphore(0);
        private final Thread listener;
        private volatile boolean stopped, fallback;
        private volatile Set<Path> trees = Set.of(), files = Set.of();
        private volatile Set<Path> fileAncestors = Set.of();
        private volatile boolean refresh = true;
        private String signature;
        private final Context owner;
        InputsWatcher(Context context) throws IOException {
            owner = context;
            events = new KotlinDirectoryWatcher(eventFactory, () -> {
                refresh = true; changed(owner);
            }, () -> {
                fallback = true; fallbackReady.release(); refresh = true; changed(owner);
            });
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
            polling.configure(canonical, this::relevant);
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
            long observedEvents = 0;
            long missedSince = 0;
            try {
                while (!stopped) {
                    // Healthy subscriptions only need a low-frequency audit/heartbeat.
                    fallbackReady.tryAcquire(fallback ? KotlinPollingWatcher.INTERVAL_MILLIS : 2000, TimeUnit.MILLISECONDS);
                    if (stopped) break;
                    if (fallback) {
                        if (polling.poll()) { refresh = true; changed(owner); }
                        synchronized (state) {
                            if (stopped || closed || owner.retired) break;
                            if (events.recover()) {
                                fallback = false; missedSince = 0; observedEvents = events.eventRevision();
                                refresh = true; changed(owner);
                            }
                        }
                    } else {
                        events.checkHealth();
                        long currentEvents = events.eventRevision();
                        boolean metadataChanged = polling.poll();
                        // An event in this interval does not prove that every metadata
                        // change was delivered, or that a warmup read the latest save.
                        if (metadataChanged) { refresh = true; changed(owner); }
                        if (currentEvents != observedEvents) missedSince = 0;
                        else if (metadataChanged && missedSince == 0) missedSince = System.nanoTime();
                        if (missedSince != 0 && System.nanoTime() - missedSince >= KotlinDirectoryWatcher.HEALTH_TIMEOUT_NANOS) {
                            // Give ordinary delivery a grace period before declaring it missing.
                            events.deliveryMissing(); missedSince = 0;
                        }
                        observedEvents = currentEvents;
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
