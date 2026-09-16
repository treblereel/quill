package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.MetaEnvelope;

/** Builds one canonical diagnostic snapshot for human and machine-readable status output. */
public final class ProjectDiagnostics {

    private ProjectDiagnostics() {}

    public static Report inspect(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path database = ProjectIndexStore.findBestAvailableDb(normalizedRoot);
        if (database == null) {
            return Report.unavailable(normalizedRoot, "NO_VALID_INDEX",
                    "No valid index found. Run: quill init --project " + normalizedRoot);
        }

        try {
            Jdbi jdbi = QuillDatabase.open(database);
            Map<String, String> metadata = new LinkedHashMap<>(IndexReader.getMetadata(jdbi));
            var classes = IndexReader.findAllClasses(jdbi);
            var beans = IndexReader.findBeans(jdbi, null);
            int totalTokens = classes.stream().mapToInt(value -> value.sourceTokens()).sum();
            Statistics statistics = new Statistics(classes.size(), beans.size(), totalTokens,
                    classes.isEmpty() ? 0 : totalTokens / classes.size());
            MetaEnvelope freshness = MetaEnvelope.from(jdbi, 0, 0);
            ProjectIndexStore.RecoveryStatus recovery = ProjectIndexStore.readRecovery(normalizedRoot)
                    .orElse(null);
            String health = health(freshness, recovery);
            return new Report(normalizedRoot, true, health, database, metadata, freshness,
                    statistics, recovery, null, null);
        } catch (RuntimeException failure) {
            return Report.unavailable(normalizedRoot, "INDEX_UNREADABLE", safeMessage(failure));
        }
    }

    private static String health(MetaEnvelope freshness,
            ProjectIndexStore.RecoveryStatus recovery) {
        if (freshness.staleWarning() && recovery != null) return "stale_recovered";
        if (freshness.staleWarning()) return "stale";
        if (recovery != null) return "recovered";
        return "healthy";
    }

    private static String safeMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }

    public record Statistics(int classes, int beans, int totalSourceTokens,
                             int averageTokensPerClass) {}

    public record Report(
            Path projectRoot,
            boolean indexed,
            String health,
            Path database,
            Map<String, String> metadata,
            MetaEnvelope freshness,
            Statistics statistics,
            ProjectIndexStore.RecoveryStatus recovery,
            String errorCode,
            String errorMessage) {

        public Report {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        static Report unavailable(Path root, String code, String message) {
            return new Report(root, false, "unavailable", null, Map.of(), null, null,
                    ProjectIndexStore.readRecovery(root).orElse(null), code, message);
        }

        public List<String> staleReasons() {
            return freshness == null ? List.of() : freshness.staleReasons();
        }
    }
}
