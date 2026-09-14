package org.treblereel.mcp.db;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.model.*;

public final class IndexReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private IndexReader() {}

    public record DependencyBreakdown(String origin, int classes, int edges) {}

    public static List<ClassRecord> findAllClasses(Jdbi jdbi) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM classes WHERE lifecycle = 'current' "
                                + "AND origin != 'orphan_output' ORDER BY id")
                        .map((rs, ctx) -> mapClass(rs))
                        .list());
    }

    public static List<BeanRecord> findBeans(Jdbi jdbi, Map<String, String> filter) {
        return jdbi.withHandle(h -> {
            var sb = new StringBuilder("SELECT b.*, c.class_name FROM beans b "
                    + "JOIN classes c ON b.class_id = c.id "
                    + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'");

            if (filter != null) {
                if (filter.containsKey("class_name")) sb.append(" AND c.class_name LIKE :className");
                if (filter.containsKey("scope")) sb.append(" AND b.scope = :scope");
                if (filter.containsKey("kind")) sb.append(" AND b.kind = :kind");
                if (filter.containsKey("qualifier")) sb.append(" AND b.qualifiers LIKE :qualifier");
            }

            var q = h.createQuery(sb.toString());
            if (filter != null) {
                if (filter.containsKey("class_name")) q.bind("className", filter.get("class_name").replace("*", "%"));
                if (filter.containsKey("scope")) q.bind("scope", filter.get("scope"));
                if (filter.containsKey("kind")) q.bind("kind", filter.get("kind"));
                if (filter.containsKey("qualifier")) q.bind("qualifier", "%" + filter.get("qualifier") + "%");
            }

            List<BeanRecord> results = q.map((rs, ctx) -> mapBean(rs)).list();

            if (filter != null && filter.containsKey("profile")) {
                String requestedProfile = filter.get("profile");
                results = results.stream()
                        .filter(b -> matchesProfile(b.profiles(), requestedProfile))
                        .toList();
            }

            return results;
        });
    }

    static boolean matchesProfile(List<String> beanProfiles, String requestedProfile) {
        if (beanProfiles == null || beanProfiles.isEmpty()) return false;
        for (String p : beanProfiles) {
            if (p.equals(requestedProfile)) return true;
        }
        return false;
    }

    public static List<InjectionPointRecord> findInjectionPoints(Jdbi jdbi, int beanId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM injection_points WHERE bean_id = :beanId")
                        .bind("beanId", beanId)
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list());
    }

    public static List<DependencyRecord> findDependencies(Jdbi jdbi, int classId, String direction) {
        String sql = switch (direction) {
            case "outbound" -> "SELECT * FROM dependencies WHERE from_class_id = :classId";
            case "inbound" -> "SELECT * FROM dependencies WHERE to_class_id = :classId";
            default -> "SELECT * FROM dependencies WHERE from_class_id = :classId OR to_class_id = :classId";
        };
        return jdbi.withHandle(h ->
                h.createQuery(sql)
                        .bind("classId", classId)
                        .map((rs, ctx) -> new DependencyRecord(
                                rs.getInt("from_class_id"), rs.getInt("to_class_id"),
                                rs.getString("kind"),
                                rs.getObject("injection_point_id") != null ? rs.getInt("injection_point_id") : null,
                                rs.getInt("occurrence_count")))
                        .list());
    }

    public static Map<String, String> getMetadata(Jdbi jdbi) {
        return jdbi.withHandle(h -> {
            Map<String, String> result = new LinkedHashMap<>();
            h.createQuery("SELECT key, value FROM metadata")
                    .map((rs, ctx) -> Map.entry(rs.getString("key"), rs.getString("value")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    public static Optional<ClassRecord> findClassByName(Jdbi jdbi, String className) {
        if (className.contains(".")) {
            return jdbi.withHandle(h ->
                    h.createQuery("SELECT * FROM classes WHERE class_name = :name "
                                    + "AND lifecycle = 'current' AND origin != 'orphan_output'")
                            .bind("name", className)
                            .map((rs, ctx) -> mapClass(rs))
                            .findFirst());
        }
        List<ClassRecord> matches = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM classes WHERE class_name LIKE :name "
                                + "AND lifecycle = 'current' AND origin != 'orphan_output' "
                                + "ORDER BY class_name")
                        .bind("name", "%" + className)
                        .map((rs, ctx) -> mapClass(rs))
                        .list());
        if (matches.size() == 1) return Optional.of(matches.get(0));
        return Optional.empty();
    }

    public static Optional<ClassRecord> findClassByPath(Jdbi jdbi, String path) {
        String normalized = path.replace('\\', '/');
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT c.* FROM classes c
                        LEFT JOIN files f ON f.id = c.file_id
                        WHERE (c.source_file = :path
                           OR f.project_path = :path
                           OR f.repository_path = :path)
                          AND c.lifecycle = 'current' AND c.origin != 'orphan_output'
                        ORDER BY CASE WHEN c.lifecycle = 'current' THEN 0 ELSE 1 END, c.class_name
                        LIMIT 1""")
                .bind("path", normalized)
                .map((rs, ctx) -> mapClass(rs))
                .findFirst());
    }

    public static Optional<FileRecord> findFileByPath(Jdbi jdbi, String path) {
        String normalized = path.replace('\\', '/');
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT * FROM files
                        WHERE project_path = :path OR repository_path = :path
                        ORDER BY CASE WHEN lifecycle = 'current' THEN 0 ELSE 1 END
                        LIMIT 1""")
                .bind("path", normalized)
                .map((rs, ctx) -> mapFile(rs))
                .findFirst());
    }

    public static List<FileRecord> findFileCandidates(Jdbi jdbi, String target, int limit) {
        String normalized = target.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (basename.contains(".")) {
            String possibleClass = basename.endsWith(".java") || basename.endsWith(".kt")
                    ? basename.substring(0, basename.lastIndexOf('.'))
                    : basename.substring(basename.lastIndexOf('.') + 1);
            basename = possibleClass;
        }
        String javaPattern = "%/" + basename + ".java";
        String kotlinPattern = "%/" + basename + ".kt";
        List<FileRecord> matches = jdbi.withHandle(h -> h.createQuery("""
                        SELECT id, project_path, repository_path, kind, origin, lifecycle,
                               worktree_status, source_rank
                        FROM (
                            SELECT id, project_path, repository_path, kind, origin, lifecycle,
                                   worktree_status, 0 AS source_rank
                            FROM files
                            WHERE project_path LIKE :javaPattern OR repository_path LIKE :javaPattern
                               OR project_path LIKE :kotlinPattern OR repository_path LIKE :kotlinPattern
                            UNION ALL
                            SELECT 0 AS id, g.file_path AS project_path,
                                   g.file_path AS repository_path,
                                   CASE WHEN g.file_path LIKE '%.kt' THEN 'kotlin' ELSE 'java' END AS kind,
                                   'source' AS origin, 'historical' AS lifecycle,
                                   NULL AS worktree_status, 1 AS source_rank
                            FROM git_file_stats g
                            WHERE g.file_path LIKE :javaPattern OR g.file_path LIKE :kotlinPattern
                        )
                        ORDER BY source_rank,
                                 CASE WHEN lifecycle = 'current' THEN 0 ELSE 1 END,
                                 repository_path""")
                .bind("javaPattern", javaPattern)
                .bind("kotlinPattern", kotlinPattern)
                .map((rs, ctx) -> mapFile(rs))
                .list());
        Map<String, FileRecord> unique = new LinkedHashMap<>();
        for (FileRecord match : matches) unique.putIfAbsent(match.repositoryPath(), match);
        return unique.values().stream().limit(limit).toList();
    }

    public static List<ClassRecord> findClassesByShortName(Jdbi jdbi, String shortName) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM classes WHERE "
                                + "(class_name = :shortName OR class_name LIKE :name) "
                                + "AND lifecycle = 'current' AND origin != 'orphan_output' "
                                + "ORDER BY class_name")
                        .bind("shortName", shortName)
                        .bind("name", "%." + shortName)
                        .map((rs, ctx) -> mapClass(rs))
                        .list());
    }

    public static List<ClassRecord> searchClasses(Jdbi jdbi, String namePattern, int limit) {
        String sql = "SELECT * FROM classes WHERE class_name LIKE :pattern "
                + "AND lifecycle = 'current' AND origin != 'orphan_output' "
                + "ORDER BY class_name LIMIT :limit";
        String pattern = namePattern.replace("*", "%");
        if (!pattern.contains("%")) {
            pattern = "%" + pattern + "%";
        }
        String finalPattern = pattern;
        return jdbi.withHandle(h ->
                h.createQuery(sql)
                        .bind("pattern", finalPattern)
                        .bind("limit", limit)
                        .map((rs, ctx) -> mapClass(rs))
                        .list());
    }

    public static Optional<BeanRecord> findBeanByClassId(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM beans WHERE class_id = :classId")
                        .bind("classId", classId)
                        .map((rs, ctx) -> mapBean(rs))
                        .findFirst());
    }

    public static Optional<ClassRecord> findClassById(Jdbi jdbi, int id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM classes WHERE id = :id")
                        .bind("id", id)
                        .map((rs, ctx) -> mapClass(rs))
                        .findFirst());
    }

    public static Optional<BeanRecord> findBeanById(Jdbi jdbi, int id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM beans WHERE id = :id")
                        .bind("id", id)
                        .map((rs, ctx) -> mapBean(rs))
                        .findFirst());
    }

    public static Map<Integer, ClassRecord> findClassesByIds(Jdbi jdbi, Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<Integer, ClassRecord> result = new LinkedHashMap<>();
            h.createQuery("SELECT * FROM classes WHERE id IN (<ids>) ORDER BY id")
                    .bindList("ids", new LinkedHashSet<>(ids))
                    .map((rs, ctx) -> mapClass(rs))
                    .forEach(record -> result.put(record.id(), record));
            return result;
        });
    }

    public static Map<Integer, BeanRecord> findBeansByIds(Jdbi jdbi, Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<Integer, BeanRecord> result = new LinkedHashMap<>();
            h.createQuery("SELECT * FROM beans WHERE id IN (<ids>) ORDER BY id")
                    .bindList("ids", new LinkedHashSet<>(ids))
                    .map((rs, ctx) -> mapBean(rs))
                    .forEach(record -> result.put(record.id(), record));
            return result;
        });
    }

    public static Map<Integer, BeanRecord> findBeansByClassIds(Jdbi jdbi, Collection<Integer> classIds) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<Integer, BeanRecord> result = new LinkedHashMap<>();
            h.createQuery("SELECT * FROM beans WHERE class_id IN (<ids>) ORDER BY id")
                    .bindList("ids", new LinkedHashSet<>(classIds))
                    .map((rs, ctx) -> mapBean(rs))
                    .forEach(record -> result.putIfAbsent(record.classId(), record));
            return result;
        });
    }

    private static ClassRecord mapClass(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClassRecord(
                rs.getInt("id"), rs.getString("class_name"), rs.getString("kind"),
                rs.getString("superclass"), fromJson(rs.getString("interfaces")),
                rs.getString("source_file"), rs.getInt("source_line"),
                rs.getInt("is_bean") == 1, rs.getInt("source_tokens"),
                rs.getObject("file_id") != null ? rs.getInt("file_id") : null,
                rs.getString("origin"), rs.getString("lifecycle"));
    }

    private static FileRecord mapFile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FileRecord(rs.getInt("id"), rs.getString("project_path"),
                rs.getString("repository_path"), rs.getString("kind"),
                rs.getString("origin"), rs.getString("lifecycle"),
                rs.getString("worktree_status"));
    }

    private static BeanRecord mapBean(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BeanRecord(
                rs.getInt("id"), rs.getInt("class_id"), rs.getString("kind"),
                rs.getString("scope"), fromJson(rs.getString("qualifiers")),
                fromJson(rs.getString("stereotypes")), rs.getInt("is_alternative") == 1,
                rs.getObject("priority") != null ? rs.getInt("priority") : null,
                fromJson(rs.getString("profiles")),
                rs.getObject("declaring_class_id") != null ? rs.getInt("declaring_class_id") : null,
                rs.getString("member_name"), fromJson(rs.getString("bean_types")));
    }

    private static InjectionPointRecord mapInjectionPoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InjectionPointRecord(
                rs.getInt("id"), rs.getInt("bean_id"), rs.getString("kind"),
                rs.getString("target_type"), fromJson(rs.getString("qualifiers")),
                rs.getString("field_name"),
                rs.getObject("resolved_bean_id") != null ? rs.getInt("resolved_bean_id") : null,
                rs.getInt("is_ambiguous") == 1);
    }

    public static List<GitFileStats> findHotspots(Jdbi jdbi, int limit, String since) {
        if (since == null) {
            return jdbi.withHandle(h ->
                    h.createQuery("SELECT * FROM git_file_stats WHERE commit_count > 0 ORDER BY commit_count DESC LIMIT :limit")
                            .bind("limit", limit)
                            .map((rs, ctx) -> mapGitFileStats(rs))
                            .list());
        }
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT gcf.file_path, gfs.class_id,
                               COUNT(DISTINCT gc.id) as commit_count,
                               MAX(gc.committed_at) as last_modified,
                               MAX(CASE WHEN gc.committed_at = (
                                   SELECT MAX(gc2.committed_at) FROM git_commits gc2
                                   JOIN git_commit_files gcf2 ON gc2.id = gcf2.commit_id
                                   WHERE gcf2.file_path = gcf.file_path AND gc2.committed_at >= :since
                               ) THEN gc.author END) as last_author,
                               MIN(gc.committed_at) as first_commit,
                               COUNT(DISTINCT gc.author) as distinct_authors
                        FROM git_commit_files gcf
                        JOIN git_commits gc ON gcf.commit_id = gc.id
                        LEFT JOIN git_file_stats gfs ON gcf.file_path = gfs.file_path
                        WHERE gc.committed_at >= :since
                        GROUP BY gcf.file_path
                        ORDER BY commit_count DESC
                        LIMIT :limit""")
                        .bind("since", since)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new GitFileStats(
                                0,
                                rs.getString("file_path"),
                                rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                                rs.getInt("commit_count"),
                                rs.getString("last_modified"),
                                rs.getString("last_author"),
                                rs.getString("first_commit"),
                                rs.getInt("distinct_authors")))
                        .list());
    }

    public static List<GitCommitRecord> findFileHistory(Jdbi jdbi, int classId, int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT gc.* FROM git_commits gc
                        JOIN git_commit_files gcf ON gc.id = gcf.commit_id
                        WHERE gcf.class_id = :classId
                        ORDER BY gc.committed_at DESC
                        LIMIT :limit""")
                        .bind("classId", classId)
                        .bind("limit", limit)
                        .map((rs, ctx) -> mapGitCommit(rs))
                        .list());
    }

    public static List<GitCommitRecord> findFileHistoryByPath(
            Jdbi jdbi, String filePath, int limit) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT gc.* FROM git_commits gc
                        JOIN git_commit_files gcf ON gc.id = gcf.commit_id
                        WHERE gcf.file_path = :filePath
                        ORDER BY gc.committed_at DESC
                        LIMIT :limit""")
                .bind("filePath", filePath.replace('\\', '/'))
                .bind("limit", limit)
                .map((rs, ctx) -> mapGitCommit(rs))
                .list());
    }

    public static List<CoChangeRecord> findCoChanges(Jdbi jdbi, int classId, int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT gcf2.file_path, gcf2.class_id, COUNT(*) as co_count
                        FROM git_commit_files gcf1
                        JOIN git_commit_files gcf2 ON gcf1.commit_id = gcf2.commit_id
                            AND gcf1.file_path != gcf2.file_path
                        WHERE gcf1.class_id = :classId
                        GROUP BY gcf2.file_path
                        ORDER BY co_count DESC
                        LIMIT :limit""")
                        .bind("classId", classId)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new CoChangeRecord(
                                rs.getString("file_path"),
                                rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                                rs.getInt("co_count"), 0.0))
                        .list());
    }

    public static List<CoChangeRecord> findCoChangesByPath(
            Jdbi jdbi, String filePath, int limit) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT gcf2.file_path, gcf2.class_id, COUNT(*) as co_count
                        FROM git_commit_files gcf1
                        JOIN git_commit_files gcf2 ON gcf1.commit_id = gcf2.commit_id
                            AND gcf1.file_path != gcf2.file_path
                        WHERE gcf1.file_path = :filePath
                        GROUP BY gcf2.file_path
                        ORDER BY co_count DESC
                        LIMIT :limit""")
                .bind("filePath", filePath.replace('\\', '/'))
                .bind("limit", limit)
                .map((rs, ctx) -> new CoChangeRecord(rs.getString("file_path"),
                        rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                        rs.getInt("co_count"), 0.0))
                .list());
    }

    public static List<GitCommitRecord> findRecentCommits(Jdbi jdbi, int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM git_commits ORDER BY committed_at DESC LIMIT :limit")
                        .bind("limit", limit)
                        .map((rs, ctx) -> mapGitCommit(rs))
                        .list());
    }

    public static List<GitCommitFile> findCommitFiles(Jdbi jdbi, int commitId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM git_commit_files WHERE commit_id = :commitId")
                        .bind("commitId", commitId)
                        .map((rs, ctx) -> new GitCommitFile(
                                rs.getInt("commit_id"),
                                rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                                rs.getString("file_path"), rs.getString("change_type")))
                        .list());
    }

    public static Map<Integer, List<GitCommitFile>> findCommitFiles(
            Jdbi jdbi, Collection<Integer> commitIds, int limit) {
        if (commitIds == null || commitIds.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<Integer, List<GitCommitFile>> result = new LinkedHashMap<>();
            h.createQuery("SELECT gcf.* FROM git_commit_files gcf "
                            + "JOIN git_commits gc ON gc.id = gcf.commit_id "
                            + "WHERE gcf.commit_id IN (<ids>) "
                            + "ORDER BY gc.committed_at DESC, gcf.file_path LIMIT :limit")
                    .bindList("ids", new LinkedHashSet<>(commitIds))
                    .bind("limit", limit)
                    .map((rs, ctx) -> new GitCommitFile(
                            rs.getInt("commit_id"),
                            rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                            rs.getString("file_path"), rs.getString("change_type")))
                    .forEach(file -> result.computeIfAbsent(file.commitId(), ignored -> new ArrayList<>()).add(file));
            return result;
        });
    }

    public static Optional<GitFileStats> findFileStatsByClassId(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM git_file_stats WHERE class_id = :classId")
                        .bind("classId", classId)
                        .map((rs, ctx) -> mapGitFileStats(rs))
                        .findFirst());
    }

    public static Optional<GitFileStats> findFileStatsByPath(Jdbi jdbi, String filePath) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM git_file_stats WHERE file_path = :filePath")
                .bind("filePath", filePath.replace('\\', '/'))
                .map((rs, ctx) -> mapGitFileStats(rs))
                .findFirst());
    }

    public static int countClasses(Jdbi jdbi) {
        return countQuery(jdbi, "SELECT COUNT(*) FROM classes "
                + "WHERE lifecycle = 'current' AND origin != 'orphan_output'");
    }

    public static int sumSourceTokens(Jdbi jdbi) {
        return countQuery(jdbi, "SELECT COALESCE(SUM(source_tokens), 0) FROM classes "
                + "WHERE lifecycle = 'current' AND origin != 'orphan_output'");
    }

    public static int countBeans(Jdbi jdbi) {
        return countQuery(jdbi, "SELECT COUNT(*) FROM beans b JOIN classes c ON c.id = b.class_id "
                + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'");
    }

    public static int countCommits(Jdbi jdbi) {
        return countQuery(jdbi, "SELECT COUNT(*) FROM git_commits");
    }

    public static Map<String, Integer> countBeansByScope(Jdbi jdbi) {
        return groupCountQuery(jdbi, "SELECT b.scope, COUNT(*) as cnt FROM beans b "
                + "JOIN classes c ON c.id = b.class_id "
                + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output' "
                + "GROUP BY b.scope ORDER BY cnt DESC");
    }

    public static Map<String, Integer> countBeansByKind(Jdbi jdbi) {
        return groupCountQuery(jdbi, "SELECT b.kind, COUNT(*) as cnt FROM beans b "
                + "JOIN classes c ON c.id = b.class_id "
                + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output' "
                + "GROUP BY b.kind ORDER BY cnt DESC");
    }

    public static List<InjectionPointRecord> findUnsatisfiedInjectionPoints(Jdbi jdbi) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT ip.* FROM injection_points ip "
                                + "JOIN beans b ON b.id = ip.bean_id "
                                + "JOIN classes c ON c.id = b.class_id "
                                + "WHERE ip.resolved_bean_id IS NULL AND ip.is_ambiguous = 0 "
                                + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list());
    }

    public static List<InjectionPointRecord> findAmbiguousInjectionPoints(Jdbi jdbi) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT ip.* FROM injection_points ip "
                                + "JOIN beans b ON b.id = ip.bean_id "
                                + "JOIN classes c ON c.id = b.class_id "
                                + "WHERE ip.is_ambiguous = 1 "
                                + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list());
    }

    public static List<Map.Entry<Integer, Integer>> findMostDependedOn(Jdbi jdbi, int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT d.to_class_id, COUNT(DISTINCT d.from_class_id) as dep_count
                        FROM dependencies d
                        JOIN classes source ON source.id = d.from_class_id
                        JOIN classes target ON target.id = d.to_class_id
                        WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                          AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                        GROUP BY d.to_class_id ORDER BY dep_count DESC LIMIT :limit""")
                        .bind("limit", limit)
                        .map((rs, ctx) -> Map.entry(rs.getInt("to_class_id"), rs.getInt("dep_count")))
                        .list());
    }

    public static int countDependents(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(DISTINCT d.from_class_id) FROM dependencies d
                        JOIN classes source ON source.id = d.from_class_id
                        WHERE d.to_class_id = :classId
                          AND source.lifecycle = 'current' AND source.origin != 'orphan_output'""")
                        .bind("classId", classId)
                        .mapTo(Integer.class)
                        .one());
    }

    public static int countDependencies(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(DISTINCT d.to_class_id) FROM dependencies d
                        JOIN classes target ON target.id = d.to_class_id
                        WHERE d.from_class_id = :classId
                          AND target.lifecycle = 'current' AND target.origin != 'orphan_output'""")
                        .bind("classId", classId)
                        .mapTo(Integer.class)
                        .one());
    }

    public static int countDependencyEdges(Jdbi jdbi, int classId, boolean inbound) {
        String endpoint = inbound ? "to_class_id" : "from_class_id";
        String related = inbound ? "from_class_id" : "to_class_id";
        return jdbi.withHandle(h -> h.createQuery("SELECT COALESCE(SUM(d.occurrence_count), 0) "
                        + "FROM dependencies d JOIN classes c ON c.id = d." + related + " "
                        + "WHERE d." + endpoint + " = :classId "
                        + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                .bind("classId", classId)
                .mapTo(Integer.class)
                .one());
    }

    public static List<DependencyBreakdown> dependencyBreakdown(
            Jdbi jdbi, int classId, boolean inbound) {
        String endpoint = inbound ? "to_class_id" : "from_class_id";
        String related = inbound ? "from_class_id" : "to_class_id";
        return jdbi.withHandle(h -> h.createQuery("SELECT c.origin, "
                        + "COUNT(DISTINCT d." + related + ") AS class_count, "
                        + "SUM(d.occurrence_count) AS edge_count "
                        + "FROM dependencies d JOIN classes c ON c.id = d." + related + " "
                        + "WHERE d." + endpoint + " = :classId AND c.lifecycle = 'current' "
                        + "AND c.origin != 'orphan_output' "
                        + "GROUP BY c.origin ORDER BY c.origin")
                .bind("classId", classId)
                .map((rs, ctx) -> new DependencyBreakdown(rs.getString("origin"),
                        rs.getInt("class_count"), rs.getInt("edge_count")))
                .list());
    }

    private static int countQuery(Jdbi jdbi, String sql) {
        return jdbi.withHandle(h ->
                h.createQuery(sql).mapTo(Integer.class).one());
    }

    private static Map<String, Integer> groupCountQuery(Jdbi jdbi, String sql) {
        return jdbi.withHandle(h -> {
            Map<String, Integer> result = new LinkedHashMap<>();
            h.createQuery(sql)
                    .map((rs, ctx) -> Map.entry(rs.getString(1), rs.getInt(2)))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    public static List<ExternalDepRecord> findExternalDeps(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM class_external_deps WHERE class_id = :classId ORDER BY usage_kind, external_type")
                        .bind("classId", classId)
                        .map((rs, ctx) -> new ExternalDepRecord(
                                rs.getInt("class_id"), rs.getString("external_type"), rs.getString("usage_kind")))
                        .list());
    }

    public static List<Map.Entry<String, Integer>> findExternalDepsByLibrary(Jdbi jdbi, int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT substr(external_type, 1, instr(substr(external_type, instr(external_type, '.') + 1), '.') + instr(external_type, '.') - 1) as library,
                               COUNT(DISTINCT class_id) as class_count
                        FROM class_external_deps
                        GROUP BY library
                        ORDER BY class_count DESC
                        LIMIT :limit""")
                        .bind("limit", limit)
                        .map((rs, ctx) -> {
                            String lib = rs.getString("library");
                            return (lib != null && !lib.isEmpty()) ? Map.entry(lib, rs.getInt("class_count")) : null;
                        })
                        .list()
                        .stream().filter(Objects::nonNull).toList());
    }

    public static List<Map.Entry<Integer, String>> findClassesUsingType(Jdbi jdbi, String typePattern) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT DISTINCT ced.class_id, c.class_name FROM class_external_deps ced JOIN classes c ON ced.class_id = c.id WHERE ced.external_type LIKE :pattern ORDER BY c.class_name")
                        .bind("pattern", typePattern.replace("*", "%"))
                        .map((rs, ctx) -> Map.entry(rs.getInt("class_id"), rs.getString("class_name")))
                        .list());
    }

    public static boolean hasExternalDeps(Jdbi jdbi) {
        try {
            return countQuery(jdbi, "SELECT COUNT(*) FROM class_external_deps") > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<CdiProblem> findCdiProblems(Jdbi jdbi) {
        try {
            return jdbi.withHandle(h ->
                    h.createQuery("SELECT * FROM cdi_problems ORDER BY id")
                            .map((rs, ctx) -> new CdiProblem(
                                    rs.getInt("id"),
                                    rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                                    rs.getString("class_name"),
                                    rs.getString("problem_type"),
                                    rs.getString("message")))
                            .list());
        } catch (Exception e) {
            return List.of();
        }
    }

    public static boolean hasGitData(Jdbi jdbi) {
        try {
            return countQuery(jdbi, "SELECT COUNT(*) FROM git_commits") > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static GitFileStats mapGitFileStats(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GitFileStats(
                rs.getInt("id"), rs.getString("file_path"),
                rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                rs.getInt("commit_count"), rs.getString("last_modified"),
                rs.getString("last_author"), rs.getString("first_commit"),
                rs.getInt("distinct_authors"));
    }

    private static GitCommitRecord mapGitCommit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GitCommitRecord(
                rs.getInt("id"), rs.getString("hash"), rs.getString("short_hash"),
                rs.getString("author"), rs.getString("author_email"),
                rs.getString("committed_at"), rs.getString("message"));
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
