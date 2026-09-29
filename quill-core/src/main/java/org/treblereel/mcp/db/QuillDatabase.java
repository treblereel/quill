package org.treblereel.mcp.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.jdbi.v3.core.Jdbi;

public final class QuillDatabase {

    static final int SCHEMA_VERSION = 22;

    private QuillDatabase() {}

    public static Jdbi create(Path dbPath) {
        return create(dbPath, true);
    }

    public static Jdbi createForBulkLoad(Path dbPath) {
        if (Files.exists(dbPath)) {
            throw new IllegalArgumentException(
                    "Bulk-loaded index must use a new staging path: " + dbPath);
        }
        return create(dbPath, false);
    }

    private static Jdbi create(Path dbPath, boolean createIndexes) {
        try {
            Path parent = dbPath.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory for " + dbPath, e);
        }
        prepareExistingDatabase(dbPath);
        Jdbi jdbi = createJdbi(dbPath, false);
        jdbi.useHandle(h ->
            // Indexes are assembled off to the side and atomically published. DELETE mode
            // keeps the complete database in one file, so no WAL sidecar can be lost on move.
            h.execute("PRAGMA journal_mode=DELETE"));
        jdbi.useTransaction(h -> {
            h.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_path TEXT NOT NULL UNIQUE,
                    repository_path TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    origin TEXT NOT NULL,
                    lifecycle TEXT NOT NULL,
                    worktree_status TEXT,
                    module TEXT,
                    source_set TEXT
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS classes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    class_name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    superclass TEXT,
                    interfaces TEXT,
                    source_file TEXT,
                    source_line INTEGER,
                    is_bean INTEGER NOT NULL DEFAULT 0,
                    source_tokens INTEGER NOT NULL DEFAULT 0,
                    file_id INTEGER REFERENCES files(id),
                    origin TEXT NOT NULL DEFAULT 'source',
                    lifecycle TEXT NOT NULL DEFAULT 'current',
                    module TEXT,
                    source_set TEXT
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS class_occurrences (
                    id INTEGER PRIMARY KEY,
                    class_id INTEGER NOT NULL REFERENCES classes(id),
                    class_name TEXT NOT NULL,
                    module TEXT NOT NULL,
                    source_set TEXT NOT NULL,
                    output_directory TEXT NOT NULL,
                    class_file TEXT NOT NULL,
                    source_file TEXT,
                    origin TEXT NOT NULL,
                    UNIQUE(class_name, output_directory, class_file)
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS class_annotations (
                    class_id INTEGER NOT NULL REFERENCES classes(id),
                    annotation_name TEXT NOT NULL,
                    direct INTEGER NOT NULL,
                    via_annotation TEXT,
                    UNIQUE(class_id, annotation_name, direct, via_annotation)
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS class_members (
                    class_id INTEGER NOT NULL REFERENCES classes(id),
                    kind TEXT NOT NULL,
                    name TEXT NOT NULL,
                    signature TEXT NOT NULL,
                    descriptor TEXT NOT NULL DEFAULT '',
                    type_name TEXT NOT NULL,
                    parameter_types TEXT NOT NULL,
                    modifiers TEXT NOT NULL,
                    annotations TEXT NOT NULL,
                    annotation_details TEXT NOT NULL DEFAULT '[]',
                    UNIQUE(class_id, kind, signature)
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS method_calls (
                    from_class_id INTEGER NOT NULL REFERENCES classes(id),
                    from_method TEXT NOT NULL,
                    from_descriptor TEXT NOT NULL,
                    to_class_id INTEGER NOT NULL REFERENCES classes(id),
                    to_method TEXT NOT NULL,
                    to_descriptor TEXT NOT NULL,
                    invocation_kind TEXT NOT NULL,
                    occurrence_count INTEGER NOT NULL DEFAULT 1,
                    evidence_lines TEXT NOT NULL DEFAULT '[]',
                    instruction_ordinals TEXT NOT NULL DEFAULT '[]',
                    caller_branch_count INTEGER NOT NULL DEFAULT 0,
                    caller_exception_handler_count INTEGER NOT NULL DEFAULT 0,
                    caller_control_flow_edges TEXT NOT NULL DEFAULT '[]',
                    caller_async_boundaries TEXT NOT NULL DEFAULT '[]',
                    UNIQUE(from_class_id, from_method, from_descriptor,
                           to_class_id, to_method, to_descriptor, invocation_kind)
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS field_accesses (
                    from_class_id INTEGER NOT NULL REFERENCES classes(id),
                    from_method TEXT NOT NULL,
                    from_descriptor TEXT NOT NULL,
                    to_class_id INTEGER NOT NULL REFERENCES classes(id),
                    field_name TEXT NOT NULL,
                    field_descriptor TEXT NOT NULL,
                    access_kind TEXT NOT NULL,
                    occurrence_count INTEGER NOT NULL DEFAULT 1,
                    evidence_lines TEXT NOT NULL DEFAULT '[]',
                    instruction_ordinals TEXT NOT NULL DEFAULT '[]',
                    UNIQUE(from_class_id, from_method, from_descriptor,
                           to_class_id, field_name, field_descriptor, access_kind)
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS module_classpath (
                    application_module TEXT NOT NULL,
                    visible_module TEXT NOT NULL,
                    distance INTEGER NOT NULL,
                    relation TEXT NOT NULL,
                    PRIMARY KEY(application_module, visible_module)
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS beans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    class_id INTEGER NOT NULL REFERENCES classes(id),
                    kind TEXT NOT NULL,
                    scope TEXT,
                    qualifiers TEXT,
                    stereotypes TEXT,
                    is_alternative INTEGER NOT NULL DEFAULT 0,
                    is_default INTEGER NOT NULL DEFAULT 0,
                    priority INTEGER,
                    profiles TEXT,
                    declaring_class_id INTEGER REFERENCES classes(id),
                    member_name TEXT,
                    bean_types TEXT
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS injection_points (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    bean_id INTEGER NOT NULL REFERENCES beans(id),
                    kind TEXT NOT NULL,
                    target_type TEXT NOT NULL,
                    qualifiers TEXT,
                    field_name TEXT,
                    resolved_bean_id INTEGER REFERENCES beans(id),
                    resolution_status TEXT NOT NULL,
                    resolution_strategy TEXT NOT NULL,
                    resolution_reason TEXT NOT NULL,
                    resolution_confidence TEXT NOT NULL,
                    limitations TEXT,
                    resolution_candidates TEXT,
                    applied_rules TEXT,
                    unsupported_rules TEXT
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS dependencies (
                    from_class_id INTEGER NOT NULL REFERENCES classes(id),
                    to_class_id INTEGER NOT NULL REFERENCES classes(id),
                    kind TEXT NOT NULL,
                    injection_point_id INTEGER REFERENCES injection_points(id),
                    occurrence_count INTEGER NOT NULL DEFAULT 1,
                    evidence_lines TEXT NOT NULL DEFAULT '[]'
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS git_file_stats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_path TEXT NOT NULL,
                    class_id INTEGER REFERENCES classes(id),
                    commit_count INTEGER NOT NULL DEFAULT 0,
                    last_modified TEXT,
                    last_author TEXT,
                    first_commit TEXT,
                    distinct_authors INTEGER NOT NULL DEFAULT 0
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS git_commits (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    hash TEXT NOT NULL UNIQUE,
                    short_hash TEXT NOT NULL,
                    author TEXT NOT NULL,
                    author_email TEXT,
                    committed_at TEXT NOT NULL,
                    message TEXT NOT NULL
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS git_commit_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    commit_id INTEGER NOT NULL REFERENCES git_commits(id),
                    class_id INTEGER REFERENCES classes(id),
                    file_path TEXT NOT NULL,
                    change_type TEXT NOT NULL
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS class_external_deps (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    class_id INTEGER NOT NULL REFERENCES classes(id),
                    external_type TEXT NOT NULL,
                    usage_kind TEXT NOT NULL
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS external_beans (
                    id INTEGER PRIMARY KEY,
                    class_name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    scope TEXT,
                    qualifiers TEXT NOT NULL,
                    stereotypes TEXT NOT NULL,
                    is_alternative INTEGER NOT NULL DEFAULT 0,
                    is_default INTEGER NOT NULL DEFAULT 0,
                    priority INTEGER,
                    profiles TEXT NOT NULL,
                    member_name TEXT,
                    bean_types TEXT NOT NULL,
                    framework TEXT NOT NULL,
                    artifact TEXT,
                    jar_path TEXT,
                    injection_points TEXT NOT NULL,
                    UNIQUE(framework, class_name, kind, member_name)
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS cdi_problems (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    class_id INTEGER REFERENCES classes(id),
                    class_name TEXT NOT NULL,
                    problem_type TEXT NOT NULL,
                    message TEXT NOT NULL
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS configuration_definitions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    key TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    file TEXT NOT NULL,
                    line INTEGER NOT NULL,
                    module TEXT NOT NULL,
                    source_set TEXT NOT NULL,
                    UNIQUE(key, kind, file, line, module, source_set)
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS configuration_usages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    key TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    class_id INTEGER NOT NULL REFERENCES classes(id),
                    class_name TEXT NOT NULL,
                    member TEXT,
                    parameter_index INTEGER,
                    annotation TEXT NOT NULL,
                    source TEXT,
                    module TEXT NOT NULL,
                    source_set TEXT NOT NULL,
                    UNIQUE(key, kind, class_id, member, parameter_index, annotation)
                )""");
            h.execute("""
                CREATE TABLE IF NOT EXISTS resource_usages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    resource_path TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    class_id INTEGER NOT NULL REFERENCES classes(id),
                    class_name TEXT NOT NULL,
                    member TEXT NOT NULL,
                    api TEXT NOT NULL,
                    source TEXT,
                    module TEXT NOT NULL,
                    source_set TEXT NOT NULL,
                    UNIQUE(resource_path, kind, class_id, member, api)
                )""");
            if (createIndexes) createIndexes(h);
            h.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        });
        return jdbi;
    }

    static void createIndexes(org.jdbi.v3.core.Handle h) {
        h.execute("CREATE INDEX IF NOT EXISTS idx_classes_name ON classes(class_name)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_classes_context ON classes(module, source_set)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_classes_file ON classes(file_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_occurrences_class ON class_occurrences(class_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_occurrences_name ON class_occurrences(class_name)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_occurrences_context ON class_occurrences(module, source_set)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_annotations_name ON class_annotations(annotation_name)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_annotations_class ON class_annotations(class_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_members_class ON class_members(class_id, kind, name)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_calls_from ON method_calls(from_class_id, from_method, from_descriptor)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_calls_to ON method_calls(to_class_id, to_method, to_descriptor)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_fields_from ON field_accesses(from_class_id, from_method)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_fields_to ON field_accesses(to_class_id, field_name, field_descriptor, access_kind)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_module_classpath_visible ON module_classpath(visible_module)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_files_repository_path ON files(repository_path)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_files_lifecycle ON files(lifecycle)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_ip_bean ON injection_points(bean_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_ip_resolved ON injection_points(resolved_bean_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_ip_status ON injection_points(resolution_status)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_dep_from ON dependencies(from_class_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_dep_to ON dependencies(to_class_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_gfs_class ON git_file_stats(class_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_gfs_count ON git_file_stats(commit_count DESC)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_gcf_commit ON git_commit_files(commit_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_gcf_class ON git_commit_files(class_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_gc_date ON git_commits(committed_at DESC)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_ced_class ON class_external_deps(class_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_ced_type ON class_external_deps(external_type)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_external_beans_name ON external_beans(class_name)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_external_beans_artifact ON external_beans(artifact)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_external_beans_scope ON external_beans(scope)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_cdip_class ON cdi_problems(class_id)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_config_def_key ON configuration_definitions(key)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_config_def_module ON configuration_definitions(module, source_set)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_config_use_key ON configuration_usages(key, kind)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_config_use_class ON configuration_usages(class_name)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_config_use_module ON configuration_usages(module, source_set)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_resource_use_path ON resource_usages(resource_path, kind)");
        h.execute("CREATE INDEX IF NOT EXISTS idx_resource_use_class ON resource_usages(class_name)");
    }

    private static void prepareExistingDatabase(Path dbPath) {
        if (!Files.exists(dbPath)) return;

        int existingVersion;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA user_version")) {
            existingVersion = result.next() ? result.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to inspect existing index at " + dbPath, e);
        }

        if (existingVersion > SCHEMA_VERSION) {
            throw new SchemaVersionException(
                    "Index at " + dbPath + " was created by a newer Quill (schema v"
                            + existingVersion + ", this build supports v" + SCHEMA_VERSION
                            + "). Upgrade Quill or delete the index.");
        }
        if (existingVersion == SCHEMA_VERSION) return;

        try {
            Files.deleteIfExists(dbPath);
            Files.deleteIfExists(Path.of(dbPath + "-wal"));
            Files.deleteIfExists(Path.of(dbPath + "-shm"));
            Files.deleteIfExists(Path.of(dbPath + "-journal"));
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to rebuild outdated index at " + dbPath, e);
        }
    }

    public static int currentSchemaVersion() {
        return SCHEMA_VERSION;
    }

    public static int inspectSchemaVersion(Path dbPath) {
        if (!Files.isRegularFile(dbPath)) return -1;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to inspect index schema at " + dbPath, e);
        }
    }

    public static Jdbi open(Path dbPath) {
        return openExisting(dbPath, true);
    }

    /** Opens an already-created staging database for an atomic incremental update. */
    public static Jdbi openWritable(Path dbPath) {
        return openExisting(dbPath, false);
    }

    private static Jdbi openExisting(Path dbPath, boolean readOnly) {
        if (!Files.exists(dbPath)) {
            throw new IllegalStateException(
                    "Index not found at " + dbPath + ". Run 'quill init' first.");
        }
        Jdbi jdbi = createJdbi(dbPath, readOnly);
        jdbi.useHandle(h -> {
            int version = h.createQuery("PRAGMA user_version")
                    .mapTo(Integer.class).one();
            if (version > SCHEMA_VERSION) {
                throw new SchemaVersionException(
                        "Index at " + dbPath + " was created by a newer Quill (schema v"
                                + version + ", this build supports v" + SCHEMA_VERSION
                                + "). Upgrade Quill or re-run 'quill init'.");
            }
            if (version < SCHEMA_VERSION) {
                throw new SchemaVersionException(
                        "Index at " + dbPath + " uses an outdated schema (v"
                                + version + ", current v" + SCHEMA_VERSION
                                + "). Re-run 'quill init' to rebuild.");
            }
        });
        return jdbi;
    }

    private static Jdbi createJdbi(Path dbPath, boolean readOnly) {
        Path absolutePath = dbPath.toAbsolutePath().normalize();
        String url = readOnly
                ? "jdbc:sqlite:" + absolutePath.toUri() + "?mode=ro"
                : "jdbc:sqlite:" + absolutePath;
        return Jdbi.create(() -> {
            var conn = DriverManager.getConnection(url);
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys=ON");
            } catch (SQLException e) {
                conn.close();
                throw e;
            }
            return conn;
        });
    }

    public static class SchemaVersionException extends RuntimeException {
        public SchemaVersionException(String message) {
            super(message);
        }
    }
}
