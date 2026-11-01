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
);

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
);

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
);

CREATE TABLE IF NOT EXISTS class_annotations (
    class_id INTEGER NOT NULL REFERENCES classes(id),
    annotation_name TEXT NOT NULL,
    direct INTEGER NOT NULL,
    via_annotation TEXT,
    annotation_values TEXT,
    UNIQUE(class_id, annotation_name, direct, via_annotation)
);

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
);

CREATE TABLE IF NOT EXISTS jvm_api (class_id INTEGER PRIMARY KEY REFERENCES classes(id) ON DELETE CASCADE, evidence TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS kotlin_source_debug (class_id INTEGER PRIMARY KEY REFERENCES classes(id) ON DELETE CASCADE, data TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS kotlin_declarations (
    class_id INTEGER NOT NULL REFERENCES classes(id),
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    jvm_name TEXT,
    descriptor TEXT NOT NULL DEFAULT '',
    semantic_model TEXT NOT NULL,
    is_suspend INTEGER NOT NULL DEFAULT 0,
    is_extension INTEGER NOT NULL DEFAULT 0,
    has_default_parameters INTEGER NOT NULL DEFAULT 0,
    is_mutable INTEGER NOT NULL DEFAULT 0,
    is_lateinit INTEGER NOT NULL DEFAULT 0,
    is_delegated INTEGER NOT NULL DEFAULT 0,
    is_synthetic INTEGER NOT NULL DEFAULT 0,
    value_parameters TEXT,
    declaration_types TEXT,
    UNIQUE(class_id, kind, name, jvm_name, descriptor)
);

CREATE TABLE IF NOT EXISTS method_evidence (
    from_class_id INTEGER NOT NULL REFERENCES classes(id),
    from_method TEXT NOT NULL,
    from_descriptor TEXT NOT NULL,
    caller_branch_count INTEGER NOT NULL DEFAULT 0,
    caller_exception_handler_count INTEGER NOT NULL DEFAULT 0,
    caller_control_flow_edges TEXT NOT NULL DEFAULT '[]',
    caller_async_boundaries TEXT NOT NULL DEFAULT '[]',
    caller_external_calls TEXT NOT NULL DEFAULT '[]',
    PRIMARY KEY(from_class_id, from_method, from_descriptor)
);

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
    PRIMARY KEY(from_class_id, from_method, from_descriptor,
           to_class_id, to_method, to_descriptor, invocation_kind)
) WITHOUT ROWID;

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
    PRIMARY KEY(from_class_id, from_method, from_descriptor,
           to_class_id, field_name, field_descriptor, access_kind)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS module_classpath (
    application_module TEXT NOT NULL,
    visible_module TEXT NOT NULL,
    distance INTEGER NOT NULL,
    relation TEXT NOT NULL,
    PRIMARY KEY(application_module, visible_module)
);

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
);

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
);

CREATE TABLE IF NOT EXISTS dependencies (
    from_class_id INTEGER NOT NULL REFERENCES classes(id),
    to_class_id INTEGER NOT NULL REFERENCES classes(id),
    kind TEXT NOT NULL,
    injection_point_id INTEGER REFERENCES injection_points(id),
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    evidence_lines TEXT NOT NULL DEFAULT '[]'
);

CREATE TABLE IF NOT EXISTS metadata (
    key TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE IF NOT EXISTS git_file_stats (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path TEXT NOT NULL,
    class_id INTEGER REFERENCES classes(id),
    commit_count INTEGER NOT NULL DEFAULT 0,
    last_modified TEXT,
    last_author TEXT,
    first_commit TEXT,
    distinct_authors INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS git_commits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hash TEXT NOT NULL UNIQUE,
    short_hash TEXT NOT NULL,
    author TEXT NOT NULL,
    author_email TEXT,
    committed_at TEXT NOT NULL,
    message TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS git_commit_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    commit_id INTEGER NOT NULL REFERENCES git_commits(id),
    class_id INTEGER REFERENCES classes(id),
    file_path TEXT NOT NULL,
    change_type TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS class_external_deps (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id INTEGER NOT NULL REFERENCES classes(id),
    external_type TEXT NOT NULL,
    usage_kind TEXT NOT NULL
);

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
);

CREATE TABLE IF NOT EXISTS cdi_problems (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id INTEGER REFERENCES classes(id),
    class_name TEXT NOT NULL,
    problem_type TEXT NOT NULL,
    message TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS configuration_definitions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key TEXT NOT NULL,
    kind TEXT NOT NULL,
    file TEXT NOT NULL,
    line INTEGER NOT NULL,
    module TEXT NOT NULL,
    source_set TEXT NOT NULL,
    evidence TEXT NOT NULL DEFAULT '{}',
    UNIQUE(key, kind, file, line, module, source_set, evidence)
);

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
    module TEXT,
    source_set TEXT,
    UNIQUE(key, kind, class_id, member, parameter_index, annotation)
);

CREATE TABLE IF NOT EXISTS resource_usages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    resource_path TEXT NOT NULL,
    kind TEXT NOT NULL,
    class_id INTEGER NOT NULL REFERENCES classes(id),
    class_name TEXT NOT NULL,
    member TEXT NOT NULL,
    api TEXT NOT NULL,
    source TEXT,
    module TEXT,
    source_set TEXT,
    UNIQUE(resource_path, kind, class_id, member, api)
);
