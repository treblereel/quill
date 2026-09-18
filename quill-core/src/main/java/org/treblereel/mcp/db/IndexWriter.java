package org.treblereel.mcp.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.ConfigurationScanner;
import org.treblereel.mcp.model.*;

public final class IndexWriter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConfigurationScanner.Result EMPTY_CONFIGURATION =
            new ConfigurationScanner.Result(List.of(), List.of());
    private static final String[] ALL_TABLES = {
            "git_commit_files", "git_commits", "git_file_stats",
            "class_external_deps", "cdi_problems",
            "configuration_usages", "configuration_definitions",
            "dependencies", "field_accesses", "method_calls", "injection_points", "beans", "class_members",
            "class_annotations",
            "class_occurrences", "module_classpath", "classes",
            "files", "metadata"
    };

    private IndexWriter() {}

    public record WriteTimings(
            long insertsMillis, long indexesMillis, long transactionOverheadMillis) {}

    public record IncrementalWriteTimings(
            long deltaMillis, long transactionOverheadMillis,
            long rowsInserted, long rowsDeleted, long rowsUnchanged) {}

    private record TableSpec(String name, List<String> columns) {
        TableSpec(String name, String... columns) {
            this(name, List.of(columns));
        }

        String desiredName() {
            return "desired_" + name;
        }
    }

    private static final TableSpec FILES = new TableSpec("files",
            "id", "project_path", "repository_path", "kind", "origin", "lifecycle",
            "worktree_status", "module", "source_set");
    private static final TableSpec CLASSES = new TableSpec("classes",
            "id", "class_name", "kind", "superclass", "interfaces", "source_file",
            "source_line", "is_bean", "source_tokens", "file_id", "origin", "lifecycle",
            "module", "source_set");
    private static final TableSpec CLASS_OCCURRENCES = new TableSpec("class_occurrences",
            "id", "class_id", "class_name", "module", "source_set", "output_directory",
            "class_file", "source_file", "origin");
    private static final TableSpec CLASS_ANNOTATIONS = new TableSpec("class_annotations",
            "class_id", "annotation_name", "direct", "via_annotation");
    private static final TableSpec CLASS_MEMBERS = new TableSpec("class_members",
            "class_id", "kind", "name", "signature", "descriptor", "type_name",
            "parameter_types", "modifiers", "annotations", "annotation_details");
    private static final TableSpec METHOD_CALLS = new TableSpec("method_calls",
            "from_class_id", "from_method", "from_descriptor", "to_class_id",
            "to_method", "to_descriptor", "invocation_kind", "occurrence_count",
            "evidence_lines");
    private static final TableSpec FIELD_ACCESSES = new TableSpec("field_accesses",
            "from_class_id", "from_method", "from_descriptor", "to_class_id",
            "field_name", "field_descriptor", "access_kind", "occurrence_count",
            "evidence_lines");
    private static final TableSpec BEANS = new TableSpec("beans",
            "id", "class_id", "kind", "scope", "qualifiers", "stereotypes",
            "is_alternative", "is_default", "priority", "profiles", "declaring_class_id",
            "member_name", "bean_types");
    private static final TableSpec INJECTION_POINTS = new TableSpec("injection_points",
            "id", "bean_id", "kind", "target_type", "qualifiers", "field_name",
            "resolved_bean_id", "resolution_status", "resolution_strategy",
            "resolution_reason", "resolution_confidence", "limitations",
            "resolution_candidates", "applied_rules", "unsupported_rules");
    private static final TableSpec DEPENDENCIES = new TableSpec("dependencies",
            "from_class_id", "to_class_id", "kind", "injection_point_id", "occurrence_count",
            "evidence_lines");
    private static final TableSpec METADATA = new TableSpec("metadata", "key", "value");
    private static final TableSpec EXTERNAL_DEPS = new TableSpec("class_external_deps",
            "class_id", "external_type", "usage_kind");
    private static final TableSpec PROBLEMS = new TableSpec("cdi_problems",
            "class_id", "class_name", "problem_type", "message");
    private static final TableSpec CONFIGURATION_DEFINITIONS = new TableSpec(
            "configuration_definitions", "key", "kind", "file", "line", "module",
            "source_set");
    private static final TableSpec CONFIGURATION_USAGES = new TableSpec(
            "configuration_usages", "key", "kind", "class_id", "class_name", "member",
            "parameter_index", "annotation", "source", "module", "source_set");
    private static final TableSpec FILE_STATS = new TableSpec("git_file_stats",
            "file_path", "class_id", "commit_count", "last_modified", "last_author",
            "first_commit", "distinct_authors");
    private static final TableSpec COMMITS = new TableSpec("git_commits",
            "id", "hash", "short_hash", "author", "author_email", "committed_at", "message");
    private static final TableSpec COMMIT_FILES = new TableSpec("git_commit_files",
            "commit_id", "class_id", "file_path", "change_type");
    private static final List<TableSpec> INSERT_ORDER = List.of(
            FILES, CLASSES, CLASS_OCCURRENCES, CLASS_ANNOTATIONS, CLASS_MEMBERS,
            METHOD_CALLS, FIELD_ACCESSES, CONFIGURATION_DEFINITIONS, CONFIGURATION_USAGES, BEANS,
            INJECTION_POINTS, DEPENDENCIES, METADATA,
            EXTERNAL_DEPS, PROBLEMS, FILE_STATS, COMMITS, COMMIT_FILES);
    private static final List<TableSpec> DELETE_ORDER = List.of(
            COMMIT_FILES, FILE_STATS, EXTERNAL_DEPS, PROBLEMS, DEPENDENCIES,
            CONFIGURATION_USAGES, CONFIGURATION_DEFINITIONS,
            FIELD_ACCESSES, METHOD_CALLS,
            INJECTION_POINTS, BEANS, COMMITS, CLASS_MEMBERS, CLASS_ANNOTATIONS, CLASS_OCCURRENCES,
            CLASSES, FILES, METADATA);

    public static void writeAll(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files) {
        writeAll(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, List.of(),
                List.of(), List.of(), List.of(), false);
    }

    public static WriteTimings writeFresh(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files) {
        return writeAll(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, List.of(),
                List.of(), List.of(), List.of(), true);
    }

    public static WriteTimings writeFresh(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences) {
        return writeFresh(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                List.of());
    }

    public static WriteTimings writeFresh(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations) {
        return writeFresh(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                annotations, List.of());
    }

    public static WriteTimings writeFresh(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members) {
        return writeFresh(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                annotations, members, List.of());
    }

    public static WriteTimings writeFresh(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members,
            List<MethodCallRecord> methodCalls) {
        return writeFresh(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                annotations, members, methodCalls, List.of());
    }

    public static WriteTimings writeFresh(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members,
            List<MethodCallRecord> methodCalls,
            List<FieldAccessRecord> fieldAccesses) {
        return writeFreshWithConfiguration(jdbi, classes, beans, injectionPoints, dependencies,
                metadata, externalDeps, problems, fileStats, commits, commitFiles, files,
                occurrences, annotations, members, methodCalls, fieldAccesses,
                EMPTY_CONFIGURATION);
    }

    public static WriteTimings writeFreshWithConfiguration(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members,
            List<MethodCallRecord> methodCalls,
            List<FieldAccessRecord> fieldAccesses,
            ConfigurationScanner.Result configuration) {
        return writeAll(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                annotations, members, methodCalls, fieldAccesses, configuration, true);
    }

    /**
     * Applies a complete logical snapshot to a cloned index while mutating only rows whose
     * persisted value changed. Secondary indexes are retained and therefore updated only for the
     * actual delta. The caller must supply a disposable staging database, never the active index.
     */
    public static IncrementalWriteTimings writeIncremental(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files) {
        return writeIncremental(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, List.of());
    }

    public static IncrementalWriteTimings writeIncremental(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences) {
        return writeIncremental(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                List.of());
    }

    public static IncrementalWriteTimings writeIncremental(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations) {
        return writeIncremental(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                annotations, List.of());
    }

    public static IncrementalWriteTimings writeIncremental(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members) {
        return writeIncremental(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                annotations, members, List.of());
    }

    public static IncrementalWriteTimings writeIncremental(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members,
            List<MethodCallRecord> methodCalls) {
        return writeIncremental(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                annotations, members, methodCalls, List.of());
    }

    public static IncrementalWriteTimings writeIncremental(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members,
            List<MethodCallRecord> methodCalls,
            List<FieldAccessRecord> fieldAccesses) {
        return writeIncrementalWithConfiguration(jdbi, classes, beans, injectionPoints,
                dependencies, metadata, externalDeps, problems, fileStats, commits, commitFiles,
                files, occurrences, annotations, members, methodCalls, fieldAccesses,
                EMPTY_CONFIGURATION);
    }

    public static IncrementalWriteTimings writeIncrementalWithConfiguration(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members,
            List<MethodCallRecord> methodCalls,
            List<FieldAccessRecord> fieldAccesses,
            ConfigurationScanner.Result configuration) {
        long startedAt = System.nanoTime();
        long[] deltaNanos = new long[1];
        long[] counts = new long[3];
        jdbi.useHandle(h -> {
            h.execute("PRAGMA foreign_keys=OFF");
            try {
                h.useTransaction(tx -> {
                    createDesiredTables(tx);
                    populateDesiredTables(tx, classes, beans, injectionPoints, dependencies,
                            metadata, externalDeps, problems, fileStats, commits, commitFiles,
                            files, occurrences, annotations, members, methodCalls, fieldAccesses,
                            configuration);
                    createDesiredIndexes(tx);
                    long deltaStartedAt = System.nanoTime();
                    for (TableSpec table : DELETE_ORDER) {
                        counts[1] += deleteMissingRows(tx, table);
                    }
                    for (TableSpec table : INSERT_ORDER) {
                        long desiredRows = countRows(tx, table.desiredName());
                        long inserted = insertMissingRows(tx, table);
                        counts[0] += inserted;
                        counts[2] += desiredRows - inserted;
                    }
                    deltaNanos[0] = System.nanoTime() - deltaStartedAt;
                    if (!tx.createQuery("PRAGMA foreign_key_check")
                            .mapToMap().list().isEmpty()) {
                        throw new IllegalStateException(
                                "Incremental index update produced invalid foreign keys");
                    }
                });
            } finally {
                h.execute("PRAGMA foreign_keys=ON");
            }
        });
        long totalNanos = System.nanoTime() - startedAt;
        return new IncrementalWriteTimings(toMillis(deltaNanos[0]),
                toMillis(Math.max(0, totalNanos - deltaNanos[0])),
                counts[0], counts[1], counts[2]);
    }

    private static void createDesiredTables(Handle h) {
        for (TableSpec table : INSERT_ORDER) {
            String columns = String.join(", ", table.columns());
            h.execute("CREATE TEMP TABLE " + table.desiredName()
                    + " AS SELECT " + columns + " FROM main." + table.name() + " WHERE 0");
        }
    }

    private static void populateDesiredTables(Handle h,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members,
            List<MethodCallRecord> methodCalls,
            List<FieldAccessRecord> fieldAccesses,
            ConfigurationScanner.Result configuration) {
        insertDesiredFiles(h, files);
        insertDesiredClasses(h, classes);
        insertDesiredClassOccurrences(h, occurrences);
        insertDesiredClassAnnotations(h, annotations);
        insertDesiredClassMembers(h, members);
        insertDesiredMethodCalls(h, methodCalls);
        insertDesiredFieldAccesses(h, fieldAccesses);
        insertDesiredConfigurationDefinitions(h, configuration.definitions());
        insertDesiredConfigurationUsages(h, configuration.usages());
        insertDesiredBeans(h, beans);
        insertDesiredInjectionPoints(h, injectionPoints);
        insertDesiredDependencies(h, dependencies);
        insertDesiredMetadata(h, metadata);
        insertDesiredExternalDeps(h, externalDeps);
        insertDesiredProblems(h, problems);
        insertDesiredFileStats(h, fileStats);
        insertDesiredCommits(h, commits);
        insertDesiredCommitFiles(h, commitFiles);
    }

    private static void createDesiredIndexes(Handle h) {
        for (TableSpec table : INSERT_ORDER) {
            h.execute("CREATE INDEX " + table.desiredName() + "_match ON "
                    + table.desiredName() + " (" + String.join(", ", table.columns()) + ")");
        }
    }

    private static int deleteMissingRows(Handle h, TableSpec table) {
        String predicate = equalityPredicate(table, "desired", table.name());
        return h.createUpdate("DELETE FROM " + table.name()
                        + " WHERE NOT EXISTS (SELECT 1 FROM " + table.desiredName()
                        + " desired WHERE " + predicate + ")")
                .execute();
    }

    private static int insertMissingRows(Handle h, TableSpec table) {
        String columns = String.join(", ", table.columns());
        return h.createUpdate("INSERT INTO " + table.name() + " (" + columns + ") "
                        + "SELECT " + columns + " FROM " + table.desiredName()
                        + " EXCEPT SELECT " + columns + " FROM " + table.name())
                .execute();
    }

    private static String equalityPredicate(TableSpec table, String left, String right) {
        return table.columns().stream()
                .map(column -> left + "." + column + " IS " + right + "." + column)
                .collect(java.util.stream.Collectors.joining(" AND "));
    }

    private static long countRows(Handle h, String table) {
        return h.createQuery("SELECT count(*) FROM " + table).mapTo(Long.class).one();
    }

    private static void insertDesiredFiles(Handle h, List<FileRecord> files) {
        executeBatch(h,
                "INSERT INTO desired_files (id, project_path, repository_path, kind, origin, lifecycle, worktree_status, module, source_set) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                files, (statement, file) -> {
                    statement.setInt(1, file.id());
                    statement.setString(2, file.projectPath());
                    statement.setString(3, file.repositoryPath());
                    statement.setString(4, file.kind());
                    statement.setString(5, file.origin());
                    statement.setString(6, file.lifecycle());
                    statement.setString(7, file.worktreeStatus());
                    statement.setString(8, file.module());
                    statement.setString(9, file.sourceSet());
                });
    }

    private static void insertDesiredClasses(Handle h, List<ClassRecord> classes) {
        try (PreparedStatement statement = h.getConnection().prepareStatement(
                "INSERT INTO desired_classes (id, class_name, kind, superclass, interfaces, source_file, source_line, is_bean, source_tokens, file_id, origin, lifecycle, module, source_set) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < classes.size(); i++) {
                ClassRecord c = classes.get(i);
                statement.setInt(1, i + 1);
                statement.setString(2, c.className());
                statement.setString(3, c.kind());
                statement.setString(4, c.superclass());
                statement.setString(5, toJson(c.interfaces()));
                statement.setString(6, c.sourceFile());
                statement.setObject(7, c.sourceLine());
                statement.setInt(8, c.isBean() ? 1 : 0);
                statement.setInt(9, c.sourceTokens());
                statement.setObject(10, c.fileId());
                statement.setString(11, c.origin());
                statement.setString(12, c.lifecycle());
                statement.setString(13, c.module());
                statement.setString(14, c.sourceSet());
                statement.addBatch();
            }
            if (!classes.isEmpty()) statement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write desired classes", e);
        }
    }

    private static void insertDesiredClassOccurrences(
            Handle h, List<ClassOccurrenceRecord> occurrences) {
        executeBatch(h,
                "INSERT INTO desired_class_occurrences (id, class_id, class_name, module, source_set, output_directory, class_file, source_file, origin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                occurrences, (statement, occurrence) -> {
                    statement.setInt(1, occurrence.id());
                    statement.setInt(2, occurrence.classId());
                    statement.setString(3, occurrence.className());
                    statement.setString(4, occurrence.module());
                    statement.setString(5, occurrence.sourceSet());
                    statement.setString(6, occurrence.outputDirectory());
                    statement.setString(7, occurrence.classFile());
                    statement.setString(8, occurrence.sourceFile());
                    statement.setString(9, occurrence.origin());
                });
    }

    private static void insertDesiredClassAnnotations(
            Handle h, List<ClassAnnotationRecord> annotations) {
        executeBatch(h,
                "INSERT INTO desired_class_annotations (class_id, annotation_name, direct, via_annotation) VALUES (?, ?, ?, ?)",
                annotations, (statement, annotation) -> {
                    statement.setInt(1, annotation.classId());
                    statement.setString(2, annotation.annotationName());
                    statement.setInt(3, annotation.direct() ? 1 : 0);
                    statement.setString(4, annotation.viaAnnotation());
                });
    }

    private static void insertDesiredClassMembers(
            Handle h, List<ClassMemberRecord> members) {
        executeBatch(h,
                "INSERT INTO desired_class_members (class_id, kind, name, signature, descriptor, type_name, parameter_types, modifiers, annotations, annotation_details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                members, (statement, member) -> {
                    statement.setInt(1, member.classId());
                    statement.setString(2, member.kind());
                    statement.setString(3, member.name());
                    statement.setString(4, member.signature());
                    statement.setString(5, member.descriptor());
                    statement.setString(6, member.typeName());
                    statement.setString(7, member.parameterTypes().isEmpty()
                            ? "[]" : toJson(member.parameterTypes()));
                    statement.setString(8, member.modifiers());
                    statement.setString(9, member.annotations().isEmpty()
                            ? "[]" : toJson(member.annotations()));
                    statement.setString(10, annotationDetailsJson(member));
                });
    }

    private static void insertDesiredMethodCalls(
            Handle h, List<MethodCallRecord> methodCalls) {
        executeBatch(h,
                "INSERT INTO desired_method_calls (from_class_id, from_method, from_descriptor, to_class_id, to_method, to_descriptor, invocation_kind, occurrence_count, evidence_lines) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                methodCalls, (statement, call) -> {
                    statement.setInt(1, call.fromClassId());
                    statement.setString(2, call.fromMethod());
                    statement.setString(3, call.fromDescriptor());
                    statement.setInt(4, call.toClassId());
                    statement.setString(5, call.toMethod());
                    statement.setString(6, call.toDescriptor());
                    statement.setString(7, call.invocationKind());
                    statement.setInt(8, call.occurrenceCount());
                    statement.setString(9, call.evidenceLines().isEmpty()
                            ? "[]" : toJson(call.evidenceLines()));
                });
    }

    private static void insertDesiredFieldAccesses(
            Handle h, List<FieldAccessRecord> fieldAccesses) {
        executeBatch(h,
                "INSERT INTO desired_field_accesses (from_class_id, from_method, from_descriptor, to_class_id, field_name, field_descriptor, access_kind, occurrence_count, evidence_lines) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                fieldAccesses, (statement, access) -> {
                    statement.setInt(1, access.fromClassId());
                    statement.setString(2, access.fromMethod());
                    statement.setString(3, access.fromDescriptor());
                    statement.setInt(4, access.toClassId());
                    statement.setString(5, access.fieldName());
                    statement.setString(6, access.fieldDescriptor());
                    statement.setString(7, access.accessKind());
                    statement.setInt(8, access.occurrenceCount());
                    statement.setString(9, access.evidenceLines().isEmpty()
                            ? "[]" : toJson(access.evidenceLines()));
                });
    }

    private static void insertDesiredBeans(Handle h, List<BeanRecord> beans) {
        try (PreparedStatement statement = h.getConnection().prepareStatement(
                "INSERT INTO desired_beans (id, class_id, kind, scope, qualifiers, stereotypes, is_alternative, is_default, priority, profiles, declaring_class_id, member_name, bean_types) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < beans.size(); i++) {
                BeanRecord b = beans.get(i);
                statement.setInt(1, i + 1);
                statement.setInt(2, b.classId());
                statement.setString(3, b.kind());
                statement.setString(4, b.scope());
                statement.setString(5, toJson(b.qualifiers()));
                statement.setString(6, toJson(b.stereotypes()));
                statement.setInt(7, b.isAlternative() ? 1 : 0);
                statement.setInt(8, b.isDefault() ? 1 : 0);
                statement.setObject(9, b.priority());
                statement.setString(10, toJson(b.profiles()));
                statement.setObject(11, b.declaringClassId());
                statement.setString(12, b.memberName());
                statement.setString(13, toJson(b.beanTypes()));
                statement.addBatch();
            }
            if (!beans.isEmpty()) statement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write desired beans", e);
        }
    }

    private static void insertDesiredInjectionPoints(
            Handle h, List<InjectionPointRecord> injectionPoints) {
        try (PreparedStatement statement = h.getConnection().prepareStatement(
                "INSERT INTO desired_injection_points (id, bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, resolution_status, resolution_strategy, resolution_reason, resolution_confidence, limitations, resolution_candidates, applied_rules, unsupported_rules) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < injectionPoints.size(); i++) {
                InjectionPointRecord ip = injectionPoints.get(i);
                statement.setInt(1, i + 1);
                statement.setInt(2, ip.beanId());
                statement.setString(3, ip.kind());
                statement.setString(4, ip.targetType());
                statement.setString(5, toJson(ip.qualifiers()));
                statement.setString(6, ip.fieldName());
                statement.setObject(7, ip.resolvedBeanId());
                statement.setString(8, ip.resolutionStatus().name());
                statement.setString(9, ip.resolutionStrategy());
                statement.setString(10, ip.resolutionReason());
                statement.setString(11, ip.resolutionConfidence().name());
                statement.setString(12, toJson(ip.limitations()));
                statement.setString(13, toJson(ip.resolutionTrace().candidates()));
                statement.setString(14, toJson(ip.resolutionTrace().appliedRules()));
                statement.setString(15, toJson(ip.resolutionTrace().unsupportedRules()));
                statement.addBatch();
            }
            if (!injectionPoints.isEmpty()) statement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write desired injection points", e);
        }
    }

    private static void insertDesiredDependencies(Handle h, List<DependencyRecord> dependencies) {
        executeBatch(h,
                "INSERT INTO desired_dependencies (from_class_id, to_class_id, kind, injection_point_id, occurrence_count, evidence_lines) VALUES (?, ?, ?, ?, ?, ?)",
                dependencies, (statement, d) -> {
                    statement.setInt(1, d.fromClassId());
                    statement.setInt(2, d.toClassId());
                    statement.setString(3, d.kind());
                    statement.setObject(4, d.injectionPointId());
                    statement.setInt(5, d.occurrenceCount());
                    statement.setString(6, d.evidenceLines().isEmpty()
                            ? "[]" : toJson(d.evidenceLines()));
                });
    }

    private static void insertDesiredMetadata(Handle h, Map<String, String> metadata) {
        executeBatch(h, "INSERT INTO desired_metadata (key, value) VALUES (?, ?)",
                metadata.entrySet(), (statement, entry) -> {
                    statement.setString(1, entry.getKey());
                    statement.setString(2, entry.getValue());
                });
    }

    private static void insertDesiredExternalDeps(Handle h, List<ExternalDepRecord> deps) {
        executeBatch(h,
                "INSERT INTO desired_class_external_deps (class_id, external_type, usage_kind) VALUES (?, ?, ?)",
                deps, (statement, d) -> {
                    statement.setInt(1, d.classId());
                    statement.setString(2, d.externalType());
                    statement.setString(3, d.usageKind());
                });
    }

    private static void insertDesiredProblems(Handle h, List<CdiProblem> problems) {
        executeBatch(h,
                "INSERT INTO desired_cdi_problems (class_id, class_name, problem_type, message) VALUES (?, ?, ?, ?)",
                problems, (statement, p) -> {
                    statement.setObject(1, p.classId());
                    statement.setString(2, p.className());
                    statement.setString(3, p.problemType());
                    statement.setString(4, p.message());
                });
    }

    private static void insertDesiredFileStats(Handle h, List<GitFileStats> fileStats) {
        executeBatch(h,
                "INSERT INTO desired_git_file_stats (file_path, class_id, commit_count, last_modified, last_author, first_commit, distinct_authors) VALUES (?, ?, ?, ?, ?, ?, ?)",
                fileStats, (statement, s) -> {
                    statement.setString(1, s.filePath());
                    statement.setObject(2, s.classId());
                    statement.setInt(3, s.commitCount());
                    statement.setString(4, s.lastModified());
                    statement.setString(5, s.lastAuthor());
                    statement.setString(6, s.firstCommit());
                    statement.setInt(7, s.distinctAuthors());
                });
    }

    private static void insertDesiredCommits(Handle h, List<GitCommitRecord> commits) {
        executeBatch(h,
                "INSERT INTO desired_git_commits (id, hash, short_hash, author, author_email, committed_at, message) VALUES (?, ?, ?, ?, ?, ?, ?)",
                commits, (statement, c) -> {
                    statement.setInt(1, c.id());
                    statement.setString(2, c.hash());
                    statement.setString(3, c.shortHash());
                    statement.setString(4, c.author());
                    statement.setString(5, c.authorEmail());
                    statement.setString(6, c.committedAt());
                    statement.setString(7, c.message());
                });
    }

    private static void insertDesiredCommitFiles(Handle h, List<GitCommitFile> commitFiles) {
        executeBatch(h,
                "INSERT INTO desired_git_commit_files (commit_id, class_id, file_path, change_type) VALUES (?, ?, ?, ?)",
                commitFiles, (statement, f) -> {
                    statement.setInt(1, f.commitId());
                    statement.setObject(2, f.classId());
                    statement.setString(3, f.filePath());
                    statement.setString(4, f.changeType());
                });
    }

    private static void insertDesiredConfigurationDefinitions(
            Handle h, List<ConfigurationScanner.Definition> definitions) {
        executeBatch(h,
                "INSERT INTO desired_configuration_definitions "
                        + "(key, kind, file, line, module, source_set) VALUES (?, ?, ?, ?, ?, ?)",
                definitions, (statement, definition) -> {
                    statement.setString(1, definition.key());
                    statement.setString(2, definition.kind());
                    statement.setString(3, definition.file());
                    statement.setInt(4, definition.line());
                    statement.setString(5, definition.module());
                    statement.setString(6, definition.sourceSet());
                });
    }

    private static void insertDesiredConfigurationUsages(
            Handle h, List<ConfigurationScanner.Usage> usages) {
        executeBatch(h,
                "INSERT INTO desired_configuration_usages "
                        + "(key, kind, class_id, class_name, member, parameter_index, "
                        + "annotation, source, module, source_set) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                usages, (statement, usage) -> {
                    statement.setString(1, usage.key());
                    statement.setString(2, usage.kind());
                    statement.setInt(3, usage.classId());
                    statement.setString(4, usage.className());
                    statement.setString(5, usage.member());
                    statement.setObject(6, usage.parameterIndex());
                    statement.setString(7, usage.annotation());
                    statement.setString(8, usage.source());
                    statement.setString(9, usage.module());
                    statement.setString(10, usage.sourceSet());
                });
    }

    private static WriteTimings writeAll(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members,
            List<MethodCallRecord> methodCalls,
            boolean freshDatabase) {
        return writeAll(jdbi, classes, beans, injectionPoints, dependencies, metadata,
                externalDeps, problems, fileStats, commits, commitFiles, files, occurrences,
                annotations, members, methodCalls, List.of(), EMPTY_CONFIGURATION, freshDatabase);
    }

    private static WriteTimings writeAll(Jdbi jdbi,
            List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata,
            List<ExternalDepRecord> externalDeps, List<CdiProblem> problems,
            List<GitFileStats> fileStats, List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles, List<FileRecord> files,
            List<ClassOccurrenceRecord> occurrences,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> members,
            List<MethodCallRecord> methodCalls,
            List<FieldAccessRecord> fieldAccesses,
            ConfigurationScanner.Result configuration,
            boolean freshDatabase) {
        long transactionStartedAt = System.nanoTime();
        long[] insertsNanos = new long[1];
        long[] indexesNanos = new long[1];
        jdbi.useTransaction(h -> {
            if (!freshDatabase) {
                for (String table : ALL_TABLES) {
                    h.execute("DELETE FROM " + table);
                }
                h.execute("DELETE FROM sqlite_sequence");
            }

            long insertsStartedAt = System.nanoTime();
            insertFiles(h, files);
            insertClasses(h, classes);
            insertClassOccurrences(h, occurrences);
            insertClassAnnotations(h, annotations);
            insertClassMembers(h, members);
            insertMethodCalls(h, methodCalls);
            insertFieldAccesses(h, fieldAccesses);
            insertConfigurationDefinitions(h, configuration.definitions());
            insertConfigurationUsages(h, configuration.usages());
            insertBeans(h, beans);
            insertInjectionPoints(h, injectionPoints);
            insertDependencies(h, dependencies);
            insertMetadata(h, metadata);
            insertExternalDeps(h, externalDeps);
            insertProblems(h, problems);
            insertGitData(h, fileStats, commits, commitFiles);
            insertsNanos[0] = System.nanoTime() - insertsStartedAt;
            if (freshDatabase) {
                long indexesStartedAt = System.nanoTime();
                QuillDatabase.createIndexes(h);
                indexesNanos[0] = System.nanoTime() - indexesStartedAt;
            }
        });
        long totalNanos = System.nanoTime() - transactionStartedAt;
        long overheadNanos = Math.max(0, totalNanos - insertsNanos[0] - indexesNanos[0]);
        return new WriteTimings(toMillis(insertsNanos[0]), toMillis(indexesNanos[0]),
                toMillis(overheadNanos));
    }

    private static long toMillis(long nanos) {
        return Math.max(0, nanos / 1_000_000);
    }

    public static void write(Jdbi jdbi, List<ClassRecord> classes, List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints, List<DependencyRecord> dependencies,
            Map<String, String> metadata) {
        jdbi.useTransaction(h -> {
            for (String table : new String[]{"dependencies", "injection_points", "beans",
                    "method_calls", "class_members", "class_annotations", "class_occurrences",
                    "classes", "files", "metadata"}) {
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

    private static void insertConfigurationDefinitions(
            Handle h, List<ConfigurationScanner.Definition> definitions) {
        executeBatch(h,
                "INSERT INTO configuration_definitions "
                        + "(key, kind, file, line, module, source_set) VALUES (?, ?, ?, ?, ?, ?)",
                definitions, (statement, definition) -> {
                    statement.setString(1, definition.key());
                    statement.setString(2, definition.kind());
                    statement.setString(3, definition.file());
                    statement.setInt(4, definition.line());
                    statement.setString(5, definition.module());
                    statement.setString(6, definition.sourceSet());
                });
    }

    private static void insertConfigurationUsages(
            Handle h, List<ConfigurationScanner.Usage> usages) {
        executeBatch(h,
                "INSERT INTO configuration_usages "
                        + "(key, kind, class_id, class_name, member, parameter_index, "
                        + "annotation, source, module, source_set) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                usages, (statement, usage) -> {
                    statement.setString(1, usage.key());
                    statement.setString(2, usage.kind());
                    statement.setInt(3, usage.classId());
                    statement.setString(4, usage.className());
                    statement.setString(5, usage.member());
                    statement.setObject(6, usage.parameterIndex());
                    statement.setString(7, usage.annotation());
                    statement.setString(8, usage.source());
                    statement.setString(9, usage.module());
                    statement.setString(10, usage.sourceSet());
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

    public static void writeModuleClasspath(
            Jdbi jdbi, List<ModuleClasspathRecord> moduleClasspath) {
        jdbi.useTransaction(h -> {
            h.execute("DELETE FROM module_classpath");
            executeBatch(h,
                    "INSERT INTO module_classpath (application_module, visible_module, distance, relation) VALUES (?, ?, ?, ?)",
                    moduleClasspath, (statement, entry) -> {
                        statement.setString(1, entry.applicationModule());
                        statement.setString(2, entry.visibleModule());
                        statement.setInt(3, entry.distance());
                        statement.setString(4, entry.relation());
                    });
        });
    }

    private static void insertClasses(Handle h, List<ClassRecord> classes) {
        executeBatch(h,
                "INSERT INTO classes (class_name, kind, superclass, interfaces, source_file, source_line, is_bean, source_tokens, file_id, origin, lifecycle, module, source_set) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
                    statement.setString(12, c.module());
                    statement.setString(13, c.sourceSet());
                });
    }

    private static void insertClassOccurrences(
            Handle h, List<ClassOccurrenceRecord> occurrences) {
        executeBatch(h,
                "INSERT INTO class_occurrences (id, class_id, class_name, module, source_set, output_directory, class_file, source_file, origin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                occurrences, (statement, occurrence) -> {
                    statement.setInt(1, occurrence.id());
                    statement.setInt(2, occurrence.classId());
                    statement.setString(3, occurrence.className());
                    statement.setString(4, occurrence.module());
                    statement.setString(5, occurrence.sourceSet());
                    statement.setString(6, occurrence.outputDirectory());
                    statement.setString(7, occurrence.classFile());
                    statement.setString(8, occurrence.sourceFile());
                    statement.setString(9, occurrence.origin());
                });
    }

    private static void insertClassAnnotations(
            Handle h, List<ClassAnnotationRecord> annotations) {
        executeBatch(h,
                "INSERT INTO class_annotations (class_id, annotation_name, direct, via_annotation) VALUES (?, ?, ?, ?)",
                annotations, (statement, annotation) -> {
                    statement.setInt(1, annotation.classId());
                    statement.setString(2, annotation.annotationName());
                    statement.setInt(3, annotation.direct() ? 1 : 0);
                    statement.setString(4, annotation.viaAnnotation());
                });
    }

    private static void insertClassMembers(Handle h, List<ClassMemberRecord> members) {
        executeBatch(h,
                "INSERT INTO class_members (class_id, kind, name, signature, descriptor, type_name, parameter_types, modifiers, annotations, annotation_details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                members, (statement, member) -> {
                    statement.setInt(1, member.classId());
                    statement.setString(2, member.kind());
                    statement.setString(3, member.name());
                    statement.setString(4, member.signature());
                    statement.setString(5, member.descriptor());
                    statement.setString(6, member.typeName());
                    statement.setString(7, member.parameterTypes().isEmpty()
                            ? "[]" : toJson(member.parameterTypes()));
                    statement.setString(8, member.modifiers());
                    statement.setString(9, member.annotations().isEmpty()
                            ? "[]" : toJson(member.annotations()));
                    statement.setString(10, annotationDetailsJson(member));
                });
    }

    private static String annotationDetailsJson(ClassMemberRecord member) {
        if (member.annotationDetails().isEmpty()) return "[]";
        return toJson(member.annotationDetails().stream().map(annotation -> Map.of(
                "annotationName", annotation.annotationName(),
                "targetKind", annotation.targetKind(),
                "parameterIndex", annotation.parameterIndex() == null
                        ? -1 : annotation.parameterIndex(),
                "parameterName", annotation.parameterName() == null
                        ? "" : annotation.parameterName(),
                "parameterType", annotation.parameterType() == null
                        ? "" : annotation.parameterType())).toList());
    }

    private static void insertMethodCalls(Handle h, List<MethodCallRecord> methodCalls) {
        executeBatch(h,
                "INSERT INTO method_calls (from_class_id, from_method, from_descriptor, to_class_id, to_method, to_descriptor, invocation_kind, occurrence_count, evidence_lines) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                methodCalls, (statement, call) -> {
                    statement.setInt(1, call.fromClassId());
                    statement.setString(2, call.fromMethod());
                    statement.setString(3, call.fromDescriptor());
                    statement.setInt(4, call.toClassId());
                    statement.setString(5, call.toMethod());
                    statement.setString(6, call.toDescriptor());
                    statement.setString(7, call.invocationKind());
                    statement.setInt(8, call.occurrenceCount());
                    statement.setString(9, call.evidenceLines().isEmpty()
                            ? "[]" : toJson(call.evidenceLines()));
                });
    }

    private static void insertFieldAccesses(Handle h, List<FieldAccessRecord> fieldAccesses) {
        executeBatch(h,
                "INSERT INTO field_accesses (from_class_id, from_method, from_descriptor, to_class_id, field_name, field_descriptor, access_kind, occurrence_count, evidence_lines) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                fieldAccesses, (statement, access) -> {
                    statement.setInt(1, access.fromClassId());
                    statement.setString(2, access.fromMethod());
                    statement.setString(3, access.fromDescriptor());
                    statement.setInt(4, access.toClassId());
                    statement.setString(5, access.fieldName());
                    statement.setString(6, access.fieldDescriptor());
                    statement.setString(7, access.accessKind());
                    statement.setInt(8, access.occurrenceCount());
                    statement.setString(9, access.evidenceLines().isEmpty()
                            ? "[]" : toJson(access.evidenceLines()));
                });
    }

    private static void insertFiles(Handle h, List<FileRecord> files) {
        if (files == null || files.isEmpty()) return;
        executeBatch(h,
                "INSERT INTO files (id, project_path, repository_path, kind, origin, lifecycle, worktree_status, module, source_set) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                files, (statement, file) -> {
                    statement.setInt(1, file.id());
                    statement.setString(2, file.projectPath());
                    statement.setString(3, file.repositoryPath());
                    statement.setString(4, file.kind());
                    statement.setString(5, file.origin());
                    statement.setString(6, file.lifecycle());
                    statement.setString(7, file.worktreeStatus());
                    statement.setString(8, file.module());
                    statement.setString(9, file.sourceSet());
                });
    }

    private static void insertBeans(Handle h, List<BeanRecord> beans) {
        executeBatch(h,
                "INSERT INTO beans (class_id, kind, scope, qualifiers, stereotypes, is_alternative, is_default, priority, profiles, declaring_class_id, member_name, bean_types) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                beans, (statement, b) -> {
                    statement.setInt(1, b.classId());
                    statement.setString(2, b.kind());
                    statement.setString(3, b.scope());
                    statement.setString(4, toJson(b.qualifiers()));
                    statement.setString(5, toJson(b.stereotypes()));
                    statement.setInt(6, b.isAlternative() ? 1 : 0);
                    statement.setInt(7, b.isDefault() ? 1 : 0);
                    statement.setObject(8, b.priority());
                    statement.setString(9, toJson(b.profiles()));
                    statement.setObject(10, b.declaringClassId());
                    statement.setString(11, b.memberName());
                    statement.setString(12, toJson(b.beanTypes()));
                });
    }

    private static void insertInjectionPoints(Handle h, List<InjectionPointRecord> ips) {
        executeBatch(h,
                "INSERT INTO injection_points (bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, resolution_status, resolution_strategy, resolution_reason, resolution_confidence, limitations, resolution_candidates, applied_rules, unsupported_rules) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ips, (statement, ip) -> {
                    statement.setInt(1, ip.beanId());
                    statement.setString(2, ip.kind());
                    statement.setString(3, ip.targetType());
                    statement.setString(4, toJson(ip.qualifiers()));
                    statement.setString(5, ip.fieldName());
                    statement.setObject(6, ip.resolvedBeanId());
                    statement.setString(7, ip.resolutionStatus().name());
                    statement.setString(8, ip.resolutionStrategy());
                    statement.setString(9, ip.resolutionReason());
                    statement.setString(10, ip.resolutionConfidence().name());
                    statement.setString(11, toJson(ip.limitations()));
                    statement.setString(12, toJson(ip.resolutionTrace().candidates()));
                    statement.setString(13, toJson(ip.resolutionTrace().appliedRules()));
                    statement.setString(14, toJson(ip.resolutionTrace().unsupportedRules()));
                });
    }

    private static void insertDependencies(Handle h, List<DependencyRecord> deps) {
        executeBatch(h,
                "INSERT INTO dependencies (from_class_id, to_class_id, kind, injection_point_id, occurrence_count, evidence_lines) VALUES (?, ?, ?, ?, ?, ?)",
                deps, (statement, d) -> {
                    statement.setInt(1, d.fromClassId());
                    statement.setInt(2, d.toClassId());
                    statement.setString(3, d.kind());
                    statement.setObject(4, d.injectionPointId());
                    statement.setInt(5, d.occurrenceCount());
                    statement.setString(6, d.evidenceLines().isEmpty()
                            ? "[]" : toJson(d.evidenceLines()));
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

    static String toJson(Object value) {
        if (value == null) return null;
        if (value instanceof Collection<?> collection && collection.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
