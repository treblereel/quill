package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.*;

/** Resolves class and file targets and calculates change-risk signals. */
final class ChangeRiskQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String getRisk(Jdbi jdbi, String target) {
        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) {
            if ("Class not found".equals(lookup.error())) {
                Optional<FileRiskTarget> file = resolveFileRiskTarget(jdbi, target);
                if (file.isPresent()) return getFileRisk(jdbi, file.get());
            }
            return classLookupError(jdbi, lookup, target);
        }
        return getClassRisk(jdbi, lookup.cls());
    }

    private String getClassRisk(Jdbi jdbi, ClassRecord cls) {
        int fanIn = IndexReader.countDependents(jdbi, cls.id());
        int fanOut = IndexReader.countDependencies(jdbi, cls.id());
        int incomingEdges = IndexReader.countDependencyEdges(jdbi, cls.id(), true);
        int outgoingEdges = IndexReader.countDependencyEdges(jdbi, cls.id(), false);

        boolean hasGit = IndexReader.hasGitData(jdbi);
        var statsOpt = hasGit ? IndexReader.findFileStatsByClassId(jdbi, cls.id()) : Optional.<GitFileStats>empty();
        int churn = statsOpt.map(GitFileStats::commitCount).orElse(0);
        int authors = statsOpt.map(GitFileStats::distinctAuthors).orElse(0);
        int coChangeCount = 0;
        if (hasGit) {
            coChangeCount = IndexReader.findCoChanges(jdbi, cls.id(), 100).size();
        }

        double fanInScore = scaleScore(fanIn, 0, 3, 8, 15);
        double fanOutScore = scaleScore(fanOut, 0, 3, 5, 8);
        double churnScore = hasGit ? scaleScore(churn, 2, 10, 30, 50) : 0;
        double busFactorScore = hasGit ? busFactorScore(authors) : 0;
        double couplingScore = hasGit ? scaleScore(coChangeCount, 0, 3, 5, 8) : 0;

        double score = fanInScore * 0.30
                + fanOutScore * 0.10
                + churnScore * 0.25
                + busFactorScore * 0.20
                + couplingScore * 0.15;
        score = Math.round(score * 10.0) / 10.0;

        String level = riskLevel(score);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("target_type", "class");
        if (cls.sourceFile() != null) root.put("file", cls.sourceFile());
        root.put("risk_score", score);
        root.put("risk_level", level);

        ObjectNode signals = root.putObject("signals");
        addSignal(signals, "fan_in", fanIn, fanInScore, 0.30,
                fanIn + " unique classes depend on this");
        addSignal(signals, "fan_out", fanOut, fanOutScore, 0.10,
                "depends on " + fanOut + " unique classes");
        ObjectNode fanInNode = (ObjectNode) signals.get("fan_in");
        fanInNode.put("edges", incomingEdges);
        appendDependencyBreakdown(fanInNode, IndexReader.dependencyBreakdown(jdbi, cls.id(), true));
        ObjectNode fanOutNode = (ObjectNode) signals.get("fan_out");
        fanOutNode.put("edges", outgoingEdges);
        appendDependencyBreakdown(fanOutNode, IndexReader.dependencyBreakdown(jdbi, cls.id(), false));
        if (hasGit) {
            addSignal(signals, "git_churn", churn, churnScore, 0.25,
                    churn + " commits — " + (churn >= 30 ? "high" : churn >= 10 ? "moderate" : "low") + " change frequency");
            addSignal(signals, "bus_factor", authors, busFactorScore, 0.20,
                    authors <= 1 ? "only 1 author — single point of knowledge"
                            : authors + " authors");
            addSignal(signals, "coupling", coChangeCount, couplingScore, 0.15,
                    coChangeCount + " files frequently co-change");
        } else {
            ObjectNode gitNote = signals.putObject("git");
            gitNote.put("note", "Git data unavailable — git signals excluded from score. Run 'quill init' in a git repository.");
        }

        root.put("recommendation", buildRecommendation(cls, fanIn, churn, authors, level, hasGit));

        appendMeta(root, jdbi, cls.sourceTokens(), cls.sourceFile(), cls.module());
        return root.toString();
    }

    private record FileRiskTarget(String projectPath, String repositoryPath, String kind,
            String origin, String lifecycle, String worktreeStatus) {}

    private record FileCriticality(int score, String note) {}

    private Optional<FileRiskTarget> resolveFileRiskTarget(Jdbi jdbi, String target) {
        if (target == null || target.isBlank()) return Optional.empty();
        String normalized = target.strip().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        WorktreeInspector.Snapshot worktree = worktreeSnapshot(metadata);

        String projectPath = normalized;
        String repositoryPath = normalized;
        try {
            Path supplied = Path.of(target).toAbsolutePath().normalize();
            if (Path.of(target).isAbsolute()) {
                String projectRootValue = metadata.get("project_root");
                if (projectRootValue != null) {
                    Path projectRoot = Path.of(projectRootValue).toAbsolutePath().normalize();
                    if (supplied.startsWith(projectRoot)) {
                        projectPath = normalizePath(projectRoot.relativize(supplied));
                    }
                }
                if (worktree.repositoryRoot() != null && supplied.startsWith(worktree.repositoryRoot())) {
                    repositoryPath = normalizePath(worktree.repositoryRoot().relativize(supplied));
                }
            }
        } catch (RuntimeException ignored) {
            // The exact database/worktree lookup below still handles portable path strings.
        }

        Optional<FileRecord> indexed = IndexReader.findFileByPath(jdbi, projectPath);
        if (indexed.isEmpty() && !repositoryPath.equals(projectPath)) {
            indexed = IndexReader.findFileByPath(jdbi, repositoryPath);
        }
        if (indexed.isPresent()) {
            FileRecord file = indexed.get();
            String status = worktree.statusesByRepositoryPath().get(file.repositoryPath());
            String lifecycle = status != null && status.equals("deleted")
                    ? "deleted" : liveLifecycle(worktree, file.repositoryPath(), file.lifecycle());
            String inferredKind = fileKind(file.projectPath());
            return Optional.of(new FileRiskTarget(file.projectPath(), file.repositoryPath(),
                    inferredKind.equals("file") ? file.kind() : inferredKind,
                    file.origin(), lifecycle, status));
        }

        for (WorktreeInspector.Change change : worktree.changes()) {
            if (change.projectPath().equals(projectPath)
                    || change.repositoryPath().equals(repositoryPath)
                    || change.repositoryPath().equals(projectPath)) {
                return Optional.of(new FileRiskTarget(change.projectPath(), change.repositoryPath(),
                        fileKind(change.projectPath()), fileOrigin(change.projectPath()),
                        change.status().equals("deleted") ? "deleted" : "current",
                        change.status()));
            }
        }

        String gitPath = repositoryPath;
        Optional<GitFileStats> stats = IndexReader.findFileStatsByPath(jdbi, gitPath);
        if (stats.isEmpty() && !projectPath.equals(gitPath)) {
            stats = IndexReader.findFileStatsByPath(jdbi, projectPath);
            if (stats.isPresent()) gitPath = projectPath;
        }
        if (stats.isPresent()) {
            return Optional.of(new FileRiskTarget(projectPath, gitPath, fileKind(projectPath),
                    fileOrigin(projectPath), liveLifecycle(worktree, gitPath, "historical"), null));
        }

        String projectRootValue = metadata.get("project_root");
        if (projectRootValue != null) {
            try {
                Path projectRoot = Path.of(projectRootValue).toAbsolutePath().normalize();
                Path candidate = projectRoot.resolve(projectPath).normalize();
                if (candidate.startsWith(projectRoot) && Files.isRegularFile(candidate)) {
                    String liveRepositoryPath = worktree.repositoryRoot() != null
                            && candidate.startsWith(worktree.repositoryRoot())
                            ? normalizePath(worktree.repositoryRoot().relativize(candidate))
                            : projectPath;
                    return Optional.of(new FileRiskTarget(projectPath, liveRepositoryPath,
                            fileKind(projectPath), fileOrigin(projectPath), "current", null));
                }
            } catch (RuntimeException ignored) {
                // Invalid or inaccessible paths are reported through the regular lookup error.
            }
        }
        return Optional.empty();
    }

    private String getFileRisk(Jdbi jdbi, FileRiskTarget file) {
        Optional<GitFileStats> stats = IndexReader.findFileStatsByPath(jdbi, file.repositoryPath());
        boolean hasFileHistory = stats.isPresent();
        int churn = stats.map(GitFileStats::commitCount).orElse(0);
        int authors = stats.map(GitFileStats::distinctAuthors).orElse(0);
        List<CoChangeRecord> coChanges = hasFileHistory
                ? IndexReader.findCoChangesByPath(jdbi, file.repositoryPath(), 100) : List.of();
        int coupling = coChanges.size();

        FileCriticality criticality = fileCriticality(file.projectPath());
        double churnScore = scaleScore(churn, 2, 10, 30, 50);
        double authorScore = busFactorScore(authors);
        double couplingScore = scaleScore(coupling, 0, 3, 5, 8);
        double criticalityWeight = hasFileHistory ? 0.50 : 1.0;
        double score = criticality.score() * criticalityWeight;
        if (hasFileHistory) {
            score += churnScore * 0.20 + authorScore * 0.15 + couplingScore * 0.15;
        }
        score = Math.round(score * 10.0) / 10.0;
        String level = riskLevel(score);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", file.repositoryPath());
        root.put("target_type", "file");
        root.put("file", file.repositoryPath());
        root.put("project_file", file.projectPath());
        root.put("kind", file.kind());
        root.put("origin", file.origin());
        root.put("lifecycle", file.lifecycle());
        if (file.worktreeStatus() != null) root.put("worktree_status", file.worktreeStatus());
        root.put("risk_score", score);
        root.put("risk_level", level);

        ObjectNode signals = root.putObject("signals");
        addSignal(signals, "file_criticality", criticality.score(), criticality.score(),
                criticalityWeight, criticality.note());
        if (hasFileHistory) {
            addSignal(signals, "git_churn", churn, churnScore, 0.20,
                    churn + " commits — " + (churn >= 30 ? "high" : churn >= 10 ? "moderate" : "low")
                            + " change frequency");
            addSignal(signals, "bus_factor", authors, authorScore, 0.15,
                    authors <= 1 ? "only 1 author — single point of knowledge" : authors + " authors");
            addSignal(signals, "coupling", coupling, couplingScore, 0.15,
                    coupling + " files frequently co-change");
            ArrayNode related = ((ObjectNode) signals.get("coupling")).putArray("top_files");
            for (CoChangeRecord coChange : coChanges.stream().limit(10).toList()) {
                ObjectNode node = related.addObject();
                node.put("file", coChange.filePath());
                node.put("co_change_count", coChange.coChangeCount());
            }
        } else {
            ObjectNode git = signals.putObject("git");
            git.put("note", "No Git history for this file; the score is based on file criticality only.");
        }

        root.put("recommendation", buildFileRecommendation(file, criticality, churn, authors,
                coupling, level, hasFileHistory));
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    private static String liveLifecycle(
            WorktreeInspector.Snapshot worktree, String repositoryPath, String fallback) {
        if (worktree.repositoryRoot() == null) return fallback;
        Path candidate = worktree.repositoryRoot().resolve(repositoryPath).normalize();
        return candidate.startsWith(worktree.repositoryRoot()) && Files.exists(candidate)
                ? "current" : fallback;
    }

    private static String normalizePath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static String fileKind(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (normalized.contains("/META-INF/services/") || normalized.startsWith("META-INF/services/")) {
            return "service_descriptor";
        }
        if (isBuildConfiguration(normalized, name)) return "build_configuration";
        if (normalized.startsWith(".github/workflows/") || normalized.contains("/.github/workflows/")) {
            return "ci_configuration";
        }
        if (normalized.contains("/resources/") || normalized.startsWith("src/main/resources/")) {
            return "resource";
        }
        if (name.endsWith(".java") || name.endsWith(".kt")) return "source";
        if (normalized.startsWith("src/test/") || normalized.contains("/src/test/")) return "test";
        if (name.endsWith(".md") || name.endsWith(".adoc")) return "documentation";
        return "file";
    }

    private static String fileOrigin(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.contains("/generated/") || normalized.contains("/generated-sources/")) {
            return "generated";
        }
        if (fileKind(path).equals("resource") || fileKind(path).equals("service_descriptor")) {
            return "resource";
        }
        return "source";
    }

    private static FileCriticality fileCriticality(String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (normalized.contains("/META-INF/services/") || normalized.startsWith("META-INF/services/")) {
            return new FileCriticality(10,
                    "Service-provider registration and ordering can change compilation or runtime discovery globally");
        }
        if (isBuildConfiguration(normalized, name)) {
            return new FileCriticality(9,
                    "Build configuration can affect dependency resolution and every compiled module");
        }
        if (normalized.startsWith(".github/workflows/") || normalized.contains("/.github/workflows/")) {
            return new FileCriticality(8,
                    "CI configuration controls repository-wide validation and release behavior");
        }
        if (normalized.contains("/src/main/resources/") || normalized.startsWith("src/main/resources/")) {
            return new FileCriticality(7,
                    "Runtime resource changes can affect behavior without Java dependency edges");
        }
        if (normalized.startsWith("src/main/") || normalized.contains("/src/main/")) {
            return new FileCriticality(5,
                    "Production source without a resolved class graph has moderate structural impact");
        }
        if (normalized.startsWith("src/test/") || normalized.contains("/src/test/")) {
            return new FileCriticality(3, "Test-only file has limited production blast radius");
        }
        if (name.endsWith(".md") || name.endsWith(".adoc")) {
            return new FileCriticality(1, "Documentation does not directly affect compiled behavior");
        }
        return new FileCriticality(4, "General project file has no static class dependency graph");
    }

    private static boolean isBuildConfiguration(String path, String name) {
        return name.equals("pom.xml") || name.equals("build.gradle")
                || name.equals("build.gradle.kts") || name.equals("settings.gradle")
                || name.equals("settings.gradle.kts") || name.equals("gradle.properties")
                || name.equals("maven-wrapper.properties") || name.equals("gradle-wrapper.properties")
                || path.startsWith("buildSrc/") || path.contains("/buildSrc/")
                || path.startsWith("gradle/libs.versions.") || path.contains("/gradle/libs.versions.");
    }

    private static String buildFileRecommendation(FileRiskTarget file,
            FileCriticality criticality, int churn, int authors, int coupling,
            String level, boolean hasFileHistory) {
        List<String> parts = new ArrayList<>();
        parts.add(("HIGH".equals(level) || "CRITICAL".equals(level))
                ? level + "-risk file change." : "MEDIUM".equals(level)
                        ? "Moderate-risk file change." : "Low-risk file change.");
        parts.add(criticality.note() + ".");
        if (file.worktreeStatus() != null) {
            parts.add("The file is currently " + file.worktreeStatus() + " in the worktree.");
        }
        if (hasFileHistory && authors <= 1) parts.add("Single author — ensure review coverage.");
        if (churn >= 30) parts.add("Frequently changed — inspect get_file_history for recent context.");
        if (coupling > 0) parts.add("Review the co-changing files before modification.");
        if (!hasFileHistory) parts.add("No file history is available, so validate with focused tests.");
        if ("service_descriptor".equals(file.kind())) {
            parts.add("Verify provider membership and ordering with annotation-processing or ServiceLoader tests.");
        }
        return String.join(" ", parts);
    }


    private static String riskLevel(double score) {
        if (score >= 8) return "CRITICAL";
        if (score >= 6) return "HIGH";
        if (score >= 3) return "MEDIUM";
        return "LOW";
    }

    private static double scaleScore(int value, int low, int mid, int high, int max) {
        if (value <= low) return 0;
        if (value >= max) return 10;
        if (value <= mid) return 5.0 * (value - low) / (mid - low);
        if (value <= high) return 5.0 + 3.0 * (value - mid) / (high - mid);
        return 8.0 + 2.0 * (value - high) / (max - high);
    }

    private static double busFactorScore(int authors) {
        if (authors <= 0) return 0;
        if (authors == 1) return 10;
        if (authors == 2) return 5;
        if (authors == 3) return 2;
        return 0;
    }

    private static void addSignal(ObjectNode signals, String name, int value, double score, double weight, String note) {
        ObjectNode s = signals.putObject(name);
        s.put("value", value);
        s.put("score", Math.round(score * 10.0) / 10.0);
        s.put("weight", weight);
        s.put("note", note);
    }

    private static void appendDependencyBreakdown(ObjectNode signal,
            List<IndexReader.DependencyBreakdown> breakdown) {
        ObjectNode result = signal.putObject("breakdown");
        writeDependencyBreakdown(result, breakdown);
    }

    private static void writeDependencyBreakdown(ObjectNode result,
            List<IndexReader.DependencyBreakdown> breakdown) {
        for (IndexReader.DependencyBreakdown entry : breakdown) {
            ObjectNode origin = result.putObject(entry.origin());
            origin.put("classes", entry.classes());
            origin.put("edges", entry.edges());
            if (entry.origin().equals("orphan_output")) origin.put("excluded_from_score", true);
        }
    }

    private static String buildRecommendation(ClassRecord cls, int fanIn, int churn, int authors, String level, boolean hasGit) {
        List<String> parts = new ArrayList<>();
        String shortName = cls.className().contains(".")
                ? cls.className().substring(cls.className().lastIndexOf('.') + 1) : cls.className();

        if ("CRITICAL".equals(level) || "HIGH".equals(level)) {
            parts.add(level + "-risk change target.");
        } else if ("MEDIUM".equals(level)) {
            parts.add("Moderate risk.");
        } else {
            parts.add("Low risk — safe to modify.");
        }

        if (fanIn > 0) {
            parts.add(fanIn + " dependents will be affected. Review with get_dependencies(\"" + shortName + "\", direction=\"inbound\").");
        }
        if (hasGit && authors <= 1) {
            parts.add("Single author — ensure review coverage.");
        }
        if (hasGit && churn >= 30) {
            parts.add("Frequently changed — check get_file_history(\"" + shortName + "\") for recent context.");
        }
        if (!hasGit) {
            parts.add("Git data unavailable — risk may be underestimated.");
        }
        return String.join(" ", parts);
    }


    private WorktreeInspector.Snapshot worktreeSnapshot(Map<String, String> metadata) {
        String projectRoot = metadata.get("project_root");
        if (projectRoot == null) return WorktreeInspector.Snapshot.empty();
        try {
            return WorktreeSnapshotCache.shared().get(Path.of(projectRoot));
        } catch (RuntimeException error) {
            return WorktreeInspector.Snapshot.empty();
        }
    }
}
