package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationIndexCacheTest {

    @TempDir Path tempDir;

    @Test
    void reusesUnchangedShardsAndRebuildsOnlyTheChangedShard() throws Exception {
        Path sources = Files.createDirectories(tempDir.resolve("sources/sample"));
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        List<Path> sourceFiles = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Path source = sources.resolve("Type" + i + ".java");
            Files.writeString(source, source(i, 1));
            sourceFiles.add(source);
        }
        compile(classes, sourceFiles);

        Path cache = tempDir.resolve("application-jandex.cache");
        var first = ApplicationIndexCache.loadOrBuild(
                ClassFileSnapshot.capture(List.of(classes)), cache);
        var unchanged = ApplicationIndexCache.loadOrBuild(
                ClassFileSnapshot.capture(List.of(classes)), cache);

        assertEquals(0, first.hits());
        assertEquals(8, unchanged.hits());
        assertEquals(16, unchanged.index().getKnownClasses().size());

        Path changedSource = sources.resolve("Type0.java");
        Files.writeString(changedSource, source(0, 2));
        compile(classes, List.of(changedSource));
        var changed = ApplicationIndexCache.loadOrBuild(
                ClassFileSnapshot.capture(List.of(classes)), cache);

        assertEquals(7, changed.hits());
        assertEquals(16, changed.index().getKnownClasses().size());
    }

    @Test
    void corruptCacheFallsBackToACompleteRebuild() throws Exception {
        Path sources = Files.createDirectories(tempDir.resolve("corrupt-sources/sample"));
        Path classes = Files.createDirectories(tempDir.resolve("corrupt-classes"));
        Path source = sources.resolve("Only.java");
        Files.writeString(source, "package sample; public class Only {}\n");
        compile(classes, List.of(source));
        Path cache = tempDir.resolve("corrupt.cache");
        Files.writeString(cache, "not an index");

        var result = ApplicationIndexCache.loadOrBuild(
                ClassFileSnapshot.capture(List.of(classes)), cache);

        assertEquals(0, result.hits());
        assertEquals(1, result.index().getKnownClasses().size());
        assertTrue(Files.size(cache) > "not an index".length());
    }

    private static String source(int type, int value) {
        return "package sample; public class Type" + type
                + " { public int value() { return " + value + "; } }\n";
    }

    private static void compile(Path classes, List<Path> sources) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-d");
        arguments.add(classes.toString());
        sources.stream().map(Path::toString).forEach(arguments::add);
        int exit = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, arguments.toArray(String[]::new));
        assertEquals(0, exit);
    }
}
