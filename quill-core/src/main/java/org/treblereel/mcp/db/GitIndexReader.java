package org.treblereel.mcp.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.model.CoChangeRecord;
import org.treblereel.mcp.model.GitCommitFile;
import org.treblereel.mcp.model.GitCommitRecord;
import org.treblereel.mcp.model.GitFileStats;

/** Git-history queries kept separate from structural and DI index reads. */
final class GitIndexReader {

    private GitIndexReader() {}

    static List<GitFileStats> findHotspots(Jdbi jdbi, int limit, String since) {
        if (since == null) {
            return jdbi.withHandle(handle -> handle.createQuery(
                            "SELECT * FROM git_file_stats WHERE commit_count > 0 "
                                    + "ORDER BY commit_count DESC LIMIT :limit")
                    .bind("limit", limit)
                    .map((rs, ctx) -> mapGitFileStats(rs))
                    .list());
        }
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT gcf.file_path, gfs.class_id,
                               COUNT(DISTINCT gc.id) as commit_count,
                               MAX(gc.committed_at) as last_modified,
                               MAX(CASE WHEN gc.committed_at = (
                                   SELECT MAX(gc2.committed_at) FROM git_commits gc2
                                   JOIN git_commit_files gcf2 ON gc2.id = gcf2.commit_id
                                   WHERE gcf2.file_path = gcf.file_path
                                     AND gc2.committed_at >= :since
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
                        0, rs.getString("file_path"), nullableInteger(rs, "class_id"),
                        rs.getInt("commit_count"), rs.getString("last_modified"),
                        rs.getString("last_author"), rs.getString("first_commit"),
                        rs.getInt("distinct_authors")))
                .list());
    }

    static List<GitCommitRecord> findFileHistory(
            Jdbi jdbi, int classId, int limit, int offset) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT gc.* FROM git_commits gc
                        JOIN git_commit_files gcf ON gc.id = gcf.commit_id
                        WHERE gcf.class_id = :classId
                        ORDER BY gc.committed_at DESC
                        LIMIT :limit OFFSET :offset""")
                .bind("classId", classId)
                .bind("limit", limit)
                .bind("offset", offset)
                .map((rs, ctx) -> mapGitCommit(rs))
                .list());
    }

    static List<GitCommitRecord> findFileHistoryByPath(
            Jdbi jdbi, String filePath, int limit, int offset) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT DISTINCT gc.* FROM git_commits gc
                        JOIN git_commit_files gcf ON gc.id = gcf.commit_id
                        WHERE gcf.file_path = :filePath
                        ORDER BY gc.committed_at DESC
                        LIMIT :limit OFFSET :offset""")
                .bind("filePath", normalize(filePath))
                .bind("limit", limit)
                .bind("offset", offset)
                .map((rs, ctx) -> mapGitCommit(rs))
                .list());
    }

    static List<CoChangeRecord> findCoChanges(Jdbi jdbi, int classId, int limit) {
        return jdbi.withHandle(handle -> handle.createQuery("""
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
                .map((rs, ctx) -> new CoChangeRecord(rs.getString("file_path"),
                        nullableInteger(rs, "class_id"), rs.getInt("co_count"), 0.0))
                .list());
    }

    static List<CoChangeRecord> findCoChangesByPath(
            Jdbi jdbi, String filePath, int limit) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT gcf2.file_path, gcf2.class_id, COUNT(*) as co_count
                        FROM git_commit_files gcf1
                        JOIN git_commit_files gcf2 ON gcf1.commit_id = gcf2.commit_id
                            AND gcf1.file_path != gcf2.file_path
                        WHERE gcf1.file_path = :filePath
                        GROUP BY gcf2.file_path
                        ORDER BY co_count DESC
                        LIMIT :limit""")
                .bind("filePath", normalize(filePath))
                .bind("limit", limit)
                .map((rs, ctx) -> new CoChangeRecord(rs.getString("file_path"),
                        nullableInteger(rs, "class_id"), rs.getInt("co_count"), 0.0))
                .list());
    }

    static List<GitCommitRecord> findRecentCommits(Jdbi jdbi, int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT * FROM git_commits ORDER BY committed_at DESC LIMIT :limit")
                .bind("limit", limit)
                .map((rs, ctx) -> mapGitCommit(rs))
                .list());
    }

    static List<GitCommitFile> findCommitFiles(Jdbi jdbi, int commitId) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT * FROM git_commit_files WHERE commit_id = :commitId")
                .bind("commitId", commitId)
                .map((rs, ctx) -> mapCommitFile(rs))
                .list());
    }

    static Map<Integer, List<GitCommitFile>> findCommitFiles(
            Jdbi jdbi, Collection<Integer> commitIds, int limit) {
        if (commitIds == null || commitIds.isEmpty()) return Map.of();
        return jdbi.withHandle(handle -> {
            Map<Integer, List<GitCommitFile>> result = new LinkedHashMap<>();
            handle.createQuery("SELECT gcf.* FROM git_commit_files gcf "
                            + "JOIN git_commits gc ON gc.id = gcf.commit_id "
                            + "WHERE gcf.commit_id IN (<ids>) "
                            + "ORDER BY gc.committed_at DESC, gcf.file_path LIMIT :limit")
                    .bindList("ids", new LinkedHashSet<>(commitIds))
                    .bind("limit", limit)
                    .map((rs, ctx) -> mapCommitFile(rs))
                    .forEach(file -> result.computeIfAbsent(
                            file.commitId(), ignored -> new ArrayList<>()).add(file));
            return result;
        });
    }

    static Optional<GitFileStats> findFileStatsByClassId(Jdbi jdbi, int classId) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT * FROM git_file_stats WHERE class_id = :classId")
                .bind("classId", classId)
                .map((rs, ctx) -> mapGitFileStats(rs))
                .findFirst());
    }

    static Optional<GitFileStats> findFileStatsByPath(Jdbi jdbi, String filePath) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT * FROM git_file_stats WHERE file_path = :filePath")
                .bind("filePath", normalize(filePath))
                .map((rs, ctx) -> mapGitFileStats(rs))
                .findFirst());
    }

    static boolean hasGitData(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT EXISTS(SELECT 1 FROM git_commits LIMIT 1)")
                .mapTo(Boolean.class).one());
    }

    private static GitCommitFile mapCommitFile(ResultSet rs) throws SQLException {
        return new GitCommitFile(rs.getInt("commit_id"), nullableInteger(rs, "class_id"),
                rs.getString("file_path"), rs.getString("change_type"));
    }

    private static GitFileStats mapGitFileStats(ResultSet rs) throws SQLException {
        return new GitFileStats(rs.getInt("id"), rs.getString("file_path"),
                nullableInteger(rs, "class_id"), rs.getInt("commit_count"),
                rs.getString("last_modified"), rs.getString("last_author"),
                rs.getString("first_commit"), rs.getInt("distinct_authors"));
    }

    private static GitCommitRecord mapGitCommit(ResultSet rs) throws SQLException {
        return new GitCommitRecord(rs.getInt("id"), rs.getString("hash"),
                rs.getString("short_hash"), rs.getString("author"),
                rs.getString("author_email"), rs.getString("committed_at"),
                rs.getString("message"));
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }
}
