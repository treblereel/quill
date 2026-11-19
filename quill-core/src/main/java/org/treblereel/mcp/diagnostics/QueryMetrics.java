package org.treblereel.mcp.diagnostics;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Opt-in, content-free diagnostics. No counters or response fields are retained when disabled.
 */
public final class QueryMetrics implements AutoCloseable {
    private static final boolean ENABLED = Boolean.getBoolean("quill.queryMetrics") || "1".equals(System.getenv("QUILL_QUERY_METRICS"));
    private static final ThreadLocal<QueryMetrics> CURRENT = new ThreadLocal<>();
    private final long started = System.nanoTime();
    private final Map<String, Long> stages = new LinkedHashMap<>();
    private final QueryMetrics parent;
    private long connections, statements, files, bytes;
    private boolean closed;

    QueryMetrics() {
        parent = CURRENT.get();
        CURRENT.set(this);
    }

    public static QueryMetrics start() {
        return ENABLED && CURRENT.get() == null ? new QueryMetrics() : null;
    }

    public static <T> T measure(String stage, Supplier<T> work) {
        QueryMetrics current = CURRENT.get();
        if (current == null) return work.get();
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            current.stages.merge(stage, System.nanoTime() - start, Long::sum);
        }
    }

    public static void measure(String stage, Runnable work) {
        measure(stage, () -> {
            work.run();
            return null;
        });
    }

    public static void connectionOpened() {
        var m = CURRENT.get();
        if (m != null) m.connections++;
    }

    public static void statementExecuted() {
        var m = CURRENT.get();
        if (m != null) m.statements++;
    }

    public static void sourceRead(long count) {
        var m = CURRENT.get();
        if (m != null) {
            m.files++;
            m.bytes += count;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (parent != null) {
            CURRENT.set(parent);
            return;
        }
        CURRENT.remove();
        var event = JsonNodeFactory.instance.objectNode();
        event.put("event", "quill_query_metrics");
        event.put("total_ms", (System.nanoTime() - started) / 1_000_000.0);
        event.put("connection_opens", connections);
        event.put("sql_statements", statements);
        event.put("source_files_read", files);
        event.put("source_bytes_read", bytes);
        var timings = event.putObject("stages_ms");
        stages.forEach((stage, nanos) -> timings.put(stage, nanos / 1_000_000.0));
        System.err.println(event);
    }
}
