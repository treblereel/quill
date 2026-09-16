package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "clean", description = "Remove .quill and Quill build integration")
public class CleanCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public void run() {
        Path root = ProjectRootFinder.find(projectPath);
        Path lockedRoot = root;

        BuildIntegrationInstaller.Result integration = BuildIntegrationInstaller.uninstall(root);

        try {
            boolean existed = Files.exists(lockedRoot.resolve(".quill"));
            boolean removed = ProjectIndexLock.withLockAndDeleteDirectory(
                    lockedRoot, () -> cleanIndexData(lockedRoot));
            removed |= existed;
            if (removed) {
                System.out.println("Removed index data from " + lockedRoot.resolve(".quill"));
            } else {
                System.out.println("No index data found.");
            }
        } catch (IOException e) {
            System.err.println("Failed to clean index: " + e.getMessage());
            return;
        }

        if (integration == BuildIntegrationInstaller.Result.REMOVED) {
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
}
