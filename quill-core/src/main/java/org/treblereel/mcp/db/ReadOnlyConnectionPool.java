package org.treblereel.mcp.db;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small generation-local pool. Leases remain isolated; retirement closes idle and returned readers. */
final class ReadOnlyConnectionPool implements Runnable {
    private final String url;
    private final int maximum;
    private final long timeoutNanos;
    private final ArrayDeque<Connection> idle=new ArrayDeque<>();
    private int borrowed;
    private boolean retired;
    ReadOnlyConnectionPool(String url,int maximum,Duration timeout) {
        if(maximum<1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid pool bounds");
        this.url=url;this.maximum=maximum;this.timeoutNanos=timeout.toNanos();
    }
    Connection borrow() throws SQLException {
        Connection connection;
        synchronized(this) {
            long deadline=System.nanoTime()+timeoutNanos;
            while(!retired && borrowed>=maximum && idle.isEmpty()) {
                long left=deadline-System.nanoTime();if(left<=0) throw new SQLTimeoutException("Read-only index pool exhausted");
                try {wait(Math.max(1,left/1_000_000));} catch(InterruptedException error) {Thread.currentThread().interrupt();throw new SQLException("Interrupted waiting for an index reader",error);}
            }
            // Retired generations may still have already-resolved requests. Serve these with
            // disposable readers, never reopen an idle pool or mix a newer generation into them.
            connection=idle.pollFirst();
            if(connection==null) {
                connection=DriverManager.getConnection(url);QueryMetrics.connectionOpened();
                try(var statement=connection.createStatement()) {statement.execute("PRAGMA foreign_keys=ON");}
                catch(SQLException error) {connection.close();throw error;}
            }
            borrowed++;
        }
        Connection physical=connection;AtomicBoolean closed=new AtomicBoolean();
        var statements=new java.util.HashSet<java.sql.Statement>();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("close")) {
                if(closed.compareAndSet(false,true)) {
                    synchronized(physical) {for(var statement:statements)try{statement.close();}catch(SQLException ignored){}statements.clear();}
                    release(physical);
                }
                return null;
            }
            if(name.equals("isClosed")) return closed.get() || physical.isClosed();
            if(name.equals("equals")) return proxy==args[0];
            if(name.equals("hashCode")) return System.identityHashCode(proxy);
            if(name.equals("toString")) return "Quill read-only connection lease";
            synchronized(physical) {
            if(closed.get()) throw new SQLException("Index reader lease is closed");
            if(name.equals("unwrap") && ((Class<?>)args[0]).isInstance(proxy)) return proxy;
            if(name.equals("isWrapperFor") && ((Class<?>)args[0]).isInstance(proxy)) return true;
            try {
                Object result=method.invoke(physical,args);
                if(result instanceof java.sql.Statement statement) statements.add(statement);
                return result;
            } catch(InvocationTargetException error) {throw error.getCause();}
            }
        });
    }
    private synchronized void release(Connection connection) {
        boolean reusable=!retired;
        try {
            if(connection.isClosed()) reusable=false;
            else if(!connection.getAutoCommit()) {connection.rollback();connection.setAutoCommit(true);}
            if(reusable) {connection.clearWarnings();idle.addLast(connection);}
            else connection.close();
        } catch(SQLException error) {try{connection.close();}catch(SQLException ignored){}}
        finally {borrowed--;notifyAll();}
    }
    @Override public synchronized void run() {
        retired=true;
        while(!idle.isEmpty()) try{idle.removeFirst().close();}catch(SQLException ignored){}
        notifyAll();
    }
    synchronized int idleCount() {return idle.size();}
    synchronized int borrowedCount() {return borrowed;}
}
