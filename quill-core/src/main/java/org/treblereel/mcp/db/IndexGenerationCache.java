package org.treblereel.mcp.db;

import java.util.*;
import org.jdbi.v3.core.Handle;
import org.treblereel.mcp.analysis.JvmTypeGraph;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ClassMemberRecord;

/** Registry-owned memory budget; generations own initialization and reader lifetimes. */
public final class IndexGenerationCache {
    private static final int MAX_GENERATIONS = 8, MAX_WEIGHT = 100_000;
    private final Map<IndexGeneration,JvmTypeGraph> graphs = new LinkedHashMap<>(16, .75f, true);
    public IndexGenerationCache() {}

    synchronized JvmTypeGraph get(IndexGeneration generation) {
        var graph = graphs.get(generation);
        trim();
        return graph;
    }

    synchronized void retain(IndexGeneration generation, JvmTypeGraph graph) {
        graphs.remove(generation);
        if (graph.retainedWeight() <= MAX_WEIGHT) graphs.put(generation, graph);
        trim();
    }

    private void trim() {
        while (!graphs.isEmpty() && (graphs.size() > MAX_GENERATIONS || totalWeight() > MAX_WEIGHT))
            graphs.remove(graphs.keySet().iterator().next());
    }

    private long totalWeight() { return graphs.values().stream().mapToLong(JvmTypeGraph::retainedWeight).sum(); }
    synchronized void invalidate(IndexGeneration generation) { graphs.remove(generation); }
    synchronized int size() { return graphs.size(); }

    static JvmTypeGraph load(Handle handle) {
        var symbols = new SymbolRepository(handle);
        var classes = symbols.findAllClasses();
        Map<Integer, List<ClassMemberRecord>> members = new HashMap<>();
        if (classes.size() <= 5000) {
            members.putAll(symbols.findJvmClassMembers(classes.stream().map(ClassRecord::id).toList()));
            classes.forEach(c -> members.putIfAbsent(c.id(), List.of()));
        }
        Set<String> ambiguous = symbols.findAmbiguousTypeNames();
        return new JvmTypeGraph(classes, members, ambiguous);
    }

}
