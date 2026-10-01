package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ProjectCodeExpectation;
import org.treblereel.mcp.workspace.WorkspaceDiscovery;

/** Initializes every discovered Java repository that already has compiled main classes. */
final class WorkspaceRepositoryInitializer {

    static final int DEFAULT_JOBS = 4;

    enum Mode {
        ALL,
        MISSING
    }

    record RepositoryResult(String name, Path root, String status, String diagnostic) {}

    record Result(List<RepositoryResult> repositories, int indexed, int pendingBuild,
            int metadataOnly, int skipped, int unchanged, int failed) {
        Result {
            repositories = List.copyOf(repositories);
        }

        boolean successful() {
            return failed == 0;
        }
    }

    private WorkspaceRepositoryInitializer() {}

    static Result initializeAll(WorkspaceDiscovery.Result discovery, boolean indexOnly,
            int jobs, Consumer<String> output) {
        return initialize(discovery, indexOnly, Mode.ALL, jobs, output);
    }

    static Result initializeMissing(WorkspaceDiscovery.Result discovery, boolean indexOnly,
            int jobs, Consumer<String> output) {
        return initialize(discovery, indexOnly, Mode.MISSING, jobs, output);
    }

    private static Result initialize(WorkspaceDiscovery.Result discovery, boolean indexOnly,
            Mode mode, int jobs, Consumer<String> output) {
        if (jobs < 1) throw new IllegalArgumentException("--jobs must be at least 1");
        Object outputLock = new Object();
        Consumer<String> serializedOutput = message -> {
            synchronized (outputLock) {
                output.accept(message);
            }
        };
        List<Callable<RepositoryResult>> tasks = discovery.repositories().stream()
                .<Callable<RepositoryResult>>map(repository -> () -> initializeRepository(
                        repository, indexOnly, mode, serializedOutput))
                .toList();
        List<RepositoryResult> results = WorkspaceTaskExecutor.invokeAll(tasks, jobs);
        discovery.diagnostics().forEach(value -> output.accept("Discovery warning: " + value));

        int indexed = 0;
        int pendingBuild = 0;
        int metadataOnly = 0;
        int skipped = 0;
        int unchanged = 0;
        int failed = 0;
        for (RepositoryResult result : results) {
            switch (result.status()) {
                case "indexed" -> indexed++;
                case "pending_build" -> pendingBuild++;
                case "metadata_only" -> metadataOnly++;
                case "skipped" -> skipped++;
                case "unchanged" -> unchanged++;
                case "failed" -> failed++;
                default -> throw new IllegalStateException(
                        "Unknown workspace repository status: " + result.status());
            }
        }
        return new Result(
                results, indexed, pendingBuild, metadataOnly, skipped, unchanged, failed);
    }

    private static RepositoryResult initializeRepository(
            WorkspaceDiscovery.Repository repository, boolean indexOnly, Mode mode,
            Consumer<String> output) {
        Path root = repository.root();
        try {
            BuildSystem buildSystem;
            try {
                buildSystem = BuildSystem.detect(root);
            } catch (IllegalArgumentException unsupported) {
                String diagnostic = "unsupported Java project";
                output.accept("Skipped " + repository.name() + ": " + diagnostic);
                return new RepositoryResult(repository.name(), root, "skipped", diagnostic);
            }
            List<Path> mainClasses = ProjectInitializer.findMainClassesDirs(root);
            if (mainClasses.isEmpty()
                    && ProjectCodeExpectation.inspect(root, buildSystem)
                            == ProjectCodeExpectation.State.METADATA_ONLY) {
                if (!indexOnly) BuildIntegrationInstaller.uninstall(root);
                String diagnostic = "build metadata does not declare JVM code; no index required";
                output.accept("Metadata only " + repository.name() + ": " + diagnostic);
                return new RepositoryResult(
                        repository.name(), root, "metadata_only", diagnostic);
            }
            BuildIntegrationInstaller.Result integration =
                    ProjectConfiguration.prepareForIndex(root, indexOnly);
            if (integration == BuildIntegrationInstaller.Result.FAILED) {
                String diagnostic = "could not install build integration";
                output.accept("Failed " + repository.name() + ": " + diagnostic);
                return new RepositoryResult(repository.name(), root, "failed", diagnostic);
            }
            if (mode == Mode.MISSING && ProjectIndexStore.findBestAvailableDb(root) != null) {
                return new RepositoryResult(repository.name(), root, "unchanged", null);
            }
            if (mainClasses.isEmpty()) {
                String diagnostic = indexOnly
                        ? "no compiled main classes; build the repository, then run "
                                + "workspace refresh"
                        : "no compiled main classes; build integration is installed and the next "
                                + "successful build will make the repository indexable";
                output.accept("Pending build " + repository.name() + ": " + diagnostic);
                return new RepositoryResult(
                        repository.name(), root, "pending_build", diagnostic);
            }

            output.accept("Initializing " + repository.name() + " at " + root + " ...");
            ProjectInitializer.InitializationResult initialization =
                    ProjectInitializer.initializeDetailed(root, indexOnly);
            if (initialization.successful()) {
                output.accept("Indexed " + repository.name() + " in "
                        + initialization.elapsedMillis() + " ms");
                return new RepositoryResult(repository.name(), root, "indexed", null);
            } else {
                output.accept("Failed " + repository.name() + ": "
                        + initialization.diagnostic());
                return new RepositoryResult(
                        repository.name(), root, "failed", initialization.diagnostic());
            }
        } catch (RuntimeException failure) {
            String diagnostic = "unexpected initialization failure: " + rootMessage(failure);
            output.accept("Failed " + repository.name() + ": " + diagnostic);
            return new RepositoryResult(repository.name(), root, "failed", diagnostic);
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
