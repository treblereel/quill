package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.*;

/** Builds Git history, hotspot, and co-change tool responses. */
final class GitToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RECENT_CHANGE_FILES = 200;
    private static final int MAX_ENTITY_MATCHES = 20;

    private static final String NO_GIT_MESSAGE = "No git data available. "
            + "Initialize a git repository and re-run 'quill init' to enable git intelligence: "
            + "git init && git add -A && git commit -m 'initial'";

    String getHotspots(Jdbi jdbi, int limit, String since) {
        return getHotspots(jdbi, limit, since, false);
    }

    String getHotspots(Jdbi jdbi, int limit, String since, boolean includeHistorical) {
        return getHotspots(jdbi, limit, 0, since, includeHistorical);
    }

    String getHotspots(Jdbi jdbi, int limit, int offset, String since,
            boolean includeHistorical) {
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        WorktreeInspector.Snapshot worktree = worktreeSnapshot(metadata);
        boolean hasGit = IndexReader.hasGitData(jdbi);
        if (!hasGit && !worktree.dirty()) return errorResponse(NO_GIT_MESSAGE);

        HotspotSelection selection = hasGit
                ? selectHotspots(jdbi, worktree, limit, offset, since, includeHistorical)
                : new HotspotSelection(List.of(), 0);
        List<GitFileStats> hotspots = selection.shown();
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(jdbi, hotspots.stream()
                .map(GitFileStats::classId).filter(Objects::nonNull).toList());
        Map<String, String> worktreeStatuses = worktree.statusesByRepositoryPath();
        ObjectNode root = JSON.createObjectNode();
        ArrayNode changes = root.putArray("worktree_changes");
        for (WorktreeInspector.Change change : worktree.changes()) {
            ObjectNode node = changes.addObject();
            node.put("file", change.repositoryPath());
            node.put("project_file", change.projectPath());
            node.put("status", change.status());
        }
        ArrayNode arr = root.putArray("hotspots");

        for (GitFileStats s : hotspots) {
            ObjectNode node = arr.addObject();
            node.put("file", s.filePath());
            String worktreeStatus = worktreeStatuses.get(s.filePath());
            if (worktreeStatus != null) node.put("worktree_status", worktreeStatus);
            boolean exists = worktree.repositoryRoot() == null
                    || Files.exists(worktree.repositoryRoot().resolve(s.filePath()).normalize());
            node.put("lifecycle", exists ? "current" : "historical");
            if (s.classId() != null) {
                Optional.ofNullable(classesById.get(s.classId())).ifPresent(c -> {
                    node.put("class", c.className());
                    node.put("is_bean", c.isBean());
                });
            }
            node.put("commit_count", s.commitCount());
            node.put("distinct_authors", s.distinctAuthors());
            node.put("last_modified", s.lastModified());
            node.put("last_author", s.lastAuthor());
        }
        appendPage(root, hotspots.size(), selection.total(), limit, offset);
        root.put("worktree_total", worktree.changes().size());
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    String resolveEntities(Jdbi jdbi, List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return errorResponse("At least one target is required");
        }
        if (targets.size() > MAX_ENTITY_MATCHES) {
            return errorResponse("At most " + MAX_ENTITY_MATCHES + " targets are allowed");
        }
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        WorktreeInspector.Snapshot worktree = worktreeSnapshot(metadata);
        ObjectNode root = JSON.createObjectNode();
        ArrayNode entities = root.putArray("entities");
        for (String target : targets) {
            ObjectNode entity = entities.addObject();
            entity.put("target", target);
            Set<String> currentPaths = new LinkedHashSet<>();
            Set<String> historicalPaths = new LinkedHashSet<>();

            var classLookup = ClassTargetResolver.resolve(jdbi, target);
            if (classLookup.cls() != null && classLookup.cls().sourceFile() != null) {
                currentPaths.add(classLookup.cls().sourceFile());
                entity.put("class", classLookup.cls().className());
            }
            for (FileRecord file : IndexReader.findFileCandidates(
                    jdbi, target, MAX_ENTITY_MATCHES)) {
                if ("current".equals(file.lifecycle())) currentPaths.add(file.repositoryPath());
                else historicalPaths.add(file.repositoryPath());
            }
            for (WorktreeInspector.Change change : worktree.changes()) {
                if (!"deleted".equals(change.status())
                        && pathMatchesTarget(change.repositoryPath(), target)) {
                    currentPaths.add(change.repositoryPath());
                }
            }

            ArrayNode history = entity.putArray("historical_paths");
            for (GitFileStats stats : IndexReader.findHistoricalPathCandidates(
                    jdbi, target, MAX_ENTITY_MATCHES)) {
                historicalPaths.add(stats.filePath());
                ObjectNode path = history.addObject();
                path.put("file", stats.filePath());
                path.put("commit_count", stats.commitCount());
                path.put("last_modified", stats.lastModified());
                path.put("last_author", stats.lastAuthor());
                path.put("current", currentPaths.contains(stats.filePath()));
            }
            // A historical candidate may come from a partial Git index without aggregate stats.
            for (String path : historicalPaths) {
                boolean alreadyPresent = false;
                for (var item : history) {
                    if (path.equals(item.path("file").asText())) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) history.addObject().put("file", path)
                        .put("current", currentPaths.contains(path));
            }
            ArrayNode current = entity.putArray("current_paths");
            currentPaths.forEach(current::add);
            entity.put("current", !currentPaths.isEmpty());
            entity.put("historical", !historicalPaths.isEmpty());
            entity.put("deleted", currentPaths.isEmpty() && !historicalPaths.isEmpty());
            entity.put("resolution", !currentPaths.isEmpty() ? "current"
                    : !historicalPaths.isEmpty() ? "historical" : "not_found");
        }
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    private static boolean pathMatchesTarget(String path, String target) {
        String normalized = target.replace('\\', '/');
        if (path.equals(normalized) || path.endsWith("/" + normalized)) return true;
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        String shortName;
        if (basename.endsWith(".java") || basename.endsWith(".kt")) {
            shortName = basename.substring(0, basename.lastIndexOf('.'));
        } else {
            int dot = basename.lastIndexOf('.');
            shortName = dot >= 0 ? basename.substring(dot + 1) : basename;
        }
        return path.endsWith("/" + shortName)
                || path.endsWith("/" + shortName + ".java")
                || path.endsWith("/" + shortName + ".kt");
    }

    private WorktreeInspector.Snapshot worktreeSnapshot(Map<String, String> metadata) {
        String projectRoot = metadata.get("project_root");
        if (projectRoot == null) return WorktreeInspector.Snapshot.empty();
        try {
            return WorktreeSnapshotCache.shared().get(Path.of(projectRoot));
        } catch (RuntimeException e) {
            return WorktreeInspector.Snapshot.empty();
        }
    }

    private record HotspotSelection(List<GitFileStats> shown, int total) {}

    private HotspotSelection selectHotspots(Jdbi jdbi, WorktreeInspector.Snapshot worktree,
            int limit, int offset, String since, boolean includeHistorical) {
        List<GitFileStats> matching = IndexReader.findHotspots(jdbi, Integer.MAX_VALUE, since);
        if (!includeHistorical) {
            matching = matching.stream()
                    .filter(stats -> isCurrentHotspot(jdbi, worktree, stats.filePath()))
                    .toList();
        }
        int total = matching.size();
        int from = Math.min(offset, total);
        int to = (int) Math.min((long) from + limit, total);
        List<GitFileStats> shown = matching.subList(from, to);
        return new HotspotSelection(shown, total);
    }

    private boolean isCurrentHotspot(Jdbi jdbi, WorktreeInspector.Snapshot worktree,
            String repositoryPath) {
        if (worktree.repositoryRoot() != null) {
            Path candidate = worktree.repositoryRoot().resolve(repositoryPath).normalize();
            return candidate.startsWith(worktree.repositoryRoot()) && Files.exists(candidate);
        }
        return IndexReader.findFileByPath(jdbi, repositoryPath)
                .map(file -> file.lifecycle().equals("current"))
                .orElse(true);
    }

    String getFileHistory(Jdbi jdbi, String target, int limit) {
        return getFileHistory(jdbi, target, limit, 0);
    }

    String getFileHistory(Jdbi jdbi, String target, int limit, int offset) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) {
            String filePath = resolveGitPath(jdbi, target);
            List<GitCommitRecord> commits = IndexReader.findFileHistoryByPath(
                    jdbi, filePath, limit, offset);
            if (commits.isEmpty()) return classLookupError(jdbi, lookup, target);
            ObjectNode root = JSON.createObjectNode();
            root.put("target", filePath);
            root.put("file", filePath);
            root.put("lifecycle", currentFileExists(jdbi, filePath) ? "current" : "historical");
            appendCommits(root, commits);
            int total = IndexReader.findFileStatsByPath(jdbi, filePath)
                    .map(GitFileStats::commitCount).orElse(commits.size());
            appendHistoryPage(root, jdbi, commits.size(), total, limit, offset);
            appendMeta(root, jdbi, 0);
            return root.toString();
        }
        ClassRecord cls = lookup.cls();

        List<GitCommitRecord> commits = IndexReader.findFileHistory(
                jdbi, cls.id(), limit, offset);
        var statsOpt = IndexReader.findFileStatsByClassId(jdbi, cls.id());

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("file", cls.sourceFile());

        appendCommits(root, commits);
        appendHistoryPage(root, jdbi, commits.size(),
                statsOpt.map(GitFileStats::commitCount).orElse(commits.size()), limit, offset);
        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    private void appendHistoryPage(ObjectNode root, Jdbi jdbi, int showing,
            int indexedTotal, int limit, int offset) {
        root.put("total_commits", indexedTotal);
        appendPage(root, showing, indexedTotal, limit, offset);
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        boolean indexComplete = Boolean.parseBoolean(
                metadata.getOrDefault("git_history_complete", "false"));
        root.put("index_history_complete", indexComplete);
        root.put("history_complete", indexComplete && offset + showing >= indexedTotal);
        root.put("repository_commits", parseInt(metadata.get("git_repository_commits")));
        root.put("scanned_repository_commits", parseInt(metadata.get("git_scanned_commits")));
        if (!indexComplete) {
            root.put("history_limitation",
                    "The Git index covers only the newest repository commits; older file history may be absent.");
        }
    }

    private static int parseInt(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    String getCoChanges(Jdbi jdbi, String target, int limit) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) {
            String filePath = resolveGitPath(jdbi, target);
            List<CoChangeRecord> coChanges = IndexReader.findCoChangesByPath(jdbi, filePath, limit);
            if (coChanges.isEmpty() && IndexReader.findFileStatsByPath(jdbi, filePath).isEmpty()) {
                return classLookupError(jdbi, lookup, target);
            }
            int targetCommitCount = IndexReader.findFileStatsByPath(jdbi, filePath)
                    .map(GitFileStats::commitCount).orElse(1);
            return coChangeResponse(jdbi, filePath, coChanges, targetCommitCount);
        }
        ClassRecord cls = lookup.cls();

        var statsOpt = IndexReader.findFileStatsByClassId(jdbi, cls.id());
        int targetCommitCount = statsOpt.map(GitFileStats::commitCount).orElse(1);

        List<CoChangeRecord> coChanges = IndexReader.findCoChanges(jdbi, cls.id(), limit);
        return coChangeResponse(jdbi, cls.className(), coChanges, targetCommitCount);
    }

    private String coChangeResponse(Jdbi jdbi, String target,
            List<CoChangeRecord> coChanges, int targetCommitCount) {
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(jdbi, coChanges.stream()
                .map(CoChangeRecord::classId).filter(Objects::nonNull).toList());

        ObjectNode root = JSON.createObjectNode();
        root.put("target", target);
        ArrayNode arr = root.putArray("co_changes");
        for (CoChangeRecord co : coChanges) {
            ObjectNode node = arr.addObject();
            node.put("file", co.filePath());
            if (co.classId() != null) {
                Optional.ofNullable(classesById.get(co.classId())).ifPresent(c -> {
                    node.put("class", c.className());
                });
            }
            node.put("co_change_count", co.coChangeCount());
            double ratio = (double) co.coChangeCount() / targetCommitCount;
            node.put("coupling_ratio", Math.round(ratio * 100.0) / 100.0);
        }
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    private void appendCommits(ObjectNode root, List<GitCommitRecord> commits) {
        ArrayNode arr = root.putArray("commits");
        Set<String> authorLabels = new TreeSet<>();
        Set<String> authorEmails = new TreeSet<>();
        for (GitCommitRecord commit : commits) {
            ObjectNode node = arr.addObject();
            node.put("hash", commit.shortHash());
            node.put("author", commit.author());
            node.put("author_email", commit.authorEmail());
            node.put("date", commit.committedAt());
            node.put("message", commit.message());
            if (commit.author() != null && !commit.author().isBlank()) {
                authorLabels.add(commit.author());
            }
            if (commit.authorEmail() != null && !commit.authorEmail().isBlank()) {
                authorEmails.add(commit.authorEmail());
            }
        }

        ObjectNode window = root.putObject("returned_window");
        window.put("commit_count", commits.size());
        window.put("distinct_author_labels", authorLabels.size());
        ArrayNode labels = window.putArray("author_labels");
        authorLabels.forEach(labels::add);
        window.put("distinct_author_emails", authorEmails.size());
        ArrayNode emails = window.putArray("author_emails");
        authorEmails.forEach(emails::add);
        window.put("identity_resolution", "not_attempted");
        window.put("history_semantics",
                "commits reachable from the indexed HEAD whose tree differs from the first parent "
                        + "for this file; merge commits may be included");
    }

    private String resolveGitPath(Jdbi jdbi, String target) {
        return IndexReader.findFileByPath(jdbi, target)
                .map(FileRecord::repositoryPath)
                .orElse(target.replace('\\', '/'));
    }

    private boolean currentFileExists(Jdbi jdbi, String repositoryPath) {
        WorktreeInspector.Snapshot snapshot = worktreeSnapshot(IndexReader.getMetadata(jdbi));
        if (snapshot.repositoryRoot() != null) {
            return Files.exists(snapshot.repositoryRoot().resolve(repositoryPath).normalize());
        }
        return IndexReader.findFileByPath(jdbi, repositoryPath)
                .map(file -> file.lifecycle().equals("current"))
                .orElse(false);
    }

    String getRecentChanges(Jdbi jdbi, int commitCount) {
        return getRecentChanges(jdbi, commitCount, MAX_RECENT_CHANGE_FILES, 0, true);
    }

    String getRecentChanges(Jdbi jdbi, int commitCount, int fileLimit, int fileOffset,
            boolean details) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        List<GitCommitRecord> commits = IndexReader.findRecentCommits(jdbi, commitCount);
        List<Integer> commitIds = commits.stream().map(GitCommitRecord::id).toList();
        int totalFiles = IndexReader.countCommitFiles(jdbi, commitIds);
        Map<Integer, List<GitCommitFile>> filesByCommit = IndexReader.findCommitFiles(jdbi,
                commitIds, fileLimit, fileOffset);
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(jdbi, filesByCommit.values()
                .stream().flatMap(Collection::stream).map(GitCommitFile::classId)
                .filter(Objects::nonNull).toList());
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("recent_changes");
        int fileCount = 0;

        for (GitCommitRecord c : commits) {
            ObjectNode node = arr.addObject();
            node.put("commit", c.shortHash());
            if (details) {
                node.put("author", c.author());
                node.put("date", c.committedAt());
                node.put("message", c.message());
            }

            List<GitCommitFile> files = filesByCommit.getOrDefault(c.id(), List.of());
            ArrayNode filesArr = node.putArray("files");
            for (GitCommitFile f : files) {
                ObjectNode fNode = filesArr.addObject();
                fileCount++;
                fNode.put("file", f.filePath());
                fNode.put("change_type", f.changeType());
                if (details && f.classId() != null) {
                    Optional.ofNullable(classesById.get(f.classId())).ifPresent(cl -> {
                        fNode.put("class", cl.className());
                        fNode.put("is_bean", cl.isBean());
                    });
                }
            }
        }
        root.put("commit_count", commits.size());
        root.put("details", details);
        appendPage(root, fileCount, totalFiles, fileLimit, fileOffset);
        appendMeta(root, jdbi, 0);
        return root.toString();
    }


    List<GitFileStats> currentHotspots(
            Jdbi jdbi, Map<String, String> metadata, int limit) {
        WorktreeInspector.Snapshot worktree = worktreeSnapshot(metadata);
        return selectHotspots(jdbi, worktree, limit, 0, null, false).shown();
    }
}
