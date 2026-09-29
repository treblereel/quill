package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class UpdateCommandTest {

    @TempDir
    Path project;

    @Test
    void successfulExternalBuildRefreshesStaleSnapshotWhenBytecodeIsUnchanged()
            throws Exception {
        try (Git ignored = Git.init().setDirectory(project.toFile()).call()) {
            Path source = project.resolve("src/main/java/acme/Changed.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package acme; class Changed {}\n");

            Path stale = database(false);
            Path compiled = database(true);

            assertTrue(UpdateCommand.requiresPostCompileRefresh(project, stale),
                    "a successful build must clear stale structural metadata even when the "
                            + "compiled-class fingerprint did not change");
            assertFalse(UpdateCommand.requiresPostCompileRefresh(project, compiled),
                    "an already compiled snapshot does not require a metadata-only refresh");
        }
    }

    private Path database(boolean compiledBeforeIndex) {
        Path database = project.resolve("index-" + compiledBeforeIndex + ".db");
        var jdbi = QuillDatabase.create(database);
        jdbi.useHandle(handle -> handle.execute(
                "INSERT INTO metadata(key, value) VALUES ('compiled_before_index', ?)",
                Boolean.toString(compiledBeforeIndex)));
        return database;
    }
}
