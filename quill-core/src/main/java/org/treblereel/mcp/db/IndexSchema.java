package org.treblereel.mcp.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/** Schema for rebuildable indexes; incompatible generations are never migrated in place. */
public final class IndexSchema {
    public static final int VERSION = 34;
    private static final String TABLES = read("schema.sql");
    private static final String INDEXES = read("indexes.sql");

    private IndexSchema() {}

    static void create(Jdbi database, boolean indexes) {
        database.useTransaction(handle -> {
            handle.createScript(TABLES).execute();
            if (indexes) createIndexes(handle);
            handle.execute("PRAGMA user_version = " + VERSION);
        });
    }

    static void createIndexes(Handle handle) {
        handle.createScript(INDEXES).execute();
    }

    public static int inspectVersion(Path path) {
        if (!Files.isRegularFile(path)) return -1;
        return QuillDatabase.database(path, true).withHandle(handle ->
                handle.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
    }

    static void requireCurrent(Jdbi database, Path path) {
        int version = database.withHandle(handle ->
                handle.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
        if (version > VERSION) {
            throw new SchemaVersionException("Index at " + path + " was created by a newer Quill (schema v"
                    + version + ", this build supports v" + VERSION + "). Upgrade Quill or re-run 'quill init'.");
        }
        if (version < VERSION) {
            throw new SchemaVersionException("Index at " + path + " uses an outdated schema (v"
                    + version + ", current v" + VERSION + "). Re-run 'quill init' to rebuild.");
        }
    }

    private static String read(String resource) {
        try (var stream = IndexSchema.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing index schema resource: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read index schema resource: " + resource, error);
        }
    }

    public static class SchemaVersionException extends RuntimeException {
        public SchemaVersionException(String message) {
            super(message);
        }
    }
}
