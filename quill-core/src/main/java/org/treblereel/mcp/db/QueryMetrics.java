package org.treblereel.mcp.db;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Opt-in, content-free diagnostics. No counters or response fields are retained when disabled. */
public final class QueryMetrics implements AutoCloseable {
    private static final boolean ENABLED=Boolean.getBoolean("quill.queryMetrics") || "1".equals(System.getenv("QUILL_QUERY_METRICS"));
    private static final ThreadLocal<QueryMetrics> CURRENT=new ThreadLocal<>();
    private final long started=System.nanoTime();
    private final Map<String,Long> stages=new LinkedHashMap<>();
    private final QueryMetrics parent;
    private long connections,statements,files,bytes;
    private boolean closed;
    QueryMetrics() {parent=CURRENT.get();CURRENT.set(this);}
    public static QueryMetrics start() {return ENABLED && CURRENT.get()==null ? new QueryMetrics() : null;}
    public static <T> T measure(String stage,Supplier<T> work) {
        QueryMetrics current=CURRENT.get();if(current==null)return work.get();
        long start=System.nanoTime();try{return work.get();}finally{current.stages.merge(stage,System.nanoTime()-start,Long::sum);}
    }
    public static void measure(String stage,Runnable work) {measure(stage,()->{work.run();return null;});}
    public static void connectionOpened() {var m=CURRENT.get();if(m!=null)m.connections++;}
    public static void statementExecuted() {var m=CURRENT.get();if(m!=null)m.statements++;}
    public static void sourceRead(long count) {var m=CURRENT.get();if(m!=null){m.files++;m.bytes+=count;}}
    @Override public void close() {
        if(closed)return;closed=true;
        CURRENT.set(parent);if(parent!=null)return;
        StringBuilder json=new StringBuilder("{\"event\":\"quill_query_metrics\",\"total_ms\":").append((System.nanoTime()-started)/1_000_000.0)
                .append(",\"connection_opens\":").append(connections).append(",\"sql_statements\":").append(statements)
                .append(",\"source_files_read\":").append(files).append(",\"source_bytes_read\":").append(bytes).append(",\"stages_ms\":{");
        boolean first=true;for(var entry:stages.entrySet()) {if(!first)json.append(',');first=false;json.append('"').append(entry.getKey()).append("\":").append(entry.getValue()/1_000_000.0);}
        System.err.println(json.append("}}"));
    }
}
