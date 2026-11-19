package org.treblereel.mcp.db;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import org.jdbi.v3.core.Jdbi;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.treblereel.mcp.analysis.JvmTypeGraph;

/** Owns the readers and cached evidence of one immutable published index generation. */
public final class IndexGeneration implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private record Stamp(Path path, Object key, long size, FileTime modified) {
        static Stamp read(Path path) throws IOException {
            Path canonical = path.toRealPath();
            var attributes = Files.readAttributes(canonical, BasicFileAttributes.class);
            return new Stamp(canonical, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
        }
    }

    private final Stamp stamp;
    private final Jdbi database;
    private final Cleaner.Cleanable cleanup;
    private volatile boolean closed;
    private final IndexGenerationCache graphCache;
    private CompletableFuture<JvmTypeGraph> loading;

    private IndexGeneration(Stamp stamp, Jdbi database, ReadOnlyConnectionPool pool, IndexGenerationCache graphCache) {
        this.stamp = stamp;
        this.graphCache = graphCache;
        this.database = database;
        // The cleanup action retains the pool, never the Jdbi referent or this owner.
        cleanup = CLEANER.register(database, pool);
    }

    public static IndexGeneration open(Path path) {
        return open(path, new IndexGenerationCache());
    }

    public static IndexGeneration open(Path path, IndexGenerationCache graphCache) {
        final Stamp stamp;
        try {
            stamp = Stamp.read(path);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect immutable index generation at " + path, error);
        }
        var pool = new ReadOnlyConnectionPool(() -> openReader(stamp), ReadConcurrency.readersPerGeneration(),
                Duration.ofSeconds(5));
        Jdbi database = Jdbi.create(pool::borrow);
        QuillDatabase.instrument(database);
        try {
            IndexSchema.requireCurrent(database, stamp.path());
            if (!stamp.equals(Stamp.read(path))) {
                throw new IllegalStateException("Index replaced while opening; retry the request");
            }
            return new IndexGeneration(stamp, database, pool, graphCache);
        } catch (IOException error) {
            pool.run();
            throw new IllegalStateException("Cannot open immutable index generation at " + path, error);
        } catch (RuntimeException error) {
            pool.run();
            throw error;
        }
    }

    public Path path() { return stamp.path(); }

    public Jdbi jdbi() {
        return database;
    }

    public <T> T read(Function<ReadQueryContext,T> work) {
        return ReadQueryContext.read(this, work, false);
    }

    public <T> T readWithGraph(Function<ReadQueryContext,T> work) {
        return ReadQueryContext.read(this, work, true);
    }

    JvmTypeGraph graph() {
        CompletableFuture<JvmTypeGraph> future;
        boolean loader;
        synchronized (this) {
            var cached = graphCache.get(this);
            if (cached != null) return cached;
            loader = !closed && loading == null;
            if (loader) loading = new CompletableFuture<>();
            future = closed ? null : loading;
        }
        if (future == null) return database.withHandle(IndexGenerationCache::load);
        if (loader) {
            try {
                var graph = database.withHandle(IndexGenerationCache::load);
                synchronized (this) {
                    if (!closed) graphCache.retain(this, graph);
                    loading = null;
                }
                future.complete(graph);
            } catch (Throwable failed) {
                synchronized (this) { loading = null; }
                future.completeExceptionally(failed);
            }
        }
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Index evidence loading interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Cannot load index evidence", failed.getCause());
        }
    }

    public boolean matches(Path path) {
        try {
            // File systems without file keys (Windows) still match on canonical path, size and
            // modification time; generations are immutable files named by their index id.
            return !closed && stamp.equals(Stamp.read(path));
        } catch (IOException missing) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            graphCache.invalidate(this);
        } finally {
            cleanup.clean();
        }
    }

    private static Connection openReader(Stamp expected) throws SQLException {
        try {
            if (!expected.equals(Stamp.read(expected.path()))) {
                throw new SQLException("Index generation was replaced; retry the request");
            }
            Connection connection = QuillDatabase.openConnection(QuillDatabase.readOnlyUrl(expected.path()));
            try {
                if (!expected.equals(Stamp.read(expected.path()))) {
                    throw new SQLException("Index generation changed while opening a reader; retry the request");
                }
                return connection;
            } catch (IOException | SQLException error) {
                try {
                    connection.close();
                } catch (SQLException closing) {
                    error.addSuppressed(closing);
                }
                throw error;
            }
        } catch (IOException error) {
            throw new SQLException("Index generation is no longer available; retry the request", error);
        }
    }
}
