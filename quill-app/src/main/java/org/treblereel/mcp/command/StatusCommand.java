package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Show index state and diagnostics")
public class StatusCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--json", description = "Write machine-readable JSON to stdout")
    boolean json;

    @Override
    public Integer call() {
        Path root = ProjectRootFinder.find(projectPath);
        ProjectDiagnostics.Report report = ProjectDiagnostics.inspect(root);
        if (json) {
            System.out.println(toJson(report));
        } else if (report.indexed()) {
            printText(report);
        } else {
            System.err.println(report.errorMessage());
        }
        return report.indexed()
                ? picocli.CommandLine.ExitCode.OK : picocli.CommandLine.ExitCode.SOFTWARE;
    }

    private static void printText(ProjectDiagnostics.Report report) {
        var freshness = report.freshness();
        var statistics = report.statistics();
        System.out.println("Index: " + report.database());
        System.out.println("  Health:              " + report.health());
        System.out.println("  Index generation:    " + freshness.indexId());
        System.out.println("  Indexed at:          "
                + report.metadata().getOrDefault("indexed_at", "unknown"));
        System.out.println("  Indexed commit:      " + freshness.lastCommit());
        System.out.println("  Current commit:      "
                + (freshness.currentCommit() != null ? freshness.currentCommit() : "unknown"));
        System.out.println("  Worktree:            " + (freshness.worktreeDirty()
                ? "dirty (" + freshness.worktreeChangedFiles() + " changed files)" : "clean"));
        System.out.println("  Structural changes:  " + freshness.structuralChangedFiles());
        System.out.println("  Structure snapshot:  "
                + (freshness.structureStale() ? "stale" : "current"));
        if (!freshness.staleReasons().isEmpty()) {
            System.out.println("  Stale reasons:       "
                    + String.join(", ", freshness.staleReasons()));
        }
        System.out.println("  Dependency index:    "
                + report.metadata().getOrDefault("dependency_index", "unknown"));
        System.out.println("  Classes:             " + statistics.classes());
        System.out.println("  Beans:               " + statistics.beans());
        System.out.println("  Total source tokens: "
                + String.format("%,d", statistics.totalSourceTokens()));
        if (statistics.classes() > 0) {
            System.out.println("  Avg tokens/class:    "
                    + String.format("%,d", statistics.averageTokensPerClass()));
        }
        if (report.recovery() != null) {
            System.out.println("  Last recovery:       " + report.recovery().reason()
                    + " at " + report.recovery().recoveredAt());
            System.out.println("  Recovered generation: "
                    + report.recovery().selectedIndexId());
        }
    }

    static String toJson(ProjectDiagnostics.Report report) {
        ObjectNode root = JSON.createObjectNode();
        root.put("project_root", report.projectRoot().toString());
        root.put("indexed", report.indexed());
        root.put("health", report.health());
        if (!report.indexed()) {
            root.putNull("index");
            ObjectNode error = root.putObject("error");
            error.put("code", report.errorCode());
            error.put("message", report.errorMessage());
            appendRecovery(root, report.recovery());
            return root.toString();
        }

        ObjectNode index = root.putObject("index");
        index.put("path", report.database().toString());
        index.put("generation", report.freshness().indexId());
        index.put("indexed_at", report.freshness().indexedAt());
        index.put("indexed_commit", report.freshness().lastCommit());
        if (report.freshness().currentCommit() == null) index.putNull("current_commit");
        else index.put("current_commit", report.freshness().currentCommit());
        index.put("framework", report.metadata().getOrDefault("framework", "unknown"));
        index.put("write_mode", report.metadata().getOrDefault("database_write_mode", "unknown"));

        ObjectNode freshness = root.putObject("freshness");
        freshness.put("commit_stale", report.freshness().commitStale());
        freshness.put("worktree_dirty", report.freshness().worktreeDirty());
        freshness.put("worktree_changed_files", report.freshness().worktreeChangedFiles());
        freshness.put("structural_changed_files", report.freshness().structuralChangedFiles());
        freshness.put("structure_stale", report.freshness().structureStale());
        freshness.set("stale_reasons", JSON.valueToTree(report.staleReasons()));

        ObjectNode statistics = root.putObject("statistics");
        statistics.put("classes", report.statistics().classes());
        statistics.put("beans", report.statistics().beans());
        statistics.put("total_source_tokens", report.statistics().totalSourceTokens());
        statistics.put("average_tokens_per_class",
                report.statistics().averageTokensPerClass());

        ObjectNode dependencies = root.putObject("dependency_index");
        dependencies.put("status",
                report.metadata().getOrDefault("dependency_index", "unknown"));
        dependencies.put("detail",
                report.metadata().getOrDefault("dependency_index_detail", ""));
        appendRecovery(root, report.recovery());
        return root.toString();
    }

    private static void appendRecovery(ObjectNode root,
            ProjectIndexStore.RecoveryStatus recovery) {
        if (recovery == null) {
            root.putNull("recovery");
            return;
        }
        ObjectNode node = root.putObject("recovery");
        node.put("reason", recovery.reason());
        node.put("selected_index_id", recovery.selectedIndexId());
        node.put("recovered_at", recovery.recoveredAt());
    }
}
