package org.treblereel.mcp.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.treblereel.mcp.model.*;

public final class IndexWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private IndexWriter() {}

    public static void write(Connection conn, List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata) {
        try {
            conn.setAutoCommit(false);
            clearTables(conn);
            writeClasses(conn, classes);
            writeBeans(conn, beans);
            writeInjectionPoints(conn, injectionPoints);
            writeDependencies(conn, dependencies);
            writeMetadata(conn, metadata);
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            throw new RuntimeException("Failed to write index", e);
        }
    }

    private static void clearTables(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            for (String table : new String[]{"dependencies", "injection_points", "beans", "classes", "metadata"}) {
                stmt.executeUpdate("DELETE FROM " + table);
            }
            stmt.executeUpdate("DELETE FROM sqlite_sequence");
        }
    }

    private static void writeClasses(Connection conn, List<ClassRecord> classes) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO classes (class_name, kind, superclass, interfaces, source_file, source_line, is_bean, source_tokens) VALUES (?,?,?,?,?,?,?,?)")) {
            for (ClassRecord c : classes) {
                ps.setString(1, c.className());
                ps.setString(2, c.kind());
                ps.setString(3, c.superclass());
                ps.setString(4, toJson(c.interfaces()));
                ps.setString(5, c.sourceFile());
                ps.setInt(6, c.sourceLine());
                ps.setInt(7, c.isBean() ? 1 : 0);
                ps.setInt(8, c.sourceTokens());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void writeBeans(Connection conn, List<BeanRecord> beans) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO beans (class_id, kind, scope, qualifiers, stereotypes, is_alternative, priority, profiles, declaring_class_id, member_name, bean_types) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            for (BeanRecord b : beans) {
                ps.setInt(1, b.classId());
                ps.setString(2, b.kind());
                ps.setString(3, b.scope());
                ps.setString(4, toJson(b.qualifiers()));
                ps.setString(5, toJson(b.stereotypes()));
                ps.setInt(6, b.isAlternative() ? 1 : 0);
                if (b.priority() != null) ps.setInt(7, b.priority()); else ps.setNull(7, java.sql.Types.INTEGER);
                ps.setString(8, toJson(b.profiles()));
                if (b.declaringClassId() != null) ps.setInt(9, b.declaringClassId()); else ps.setNull(9, java.sql.Types.INTEGER);
                ps.setString(10, b.memberName());
                ps.setString(11, toJson(b.beanTypes()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void writeInjectionPoints(Connection conn, List<InjectionPointRecord> ips) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO injection_points (bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, is_ambiguous) VALUES (?,?,?,?,?,?,?)")) {
            for (InjectionPointRecord ip : ips) {
                ps.setInt(1, ip.beanId());
                ps.setString(2, ip.kind());
                ps.setString(3, ip.targetType());
                ps.setString(4, toJson(ip.qualifiers()));
                ps.setString(5, ip.fieldName());
                if (ip.resolvedBeanId() != null) ps.setInt(6, ip.resolvedBeanId()); else ps.setNull(6, java.sql.Types.INTEGER);
                ps.setInt(7, ip.isAmbiguous() ? 1 : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void writeDependencies(Connection conn, List<DependencyRecord> deps) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO dependencies (from_class_id, to_class_id, kind, injection_point_id) VALUES (?,?,?,?)")) {
            for (DependencyRecord d : deps) {
                ps.setInt(1, d.fromClassId());
                ps.setInt(2, d.toClassId());
                ps.setString(3, d.kind());
                if (d.injectionPointId() != null) ps.setInt(4, d.injectionPointId()); else ps.setNull(4, java.sql.Types.INTEGER);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void writeMetadata(Connection conn, Map<String, String> metadata) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO metadata (key, value) VALUES (?,?)")) {
            for (var entry : metadata.entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setString(2, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
