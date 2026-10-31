package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadOnlyConnectionPoolTest {
    @TempDir Path root;
    private final java.util.List<Connection> opened = new java.util.ArrayList<>();
    ReadOnlyConnectionPool pool(int capacity,Duration timeout) {
        Path path=root.resolve("index.db");QuillDatabase.create(path);
        return new ReadOnlyConnectionPool(()->{var physical=QuillDatabase.openConnection(QuillDatabase.readOnlyUrl(path));opened.add(physical);return physical;},capacity,timeout);
    }
    @Test void reusesPhysicalReaderButOldLeaseCannotAffectNewBorrower() throws Exception {
        var pool=pool(1,Duration.ofSeconds(1));
        Connection first=pool.borrow();Statement outstanding=first.createStatement();Object physical=opened.getLast();first.close();first.close();assertTrue(outstanding.isClosed());
        try(Connection second=pool.borrow()) {
            assertSame(physical,opened.getLast());assertFalse(second.isClosed());
            first.close();assertFalse(second.isClosed());
            assertThrows(SQLException.class,()->first.createStatement());
            assertThrows(SQLException.class,()->second.createStatement().execute("CREATE TABLE forbidden(id INTEGER)"));
            assertEquals(1,pool.borrowedCount());
        }
        assertEquals(1,pool.idleCount());pool.run();assertEquals(0,pool.idleCount());
    }
    @Test void physicalConnectionsCannotEscapeThroughJdbcResources() throws Exception {
        var pool = pool(1, Duration.ofSeconds(1));
        try (var lease = pool.borrow()) {
            assertSame(lease, lease.unwrap(Connection.class));
            assertFalse(lease.isWrapperFor(org.sqlite.SQLiteConnection.class));
            assertThrows(SQLException.class, () -> lease.unwrap(org.sqlite.SQLiteConnection.class));
            try (var statement = lease.createStatement(); var rows = statement.executeQuery("SELECT 1")) {
                assertEquals(1, rows.getMetaData().getColumnCount());
                assertThrows(SQLException.class, () -> rows.getMetaData().unwrap(ResultSet.class));
                assertSame(lease, statement.getConnection());
                assertSame(statement, rows.getStatement());
                assertThrows(SQLException.class, () -> statement.unwrap(org.sqlite.jdbc4.JDBC4Statement.class));
                assertSame(lease, rows.getStatement().getConnection());
            }
            try (var statement = lease.prepareStatement("SELECT ?")) {
                assertEquals(1, statement.getParameterMetaData().getParameterCount());
                assertThrows(SQLException.class, () -> statement.getParameterMetaData().unwrap(Statement.class));
            }
            assertSame(lease, lease.getMetaData().getConnection());
            try (var rows = lease.getMetaData().getTables(null, null, "%", null)) {
                if (rows.getStatement() != null) assertSame(lease, rows.getStatement().getConnection());
            }
        } finally { pool.run(); }
    }

    @Test void leaseClosesOutstandingMetadataResultSets() throws Exception {
        Path path = root.resolve("metadata.db");
        QuillDatabase.create(path);
        var captured = new java.util.concurrent.atomic.AtomicReference<ResultSet>();
        var pool = new ReadOnlyConnectionPool(() -> {
            var physical = QuillDatabase.openConnection(QuillDatabase.readOnlyUrl(path));
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                Object value;
                try { value = method.invoke(physical, args); }
                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                if (!(value instanceof DatabaseMetaData metadata)) return value;
                return java.lang.reflect.Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (ignored, operation, parameters) -> {
                    try {
                        Object result = operation.invoke(metadata, parameters);
                        if (result instanceof ResultSet rows) captured.set(rows);
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
            });
        }, 1, Duration.ofSeconds(1));
        try {
            try (var lease = pool.borrow()) { lease.getMetaData().getTables(null,null,"%",null); }
            assertTrue(captured.get().isClosed());
        } finally { pool.run(); }
    }

    @Test void waitsAndReturnsReaderAfterAnotherLeaseCloses() throws Exception {
        var pool=pool(1,Duration.ofSeconds(2));var first=pool.borrow();var executor=Executors.newSingleThreadExecutor();
        var started=new CountDownLatch(1);
        try {
            var pending=executor.submit(()->{started.countDown();try(var next=pool.borrow()){return next.createStatement().executeQuery("SELECT 1").getInt(1);}});
            assertTrue(started.await(1,TimeUnit.SECONDS));assertFalse(pending.isDone());first.close();
            assertEquals(1,pending.get(2,TimeUnit.SECONDS));assertEquals(0,pool.borrowedCount());
        } finally {first.close();pool.run();executor.shutdownNow();}
    }
    @Test void timeoutAndInterruptionDoNotLeakCapacity() throws Exception {
        var pool=pool(1,Duration.ofMillis(30));
        try(var held=pool.borrow()) {
            assertThrows(SQLTimeoutException.class,pool::borrow);assertEquals(1,pool.borrowedCount());
            Thread.currentThread().interrupt();try{assertThrows(SQLException.class,pool::borrow);}finally{assertTrue(Thread.interrupted());}
        }
        try(var next=pool.borrow()){assertFalse(next.isClosed());}finally{pool.run();}
    }
    @Test void retirementPreservesActiveGenerationAndClosesItsReturnedReaders() throws Exception {
        var pool=pool(2,Duration.ofSeconds(1));var active=pool.borrow();Connection physical=opened.getLast();
        pool.run();assertEquals(1,active.createStatement().executeQuery("SELECT 1").getInt(1));active.close();assertTrue(physical.isClosed());
        // A request resolved before retirement can still read the old immutable generation.
        try(var late=pool.borrow()){assertEquals(1,late.createStatement().executeQuery("SELECT 1").getInt(1));}
        assertEquals(0,pool.idleCount());assertEquals(0,pool.borrowedCount());
    }
    @Test void retirementDoesNotRemoveTheConcurrencyBoundForLateReaders() throws Exception {
        var pool=pool(1,Duration.ofMillis(30));
        try(var active=pool.borrow()) {
            pool.run();assertThrows(SQLTimeoutException.class,pool::borrow);assertEquals(1,pool.borrowedCount());
        }
        try(var late=pool.borrow()){assertEquals(1,pool.borrowedCount());}
        assertEquals(0,pool.borrowedCount());assertEquals(0,pool.idleCount());
    }
    @Test void publishedGenerationsNeverShareConnectionsOrRows() throws Exception {
        Path old=root.resolve("old.db"),now=root.resolve("new.db");
        QuillDatabase.create(old).useHandle(h->{h.execute("CREATE TABLE marker(value INTEGER)");h.execute("INSERT INTO marker VALUES(1)");});
        QuillDatabase.create(now).useHandle(h->{h.execute("CREATE TABLE marker(value INTEGER)");h.execute("INSERT INTO marker VALUES(2)");});
        var oldGeneration=IndexGeneration.open(old);var newGeneration=IndexGeneration.open(now);
        var oldDb=oldGeneration.jdbi();var newDb=newGeneration.jdbi();
        try(var active=oldDb.open()) {
            oldGeneration.close();
            assertEquals(1,active.createQuery("SELECT value FROM marker").mapTo(Integer.class).one().intValue());
            assertEquals(2,newDb.withHandle(h->h.createQuery("SELECT value FROM marker").mapTo(Integer.class).one()).intValue());
            assertEquals(1,oldDb.withHandle(h->h.createQuery("SELECT value FROM marker").mapTo(Integer.class).one()).intValue());
        } finally {oldGeneration.close();newGeneration.close();}
    }
    @Test void rollsBackUnfinishedTransactionsAndAllowsRepeatedRetirement() throws Exception {
        var pool=pool(1,Duration.ofSeconds(1));
        try(var lease=pool.borrow()){lease.setAutoCommit(false);lease.createStatement().executeQuery("SELECT 1").close();}
        try(var next=pool.borrow()){assertTrue(next.getAutoCommit());}
        pool.run();pool.run();assertEquals(0,pool.idleCount());
    }
}
