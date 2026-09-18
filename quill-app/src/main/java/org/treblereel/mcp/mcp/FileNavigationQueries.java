package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;

/** Compact file navigation over the indexed, worktree-aware file inventory. */
final class FileNavigationQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String listProjectTree(Jdbi jdbi, String path, int depth, boolean includeDeleted,
            int limit, int offset) {
        String base = normalizeBase(path);
        List<FileRow> files = files(jdbi, base, includeDeleted);
        Map<String, TreeEntry> entries = new LinkedHashMap<>();
        for (FileRow file : files) {
            String relative = relative(base, file.path());
            if (relative == null || relative.isBlank()) continue;
            String[] parts = relative.split("/");
            int visibleParts = Math.min(parts.length, depth);
            StringBuilder current = new StringBuilder(base.equals(".") ? "" : base + "/");
            for (int i = 0; i < visibleParts; i++) {
                if (i > 0) current.append('/');
                current.append(parts[i]);
                boolean directory = i < parts.length - 1;
                String entryPath = current.toString();
                entries.putIfAbsent(entryPath, directory
                        ? new TreeEntry(entryPath, "directory", null)
                        : new TreeEntry(entryPath, "file", file));
            }
        }
        List<TreeEntry> ordered = entries.values().stream()
                .sorted(Comparator.comparing(TreeEntry::path)).toList();
        return treeResponse(base, depth, ordered, limit, offset);
    }

    String searchFiles(Jdbi jdbi, String pattern, String module, String kind,
            boolean includeDeleted, int limit, int offset) {
        String needle = pattern.strip().toLowerCase(Locale.ROOT).replace("*", "");
        List<FileRow> matches = files(jdbi, ".", includeDeleted).stream()
                .filter(file -> needle.isEmpty()
                        || file.path().toLowerCase(Locale.ROOT).contains(needle))
                .filter(file -> module == null || module.isBlank()
                        || normalizeBase(module).equals(normalizeBase(file.module())))
                .filter(file -> kind == null || kind.isBlank()
                        || file.kind().equalsIgnoreCase(kind.strip()))
                .sorted(Comparator.comparing(FileRow::path)).toList();
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + limit, matches.size());
        ObjectNode result = page(matches.size(), to - from, offset, to < matches.size());
        result.put("pattern", pattern);
        ArrayNode files = result.putArray("files");
        matches.subList(from, to).forEach(file -> addFile(files, file));
        return result.toString();
    }

    private static List<FileRow> files(Jdbi jdbi, String base, boolean includeDeleted) {
        String prefix = base.equals(".") ? "" : base + "/";
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT repository_path, kind, origin, lifecycle, worktree_status,
                               module, source_set
                        FROM files
                        WHERE (:includeDeleted = 1 OR lifecycle = 'current')
                          AND (:prefix = '' OR repository_path = :base
                               OR repository_path LIKE :prefix || '%')
                        ORDER BY repository_path
                        """)
                .bind("includeDeleted", includeDeleted ? 1 : 0)
                .bind("prefix", prefix)
                .bind("base", base)
                .map((row, context) -> new FileRow(
                        row.getString("repository_path"), row.getString("kind"),
                        row.getString("origin"), row.getString("lifecycle"),
                        row.getString("worktree_status"), row.getString("module"),
                        row.getString("source_set")))
                .list());
    }

    private static String treeResponse(
            String base, int depth, List<TreeEntry> entries, int limit, int offset) {
        int from = Math.min(offset, entries.size());
        int to = Math.min(from + limit, entries.size());
        ObjectNode result = page(entries.size(), to - from, offset, to < entries.size());
        result.put("path", base);
        result.put("depth", depth);
        ArrayNode listed = result.putArray("entries");
        entries.subList(from, to).forEach(entry -> {
            ObjectNode item = listed.addObject();
            item.put("path", entry.path());
            item.put("type", entry.type());
            if (entry.file() != null) addFileContext(item, entry.file());
        });
        return result.toString();
    }

    private static ObjectNode page(int total, int showing, int offset, boolean hasMore) {
        ObjectNode result = JSON.createObjectNode();
        result.put("total", total);
        result.put("showing", showing);
        result.put("offset", offset);
        result.put("has_more", hasMore);
        return result;
    }

    private static void addFile(ArrayNode files, FileRow file) {
        ObjectNode item = files.addObject();
        item.put("path", file.path());
        addFileContext(item, file);
    }

    private static void addFileContext(ObjectNode item, FileRow file) {
        item.put("kind", file.kind());
        item.put("origin", file.origin());
        item.put("lifecycle", file.lifecycle());
        if (file.worktreeStatus() != null) item.put("worktree_status", file.worktreeStatus());
        if (file.module() != null) item.put("module", file.module());
        if (file.sourceSet() != null) item.put("source_set", file.sourceSet());
    }

    private static String normalizeBase(String value) {
        if (value == null || value.isBlank() || value.equals(".")) return ".";
        String normalized = value.strip().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isBlank() ? "." : normalized;
    }

    private static String relative(String base, String path) {
        if (base.equals(".")) return path;
        if (path.equals(base)) return path.substring(path.lastIndexOf('/') + 1);
        return path.startsWith(base + "/") ? path.substring(base.length() + 1) : null;
    }

    private record FileRow(String path, String kind, String origin, String lifecycle,
            String worktreeStatus, String module, String sourceSet) {}
    private record TreeEntry(String path, String type, FileRow file) {}
}
