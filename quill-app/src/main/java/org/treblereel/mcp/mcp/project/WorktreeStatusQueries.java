package org.treblereel.mcp.mcp.project;

import org.treblereel.mcp.index.BuildStatusInspector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.model.MetaEnvelope;

/** Live Git worktree state paired with the commit represented by the index. */
public final class WorktreeStatusQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    public String getWorktreeStatus(
            ReadQueryContext reader, Path root, String status, int limit, int offset) {
        return getWorktreeStatus(reader, root, status, limit, offset, false);
    }

    public String getFreshWorktreeStatus(
            ReadQueryContext reader, Path root, String status, int limit, int offset) {
        return getWorktreeStatus(reader, root, status, limit, offset, true);
    }

    private String getWorktreeStatus(
            ReadQueryContext reader, Path root, String status, int limit, int offset, boolean fresh) {
        if (reader.snapshot() != null) return page(reader.snapshot().worktree(), status, limit, offset);
        WorktreeInspector.Snapshot snapshot = fresh
                ? WorktreeSnapshotCache.shared().refresh(root) : WorktreeSnapshotCache.shared().get(root);
        return page(capture(root, reader.index.getMetadata(), snapshot), status, limit, offset);
    }

    static ObjectNode capture(Path root, Map<String,String> metadata, WorktreeInspector.Snapshot snapshot) {
        MetaEnvelope freshness = MetaEnvelope.from(metadata, snapshot.currentCommit(), snapshot.structuralFingerprint(),
                snapshot.structuralDirty(), snapshot.dirty(), snapshot.changes().size(), snapshot.structuralChanges().size(), 0, 0);
        ObjectNode result = JSON.createObjectNode();
        result.put("branch", GitAnalyzer.resolveBranch(root));
        result.put("repository_root", snapshot.repositoryRoot() == null
                ? null : snapshot.repositoryRoot().toString());
        result.put("indexed_commit", freshness.lastCommit());
        if (snapshot.currentCommit() == null) result.putNull("current_commit");
        else result.put("current_commit", snapshot.currentCommit());
        result.put("commit_stale", freshness.commitStale());
        result.put("dirty", snapshot.dirty());
        result.put("structural_dirty", snapshot.structuralDirty());
        result.put("total", snapshot.changes().size());
        Map<String, Integer> counts = new TreeMap<>();
        snapshot.changes().forEach(change -> counts.merge(change.status(), 1, Integer::sum));
        result.set("counts_by_status", JSON.valueToTree(counts));
        ArrayNode listed = result.putArray("changes");
        snapshot.changes().forEach(change -> {
            ObjectNode item = listed.addObject();
            item.put("path", change.projectPath());
            item.put("repository_path", change.repositoryPath());
            item.put("status", change.status());
            item.put("structural", snapshot.structuralChanges().contains(change));
        });
        var submodules=result.putArray("submodules");
        for(var module:org.treblereel.mcp.core.SubmoduleInventory.read(root))
            submodules.addObject().put("path",module.path()).put("index_commit",module.indexCommit())
                    .put("head_commit",module.headCommit()).put("initialized",module.initialized()).put("status",module.status());
        result.put("source", "live_git_worktree");
        result.put("observed_at", java.time.Instant.now().toString());
        return result;
    }

    private static String page(com.fasterxml.jackson.databind.JsonNode snapshot, String status, int limit, int offset) {
        ObjectNode result = snapshot.deepCopy();
        String filter = status == null || status.isBlank() ? null : status.strip().toLowerCase(Locale.ROOT);
        var changes = snapshot.path("changes").valueStream()
                .filter(change -> filter == null || change.path("status").asText().equals(filter)).toList();
        int from = Math.min(offset, changes.size()), to = Math.min(from + limit, changes.size());
        ArrayNode listed = result.putArray("changes");
        changes.subList(from, to).forEach(change -> listed.add(change.deepCopy()));
        result.put("total", changes.size()).put("showing", to-from).put("offset", offset).put("has_more", to < changes.size());
        return result.toString();
    }
}
