package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceTokenCacheTest {

    @TempDir Path tempDir;

    @Test
    void reusesUnchangedContentAndRetokenizesOnlyChanges() throws Exception {
        Path first = Files.writeString(tempDir.resolve("First.java"),
                "class First { String value; }");
        Path second = Files.writeString(tempDir.resolve("Second.java"),
                "class Second { int value; }");
        Path cache = tempDir.resolve(".quill/source-tokens.cache");

        SourceTokenCache.Result initial = SourceTokenCache.count(List.of(first, second), cache);
        SourceTokenCache.Result unchanged = SourceTokenCache.count(List.of(first, second), cache);

        assertEquals(0, initial.hits());
        assertEquals(2, initial.misses());
        assertEquals(2, unchanged.hits());
        assertEquals(0, unchanged.misses());
        assertEquals(initial.tokenCounts(), unchanged.tokenCounts());

        String changedSource = "class Second { int value; void update() {} }";
        Files.writeString(second, changedSource);
        SourceTokenCache.Result changed = SourceTokenCache.count(List.of(first, second), cache);

        assertEquals(1, changed.hits());
        assertEquals(1, changed.misses());
        assertEquals(TokenCounter.count(changedSource), changed.tokenCounts().get(second));
    }

    @Test
    void corruptCacheFallsBackToExactCounting() throws Exception {
        Path source = Files.writeString(tempDir.resolve("Sample.java"), "class Sample {}");
        Path cache = tempDir.resolve(".quill/source-tokens.cache");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "not a token cache");

        SourceTokenCache.Result result = SourceTokenCache.count(List.of(source), cache);

        assertEquals(0, result.hits());
        assertEquals(1, result.misses());
        assertEquals(TokenCounter.count("class Sample {}"), result.tokenCounts().get(source));
        assertTrue(Files.size(cache) > "not a token cache".length());
    }
}
