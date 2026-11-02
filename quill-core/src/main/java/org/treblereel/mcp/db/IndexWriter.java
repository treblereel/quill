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

        String insertSql(boolean desired) {
            return "INSERT INTO " + (desired ? desiredName() : name) + " ("
                    + String.join(", ", columns) + ") VALUES ("
                    + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
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
            "class_id", "annotation_name", "direct", "via_annotation", "annotation_values");
    private static final TableSpec CLASS_MEMBERS = new TableSpec("class_members",
            "class_id", "kind", "name", "signature", "descriptor", "type_name",
            "parameter_types", "modifiers", "annotations", "annotation_details");
    private static final TableSpec JVM_API = new TableSpec("jvm_api","class_id","evidence");
    private static final TableSpec METHOD_CALLS = new TableSpec("method_calls",
            "from_class_id", "from_method", "from_descriptor", "to_class_id",
            "to_method", "to_descriptor", "invocation_kind", "occurrence_count",
            "evidence_lines", "instruction_ordinals");
    private static final TableSpec METHOD_EVIDENCE = new TableSpec("method_evidence",
            "from_class_id", "from_method", "from_descriptor", "caller_branch_count",
            "caller_exception_handler_count", "caller_control_flow_edges",
            "caller_async_boundaries", "caller_external_calls");
    private static final TableSpec FIELD_ACCESSES = new TableSpec("field_accesses",
            "from_class_id", "from_method", "from_descriptor", "to_class_id",
            "field_name", "field_descriptor", "access_kind", "occurrence_count",
            "evidence_lines", "instruction_ordinals");
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
            "source_set", "evidence");
    private static final TableSpec CONFIGURATION_USAGES = new TableSpec(
            "configuration_usages", "key", "kind", "class_id", "class_name", "member",
            "parameter_index", "annotation", "source", "module", "source_set");
    private static final TableSpec RESOURCE_USAGES = new TableSpec(
            "resource_usages", "resource_path", "kind", "class_id", "class_name", "member",
            "api", "source", "module", "source_set");
    private static final TableSpec FILE_STATS = new TableSpec("git_file_stats",
            "file_path", "class_id", "commit_count", "last_modified", "last_author",
            "first_commit", "distinct_authors");
    private static final TableSpec COMMITS = new TableSpec("git_commits",
            "id", "hash", "short_hash", "author", "author_email", "committed_at", "message");
    private static final TableSpec COMMIT_FILES = new TableSpec("git_commit_files",
            "commit_id", "class_id", "file_path", "change_type");
    private static final TableSpec MODULE_CLASSPATH = new TableSpec("module_classpath",
            "application_module", "visible_module", "distance", "relation");
    private static final TableSpec KOTLIN_DEBUG = new TableSpec("kotlin_source_debug", "class_id", "data");
    private static final TableSpec KOTLIN_DECLARATIONS = new TableSpec("kotlin_declarations",
            "class_id", "kind", "name", "jvm_name", "descriptor", "semantic_model", "is_suspend",
            "is_extension", "has_default_parameters", "is_mutable", "is_lateinit", "is_delegated",
            "is_synthetic", "value_parameters", "declaration_types");
    private static final TableSpec EXTERNAL_BEANS = new TableSpec("external_beans",
            "id", "class_name", "kind", "scope", "qualifiers", "stereotypes", "is_alternative",
            "is_default", "priority", "profiles", "member_name", "bean_types", "framework",
            "artifact", "jar_path", "injection_points", "interceptor_bindings");
    private static final List<TableSpec> INSERT_ORDER = List.of(
            FILES, CLASSES, CLASS_OCCURRENCES, CLASS_ANNOTATIONS, CLASS_MEMBERS, JVM_API,
            METHOD_EVIDENCE, METHOD_CALLS, FIELD_ACCESSES, CONFIGURATION_DEFINITIONS, CONFIGURATION_USAGES,
            RESOURCE_USAGES, BEANS,
            INJECTION_POINTS, DEPENDENCIES, METADATA,
            EXTERNAL_DEPS, PROBLEMS, FILE_STATS, COMMITS, COMMIT_FILES,
            MODULE_CLASSPATH, KOTLIN_DEBUG, KOTLIN_DECLARATIONS, EXTERNAL_BEANS);
    private static final List<TableSpec> DELETE_ORDER = List.of(
            COMMIT_FILES, FILE_STATS, EXTERNAL_DEPS, PROBLEMS, DEPENDENCIES,
            RESOURCE_USAGES, CONFIGURATION_USAGES, CONFIGURATION_DEFINITIONS,
            FIELD_ACCESSES, METHOD_CALLS, METHOD_EVIDENCE,
            INJECTION_POINTS, BEANS, COMMITS, JVM_API, CLASS_MEMBERS, CLASS_ANNOTATIONS, CLASS_OCCURRENCES,
            KOTLIN_DEBUG, KOTLIN_DECLARATIONS, MODULE_CLASSPATH, EXTERNAL_BEANS,
            CLASSES, FILES, METADATA);

    /** Writes and compacts a new disposable staging database before publication validation. */
    public static WriteTimings writeFresh(Jdbi jdbi, IndexSnapshot snapshot) {
        long transactionStartedAt = System.nanoTime();
        long[] insertsNanos = new long[1];
        long[] indexesNanos = new long[1];
        try (Handle handle = jdbi.open()) {
            handle.useTransaction(h -> {
                long insertsStartedAt = System.nanoTime();
                populateTables(h, snapshot, false);
                insertsNanos[0] = System.nanoTime() - insertsStartedAt;
                long indexesStartedAt = System.nanoTime();
                IndexSchema.createIndexes(h);
                indexesNanos[0] = System.nanoTime() - indexesStartedAt;
            });
            // VACUUM cannot run in a transaction and never belongs on the incremental path.
            handle.execute("VACUUM");
        }
        long totalNanos = System.nanoTime() - transactionStartedAt;
        long overheadNanos = Math.max(0, totalNanos - insertsNanos[0] - indexesNanos[0]);
        return new WriteTimings(toMillis(insertsNanos[0]), toMillis(indexesNanos[0]),
                toMillis(overheadNanos));
    }

    /**
     * Applies a complete logical snapshot to a cloned index while mutating only rows whose
     * persisted value changed. Secondary indexes are retained and therefore updated only for the
     * actual delta. The caller must supply a disposable staging database, never the active index.
     */
    public static IncrementalWriteTimings writeIncremental(Jdbi jdbi, IndexSnapshot snapshot) {
        long startedAt = System.nanoTime();
        long[] deltaNanos = new long[1];
        long[] counts = new long[3];
        try (Handle h = jdbi.open()) {
            h.execute("PRAGMA foreign_keys=OFF");
            try {
                h.useTransaction(tx -> {
                    createDesiredTables(tx);
                    populateTables(tx, snapshot, true);
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
        }
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

    private static void populateTables(Handle h, IndexSnapshot snapshot, boolean desired) {
        insertFiles(h, desired, snapshot.files());
        insertClasses(h, desired, snapshot.classes());
        insertClassOccurrences(h, desired, snapshot.classOccurrences());
        insertClassAnnotations(h, desired, snapshot.classAnnotations());
        insertClassMembers(h, desired, snapshot.classMembers());
        insertJvmApi(h, desired, snapshot.jvmApi());
        insertMethodCalls(h, desired, snapshot.methodCalls());
        insertFieldAccesses(h, desired, snapshot.fieldAccesses());
        insertConfigurationDefinitions(h, desired, snapshot.configuration().definitions());
        insertConfigurationUsages(h, desired, snapshot.configuration().usages());
        insertResourceUsages(h, desired, snapshot.resourceUsages());
        insertBeans(h, desired, snapshot.beans());
        insertInjectionPoints(h, desired, snapshot.injectionPoints());
        insertDependencies(h, desired, snapshot.dependencies());
        insertMetadata(h, desired, snapshot.metadata());
        insertExternalDeps(h, desired, snapshot.externalDependencies());
        insertProblems(h, desired, snapshot.problems());
        insertFileStats(h, desired, snapshot.gitFileStats());
        insertCommits(h, desired, snapshot.gitCommits());
        insertCommitFiles(h, desired, snapshot.gitCommitFiles());
        insertModuleClasspath(h, desired, snapshot.moduleClasspath());
        insertKotlinSourceDebug(h, desired, snapshot.kotlinSourceDebug());
        insertKotlinDeclarations(h, desired, snapshot.kotlinDeclarations());
        insertExternalBeans(h, desired, snapshot.externalBeans());
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

    private static void insertFiles(Handle h, boolean desired, List<FileRecord> files) {
        executeBatch(h,
                FILES.insertSql(desired),
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

    private static void insertClasses(Handle h, boolean desired, List<ClassRecord> classes) {
        try (PreparedStatement statement = h.getConnection().prepareStatement(CLASSES.insertSql(desired))) {
            for (int i = 0; i < classes.size(); i++) {
                ClassRecord c = classes.get(i);
                statement.setInt(1, c.id());
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

    private static void insertClassOccurrences(Handle h, boolean desired, List<ClassOccurrenceRecord> occurrences) {
        executeBatch(h,
                CLASS_OCCURRENCES.insertSql(desired),
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

    private static void insertClassAnnotations(Handle h, boolean desired, List<ClassAnnotationRecord> annotations) {
        executeBatch(h,
                CLASS_ANNOTATIONS.insertSql(desired),
                annotations, (statement, annotation) -> {
                    statement.setInt(1, annotation.classId());
                    statement.setString(2, annotation.annotationName());
                    statement.setInt(3, annotation.direct() ? 1 : 0);
                    statement.setString(4, annotation.viaAnnotation());
                    statement.setString(5, annotation.values().isEmpty() ? null : toJson(annotation.values()));
                });
    }

    private static void insertClassMembers(Handle h, boolean desired, List<ClassMemberRecord> members) {
        executeBatch(h,
                CLASS_MEMBERS.insertSql(desired),
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

    private static void insertMethodCalls(Handle h, boolean desired, List<MethodCallRecord> methodCalls) {
        executeBatch(h,
                METHOD_CALLS.insertSql(desired),
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
                    statement.setString(10, call.instructionOrdinals().isEmpty()
                            ? "[]" : toJson(call.instructionOrdinals()));
                });
        record CallerMethod(int classId, String name, String descriptor) {}
        var methods = new java.util.LinkedHashMap<CallerMethod, MethodCallRecord>();
        for (var call : methodCalls) {
            var key = new CallerMethod(call.fromClassId(), call.fromMethod(), call.fromDescriptor());
            var previous = methods.putIfAbsent(key, call);
            if (previous != null && (previous.callerBranchCount() != call.callerBranchCount()
                    || previous.callerExceptionHandlerCount() != call.callerExceptionHandlerCount()
                    || !previous.callerControlFlowEdges().equals(call.callerControlFlowEdges())
                    || !previous.callerAsyncBoundaries().equals(call.callerAsyncBoundaries())
                    || !previous.callerExternalCalls().equals(call.callerExternalCalls()))) {
                throw new IllegalArgumentException("Conflicting method evidence for " + key);
            }
        }
        executeBatch(h, METHOD_EVIDENCE.insertSql(desired), methods.values(), (statement, call) -> {
            statement.setInt(1, call.fromClassId());
            statement.setString(2, call.fromMethod());
            statement.setString(3, call.fromDescriptor());
            statement.setInt(4, call.callerBranchCount());
            statement.setInt(5, call.callerExceptionHandlerCount());
            statement.setString(6, call.callerControlFlowEdges().isEmpty()
                    ? "[]" : toJson(call.callerControlFlowEdges()));
            statement.setString(7, call.callerAsyncBoundaries().isEmpty()
                    ? "[]" : toJson(call.callerAsyncBoundaries()));
            statement.setString(8, call.callerExternalCalls().isEmpty()
                    ? "[]" : toJson(call.callerExternalCalls()));
        });
    }

    private static void insertFieldAccesses(Handle h, boolean desired, List<FieldAccessRecord> fieldAccesses) {
        executeBatch(h,
                FIELD_ACCESSES.insertSql(desired),
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
                    statement.setString(10, access.instructionOrdinals().isEmpty()
                            ? "[]" : toJson(access.instructionOrdinals()));
                });
    }

    private static void insertBeans(Handle h, boolean desired, List<BeanRecord> beans) {
        try (PreparedStatement statement = h.getConnection().prepareStatement(BEANS.insertSql(desired))) {
            for (int i = 0; i < beans.size(); i++) {
                BeanRecord b = beans.get(i);
                statement.setInt(1, b.id());
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

    private static void insertInjectionPoints(Handle h, boolean desired, List<InjectionPointRecord> injectionPoints) {
        try (PreparedStatement statement = h.getConnection().prepareStatement(INJECTION_POINTS.insertSql(desired))) {
            for (int i = 0; i < injectionPoints.size(); i++) {
                InjectionPointRecord ip = injectionPoints.get(i);
                statement.setInt(1, ip.id());
                statement.setInt(2, ip.beanId());
                statement.setString(3, ip.kind());
                statement.setString(4, ip.targetType());
                statement.setString(5, toJson(ip.qualifiers()));
                statement.setString(6, ip.fieldName());
                statement.setObject(7, ip.resolvedBeanId());
                statement.setString(8, ip.resolutionStatus().name());
                statement.setString(9, ip.resolutionStrategy());
                statement.setString(10, ip.resolutionReason().name());
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

    private static void insertDependencies(Handle h, boolean desired, List<DependencyRecord> dependencies) {
        executeBatch(h,
                DEPENDENCIES.insertSql(desired),
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

    private static void insertMetadata(Handle h, boolean desired, Map<String, String> metadata) {
        executeBatch(h, METADATA.insertSql(desired),
                metadata.entrySet(), (statement, entry) -> {
                    statement.setString(1, entry.getKey());
                    statement.setString(2, entry.getValue());
                });
    }

    static void insertExternalDeps(Handle h, boolean desired, List<ExternalDepRecord> deps) {
        executeBatch(h,
                EXTERNAL_DEPS.insertSql(desired),
                deps, (statement, d) -> {
                    statement.setInt(1, d.classId());
                    statement.setString(2, d.externalType());
                    statement.setString(3, d.usageKind());
                });
    }

    static void insertProblems(Handle h, boolean desired, List<CdiProblem> problems) {
        executeBatch(h,
                PROBLEMS.insertSql(desired),
                problems, (statement, p) -> {
                    statement.setObject(1, p.classId());
                    statement.setString(2, p.className());
                    statement.setString(3, p.problemType());
                    statement.setString(4, p.message());
                });
    }

    static void insertFileStats(Handle h, boolean desired, List<GitFileStats> fileStats) {
        executeBatch(h,
                FILE_STATS.insertSql(desired),
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

    static void insertCommits(Handle h, boolean desired, List<GitCommitRecord> commits) {
        executeBatch(h,
                COMMITS.insertSql(desired),
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

    static void insertCommitFiles(Handle h, boolean desired, List<GitCommitFile> commitFiles) {
        executeBatch(h,
                COMMIT_FILES.insertSql(desired),
                commitFiles, (statement, f) -> {
                    statement.setInt(1, f.commitId());
                    statement.setObject(2, f.classId());
                    statement.setString(3, f.filePath());
                    statement.setString(4, f.changeType());
                });
    }

    private static void insertConfigurationDefinitions(Handle h, boolean desired, List<ConfigurationScanner.Definition> definitions) {
        executeBatch(h,
                CONFIGURATION_DEFINITIONS.insertSql(desired),
                definitions, (statement, definition) -> {
                    statement.setString(1, definition.key());
                    statement.setString(2, definition.kind());
                    statement.setString(3, definition.file());
                    statement.setInt(4, definition.line());
                    statement.setString(5, definition.module());
                    statement.setString(6, definition.sourceSet());
                    statement.setString(7, definition.evidence());
                });
    }

    private static void insertConfigurationUsages(Handle h, boolean desired, List<ConfigurationScanner.Usage> usages) {
        executeBatch(h,
                CONFIGURATION_USAGES.insertSql(desired),
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

    private static void insertResourceUsages(Handle h, boolean desired, List<ResourceUsageRecord> usages) {
        executeBatch(h,
                RESOURCE_USAGES.insertSql(desired),
                usages, (statement, usage) -> {
                    statement.setString(1, usage.resourcePath());
                    statement.setString(2, usage.kind());
                    statement.setInt(3, usage.classId());
                    statement.setString(4, usage.className());
                    statement.setString(5, usage.member());
                    statement.setString(6, usage.api());
                    statement.setString(7, usage.source());
                    statement.setString(8, usage.module());
                    statement.setString(9, usage.sourceSet());
                });
    }

    private static long toMillis(long nanos) {
        return Math.max(0, nanos / 1_000_000);
    }

    static void insertModuleClasspath(Handle h, boolean desired, List<ModuleClasspathRecord> moduleClasspath) {
            executeBatch(h,
                    MODULE_CLASSPATH.insertSql(desired),
                    moduleClasspath, (statement, entry) -> {
                        statement.setString(1, entry.applicationModule());
                        statement.setString(2, entry.visibleModule());
                        statement.setInt(3, entry.distance());
                        statement.setString(4, entry.relation());
                    });
    }

    static void insertKotlinSourceDebug(Handle h, boolean desired, Map<Integer, org.treblereel.mcp.core.KotlinSourceDebug.Info> values) {
        executeBatch(h, KOTLIN_DEBUG.insertSql(desired), values.entrySet(), (statement, entry) -> {
            statement.setInt(1, entry.getKey());
            statement.setString(2, toJson(entry.getValue()));
        });
    }

    static void insertKotlinDeclarations(Handle h, boolean desired, List<KotlinDeclarationRecord> declarations) {
            executeBatch(h,
                    KOTLIN_DECLARATIONS.insertSql(desired),
                    declarations, (statement, declaration) -> {
                        statement.setInt(1, declaration.classId());
                        statement.setString(2, declaration.kind());
                        statement.setString(3, declaration.name());
                        statement.setString(4, declaration.jvmName());
                        statement.setString(5, declaration.descriptor());
                        statement.setString(6, declaration.semanticModel());
                        statement.setInt(7, declaration.isSuspend() ? 1 : 0);
                        statement.setInt(8, declaration.extension() ? 1 : 0);
                        statement.setInt(9, declaration.hasDefaultParameters() ? 1 : 0);
                        statement.setInt(10, declaration.mutable() ? 1 : 0);
                        statement.setInt(11, declaration.lateinit() ? 1 : 0);
                        statement.setInt(12, declaration.delegated() ? 1 : 0);
                        statement.setInt(13, declaration.synthetic() ? 1 : 0);
                        statement.setString(14, declaration.valueParameters() == null ? null
                                : jsonArray(declaration.valueParameters()));
                        statement.setString(15, declaration.declarationTypes() == null ? null : toJson(declaration.declarationTypes()));
                    });
    }

    static void insertExternalBeans(Handle h, boolean desired, List<ExternalBeanRecord> beans) {
            executeBatch(h,
                    EXTERNAL_BEANS.insertSql(desired),
                    beans, (statement, bean) -> {
                        statement.setInt(1, bean.id());
                        statement.setString(2, bean.className());
                        statement.setString(3, bean.kind());
                        statement.setString(4, bean.scope());
                        statement.setString(5, jsonArray(bean.qualifiers()));
                        statement.setString(6, jsonArray(bean.stereotypes()));
                        statement.setInt(7, bean.alternative() ? 1 : 0);
                        statement.setInt(8, bean.defaultBean() ? 1 : 0);
                        statement.setObject(9, bean.priority());
                        statement.setString(10, jsonArray(bean.profiles()));
                        statement.setString(11, bean.memberName());
                        statement.setString(12, jsonArray(bean.beanTypes()));
                        statement.setString(13, bean.framework());
                        statement.setString(14, bean.artifact());
                        statement.setString(15, bean.jarPath());
                        statement.setString(16,
                                jsonArray(bean.injectionPoints()));
                        statement.setString(17, jsonArray(bean.interceptorBindings()));
                    });
    }

    private static void insertJvmApi(Handle h, boolean desired,Map<Integer,String> evidence) {
        executeBatch(h,JVM_API.insertSql(desired),
                evidence.entrySet(),(statement,entry) -> {
                    statement.setInt(1,entry.getKey()); statement.setString(2,entry.getValue());
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

    private static String jsonArray(Collection<?> value) {
        return value == null || value.isEmpty() ? "[]" : toJson(value);
    }
    private static String annotationDetailsJson(ClassMemberRecord member) {
        if (member.annotationDetails().isEmpty()) return "[]";
        return toJson(member.annotationDetails().stream().map(annotation -> {
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("annotationName", annotation.annotationName());
            detail.put("targetKind", annotation.targetKind());
            detail.put("parameterIndex", annotation.parameterIndex() == null
                    ? -1 : annotation.parameterIndex());
            detail.put("parameterName", annotation.parameterName() == null
                    ? "" : annotation.parameterName());
            detail.put("parameterType", annotation.parameterType() == null
                    ? "" : annotation.parameterType());
            if (!annotation.values().isEmpty()) detail.put("values", annotation.values());
            return detail;
        }).toList());
    }

}
