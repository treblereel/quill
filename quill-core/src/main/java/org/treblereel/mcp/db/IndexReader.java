package org.treblereel.mcp.db;

import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.treblereel.mcp.model.*;
import org.treblereel.mcp.model.CoChangeRecord;
import org.treblereel.mcp.model.GitCommitFile;
import org.treblereel.mcp.model.GitCommitRecord;
import org.treblereel.mcp.model.GitFileStats;

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

    public static List<GitFileStats> findHotspots(Connection conn, int limit, String since) {
        var sb = new StringBuilder(
                "SELECT * FROM git_file_stats WHERE commit_count > 0");
        List<Object> params = new ArrayList<>();
        if (since != null) {
            sb.append(" AND last_modified >= ?");
            params.add(since);
        }
        sb.append(" ORDER BY commit_count DESC LIMIT ?");
        params.add(limit);

        List<GitFileStats> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapGitFileStats(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<GitCommitRecord> findFileHistory(Connection conn, int classId, int limit) {
        String sql = """
                SELECT gc.* FROM git_commits gc
                JOIN git_commit_files gcf ON gc.id = gcf.commit_id
                WHERE gcf.class_id = ?
                ORDER BY gc.committed_at DESC
                LIMIT ?""";
        List<GitCommitRecord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapGitCommit(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<CoChangeRecord> findCoChanges(Connection conn, int classId, int limit) {
        String sql = """
                SELECT gcf2.file_path, gcf2.class_id, COUNT(*) as co_count
                FROM git_commit_files gcf1
                JOIN git_commit_files gcf2 ON gcf1.commit_id = gcf2.commit_id
                    AND gcf1.file_path != gcf2.file_path
                WHERE gcf1.class_id = ?
                GROUP BY gcf2.file_path
                ORDER BY co_count DESC
                LIMIT ?""";
        List<CoChangeRecord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int coCount = rs.getInt("co_count");
                    result.add(new CoChangeRecord(
                            rs.getString("file_path"),
                            rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                            coCount,
                            0.0
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<GitCommitRecord> findRecentCommits(Connection conn, int limit) {
        String sql = "SELECT * FROM git_commits ORDER BY committed_at DESC LIMIT ?";
        List<GitCommitRecord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapGitCommit(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<GitCommitFile> findCommitFiles(Connection conn, int commitId) {
        String sql = "SELECT * FROM git_commit_files WHERE commit_id = ?";
        List<GitCommitFile> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, commitId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new GitCommitFile(
                            rs.getInt("commit_id"),
                            rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                            rs.getString("file_path"),
                            rs.getString("change_type")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static Optional<GitFileStats> findFileStatsByClassId(Connection conn, int classId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM git_file_stats WHERE class_id = ?")) {
            ps.setInt(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapGitFileStats(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    public static int countClasses(Connection conn) {
        return countQuery(conn, "SELECT COUNT(*) FROM classes");
    }

    public static int countBeans(Connection conn) {
        return countQuery(conn, "SELECT COUNT(*) FROM beans");
    }

    public static int countCommits(Connection conn) {
        return countQuery(conn, "SELECT COUNT(*) FROM git_commits");
    }

    public static Map<String, Integer> countBeansByScope(Connection conn) {
        return groupCountQuery(conn, "SELECT scope, COUNT(*) as cnt FROM beans GROUP BY scope ORDER BY cnt DESC");
    }

    public static Map<String, Integer> countBeansByKind(Connection conn) {
        return groupCountQuery(conn, "SELECT kind, COUNT(*) as cnt FROM beans GROUP BY kind ORDER BY cnt DESC");
    }

    public static List<InjectionPointRecord> findUnsatisfiedInjectionPoints(Connection conn) {
        List<InjectionPointRecord> result = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM injection_points WHERE resolved_bean_id IS NULL")) {
            while (rs.next()) {
                result.add(mapInjectionPoint(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<InjectionPointRecord> findAmbiguousInjectionPoints(Connection conn) {
        List<InjectionPointRecord> result = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM injection_points WHERE is_ambiguous = 1")) {
            while (rs.next()) {
                result.add(mapInjectionPoint(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<Map.Entry<Integer, Integer>> findMostDependedOn(Connection conn, int limit) {
        String sql = "SELECT to_class_id, COUNT(*) as dep_count FROM dependencies GROUP BY to_class_id ORDER BY dep_count DESC LIMIT ?";
        List<Map.Entry<Integer, Integer>> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(Map.entry(rs.getInt("to_class_id"), rs.getInt("dep_count")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static int countDependents(Connection conn, int classId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM dependencies WHERE to_class_id = ?")) {
            ps.setInt(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int countDependencies(Connection conn, int classId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM dependencies WHERE from_class_id = ?")) {
            ps.setInt(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static int countQuery(Connection conn, String sql) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Integer> groupCountQuery(Connection conn, String sql) {
        Map<String, Integer> result = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<ExternalDepRecord> findExternalDeps(Connection conn, int classId) {
        List<ExternalDepRecord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM class_external_deps WHERE class_id = ? ORDER BY usage_kind, external_type")) {
            ps.setInt(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ExternalDepRecord(
                            rs.getInt("class_id"), rs.getString("external_type"), rs.getString("usage_kind")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<Map.Entry<String, Integer>> findExternalDepsByLibrary(Connection conn, int limit) {
        String sql = """
                SELECT substr(external_type, 1, instr(substr(external_type, instr(external_type, '.') + 1), '.') + instr(external_type, '.') - 1) as library,
                       COUNT(DISTINCT class_id) as class_count
                FROM class_external_deps
                GROUP BY library
                ORDER BY class_count DESC
                LIMIT ?""";
        List<Map.Entry<String, Integer>> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String lib = rs.getString("library");
                    if (lib != null && !lib.isEmpty()) {
                        result.add(Map.entry(lib, rs.getInt("class_count")));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static List<Map.Entry<Integer, String>> findClassesUsingType(Connection conn, String typePattern) {
        String sql = "SELECT DISTINCT ced.class_id, c.class_name FROM class_external_deps ced JOIN classes c ON ced.class_id = c.id WHERE ced.external_type LIKE ? ORDER BY c.class_name";
        List<Map.Entry<Integer, String>> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, typePattern.replace("*", "%"));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(Map.entry(rs.getInt("class_id"), rs.getString("class_name")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public static boolean hasExternalDeps(Connection conn) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM class_external_deps")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean hasGitData(Connection conn) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM git_commits")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    private static GitFileStats mapGitFileStats(ResultSet rs) throws SQLException {
        return new GitFileStats(
                rs.getInt("id"),
                rs.getString("file_path"),
                rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                rs.getInt("commit_count"),
                rs.getString("last_modified"),
                rs.getString("last_author"),
                rs.getString("first_commit"),
                rs.getInt("distinct_authors")
        );
    }

    private static GitCommitRecord mapGitCommit(ResultSet rs) throws SQLException {
        return new GitCommitRecord(
                rs.getInt("id"),
                rs.getString("hash"),
                rs.getString("short_hash"),
                rs.getString("author"),
                rs.getString("author_email"),
                rs.getString("committed_at"),
                rs.getString("message")
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
