package org.treblereel.mcp.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.model.*;

public final class IndexWriter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] ALL_TABLES = {
            "git_commit_files", "git_commits", "git_file_stats",
            "class_external_deps", "cdi_problems",
            "dependencies", "injection_points", "beans", "classes", "files", "metadata"
    };

    private IndexWriter() {}

    public static void writeAll(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files) {
        writeAll(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, false);
    }

    public static void writeFresh(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files) {
        writeAll(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, true);
    }

    private static void writeAll(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            boolean freshDatabase) {
        jdbi.useTransaction(h -> {
            if (!freshDatabase) {
                for (String table : ALL_TABLES) {
                    h.execute("DELETE FROM " + table);
                }
                h.execute("DELETE FROM sqlite_sequence");
            }

            insertFiles(h, files);
            insertClasses(h, classes);
            insertBeans(h, beans);
            insertInjectionPoints(h, injectionPoints);
            insertDependencies(h, dependencies);
            insertMetadata(h, metadata);
            insertExternalDeps(h, externalDeps);
            insertProblems(h, problems);
            insertGitData(h, fileStats, commits, commitFiles);
            if (freshDatabase) QuillDatabase.createIndexes(h);
        });
    }

    public static void write(Jdbi jdbi, List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata) {
        jdbi.useTransaction(h -> {
            for (String table : new String[]{"dependencies", "injection_points", "beans", "classes", "files", "metadata"}) {
                h.execute("DELETE FROM " + table);
            }
            h.execute("DELETE FROM sqlite_sequence");

            insertClasses(h, classes);
            insertBeans(h, beans);
            insertInjectionPoints(h, injectionPoints);
            insertDependencies(h, dependencies);
            insertMetadata(h, metadata);
        });
    }

    public static void writeGitData(Jdbi jdbi, List<GitFileStats> fileStats,
            List<GitCommitRecord> commits, List<GitCommitFile> commitFiles) {
        jdbi.useTransaction(h -> {
            for (String table : new String[]{"git_commit_files", "git_commits", "git_file_stats"}) {
                h.execute("DELETE FROM " + table);
            }
            insertGitData(h, fileStats, commits, commitFiles);
        });
    }

    public static void writeExternalDeps(Jdbi jdbi, List<ExternalDepRecord> deps) {
        jdbi.useTransaction(h -> {
            h.execute("DELETE FROM class_external_deps");
            insertExternalDeps(h, deps);
        });
    }

    public static void writeProblems(Jdbi jdbi, List<CdiProblem> problems) {
        if (problems.isEmpty()) return;
        jdbi.useTransaction(h -> {
            h.execute("DELETE FROM cdi_problems");
            insertProblems(h, problems);
        });
    }

    private static void insertClasses(Handle h, List<ClassRecord> classes) {
        executeBatch(h,
                "INSERT INTO classes (class_name, kind, superclass, interfaces, source_file, source_line, is_bean, source_tokens, file_id, origin, lifecycle) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                classes, (statement, c) -> {
                    statement.setString(1, c.className());
                    statement.setString(2, c.kind());
                    statement.setString(3, c.superclass());
                    statement.setString(4, toJson(c.interfaces()));
                    statement.setString(5, c.sourceFile());
                    statement.setObject(6, c.sourceLine());
                    statement.setInt(7, c.isBean() ? 1 : 0);
                    statement.setInt(8, c.sourceTokens());
                    statement.setObject(9, c.fileId());
                    statement.setString(10, c.origin());
                    statement.setString(11, c.lifecycle());
                });
    }

    private static void insertFiles(Handle h, List<FileRecord> files) {
        if (files == null || files.isEmpty()) return;
        executeBatch(h,
                "INSERT INTO files (id, project_path, repository_path, kind, origin, lifecycle, worktree_status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                files, (statement, file) -> {
                    statement.setInt(1, file.id());
                    statement.setString(2, file.projectPath());
                    statement.setString(3, file.repositoryPath());
                    statement.setString(4, file.kind());
                    statement.setString(5, file.origin());
                    statement.setString(6, file.lifecycle());
                    statement.setString(7, file.worktreeStatus());
                });
    }

    private static void insertBeans(Handle h, List<BeanRecord> beans) {
        executeBatch(h,
                "INSERT INTO beans (class_id, kind, scope, qualifiers, stereotypes, is_alternative, priority, profiles, declaring_class_id, member_name, bean_types) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                beans, (statement, b) -> {
                    statement.setInt(1, b.classId());
                    statement.setString(2, b.kind());
                    statement.setString(3, b.scope());
                    statement.setString(4, toJson(b.qualifiers()));
                    statement.setString(5, toJson(b.stereotypes()));
                    statement.setInt(6, b.isAlternative() ? 1 : 0);
                    statement.setObject(7, b.priority());
                    statement.setString(8, toJson(b.profiles()));
                    statement.setObject(9, b.declaringClassId());
                    statement.setString(10, b.memberName());
                    statement.setString(11, toJson(b.beanTypes()));
                });
    }

    private static void insertInjectionPoints(Handle h, List<InjectionPointRecord> ips) {
        executeBatch(h,
                "INSERT INTO injection_points (bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, is_ambiguous) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ips, (statement, ip) -> {
                    statement.setInt(1, ip.beanId());
                    statement.setString(2, ip.kind());
                    statement.setString(3, ip.targetType());
                    statement.setString(4, toJson(ip.qualifiers()));
                    statement.setString(5, ip.fieldName());
                    statement.setObject(6, ip.resolvedBeanId());
                    statement.setInt(7, ip.isAmbiguous() ? 1 : 0);
                });
    }

    private static void insertDependencies(Handle h, List<DependencyRecord> deps) {
        executeBatch(h,
                "INSERT INTO dependencies (from_class_id, to_class_id, kind, injection_point_id, occurrence_count) VALUES (?, ?, ?, ?, ?)",
                deps, (statement, d) -> {
                    statement.setInt(1, d.fromClassId());
                    statement.setInt(2, d.toClassId());
                    statement.setString(3, d.kind());
                    statement.setObject(4, d.injectionPointId());
                    statement.setInt(5, d.occurrenceCount());
                });
    }

    private static void insertMetadata(Handle h, Map<String, String> metadata) {
        executeBatch(h, "INSERT INTO metadata (key, value) VALUES (?, ?)",
                metadata.entrySet(), (statement, entry) -> {
                    statement.setString(1, entry.getKey());
                    statement.setString(2, entry.getValue());
                });
    }

    private static void insertExternalDeps(Handle h, List<ExternalDepRecord> deps) {
        if (deps.isEmpty()) return;
        executeBatch(h,
                "INSERT INTO class_external_deps (class_id, external_type, usage_kind) VALUES (?, ?, ?)",
                deps, (statement, d) -> {
                    statement.setInt(1, d.classId());
                    statement.setString(2, d.externalType());
                    statement.setString(3, d.usageKind());
                });
    }

    private static void insertProblems(Handle h, List<CdiProblem> problems) {
        if (problems.isEmpty()) return;
        executeBatch(h,
                "INSERT INTO cdi_problems (class_id, class_name, problem_type, message) VALUES (?, ?, ?, ?)",
                problems, (statement, p) -> {
                    statement.setObject(1, p.classId());
                    statement.setString(2, p.className());
                    statement.setString(3, p.problemType());
                    statement.setString(4, p.message());
                });
    }

    private static void insertGitData(Handle h,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles) {
        if (fileStats.isEmpty() && commits.isEmpty()) return;

        executeBatch(h,
                "INSERT INTO git_file_stats (file_path, class_id, commit_count, last_modified, last_author, first_commit, distinct_authors) VALUES (?, ?, ?, ?, ?, ?, ?)",
                fileStats, (statement, s) -> {
                    statement.setString(1, s.filePath());
                    statement.setObject(2, s.classId());
                    statement.setInt(3, s.commitCount());
                    statement.setString(4, s.lastModified());
                    statement.setString(5, s.lastAuthor());
                    statement.setString(6, s.firstCommit());
                    statement.setInt(7, s.distinctAuthors());
                });

        executeBatch(h,
                "INSERT INTO git_commits (hash, short_hash, author, author_email, committed_at, message) VALUES (?, ?, ?, ?, ?, ?)",
                commits, (statement, c) -> {
                    statement.setString(1, c.hash());
                    statement.setString(2, c.shortHash());
                    statement.setString(3, c.author());
                    statement.setString(4, c.authorEmail());
                    statement.setString(5, c.committedAt());
                    statement.setString(6, c.message());
                });

        executeBatch(h,
                "INSERT INTO git_commit_files (commit_id, class_id, file_path, change_type) VALUES (?, ?, ?, ?)",
                commitFiles, (statement, f) -> {
                    statement.setInt(1, f.commitId());
                    statement.setObject(2, f.classId());
                    statement.setString(3, f.filePath());
                    statement.setString(4, f.changeType());
                });
    }

    private static <T> void executeBatch(Handle h, String sql, Iterable<T> rows,
            BatchBinder<T> binder) {
        try (PreparedStatement statement = h.getConnection().prepareStatement(sql)) {
            boolean hasRows = false;
            for (T row : rows) {
                binder.bind(statement, row);
                statement.addBatch();
                hasRows = true;
            }
            if (hasRows) statement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write index batch", e);
        }
    }

    @FunctionalInterface
    private interface BatchBinder<T> {
        void bind(PreparedStatement statement, T row) throws SQLException;
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
