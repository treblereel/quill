package org.treblereel.mcp.db;

import java.util.*;
import org.jdbi.v3.core.Handle;
import org.treblereel.mcp.analysis.JvmTypeGraph;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ClassMemberRecord;

/**
 * Registry-owned memory budget; generations own initialization and reader lifetimes.
 *
 * <p>Two bounds apply: one graph may weigh at most {@code generationWeight}, so a single large project
 * stays cacheable, and all graphs together at most {@code totalWeight}. The number of generations kept
 * follows the workspace: each project holds its current generation and may be preparing the next.
 */
public final class IndexGenerationCache {
    static final long DEFAULT_GENERATION_WEIGHT = 400_000, DEFAULT_TOTAL_WEIGHT = 800_000;
    private static final int MIN_GENERATIONS = 8;
    private final long generationWeight, totalWeight;
    private int projects = 1;
    private final Map<IndexGeneration,JvmTypeGraph> graphs = new LinkedHashMap<>(16, .75f, true);

    public IndexGenerationCache() {
        this(DEFAULT_GENERATION_WEIGHT, ReadConcurrency.positiveLong(System.getenv("QUILL_GRAPH_CACHE_WEIGHT"), DEFAULT_TOTAL_WEIGHT));
    }

    IndexGenerationCache(long generationWeight, long totalWeight) {
        if (generationWeight < 1 || totalWeight < 1) throw new IllegalArgumentException("Invalid cache bounds");
        this.generationWeight = Math.min(generationWeight, totalWeight);
        this.totalWeight = totalWeight;
    }

    /** Sizes the generation count for a workspace of {@code count} projects. */
    public synchronized void expectProjects(int count) {
        projects = Math.max(1, count);
        trim(null);
    }

    synchronized JvmTypeGraph get(IndexGeneration generation) {
        var graph = graphs.get(generation);
        // Derived results may have grown other graphs; the one just read is the last to go.
        trim(generation);
        return graph;
    }

    synchronized void retain(IndexGeneration generation, JvmTypeGraph graph) {
        graphs.remove(generation);
        if (graph.retainedWeight() <= generationWeight) graphs.put(generation, graph);
        trim(generation);
    }

    private void trim(IndexGeneration keep) {
        if (keep != null) {
            var kept = graphs.get(keep);
            if (kept != null && kept.retainedWeight() > generationWeight) graphs.remove(keep);
        }
        var eldest = graphs.keySet().iterator();
        while (eldest.hasNext() && (graphs.size() > maxGenerations() || totalWeight() > totalWeight)) {
            var candidate = eldest.next();
            if (candidate != keep) eldest.remove();
        }
    }

    int maxGenerations() { return Math.max(MIN_GENERATIONS, 2 * projects); }
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
