package org.treblereel.mcp.db;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import org.jdbi.v3.core.Handle;
import org.treblereel.mcp.model.CoChangeRecord;
import org.treblereel.mcp.model.GitCommitFile;
import org.treblereel.mcp.model.GitCommitRecord;
import org.treblereel.mcp.model.GitFileStats;

/** Read-only Git history repository. The caller owns the handle and transaction. */
public final class GitRepository {

    private final Handle handle;

    public GitRepository(Handle handle) {
        this.handle = Objects.requireNonNull(handle);
    }

    public List<GitFileStats> findHotspots(int limit, String since) {
        if (since == null) {
            return handle.createQuery(
                            "SELECT * FROM git_file_stats WHERE commit_count > 0 "
                                    + "ORDER BY commit_count DESC, file_path LIMIT :limit")
                    .bind("limit", limit)
                    .map((rs, ctx) -> mapGitFileStats(rs))
                    .list();
        }
        return handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(GitRepository.class, "findHotspots"))
                .bind("since", since)
                .bind("limit", limit)
                .map((rs, ctx) -> mapGitFileStats(rs))
                .list();
    }

    public List<GitCommitRecord> findFileHistory(
            int classId, int limit, int offset) {
        return handle.createQuery("""
                        SELECT gc.* FROM git_commits gc
                        JOIN git_commit_files gcf ON gc.id = gcf.commit_id
                        WHERE gcf.class_id = :classId
                        ORDER BY gc.committed_at DESC
                        LIMIT :limit OFFSET :offset""")
                .bind("classId", classId)
                .bind("limit", limit)
                .bind("offset", offset)
                .map((rs, ctx) -> mapGitCommit(rs))
                .list();
    }

    public List<GitCommitRecord> findFileHistoryByPath(
            String filePath, int limit, int offset) {
        return handle.createQuery("""
                        SELECT DISTINCT gc.* FROM git_commits gc
                        JOIN git_commit_files gcf ON gc.id = gcf.commit_id
                        WHERE gcf.file_path = :filePath
                        ORDER BY gc.committed_at DESC
                        LIMIT :limit OFFSET :offset""")
                .bind("filePath", normalize(filePath))
                .bind("limit", limit)
                .bind("offset", offset)
                .map((rs, ctx) -> mapGitCommit(rs))
                .list();
    }

    public List<CoChangeRecord> findCoChanges(int classId, int limit) {
        return handle.createQuery("""
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
                .list();
    }

    public List<CoChangeRecord> findCoChangesByPath(
            String filePath, int limit) {
        return handle.createQuery("""
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
                .list();
    }

    public List<GitCommitRecord> findRecentCommits(int limit) {
        return handle.createQuery(
                        "SELECT * FROM git_commits ORDER BY committed_at DESC LIMIT :limit")
                .bind("limit", limit)
                .map((rs, ctx) -> mapGitCommit(rs))
                .list();
    }

    public List<GitCommitFile> findCommitFiles(int commitId) {
        return handle.createQuery(
                        "SELECT * FROM git_commit_files WHERE commit_id = :commitId")
                .bind("commitId", commitId)
                .map((rs, ctx) -> mapCommitFile(rs))
                .list();
    }

    public Map<Integer, List<GitCommitFile>> findCommitFiles(
            Collection<Integer> commitIds, int limit) {
        return findCommitFiles(commitIds, limit, 0);
    }

    public Map<Integer, List<GitCommitFile>> findCommitFiles(
            Collection<Integer> commitIds, int limit, int offset) {
        if (commitIds == null || commitIds.isEmpty()) return Map.of();
        
            Map<Integer, List<GitCommitFile>> result = new LinkedHashMap<>();
            handle.createQuery("SELECT gcf.* FROM git_commit_files gcf "
                            + "JOIN git_commits gc ON gc.id = gcf.commit_id "
                            + "WHERE gcf.commit_id IN (<ids>) "
                            + "ORDER BY gc.committed_at DESC, gcf.file_path "
                            + "LIMIT :limit OFFSET :offset")
                    .bindList("ids", new LinkedHashSet<>(commitIds))
                    .bind("limit", limit)
                    .bind("offset", offset)
                    .map((rs, ctx) -> mapCommitFile(rs))
                    .forEach(file -> result.computeIfAbsent(
                            file.commitId(), ignored -> new ArrayList<>()).add(file));
            return result;
        
    }

    public int countCommitFiles(Collection<Integer> commitIds) {
        if (commitIds == null || commitIds.isEmpty()) return 0;
        return handle.createQuery(
                        "SELECT count(*) FROM git_commit_files WHERE commit_id IN (<ids>)")
                .bindList("ids", new LinkedHashSet<>(commitIds))
                .mapTo(Integer.class).one();
    }

    public Optional<GitFileStats> findFileStatsByClassId(int classId) {
        return handle.createQuery(
                        "SELECT * FROM git_file_stats WHERE class_id = :classId")
                .bind("classId", classId)
                .map((rs, ctx) -> mapGitFileStats(rs))
                .findFirst();
    }

    public Optional<GitFileStats> findFileStatsByPath(String filePath) {
        return handle.createQuery(
                        "SELECT * FROM git_file_stats WHERE file_path = :filePath")
                .bind("filePath", normalize(filePath))
                .map((rs, ctx) -> mapGitFileStats(rs))
                .findFirst();
    }

    public int countCommits() {
        return handle.createQuery("SELECT COUNT(*) FROM git_commits")
                .mapTo(Integer.class).one();
    }

    public boolean hasGitData() {
            return handle.createQuery(
                            "SELECT EXISTS(SELECT 1 FROM git_commits LIMIT 1)")
                    .mapTo(Boolean.class).one();

    }

    public List<GitFileStats> findHistoricalPathCandidates(
            String target, int limit) {
        String normalized = target.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!basename.endsWith(".java") && !basename.endsWith(".kt")) {
            int dot = basename.lastIndexOf('.');
            if (dot >= 0) basename = basename.substring(dot + 1);
            basename += ".java";
        }
        String suffixPattern = "%/" + basename;
        return handle.createQuery("""
                        SELECT * FROM git_file_stats
                        WHERE file_path = :path OR file_path LIKE :suffix
                        ORDER BY CASE WHEN file_path = :path THEN 0 ELSE 1 END,
                                 commit_count DESC, file_path
                        LIMIT :limit""")
                .bind("path", normalized)
                .bind("suffix", suffixPattern)
                .bind("limit", limit)
                .map((rs, ctx) -> mapGitFileStats(rs))
                .list();
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
