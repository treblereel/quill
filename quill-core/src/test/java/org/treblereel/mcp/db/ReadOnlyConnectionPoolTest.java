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
    ReadOnlyConnectionPool pool(int capacity,Duration timeout) {
        Path path=root.resolve("index.db");QuillDatabase.create(path);
        return new ReadOnlyConnectionPool("jdbc:sqlite:"+path.toUri()+"?mode=ro",capacity,timeout);
    }
    @Test void reusesPhysicalReaderButOldLeaseCannotAffectNewBorrower() throws Exception {
        var pool=pool(1,Duration.ofSeconds(1));
        Connection first=pool.borrow();Statement outstanding=first.createStatement();Object physical=first.unwrap(org.sqlite.SQLiteConnection.class);first.close();first.close();assertTrue(outstanding.isClosed());
        try(Connection second=pool.borrow()) {
            assertSame(physical,second.unwrap(org.sqlite.SQLiteConnection.class));assertFalse(second.isClosed());
            first.close();assertFalse(second.isClosed());
            assertThrows(SQLException.class,()->first.createStatement());
            assertThrows(SQLException.class,()->second.createStatement().execute("CREATE TABLE forbidden(id INTEGER)"));
            assertEquals(1,pool.borrowedCount());
        }
        assertEquals(1,pool.idleCount());pool.run();assertEquals(0,pool.idleCount());
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
        var pool=pool(2,Duration.ofSeconds(1));var active=pool.borrow();Connection physical=active.unwrap(org.sqlite.SQLiteConnection.class);
        pool.run();assertEquals(1,active.createStatement().executeQuery("SELECT 1").getInt(1));active.close();assertTrue(physical.isClosed());
        // A request resolved before retirement can still read the old immutable generation.
        try(var late=pool.borrow()){assertEquals(1,late.createStatement().executeQuery("SELECT 1").getInt(1));}
        assertEquals(0,pool.idleCount());assertEquals(0,pool.borrowedCount());
    }
    @Test void publishedGenerationsNeverShareConnectionsOrRows() throws Exception {
        Path old=root.resolve("old.db"),now=root.resolve("new.db");
        QuillDatabase.create(old).useHandle(h->{h.execute("CREATE TABLE marker(value INTEGER)");h.execute("INSERT INTO marker VALUES(1)");});
        QuillDatabase.create(now).useHandle(h->{h.execute("CREATE TABLE marker(value INTEGER)");h.execute("INSERT INTO marker VALUES(2)");});
        var oldDb=QuillDatabase.openPooled(old);var newDb=QuillDatabase.openPooled(now);
        try(var active=oldDb.open()) {
            QuillDatabase.retire(oldDb);
            assertEquals(1,active.createQuery("SELECT value FROM marker").mapTo(Integer.class).one().intValue());
            assertEquals(2,newDb.withHandle(h->h.createQuery("SELECT value FROM marker").mapTo(Integer.class).one()).intValue());
            assertEquals(1,oldDb.withHandle(h->h.createQuery("SELECT value FROM marker").mapTo(Integer.class).one()).intValue());
        } finally {QuillDatabase.retire(oldDb);QuillDatabase.retire(newDb);}
    }
    @Test void rollsBackUnfinishedTransactionsAndAllowsRepeatedRetirement() throws Exception {
        var pool=pool(1,Duration.ofSeconds(1));
        try(var lease=pool.borrow()){lease.setAutoCommit(false);lease.createStatement().executeQuery("SELECT 1").close();}
        try(var next=pool.borrow()){assertTrue(next.getAutoCommit());}
        pool.run();pool.run();assertEquals(0,pool.idleCount());
    }
}
