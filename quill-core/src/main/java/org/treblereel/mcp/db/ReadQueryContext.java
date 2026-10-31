package org.treblereel.mcp.db;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.analysis.*;

/** One project task's handle, repositories and reusable observations; never shared across tasks. */
public final class ReadQueryContext implements AutoCloseable {
    private final Jdbi database;
    private final Handle handle;
    private final boolean ownsHandle;
    private boolean closed;
    private final JvmTypeGraph graph;
    public final SymbolRepository symbols;
    public final UsageRepository usages;
    public final BeanRepository beans;
    public final IndexRepository index;
    public final GitRepository git;
    public final ReferenceRepository references;
    private JvmMethodResolver resolver;
    private JsonNode sourceFreshness, buildFreshness;
    private org.treblereel.mcp.model.ProjectReadSnapshot snapshot;

    public org.treblereel.mcp.model.ProjectReadSnapshot snapshot() { return snapshot; }

    public void useSnapshot(org.treblereel.mcp.model.ProjectReadSnapshot prepared) {
        snapshot = prepared;
        if (prepared == null) return;
        symbols.useClasses(prepared.classes());
        index.useMetadata(prepared.metadata());
        sourceFreshness = prepared.sourceFreshness().deepCopy();
        buildFreshness = prepared.buildFreshness().deepCopy();
    }

    private ReadQueryContext(Jdbi database, Handle handle, JvmTypeGraph graph, boolean ownsHandle) {
        this.database = database;
        this.handle = handle;
        this.ownsHandle = ownsHandle;
        this.graph = graph;
        symbols = new SymbolRepository(handle, graph == null ? null : graph.classes);
        usages = new UsageRepository(handle);
        beans = new BeanRepository(handle);
        index = new IndexRepository(handle);
        git = new GitRepository(handle);
        references = new ReferenceRepository(handle);
    }

    public static <T> T read(Jdbi database, Function<ReadQueryContext,T> work) {
        try (var context = open(database)) { return work.apply(context); }
    }

    public static <T> T readWithGraph(Jdbi database, Function<ReadQueryContext,T> work) {
        try (var context = openWithGraph(database)) { return work.apply(context); }
    }

    static <T> T read(IndexGeneration generation, Function<ReadQueryContext,T> work, boolean withGraph) {
        // A graph query waits before borrowing its request reader; SQL-only queries skip it.
        var graph = withGraph ? generation.graph() : null;
        try (var context = open(generation.jdbi(), graph)) { return work.apply(context); }
    }

    public static ReadQueryContext open(Jdbi database) { return open(database, null); }

    public static ReadQueryContext openWithGraph(Jdbi database) {
        return open(database, database.withHandle(IndexGenerationCache::load));
    }

    private static ReadQueryContext open(Jdbi database, JvmTypeGraph graph) {
        var scope = database.getHandleScope();
        var parent = scope.get();
        if (parent != null) return new ReadQueryContext(database, parent.getHandle(), graph, false);
        Handle handle = database.open();
        try {
            var context = new ReadQueryContext(database, handle, graph, true);
            scope.set(handle);
            return context;
        } catch (RuntimeException | Error failure) {
            try { handle.close(); } catch (RuntimeException | Error closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    /** Background work opens a scope when invoked and never captures this task's handle. */
    public <T> Supplier<T> independentRead(Function<ReadQueryContext,T> work) {
        Jdbi target = database;
        JvmTypeGraph evidence = graph;
        var observations = snapshot;
        return () -> {
            try (var context = open(target, evidence)) {
                context.useSnapshot(observations);
                return work.apply(context);
            }
        };
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (ownsHandle) {
            database.getHandleScope().clear();
            handle.close();
        }
    }

    public boolean hasGraph() { return graph != null; }

    public JvmTypeGraph graph() {
        if (graph == null) throw new IllegalStateException("This operation requires readWithGraph");
        return graph;
    }

    public JvmMethodResolver methods() {
        if (resolver == null) resolver = new JvmMethodResolver(graph(), graph().members,
                symbols::findJvmClassMembers, usages::findMethodOwnerCandidates);
        return resolver;
    }

    public JsonNode sourceFreshness(Supplier<JsonNode> read) {
        if (sourceFreshness == null) sourceFreshness = read.get();
        return sourceFreshness;
    }

    public JsonNode buildFreshness(Supplier<JsonNode> read) {
        if (buildFreshness == null) buildFreshness = read.get();
        return buildFreshness;
    }
}
