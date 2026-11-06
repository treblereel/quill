package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.ignore.IgnoreNode;

/**
 * The project's root {@code .gitignore}, for skipping trees a project keeps out of version control:
 * captured benchmark traffic alone can hold millions of files. Nested ignore files are not read, so
 * a tree they ignore is still visited. Callers keep what a build writes, which is usually ignored too.
 */
public final class IgnoredTrees {

    private final Path root;
    private final IgnoreNode rules = new IgnoreNode();

    private IgnoredTrees(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static IgnoredTrees of(Path root) {
        var trees = new IgnoredTrees(root);
        Path file = trees.root.resolve(".gitignore");
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                trees.rules.parse(input);
            } catch (IOException unreadable) {
                // Without rules everything is visited, which is slower but never misses an input.
                return new IgnoredTrees(root);
            }
        }
        return trees;
    }

    /** Whether git ignores {@code path} or a directory holding it; false outside the root. */
    public boolean ignored(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) return false;
        Path relative = root.relativize(normalized);
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < relative.getNameCount(); index++) {
            if (index > 0) current.append('/');
            current.append(relative.getName(index));
            // A path already deleted may have been a directory, so only a real file fails a directory rule.
            boolean directory = index < relative.getNameCount() - 1 || !Files.isRegularFile(normalized);
            if (rules.isIgnored(current.toString(), directory) == IgnoreNode.MatchResult.IGNORED) return true;
        }
        return false;
    }
}
