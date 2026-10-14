package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceFileHashesTest {
    @TempDir Path root;
    @Test void capturesContentDespiteRestoredTimestampAndTracksAdditionAndDeletion() throws Exception {
        Path source = Files.createDirectories(root.resolve("src")), file = Files.writeString(source.resolve("A.java"),"class A{}");
        var before = SourceFileHashes.captureJava(root,List.of(source,source));
        var time = Files.getLastModifiedTime(file);
        Files.writeString(file,"class B{}"); Files.setLastModifiedTime(file,time);
        assertNotEquals(before,SourceFileHashes.captureJava(root,List.of(source)));
        Path extra = Files.writeString(source.resolve("B.java"),"class B{}");
        assertEquals(2,SourceFileHashes.captureJava(root,List.of(source)).size());
        Files.delete(extra); assertEquals(1,SourceFileHashes.captureJava(root,List.of(source)).size());
        Files.delete(file); assertTrue(SourceFileHashes.captureJava(root,List.of(source)).isEmpty());
    }
    @Test void excludesFilesOutsideTheProjectThroughSymlinks() throws Exception {
        Path source = Files.createDirectories(root.resolve("src")), outside = Files.createTempFile("quill-source-", ".java");
        try {
            Files.createSymbolicLink(source.resolve("Outside.java"),outside);
            assertTrue(SourceFileHashes.captureJava(root,List.of(source)).isEmpty());
        } finally { Files.deleteIfExists(outside); }
    }
}
