package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "clean", mixinStandardHelpOptions = true,
        description = "Remove .quill and Quill build integration")
public class CleanCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public Integer call() {
        Path root = ProjectRootFinder.find(projectPath);
        try {
            CleanResult result = cleanProject(root);
            printResult(root, result);
            return result.integration() == BuildIntegrationInstaller.Result.FAILED
                    ? picocli.CommandLine.ExitCode.SOFTWARE
                    : picocli.CommandLine.ExitCode.OK;
        } catch (IOException e) {
            System.err.println("Failed to clean index: " + e.getMessage());
            return picocli.CommandLine.ExitCode.SOFTWARE;
        }
    }

    static CleanResult cleanProject(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        BuildIntegrationInstaller.Result integration =
                BuildIntegrationInstaller.uninstall(normalized);
        boolean existed = Files.exists(normalized.resolve(".quill"));
        boolean removed = ProjectIndexLock.withLockAndDeleteDirectory(
                normalized, () -> cleanIndexData(normalized));
        return new CleanResult(removed || existed, integration);
    }

    static void printResult(Path root, CleanResult result) {
        if (result.indexRemoved()) {
            System.out.println("Removed index data from " + root.resolve(".quill"));
        } else {
            System.out.println("No index data found.");
        }
        if (result.integration() == BuildIntegrationInstaller.Result.REMOVED) {
            System.out.println("Build integration removed.");
        }
    }

    private static boolean cleanIndexData(Path root) throws IOException {
        Path quillDir = root.resolve(".quill");
        boolean[] removed = {false};
        try (Stream<Path> walk = Files.walk(quillDir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(quillDir)
                        || path.getFileName().toString().equals(ProjectIndexLock.LOCK_FILE)) {
                    continue;
                }
                Files.delete(path);
                removed[0] = true;
            }
        }
        return removed[0];
    }

    record CleanResult(boolean indexRemoved, BuildIntegrationInstaller.Result integration) {}
}
