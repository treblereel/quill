package org.treblereel.mcp.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Structured, stderr-safe diagnostics for explaining how an MCP answer was produced. */
public final class DebugTrace {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object WRITE_LOCK = new Object();
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private static final long MAX_LOG_BYTES = 5L * 1024 * 1024;
    private static volatile Path logFile;

    private DebugTrace() {}

    public static void configure(boolean enabled, Path root) {
        logFile = enabled
                ? root.toAbsolutePath().normalize().resolve(".quill/debug/quill-debug.jsonl")
                : null;
    }

    public static boolean enabled() {
        return logFile != null;
    }

    public static Path logFile() {
        return logFile;
    }

    public static Trace start(String operation) {
        if (!enabled()) return Trace.disabled();
        Trace parent = CURRENT.get();
        Trace trace = new Trace(UUID.randomUUID().toString(), operation, System.nanoTime(), true,
                parent);
        CURRENT.set(trace);
        trace.event("query_started", Map.of());
        return trace;
    }

    private static void write(ObjectNode event) {
        Path destination = logFile;
        if (destination == null) return;
        String line = event.toString();
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(destination.getParent());
                rotate(destination);
                Files.writeString(destination, line + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException error) {
                System.err.println("[quill-debug] could not write debug log: "
                        + safeMessage(error));
            }
            System.err.println("[quill-debug] " + line);
        }
    }

    private static void rotate(Path destination) throws IOException {
        if (!Files.isRegularFile(destination) || Files.size(destination) < MAX_LOG_BYTES) return;
        Files.move(destination, destination.resolveSibling(destination.getFileName() + ".1"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    public static final class Trace implements AutoCloseable {
        private final String id;
        private final String operation;
        private final long started;
        private final boolean enabled;
        private final Trace parent;
        private boolean closed;
        private boolean failed;

        private Trace(String id, String operation, long started, boolean enabled, Trace parent) {
            this.id = id;
            this.operation = operation;
            this.started = started;
            this.enabled = enabled;
            this.parent = parent;
        }

        private static Trace disabled() {
            return new Trace("", "", 0, false, null);
        }

        public boolean enabled() {
            return enabled;
        }

        public String id() {
            return id;
        }

        public void event(String name, Map<String, ?> fields) {
            if (!enabled) return;
            ObjectNode node = JSON.createObjectNode();
            node.put("timestamp", Instant.now().toString());
            node.put("trace_id", id);
            if (parent != null) node.put("parent_trace_id", parent.id());
            node.put("operation", operation);
            node.put("event", name);
            node.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000L);
            fields.forEach((key, value) -> node.set(key, JSON.valueToTree(value)));
            DebugTrace.write(node);
        }

        public void failure(Throwable error) {
            failed = true;
            event("query_failed", Map.of("error_type", error.getClass().getSimpleName(),
                    "message", safeMessage(error)));
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            event(failed ? "query_terminated" : "query_completed", Map.of());
            if (enabled) {
                if (parent == null) CURRENT.remove();
                else CURRENT.set(parent);
            }
        }
    }
}
