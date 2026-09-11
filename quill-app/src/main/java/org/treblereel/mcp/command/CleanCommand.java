package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.treblereel.mcp.core.GitHookInstaller;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "clean", description = "Remove quill index and git hooks")
public class CleanCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public void run() {
        Path root = ProjectRootFinder.find(projectPath);
        Path lockedRoot = root;

        try {
            boolean removed = ProjectIndexLock.withLock(lockedRoot, () -> cleanIndexData(lockedRoot));
            if (removed) {
                System.out.println("Removed index data from " + lockedRoot.resolve(".quill"));
            } else {
                System.out.println("No index data found.");
            }
        } catch (IOException e) {
            System.err.println("Failed to clean index: " + e.getMessage());
            return;
        }

        GitHookInstaller.uninstall(root);
        System.out.println("Git hooks cleaned.");
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
