package org.treblereel.mcp.core;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class SubmoduleInventoryTest {
    @TempDir Path root;
    void commit(Git git,String message) throws Exception {git.add().addFilepattern(".").call();git.commit().setSign(false).setMessage(message).setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call();}
    @Test void localGitlinksTrackHeadDirtyNestedSourceAndUninitializedStateWithoutFetching() throws Exception {
        Path source=Files.createDirectories(root.resolve("source")),parent=Files.createDirectories(root.resolve("parent"));
        Files.createDirectories(source.resolve("src"));Files.writeString(source.resolve("src/Api.java"),"class Api {int n;}");Files.writeString(source.resolve("README.md"),"docs");
        try(Git original=Git.init().setDirectory(source.toFile()).call();Git git=Git.init().setDirectory(parent.toFile()).call()) {
            commit(original,"source");Files.writeString(parent.resolve("README"),"parent");commit(git,"parent");
            try(var child=git.submoduleAdd().setPath("library").setURI(source.toUri().toString()).call()){}
            commit(git,"gitlink");
            var clean=SubmoduleInventory.read(parent).getFirst();assertEquals("clean",clean.status());assertEquals(clean.indexCommit(),clean.headCommit());
            assertFalse(WorktreeInspector.inspect(parent).structuralDirty());
            Files.writeString(parent.resolve("library/README.md"),"changed docs");
            assertTrue(WorktreeInspector.inspect(parent).dirty());assertFalse(WorktreeInspector.inspect(parent).structuralDirty());
            Path file=parent.resolve("library/src/Api.java");Files.writeString(file,"class Api {long n;}");
            assertEquals("dirty",SubmoduleInventory.read(parent).getFirst().status());
            var dirty=WorktreeInspector.inspect(parent);assertTrue(dirty.structuralDirty());
            Files.writeString(file,"class Api {String n;}");
            assertNotEquals(dirty.structuralFingerprint(),WorktreeInspector.inspect(parent).structuralFingerprint());
            try(Git child=Git.open(parent.resolve("library").toFile())) {commit(child,"change");}
            assertEquals("commit_changed",SubmoduleInventory.read(parent).getFirst().status());
            Files.move(parent.resolve("library/.git"),parent.resolve("library/.git.saved"));
            assertEquals("uninitialized",SubmoduleInventory.read(parent).getFirst().status());
        }
    }
}
