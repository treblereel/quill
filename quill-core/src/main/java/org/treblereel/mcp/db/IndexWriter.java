package org.treblereel.mcp.db;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        jdbi.useTransaction(h -> {
            for (String table : ALL_TABLES) {
                h.execute("DELETE FROM " + table);
            }
            h.execute("DELETE FROM sqlite_sequence");

            insertFiles(h, files);
            insertClasses(h, classes);
            insertBeans(h, beans);
            insertInjectionPoints(h, injectionPoints);
            insertDependencies(h, dependencies);
            insertMetadata(h, metadata);
            insertExternalDeps(h, externalDeps);
            insertProblems(h, problems);
            insertGitData(h, fileStats, commits, commitFiles);
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

    private static void insertClasses(org.jdbi.v3.core.Handle h, List<ClassRecord> classes) {
        var batch = h.prepareBatch(
                "INSERT INTO classes (class_name, kind, superclass, interfaces, source_file, source_line, is_bean, source_tokens, file_id, origin, lifecycle) VALUES (:className, :kind, :superclass, :interfaces, :sourceFile, :sourceLine, :isBean, :sourceTokens, :fileId, :origin, :lifecycle)");
        for (ClassRecord c : classes) {
            batch
                    .bind("className", c.className())
                    .bind("kind", c.kind())
                    .bind("superclass", c.superclass())
                    .bind("interfaces", toJson(c.interfaces()))
                    .bind("sourceFile", c.sourceFile())
                    .bind("sourceLine", c.sourceLine())
                    .bind("isBean", c.isBean() ? 1 : 0)
                    .bind("sourceTokens", c.sourceTokens())
                    .bind("fileId", c.fileId())
                    .bind("origin", c.origin())
                    .bind("lifecycle", c.lifecycle())
                    .add();
        }
        batch.execute();
    }

    private static void insertFiles(org.jdbi.v3.core.Handle h, List<FileRecord> files) {
        if (files == null || files.isEmpty()) return;
        var batch = h.prepareBatch(
                "INSERT INTO files (id, project_path, repository_path, kind, origin, lifecycle, worktree_status) VALUES (:id, :projectPath, :repositoryPath, :kind, :origin, :lifecycle, :worktreeStatus)");
        for (FileRecord file : files) {
            batch.bind("id", file.id())
                    .bind("projectPath", file.projectPath())
                    .bind("repositoryPath", file.repositoryPath())
                    .bind("kind", file.kind())
                    .bind("origin", file.origin())
                    .bind("lifecycle", file.lifecycle())
                    .bind("worktreeStatus", file.worktreeStatus())
                    .add();
        }
        batch.execute();
    }

    private static void insertBeans(org.jdbi.v3.core.Handle h, List<BeanRecord> beans) {
        var batch = h.prepareBatch(
                "INSERT INTO beans (class_id, kind, scope, qualifiers, stereotypes, is_alternative, priority, profiles, declaring_class_id, member_name, bean_types) VALUES (:classId, :kind, :scope, :qualifiers, :stereotypes, :isAlternative, :priority, :profiles, :declaringClassId, :memberName, :beanTypes)");
        for (BeanRecord b : beans) {
            batch
                    .bind("classId", b.classId())
                    .bind("kind", b.kind())
                    .bind("scope", b.scope())
                    .bind("qualifiers", toJson(b.qualifiers()))
                    .bind("stereotypes", toJson(b.stereotypes()))
                    .bind("isAlternative", b.isAlternative() ? 1 : 0)
                    .bind("priority", b.priority())
                    .bind("profiles", toJson(b.profiles()))
                    .bind("declaringClassId", b.declaringClassId())
                    .bind("memberName", b.memberName())
                    .bind("beanTypes", toJson(b.beanTypes()))
                    .add();
        }
        batch.execute();
    }

    private static void insertInjectionPoints(org.jdbi.v3.core.Handle h, List<InjectionPointRecord> ips) {
        var batch = h.prepareBatch(
                "INSERT INTO injection_points (bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, is_ambiguous) VALUES (:beanId, :kind, :targetType, :qualifiers, :fieldName, :resolvedBeanId, :isAmbiguous)");
        for (InjectionPointRecord ip : ips) {
            batch
                    .bind("beanId", ip.beanId())
                    .bind("kind", ip.kind())
                    .bind("targetType", ip.targetType())
                    .bind("qualifiers", toJson(ip.qualifiers()))
                    .bind("fieldName", ip.fieldName())
                    .bind("resolvedBeanId", ip.resolvedBeanId())
                    .bind("isAmbiguous", ip.isAmbiguous() ? 1 : 0)
                    .add();
        }
        batch.execute();
    }

    private static void insertDependencies(org.jdbi.v3.core.Handle h, List<DependencyRecord> deps) {
        var batch = h.prepareBatch(
                "INSERT INTO dependencies (from_class_id, to_class_id, kind, injection_point_id, occurrence_count) VALUES (:fromClassId, :toClassId, :kind, :injectionPointId, :occurrenceCount)");
        for (DependencyRecord d : deps) {
            batch
                    .bind("fromClassId", d.fromClassId())
                    .bind("toClassId", d.toClassId())
                    .bind("kind", d.kind())
                    .bind("injectionPointId", d.injectionPointId())
                    .bind("occurrenceCount", d.occurrenceCount())
                    .add();
        }
        batch.execute();
    }

    private static void insertMetadata(org.jdbi.v3.core.Handle h, Map<String, String> metadata) {
        var batch = h.prepareBatch("INSERT INTO metadata (key, value) VALUES (:key, :value)");
        for (var entry : metadata.entrySet()) {
            batch.bind("key", entry.getKey()).bind("value", entry.getValue()).add();
        }
        batch.execute();
    }

    private static void insertExternalDeps(org.jdbi.v3.core.Handle h, List<ExternalDepRecord> deps) {
        if (deps.isEmpty()) return;
        var batch = h.prepareBatch(
                "INSERT INTO class_external_deps (class_id, external_type, usage_kind) VALUES (:classId, :externalType, :usageKind)");
        for (ExternalDepRecord d : deps) {
            batch
                    .bind("classId", d.classId())
                    .bind("externalType", d.externalType())
                    .bind("usageKind", d.usageKind())
                    .add();
        }
        batch.execute();
    }

    private static void insertProblems(org.jdbi.v3.core.Handle h, List<CdiProblem> problems) {
        if (problems.isEmpty()) return;
        var batch = h.prepareBatch(
                "INSERT INTO cdi_problems (class_id, class_name, problem_type, message) VALUES (:classId, :className, :problemType, :message)");
        for (CdiProblem p : problems) {
            batch
                    .bind("classId", p.classId())
                    .bind("className", p.className())
                    .bind("problemType", p.problemType())
                    .bind("message", p.message())
                    .add();
        }
        batch.execute();
    }

    private static void insertGitData(org.jdbi.v3.core.Handle h,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles) {
        if (fileStats.isEmpty() && commits.isEmpty()) return;

        var statsBatch = h.prepareBatch(
                "INSERT INTO git_file_stats (file_path, class_id, commit_count, last_modified, last_author, first_commit, distinct_authors) VALUES (:filePath, :classId, :commitCount, :lastModified, :lastAuthor, :firstCommit, :distinctAuthors)");
        for (GitFileStats s : fileStats) {
            statsBatch
                    .bind("filePath", s.filePath())
                    .bind("classId", s.classId())
                    .bind("commitCount", s.commitCount())
                    .bind("lastModified", s.lastModified())
                    .bind("lastAuthor", s.lastAuthor())
                    .bind("firstCommit", s.firstCommit())
                    .bind("distinctAuthors", s.distinctAuthors())
                    .add();
        }
        statsBatch.execute();

        var commitBatch = h.prepareBatch(
                "INSERT INTO git_commits (hash, short_hash, author, author_email, committed_at, message) VALUES (:hash, :shortHash, :author, :authorEmail, :committedAt, :message)");
        for (GitCommitRecord c : commits) {
            commitBatch
                    .bind("hash", c.hash())
                    .bind("shortHash", c.shortHash())
                    .bind("author", c.author())
                    .bind("authorEmail", c.authorEmail())
                    .bind("committedAt", c.committedAt())
                    .bind("message", c.message())
                    .add();
        }
        commitBatch.execute();

        var fileBatch = h.prepareBatch(
                "INSERT INTO git_commit_files (commit_id, class_id, file_path, change_type) VALUES (:commitId, :classId, :filePath, :changeType)");
        for (GitCommitFile f : commitFiles) {
            fileBatch
                    .bind("commitId", f.commitId())
                    .bind("classId", f.classId())
                    .bind("filePath", f.filePath())
                    .bind("changeType", f.changeType())
                    .add();
        }
        fileBatch.execute();
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
