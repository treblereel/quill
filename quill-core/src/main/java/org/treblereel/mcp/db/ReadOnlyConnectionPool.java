package org.treblereel.mcp.db;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import org.jdbi.v3.core.ConnectionFactory;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small generation-local pool. Leases remain isolated; retirement closes idle and returned readers.
 */
final class ReadOnlyConnectionPool implements Runnable {
    private final ConnectionFactory connections;
    private final int maximum;
    private final long timeoutNanos;
    private final ArrayDeque<Connection> idle = new ArrayDeque<>();
    private int borrowed;
    private boolean retired;

    ReadOnlyConnectionPool(ConnectionFactory connections, int maximum, Duration timeout) {
        if (maximum < 1 || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Invalid pool bounds");
        this.connections = connections;
        this.maximum = maximum;
        this.timeoutNanos = timeout.toNanos();
    }

    Connection borrow() throws SQLException {
        Connection connection;
        synchronized (this) {
            long deadline = System.nanoTime() + timeoutNanos;
            while (borrowed >= maximum && idle.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new SQLTimeoutException("Read-only index pool exhausted");
                try {
                    wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted waiting for an index reader", error);
                }
            }
            // Retired generations may still have already-resolved requests. Serve these with
            // disposable readers, never reopen an idle pool or mix a newer generation into them.
            connection = idle.pollFirst();
            // Reserve the slot now; a new reader is opened outside the monitor so concurrent
            // borrowers and returning leases are not serialized behind SQLite file I/O.
            borrowed++;
        }
        if (connection == null) {
            try {
                connection = connections.openConnection();
            } catch (SQLException | RuntimeException | Error failure) {
                synchronized (this) {
                    borrowed--;
                    notifyAll();
                }
                throw failure;
            }
        }
        Connection physical = connection;
        AtomicBoolean closed = new AtomicBoolean();
        var resources = new java.util.HashSet<AutoCloseable>();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "close" -> {
                    if (closed.compareAndSet(false, true)) {
                        synchronized (physical) {
                            for (var resource : resources)
                                try {
                                    resource.close();
                                } catch (Exception ignored) {
                                }
                            resources.clear();
                        }
                        release(physical);
                    }
                    return null;
                }
                case "isClosed" -> {
                    return closed.get() || physical.isClosed();
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "Quill read-only connection lease";
                }
            }
            synchronized (physical) {
                if (closed.get()) throw new SQLException("Index reader lease is closed");
                if (name.equals("unwrap")) {
                    if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                    throw new SQLException("Physical index readers cannot escape their lease");
                }
                if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
                try {
                    Object result = method.invoke(physical, args);
                    if (result instanceof java.sql.Statement statement) {
                        resources.add(statement);
                        Class<?> api = statement instanceof java.sql.CallableStatement ? java.sql.CallableStatement.class
                                : statement instanceof java.sql.PreparedStatement ? java.sql.PreparedStatement.class : java.sql.Statement.class;
                        return guard(result, api, (Connection) proxy, null, physical, closed, resources);
                    }
                    if (result instanceof java.sql.DatabaseMetaData)
                        return guard(result, java.sql.DatabaseMetaData.class, (Connection) proxy, null, physical, closed, resources);
                    return result;
                } catch (InvocationTargetException error) {
                    throw error.getCause();
                }
            }
        });
    }

    private static Object guard(Object target, Class<?> api, Connection lease,
            java.sql.Statement owner, Connection physical, AtomicBoolean closed, java.util.Set<AutoCloseable> resources) {
        return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("equals")) return proxy == args[0];
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("toString")) return "Quill leased " + api.getSimpleName();
            synchronized (physical) {
                if (name.equals("isClosed") && closed.get()) return true;
                if (name.equals("close") && closed.get()) return null;
                if (closed.get()) throw new SQLException("Index reader lease is closed");
                if (name.equals("getConnection")) return lease;
                if (name.equals("getStatement") && owner != null) return owner;
                if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
                if (name.equals("unwrap")) {
                    if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                    throw new SQLException("Physical index resources cannot escape their lease");
                }
                try {
                    Object value = method.invoke(target, args);
                    if (name.equals("close")) resources.remove(target);
                    Class<?> resultType = method.getReturnType();
                    if (value != null && (resultType == java.sql.ResultSetMetaData.class || resultType == java.sql.ParameterMetaData.class))
                        return guard(value, resultType, lease, owner, physical, closed, resources);
                    if (java.sql.Statement.class.isAssignableFrom(resultType) && value instanceof java.sql.Statement statement) {
                        resources.add(statement);
                        return guard(value, java.sql.Statement.class, lease, null, physical, closed, resources);
                    }
                    if (resultType == java.sql.ResultSet.class && value instanceof java.sql.ResultSet rows) {
                        resources.add(rows);
                        return guard(value, java.sql.ResultSet.class, lease,
                                proxy instanceof java.sql.Statement statement ? statement : owner, physical, closed, resources);
                    }
                    return value;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            }
        });
    }

    private synchronized void release(Connection connection) {
        boolean reusable = !retired;
        try {
            if (connection.isClosed()) reusable = false;
            else if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            if (reusable) {
                connection.clearWarnings();
                idle.addLast(connection);
            } else connection.close();
        } catch (SQLException error) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        } finally {
            borrowed--;
            notifyAll();
        }
    }

    @Override
    public synchronized void run() {
        retired = true;
        while (!idle.isEmpty()) try {
            idle.removeFirst().close();
        } catch (SQLException ignored) {
        }
        notifyAll();
    }

    synchronized int idleCount() {
        return idle.size();
    }

    synchronized int borrowedCount() {
        return borrowed;
    }
}
