package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.workspace.WorkspaceDiscovery;

/** Initializes every discovered Java repository that already has compiled main classes. */
final class WorkspaceRepositoryInitializer {

    enum Mode {
        ALL,
        MISSING
    }

    record RepositoryResult(String name, Path root, String status, String diagnostic) {}

    record Result(List<RepositoryResult> repositories, int indexed, int skipped,
            int unchanged, int failed) {
        Result {
            repositories = List.copyOf(repositories);
        }

        boolean successful() {
            return failed == 0;
        }
    }

    private WorkspaceRepositoryInitializer() {}

    static Result initializeAll(WorkspaceDiscovery.Result discovery, boolean indexOnly,
            Consumer<String> output) {
        return initialize(discovery, indexOnly, Mode.ALL, output);
    }

    static Result initializeMissing(WorkspaceDiscovery.Result discovery, boolean indexOnly,
            Consumer<String> output) {
        return initialize(discovery, indexOnly, Mode.MISSING, output);
    }

    private static Result initialize(WorkspaceDiscovery.Result discovery, boolean indexOnly,
            Mode mode, Consumer<String> output) {
        List<RepositoryResult> results = new ArrayList<>();
        int indexed = 0;
        int skipped = 0;
        int unchanged = 0;
        int failed = 0;
        for (WorkspaceDiscovery.Repository repository : discovery.repositories()) {
            Path root = repository.root();
            if (mode == Mode.MISSING && ProjectIndexStore.findBestAvailableDb(root) != null) {
                results.add(new RepositoryResult(repository.name(), root,
                        "unchanged", null));
                unchanged++;
                continue;
            }
            try {
                BuildSystem.detect(root);
            } catch (IllegalArgumentException unsupported) {
                String diagnostic = "unsupported Java project";
                output.accept("Skipped " + repository.name() + ": " + diagnostic);
                results.add(new RepositoryResult(repository.name(), root,
                        "skipped", diagnostic));
                skipped++;
                continue;
            }
            if (ProjectInitializer.findClassesDirs(root).isEmpty()) {
                String diagnostic = "no compiled main classes; build the repository first";
                output.accept("Skipped " + repository.name() + ": " + diagnostic);
                results.add(new RepositoryResult(repository.name(), root,
                        "skipped", diagnostic));
                skipped++;
                continue;
            }

            output.accept("Initializing " + repository.name() + " at " + root + " ...");
            ProjectInitializer.InitializationResult initialization =
                    ProjectInitializer.initializeDetailed(root, indexOnly);
            if (initialization.successful()) {
                output.accept("Indexed " + repository.name() + " in "
                        + initialization.elapsedMillis() + " ms");
                results.add(new RepositoryResult(repository.name(), root, "indexed", null));
                indexed++;
            } else {
                output.accept("Failed " + repository.name() + ": "
                        + initialization.diagnostic());
                results.add(new RepositoryResult(repository.name(), root,
                        "failed", initialization.diagnostic()));
                failed++;
            }
        }
        discovery.diagnostics().forEach(value -> output.accept("Discovery warning: " + value));
        return new Result(results, indexed, skipped, unchanged, failed);
    }
}
