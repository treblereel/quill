package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.model.MetaEnvelope;

/** Live Git worktree state paired with the commit represented by the index. */
final class WorktreeStatusQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String getWorktreeStatus(
            Jdbi jdbi, Path root, String status, int limit, int offset) {
        return getWorktreeStatus(jdbi, root, status, limit, offset, false);
    }

    String getFreshWorktreeStatus(
            Jdbi jdbi, Path root, String status, int limit, int offset) {
        return getWorktreeStatus(jdbi, root, status, limit, offset, true);
    }

    private String getWorktreeStatus(
            Jdbi jdbi, Path root, String status, int limit, int offset, boolean fresh) {
        WorktreeInspector.Snapshot snapshot = fresh
                ? WorktreeSnapshotCache.shared().refresh(root)
                : WorktreeSnapshotCache.shared().get(root);
        MetaEnvelope freshness = MetaEnvelope.from(jdbi, 0, 0);
        String filter = status == null || status.isBlank()
                ? null : status.strip().toLowerCase(Locale.ROOT);
        List<WorktreeInspector.Change> changes = snapshot.changes().stream()
                .filter(change -> filter == null || change.status().equals(filter)).toList();
        int from = Math.min(offset, changes.size());
        int to = Math.min(from + limit, changes.size());

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
        result.put("total", changes.size());
        result.put("showing", to - from);
        result.put("offset", offset);
        result.put("has_more", to < changes.size());
        Map<String, Integer> counts = new TreeMap<>();
        snapshot.changes().forEach(change -> counts.merge(change.status(), 1, Integer::sum));
        result.set("counts_by_status", JSON.valueToTree(counts));
        ArrayNode listed = result.putArray("changes");
        changes.subList(from, to).forEach(change -> {
            ObjectNode item = listed.addObject();
            item.put("path", change.projectPath());
            item.put("repository_path", change.repositoryPath());
            item.put("status", change.status());
            item.put("structural", WorktreeInspector.isStructuralPath(change.projectPath()));
        });
        result.put("source", "live_git_worktree");
        return result.toString();
    }
}
