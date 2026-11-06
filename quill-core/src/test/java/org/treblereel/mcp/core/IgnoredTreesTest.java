package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IgnoredTreesTest {

    @TempDir Path root;

    @Test
    void matchesDirectoryRulesAtAnyDepthAndEverythingBelowThem() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "results/**/wire/\ntarget/\n*.log\n!keep.log\n");
        Path wire = Files.createDirectories(root.resolve("results/run/session/wire"));
        Path file = Files.writeString(wire.resolve("frame.json"), "{}");
        var trees = IgnoredTrees.of(root);

        assertTrue(trees.ignored(wire));
        assertTrue(trees.ignored(file));
        assertTrue(trees.ignored(wire.resolve("deleted/gone.json")), "events can name paths no longer on disk");
        assertTrue(trees.ignored(root.resolve("target")));
        assertTrue(trees.ignored(root.resolve("build.log")));
        assertFalse(trees.ignored(root.resolve("keep.log")));
        assertFalse(trees.ignored(root.resolve("results/run/session")));
        assertFalse(trees.ignored(root.resolve("src/main/java/App.java")));
        assertFalse(trees.ignored(root));
        assertFalse(trees.ignored(root.getParent().resolve("elsewhere")));
    }

    @Test
    void aProjectWithoutRulesIgnoresNothing() {
        assertFalse(IgnoredTrees.of(root).ignored(root.resolve("wire/frame.json")));
    }
}
