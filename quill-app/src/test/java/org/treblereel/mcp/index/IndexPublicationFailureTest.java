package org.treblereel.mcp.index;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexGeneration;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.QuillDatabase;

class IndexPublicationFailureTest {
    @TempDir Path root;

    private Path generation(String id) {
        Path db = ProjectIndexStore.resolveDbPath(root, id);
        IndexWriter.writeFresh(QuillDatabase.createForBulkLoad(db), new IndexFixture()
                .metadata(Map.of("index_id",id,"last_commit","unknown","project_root",root.toString())).snapshot());
        return db;
    }

    @Test void failureAfterRefsCommitCannotInvalidatePublishedOrInFlightReaders() throws Exception {
        Path old = generation("previous");
        ProjectIndexStore.updateRefs(root,"unknown","previous");
        try (var owner = IndexGeneration.open(old); var active = owner.jdbi().open()) {
            Path next = generation("current");
            assertDoesNotThrow(() -> ProjectIndexStore.updateRefs(root,"unknown","current", refs -> {
                assertEquals("current", ProjectIndexStore.readRefs(root.resolve(".quill/refs.json")).get("@worktree"));
                throw new IllegalStateException("injected cleanup failure");
            }));
            assertEquals(next.toAbsolutePath(), ProjectIndexStore.findBestAvailableDb(root));
            assertEquals("previous", active.createQuery("SELECT value FROM metadata WHERE key='index_id'").mapTo(String.class).one());
            try (var published = IndexGeneration.open(next)) {
                assertEquals("current", published.read(c -> c.index.getMetadata().get("index_id")));
            }
        }
    }

    @Test void failedSchemaReplacementLeavesOldDatabaseAndRefsIntact() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>p</groupId>
                  <artifactId>schema-replacement</artifactId><version>1</version></project>
                """);
        Path source = root.resolve("src/main/java/Marker.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Marker {}");
        Path classes = Files.createDirectories(root.resolve("target/classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString()));
        Path old = generation("old-schema");
        ProjectIndexStore.updateRefs(root, "unknown", "old-schema");
        QuillDatabase.openWritable(old).useHandle(h -> h.execute("PRAGMA user_version="
                + (org.treblereel.mcp.db.IndexSchema.VERSION - 1)));
        byte[] oldBytes = Files.readAllBytes(old);
        String oldRefs = Files.readString(root.resolve(".quill/refs.json"));
        // An incompatible incremental base must fail without deleting the published generation.
        var result = ProjectInitializer.initializeLockedDetailed(root, true, false, old);
        assertFalse(result.successful());
        assertArrayEquals(oldBytes, Files.readAllBytes(old));
        assertEquals(oldRefs, Files.readString(root.resolve(".quill/refs.json")));
        assertEquals("old-schema", org.jdbi.v3.core.Jdbi.create("jdbc:sqlite:" + old)
                .withHandle(h -> h.createQuery("SELECT value FROM metadata WHERE key='index_id'")
                        .mapTo(String.class).one()));
    }

    @Test void maintenanceKeepsActiveGenerationEvenWhenItsTimestampIsOldest() throws Exception {
        Path active = generation("active");
        Files.setLastModifiedTime(active, FileTime.fromMillis(1));
        for (int i=0;i<6;i++) Files.setLastModifiedTime(generation("future"+i), FileTime.fromMillis(1000+i));
        ProjectIndexStore.updateRefs(root,"unknown","active");
        assertTrue(Files.isRegularFile(active));
        assertEquals("active", ProjectIndexStore.readRefs(root.resolve(".quill/refs.json")).get("@worktree"));
        assertEquals(active.toAbsolutePath(), ProjectIndexStore.findBestAvailableDb(root));
        Files.setLastModifiedTime(generation("future6"), FileTime.fromMillis(2000));
        ProjectIndexStore.cleanupLru(root, ProjectIndexStore.readRefs(root.resolve(".quill/refs.json")));
        assertTrue(Files.isRegularFile(active));
        try (var paths = Files.list(root.resolve(".quill"))) {
            assertEquals(5, paths.filter(p -> p.toString().endsWith(".db")).count());
        }
    }

    @Test void publicationAndUpdateAgreeOnDocumentationButRejectChangedIndexedInputs() throws Exception {
        Path source = root.resolve("src/main/java/Marker.java");
        Path test = root.resolve("src/test/java/MarkerTest.java");
        Files.createDirectories(source.getParent()); Files.createDirectories(test.getParent());
        Files.writeString(source, "class Marker {}"); Files.writeString(test, "class MarkerTest {}");
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>guard</artifactId><version>1</version></project>");
        Files.writeString(root.resolve(".gitignore"), "target/\n.quill/\n");
        var classes = java.util.List.of(Files.createDirectories(root.resolve("target/classes")));
        var sources = java.util.List.of(source.getParent(), test.getParent());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.getFirst().toString(), source.toString()));
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("fixture").setAuthor("Fixture", "fixture@example.test").call();
            var worktree = org.treblereel.mcp.core.WorktreeInspector.inspect(root);
            String inputs = worktree.indexInputFingerprint();
            String state = ProjectLayout.computeStateFingerprint(root, classes);
            var hashes = org.treblereel.mcp.core.SourceFileHashes.captureJava(root, sources);
            var metadata = Map.of("indexed_input_fingerprint", inputs, "state_fingerprint", state);
            Files.writeString(root.resolve("README.md"), "new documentation");
            assertTrue(ProjectInitializer.indexInputsMatch(root, inputs, state, classes, hashes, sources));
            assertFalse(ProjectUpdater.hasProjectChanges(root, metadata, classes));
            for (Path file : java.util.List.of(source, test, root.resolve("pom.xml"))) {
                String original = Files.readString(file);
                Files.writeString(file, original + "\n");
                assertFalse(ProjectInitializer.indexInputsMatch(root, inputs, state, classes, hashes, sources), file.toString());
                assertTrue(ProjectUpdater.hasProjectChanges(root, metadata, classes), file.toString());
                Files.writeString(file, original);
            }
            Path bytecode = classes.getFirst().resolve("Marker.class");
            byte[] original = Files.readAllBytes(bytecode); byte[] changed = original.clone(); changed[changed.length - 1] ^= 1;
            var timestamp = Files.getLastModifiedTime(bytecode);
            Files.write(bytecode, changed); Files.setLastModifiedTime(bytecode, timestamp);
            assertFalse(ProjectInitializer.indexInputsMatch(root, inputs, state, classes, hashes, sources));
            assertTrue(ProjectUpdater.hasProjectChanges(root, metadata, classes));
        }
    }

    @Test void failedRefsReplacementPreservesTheCompletedGenerationForRecovery() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>p</groupId>
                  <artifactId>publication</artifactId><version>1</version></project>
                """);
        Path source = root.resolve("src/main/java/Marker.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,"public class Marker {}");
        Path classes = Files.createDirectories(root.resolve("target/classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),source.toString()));
        Files.writeString(root.resolve("target/quill-classpath.txt"), "");
        Path blockedRefs = Files.createDirectories(root.resolve(".quill/refs.json"));
        Files.writeString(blockedRefs.resolve("obstruction"),"preserved");
        var result = ProjectInitializer.initializeDetailed(root,true);
        assertFalse(result.successful());
        assertEquals(ProjectInitializer.FailureReason.INDEX_PUBLICATION_FAILED,result.reason());
        assertEquals("preserved",Files.readString(blockedRefs.resolve("obstruction")));
        try (var paths = Files.list(root.resolve(".quill"))) {
            var complete = paths.filter(p -> p.toString().endsWith(".db")).toList();
            assertEquals(1,complete.size(),result.diagnostic());
            assertDoesNotThrow(() -> ProjectIndexStore.validateForPublication(complete.getFirst()));
            try (var owner = IndexGeneration.open(complete.getFirst())) {
                assertEquals("Marker",owner.read(c -> c.symbols.findAllClasses().getFirst().className()));
            }
        }
    }
}
