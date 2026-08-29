package org.treblereel.mcp.db;

import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.treblereel.mcp.model.*;

public final class IndexReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private IndexReader() {}

    public static List<ClassRecord> findAllClasses(Connection conn) {
        List<ClassRecord> result = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM classes ORDER BY id")) {
            while (rs.next()) {
                result.add(mapClass(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<BeanRecord> findBeans(Connection conn, Map<String, String> filter) {
        var sb = new StringBuilder("SELECT b.*, c.class_name FROM beans b JOIN classes c ON b.class_id = c.id WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (filter != null) {
            if (filter.containsKey("class_name")) {
                sb.append(" AND c.class_name LIKE ?");
                params.add(filter.get("class_name").replace("*", "%"));
            }
            if (filter.containsKey("scope")) {
                sb.append(" AND b.scope = ?");
                params.add(filter.get("scope"));
            }
            if (filter.containsKey("kind")) {
                sb.append(" AND b.kind = ?");
                params.add(filter.get("kind"));
            }
            if (filter.containsKey("profile")) {
                sb.append(" AND b.profiles LIKE ?");
                params.add("%" + filter.get("profile") + "%");
            }
            if (filter.containsKey("qualifier")) {
                sb.append(" AND b.qualifiers LIKE ?");
                params.add("%" + filter.get("qualifier") + "%");
            }
        }

        List<BeanRecord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapBean(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<InjectionPointRecord> findInjectionPoints(Connection conn, int beanId) {
        List<InjectionPointRecord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM injection_points WHERE bean_id = ?")) {
            ps.setInt(1, beanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapInjectionPoint(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<DependencyRecord> findDependencies(Connection conn, int classId, String direction) {
        List<DependencyRecord> result = new ArrayList<>();
        String sql = switch (direction) {
            case "outbound" -> "SELECT * FROM dependencies WHERE from_class_id = ?";
            case "inbound" -> "SELECT * FROM dependencies WHERE to_class_id = ?";
            default -> "SELECT * FROM dependencies WHERE from_class_id = ? OR to_class_id = ?";
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            if ("both".equals(direction)) ps.setInt(2, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new DependencyRecord(
                            rs.getInt("from_class_id"), rs.getInt("to_class_id"),
                            rs.getString("kind"),
                            rs.getObject("injection_point_id") != null ? rs.getInt("injection_point_id") : null
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static Map<String, String> getMetadata(Connection conn) {
        Map<String, String> result = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT key, value FROM metadata")) {
            while (rs.next()) {
                result.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static Optional<ClassRecord> findClassByName(Connection conn, String className) {
        String sql = className.contains(".")
                ? "SELECT * FROM classes WHERE class_name = ?"
                : "SELECT * FROM classes WHERE class_name LIKE ?";
        String param = className.contains(".") ? className : "%" + className;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapClass(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public static Optional<BeanRecord> findBeanByClassId(Connection conn, int classId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM beans WHERE class_id = ?")) {
            ps.setInt(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapBean(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public static Optional<ClassRecord> findClassById(Connection conn, int id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM classes WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapClass(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public static Optional<BeanRecord> findBeanById(Connection conn, int id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM beans WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapBean(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    private static ClassRecord mapClass(ResultSet rs) throws SQLException {
        return new ClassRecord(
                rs.getInt("id"), rs.getString("class_name"), rs.getString("kind"),
                rs.getString("superclass"), fromJson(rs.getString("interfaces")),
                rs.getString("source_file"), rs.getInt("source_line"),
                rs.getInt("is_bean") == 1, rs.getInt("source_tokens")
        );
    }

    private static BeanRecord mapBean(ResultSet rs) throws SQLException {
        return new BeanRecord(
                rs.getInt("id"), rs.getInt("class_id"), rs.getString("kind"),
                rs.getString("scope"), fromJson(rs.getString("qualifiers")),
                fromJson(rs.getString("stereotypes")), rs.getInt("is_alternative") == 1,
                rs.getObject("priority") != null ? rs.getInt("priority") : null,
                fromJson(rs.getString("profiles")),
                rs.getObject("declaring_class_id") != null ? rs.getInt("declaring_class_id") : null,
                rs.getString("member_name"), fromJson(rs.getString("bean_types"))
        );
    }

    private static InjectionPointRecord mapInjectionPoint(ResultSet rs) throws SQLException {
        return new InjectionPointRecord(
                rs.getInt("id"), rs.getInt("bean_id"), rs.getString("kind"),
                rs.getString("target_type"), fromJson(rs.getString("qualifiers")),
                rs.getString("field_name"),
                rs.getObject("resolved_bean_id") != null ? rs.getInt("resolved_bean_id") : null,
                rs.getInt("is_ambiguous") == 1
        );
    }

    static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
