package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
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
            Unavailable unavailable = unavailable(normalizedRoot);
            return Report.unavailable(normalizedRoot, unavailable.health(), unavailable.code(),
                    unavailable.message());
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
            return Report.unavailable(normalizedRoot, "corrupt", "INDEX_UNREADABLE",
                    safeMessage(failure));
        }
    }

    private static Unavailable unavailable(Path root) {
        long pending = regularFileCount(root.resolve(".quill/build-events"), null);
        if (pending > 0) {
            return new Unavailable("refresh_pending", "INDEX_REFRESH_PENDING",
                    pending + " build event" + (pending == 1 ? " is" : "s are")
                            + " waiting to refresh the index. Make an MCP request to consume "
                            + (pending == 1 ? "it." : "them."));
        }
        Path directory = root.resolve(".quill");
        List<Path> databases = regularFiles(directory, ".db");
        if (databases.isEmpty()) {
            return new Unavailable("missing", "INDEX_MISSING",
                    "No index has been created. Run: quill init --project " + root);
        }
        for (Path candidate : databases) {
            try {
                QuillDatabase.open(candidate);
            } catch (QuillDatabase.SchemaVersionException incompatible) {
                return new Unavailable("incompatible", "INDEX_INCOMPATIBLE",
                        safeMessage(incompatible));
            } catch (RuntimeException ignored) {
                // Inspect the remaining generations before classifying all of them as corrupt.
            }
        }
        return new Unavailable("corrupt", "INDEX_CORRUPT",
                "Index generations exist but none can be opened or recovered. "
                        + "Run: quill init --project " + root);
    }

    private static long regularFileCount(Path directory, String suffix) {
        return regularFiles(directory, suffix).size();
    }

    private static List<Path> regularFiles(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> suffix == null
                            || path.getFileName().toString().endsWith(suffix))
                    .sorted().toList();
        } catch (IOException ignored) {
            return List.of();
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

        static Report unavailable(Path root, String health, String code, String message) {
            return new Report(root, false, health, null, Map.of(), null, null,
                    ProjectIndexStore.readRecovery(root).orElse(null), code, message);
        }

        public List<String> staleReasons() {
            return freshness == null ? List.of() : freshness.staleReasons();
        }
    }

    private record Unavailable(String health, String code, String message) {}
}
