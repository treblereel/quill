package org.treblereel.mcp.db;

import org.treblereel.mcp.diagnostics.QueryMetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;

/** Creates writable index databases and opens existing databases without owning a pool. */
public final class QuillDatabase {
    private QuillDatabase() {}

    public static Jdbi create(Path path) {
        return create(path, false);
    }

    /** Creates a new staging database; secondary indexes are built after bulk loading. */
    public static Jdbi createForBulkLoad(Path path) {
        return create(path, true);
    }

    private static Jdbi create(Path path, boolean bulkLoad) {
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            if (bulkLoad) Files.createFile(path);
            else if (Files.exists(path)) IndexSchema.requireCurrent(database(path, true), path);
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            throw new IllegalArgumentException("Bulk-loaded index must use a new staging path: " + path, exists);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot create index at " + path, error);
        }
        Jdbi database = database(path, false);
        // Atomic publication moves one complete file, without WAL sidecars.
        database.useHandle(handle -> handle.execute("PRAGMA journal_mode=DELETE"));
        IndexSchema.create(database, !bulkLoad);
        return database;
    }

    public static Jdbi open(Path path) {
        return openExisting(path, true);
    }

    /** Opens an existing staging database for an atomic incremental update. */
    public static Jdbi openWritable(Path path) {
        return openExisting(path, false);
    }

    private static Jdbi openExisting(Path path, boolean readOnly) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Index not found at " + path + ". Run 'quill init' first.");
        }
        Jdbi database = database(path, readOnly);
        IndexSchema.requireCurrent(database, path);
        return database;
    }
    static Jdbi database(Path dbPath, boolean readOnly) {
        Path absolutePath = dbPath.toAbsolutePath().normalize();
        String url = readOnly
                ? readOnlyUrl(absolutePath)
                : "jdbc:sqlite:" + absolutePath;
        Jdbi database = Jdbi.create(() -> openConnection(url));
        instrument(database);
        return database;
    }

    static void instrument(Jdbi database) {
        database.setSqlLogger(new SqlLogger() {
            @Override
            public void logAfterExecution(StatementContext context) {
                QueryMetrics.statementExecuted();
            }

            @Override
            public void logException(StatementContext context, SQLException error) {
                QueryMetrics.statementExecuted();
            }
        });
    }

    static java.sql.Connection openConnection(String url) throws SQLException {
        var connection = DriverManager.getConnection(url);
        QueryMetrics.connectionOpened();
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException error) {
            try {
                connection.close();
            } catch (SQLException closing) {
                error.addSuppressed(closing);
            }
            throw error;
        }
        return connection;
    }

    static String readOnlyUrl(Path path) {
        return "jdbc:sqlite:" + path.toAbsolutePath().normalize().toUri() + "?mode=ro";
    }
}
