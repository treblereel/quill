package org.treblereel.mcp.db;

import java.util.Map;
import java.util.function.Supplier;
import org.jdbi.v3.core.Jdbi;

/** One pinned handle and reusable evidence per read-only response. Nested reads share Jdbi's handle scope. */
public final class ReadQueryContext {
    private static final ThreadLocal<ReadQueryContext> CURRENT=new ThreadLocal<>();
    private final Jdbi database;
    private JvmMethodResolver resolver;
    private Map<String,String> metadata;
    private ReadQueryContext(Jdbi database) {this.database=database;}
    public static <T> T read(Jdbi database,Supplier<T> work) {
        ReadQueryContext parent=CURRENT.get();
        if(parent!=null && parent.database==database) return work.get();
        return database.withHandle(handle->{
            CURRENT.set(new ReadQueryContext(database));
            try{return work.get();}finally{if(parent==null)CURRENT.remove();else CURRENT.set(parent);}
        });
    }
    public static JvmMethodResolver resolver(Jdbi database) {
        var context=CURRENT.get();
        if(context==null || context.database!=database) return new JvmMethodResolver(database);
        if(context.resolver==null)context.resolver=new JvmMethodResolver(database);
        return context.resolver;
    }
    static Map<String,String> metadata(Jdbi database,Supplier<Map<String,String>> read) {
        var context=CURRENT.get();
        if(context==null || context.database!=database)return read.get();
        if(context.metadata==null)context.metadata=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(read.get()));
        return context.metadata;
    }
}
