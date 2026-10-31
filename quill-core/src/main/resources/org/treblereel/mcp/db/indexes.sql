CREATE INDEX IF NOT EXISTS idx_classes_name ON classes(class_name);

CREATE INDEX IF NOT EXISTS idx_classes_context ON classes(module, source_set);

CREATE INDEX IF NOT EXISTS idx_classes_file ON classes(file_id);

CREATE INDEX IF NOT EXISTS idx_occurrences_class ON class_occurrences(class_id);

CREATE INDEX IF NOT EXISTS idx_occurrences_name ON class_occurrences(class_name);

CREATE INDEX IF NOT EXISTS idx_occurrences_context ON class_occurrences(module, source_set);

CREATE INDEX IF NOT EXISTS idx_annotations_name ON class_annotations(annotation_name);

CREATE INDEX IF NOT EXISTS idx_annotations_class ON class_annotations(class_id);

CREATE INDEX IF NOT EXISTS idx_members_class ON class_members(class_id, kind, name);

CREATE INDEX IF NOT EXISTS idx_kotlin_declarations_class ON kotlin_declarations(class_id, kind, name);

CREATE INDEX IF NOT EXISTS idx_calls_to ON method_calls(to_class_id, to_method, to_descriptor);

CREATE INDEX IF NOT EXISTS idx_fields_to ON field_accesses(to_class_id, field_name, field_descriptor, access_kind);

CREATE INDEX IF NOT EXISTS idx_module_classpath_visible ON module_classpath(visible_module);

CREATE INDEX IF NOT EXISTS idx_files_repository_path ON files(repository_path);

CREATE INDEX IF NOT EXISTS idx_files_lifecycle ON files(lifecycle);

CREATE INDEX IF NOT EXISTS idx_ip_bean ON injection_points(bean_id);

CREATE INDEX IF NOT EXISTS idx_ip_resolved ON injection_points(resolved_bean_id);

CREATE INDEX IF NOT EXISTS idx_ip_status ON injection_points(resolution_status);

CREATE INDEX IF NOT EXISTS idx_dep_from ON dependencies(from_class_id);

CREATE INDEX IF NOT EXISTS idx_dep_to ON dependencies(to_class_id);

CREATE INDEX IF NOT EXISTS idx_gfs_class ON git_file_stats(class_id);

CREATE INDEX IF NOT EXISTS idx_gfs_count ON git_file_stats(commit_count DESC);

CREATE INDEX IF NOT EXISTS idx_gcf_commit ON git_commit_files(commit_id);

CREATE INDEX IF NOT EXISTS idx_gcf_class ON git_commit_files(class_id);

CREATE INDEX IF NOT EXISTS idx_gc_date ON git_commits(committed_at DESC);

CREATE INDEX IF NOT EXISTS idx_ced_class ON class_external_deps(class_id);

CREATE INDEX IF NOT EXISTS idx_ced_type ON class_external_deps(external_type);

CREATE INDEX IF NOT EXISTS idx_external_beans_name ON external_beans(class_name);

CREATE INDEX IF NOT EXISTS idx_external_beans_artifact ON external_beans(artifact);

CREATE INDEX IF NOT EXISTS idx_external_beans_scope ON external_beans(scope);

CREATE INDEX IF NOT EXISTS idx_cdip_class ON cdi_problems(class_id);

CREATE INDEX IF NOT EXISTS idx_config_def_key ON configuration_definitions(key);

CREATE INDEX IF NOT EXISTS idx_config_def_module ON configuration_definitions(module, source_set);

CREATE INDEX IF NOT EXISTS idx_config_use_key ON configuration_usages(key, kind);

CREATE INDEX IF NOT EXISTS idx_config_use_class ON configuration_usages(class_name);

CREATE INDEX IF NOT EXISTS idx_config_use_module ON configuration_usages(module, source_set);

CREATE INDEX IF NOT EXISTS idx_resource_use_path ON resource_usages(resource_path, kind);

CREATE INDEX IF NOT EXISTS idx_resource_use_class ON resource_usages(class_name);
