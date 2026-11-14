package org.treblereel.mcp.db;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

import java.util.*;
import org.jdbi.v3.core.Handle;
import org.treblereel.mcp.model.*;

/** Read-only index queries; the caller owns the handle and transaction. */
public final class IndexRepository {
    private final Handle handle;
    private Map<String,String> metadata;

    public IndexRepository(Handle handle) { this.handle = Objects.requireNonNull(handle); }

    void useMetadata(Map<String,String> prepared) { metadata = prepared; }

    public Map<String, String> getMetadata() {
        if (metadata == null) metadata = Collections.unmodifiableMap(new LinkedHashMap<>(readMetadata()));
        return metadata;
    }

    private Map<String,String> readMetadata() {
        
            Map<String, String> result = new LinkedHashMap<>();
            handle.createQuery("SELECT key, value FROM metadata")
                    .map((rs, ctx) -> Map.entry(rs.getString("key"), rs.getString("value")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        
    }

    public Optional<FileRecord> findFileByPath(String path) {
        String normalized = path.replace('\\', '/');
        return handle.createQuery("""
                        SELECT * FROM files
                        WHERE project_path = :path OR repository_path = :path
                        ORDER BY CASE WHEN lifecycle = 'current' THEN 0 ELSE 1 END
                        LIMIT 1""")
                .bind("path", normalized)
                .map((rs, ctx) -> mapFile(rs))
                .findFirst();
    }

    public List<FileRecord> findFileCandidates(String target, int limit) {
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
        List<FileRecord> matches = handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(IndexRepository.class, "findFileCandidates"))
                .bind("javaPattern", javaPattern)
                .bind("kotlinPattern", kotlinPattern)
                .map((rs, ctx) -> mapFile(rs))
                .list();
        Map<String, FileRecord> unique = new LinkedHashMap<>();
        for (FileRecord match : matches) unique.putIfAbsent(match.repositoryPath(), match);
        return unique.values().stream().limit(limit).toList();
    }

    public Map<Integer, List<ClassOccurrenceRecord>> findClassOccurrencesByClassIds(Collection<Integer> classIds) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        
            Map<Integer, List<ClassOccurrenceRecord>> result = new LinkedHashMap<>();
            handle.createQuery("SELECT * FROM class_occurrences WHERE class_id IN (<ids>) "
                            + "ORDER BY class_id, module, source_set, output_directory, class_file")
                    .bindList("ids", new LinkedHashSet<>(classIds))
                    .map((rs, ctx) -> mapClassOccurrence(rs))
                    .forEach(record -> result.computeIfAbsent(record.classId(),
                            ignored -> new ArrayList<>()).add(record));
            result.replaceAll((ignored, values) -> List.copyOf(values));
            return result;
        
    }

    public List<ModuleClasspathRecord> findModuleClasspath(String applicationModule) {
        return handle.createQuery(
                        "SELECT application_module, visible_module, distance, relation "
                                + "FROM module_classpath WHERE application_module = :application "
                                + "ORDER BY distance, visible_module")
                .bind("application", applicationModule)
                .map((rs, ctx) -> new ModuleClasspathRecord(
                        rs.getString("application_module"), rs.getString("visible_module"),
                        rs.getInt("distance"), rs.getString("relation")))
                .list();
    }

    public List<ModuleClasspathRecord> findAllModuleClasspath() {
        return handle.createQuery(
                        "SELECT application_module, visible_module, distance, relation "
                                + "FROM module_classpath "
                                + "ORDER BY application_module, distance, visible_module")
                .map((rs, ctx) -> new ModuleClasspathRecord(
                        rs.getString("application_module"), rs.getString("visible_module"),
                        rs.getInt("distance"), rs.getString("relation")))
                .list();
    }

    public Set<String> findVisibleModules(String applicationModule) {
        return findModuleClasspath(applicationModule).stream()
                .map(ModuleClasspathRecord::visibleModule)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static ClassOccurrenceRecord mapClassOccurrence(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ClassOccurrenceRecord(
                rs.getInt("id"), rs.getInt("class_id"), rs.getString("class_name"),
                rs.getString("module"), rs.getString("source_set"),
                rs.getString("output_directory"), rs.getString("class_file"),
                rs.getString("source_file"), rs.getString("origin"));
    }

    private static FileRecord mapFile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FileRecord(rs.getInt("id"), rs.getString("project_path"),
                rs.getString("repository_path"), rs.getString("kind"),
                rs.getString("origin"), rs.getString("lifecycle"),
                rs.getString("worktree_status"), rs.getString("module"),
                rs.getString("source_set"));
    }
    public List<FileRow> findNavigationFiles( String base, boolean includeDeleted) {
        String prefix = base.equals(".") ? "" : base + "/";
        return handle.createQuery("""
                        SELECT repository_path, kind, origin, lifecycle, worktree_status,
                               module, source_set
                        FROM files
                        WHERE (:includeDeleted = 1 OR lifecycle = 'current')
                          AND (:prefix = '' OR repository_path = :base
                               OR repository_path LIKE :prefix || '%')
                        ORDER BY repository_path
                        """)
                .bind("includeDeleted", includeDeleted ? 1 : 0)
                .bind("prefix", prefix)
                .bind("base", base)
                .map((row, context) -> new FileRow(
                        row.getString("repository_path"), row.getString("kind"),
                        row.getString("origin"), row.getString("lifecycle"),
                        row.getString("worktree_status"), row.getString("module"),
                        row.getString("source_set")))
                .list();
    }
    public record FileRow(String path, String kind, String origin, String lifecycle,
            String worktreeStatus, String module, String sourceSet) {}
    /** Current Maven/Gradle build descriptors, as project-relative paths. */
    public List<String> findBuildDescriptors() {
        return handle.createQuery("""
                        SELECT project_path FROM files
                        WHERE lifecycle = 'current'
                          AND (project_path = 'pom.xml' OR project_path LIKE '%/pom.xml'
                               OR project_path LIKE 'build.gradle%' OR project_path LIKE '%/build.gradle%')
                        ORDER BY project_path
                        """).mapTo(String.class).list();
    }
    public List<String> findIndexedModules() {
        List<String> modules = handle.createQuery("""
                        SELECT DISTINCT module FROM (
                          SELECT module FROM files WHERE lifecycle = 'current'
                          UNION ALL
                          SELECT module FROM classes WHERE lifecycle = 'current'
                        ) WHERE module IS NOT NULL AND module != ''
                        ORDER BY module
                        """).mapTo(String.class).list();
        return modules.isEmpty() ? List.of(".") : List.copyOf(modules);
    }
    public List<String> findSemanticAnchors(String kind, String suffix) {
        return handle.createQuery("""
                SELECT MIN(repository_path) FROM files
                WHERE kind=:kind AND lifecycle='current' AND source_set='main'
                  AND repository_path LIKE :suffix
                GROUP BY module LIMIT 2
                """).bind("kind", kind).bind("suffix", suffix).mapTo(String.class).list();
    }

    public ComparisonSnapshot comparisonSnapshot() {
        Map<String, StringBuilder> classValues = new TreeMap<>();
            handle.createQuery("""
                    SELECT id, class_name, kind, superclass, interfaces, source_file,
                           is_bean, origin, module, source_set
                    FROM classes
                    WHERE lifecycle = 'current' AND origin != 'orphan_output'
                    ORDER BY class_name, id
                    """).map((rs, ctx) -> Map.entry(rs.getInt("id"), new ClassValue(
                            rs.getString("class_name"), String.join("|",
                                    value(rs.getString("kind")),
                                    value(rs.getString("superclass")),
                                    value(rs.getString("interfaces")),
                                    value(rs.getString("source_file")),
                                    Integer.toString(rs.getInt("is_bean")),
                                    value(rs.getString("origin")),
                                    value(rs.getString("module")),
                                    value(rs.getString("source_set"))))))
                    .forEach(entry -> classValues.put(entry.getValue().name(),
                            new StringBuilder(entry.getValue().fingerprint())));

        
        appendClassDetails( classValues, """
                SELECT c.class_name, 'A|' || a.annotation_name || '|' || a.direct || '|'
                       || COALESCE(a.via_annotation, '') AS detail
                FROM class_annotations a JOIN classes c ON c.id = a.class_id
                WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                ORDER BY c.class_name, detail
                """);
        appendClassDetails( classValues, """
                SELECT c.class_name, 'M|' || m.kind || '|' || m.signature || '|' || m.descriptor || '|'
                       || m.type_name || '|' || m.modifiers || '|' || m.annotations || '|' || m.annotation_details AS detail
                FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                ORDER BY c.class_name, detail
                """);
        appendClassDetails(classValues,"""
                SELECT c.class_name, 'JVM_API|' || a.evidence AS detail
                FROM jvm_api a JOIN classes c ON c.id=a.class_id
                WHERE c.lifecycle='current' AND c.origin!='orphan_output'
                ORDER BY c.class_name,detail
                """);
        Map<String, String> classes = new TreeMap<>();
        classValues.forEach((name, fingerprint) -> classes.put(name, fingerprint.toString()));

        Map<String, Integer> dependencies = new TreeMap<>();
            handle.createQuery("""
                    SELECT source.class_name AS source_name, target.class_name AS target_name,
                           d.kind, d.occurrence_count
                    FROM dependencies d
                    JOIN classes source ON source.id = d.from_class_id
                    JOIN classes target ON target.id = d.to_class_id
                    WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                      AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                    ORDER BY source_name, target_name, d.kind
                    """).map((rs, ctx) -> Map.entry(
                            rs.getString("source_name") + " -> "
                                    + rs.getString("target_name") + " ["
                                    + rs.getString("kind") + "]",
                            rs.getInt("occurrence_count")))
                    .forEach(entry -> dependencies.merge(entry.getKey(), entry.getValue(), Integer::sum));

        
        Set<String> beans = new TreeSet<>(handle.createQuery("""
                SELECT c.class_name || ' [' || b.kind || '] ' || COALESCE(b.member_name, '')
                FROM beans b JOIN classes c ON c.id = b.class_id
                WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                ORDER BY 1
                """).mapTo(String.class).list());
        Map<String, String> injections = new TreeMap<>();
            handle.createQuery("""
                    SELECT c.class_name, ip.kind, ip.target_type, ip.field_name,
                           ip.qualifiers, ip.resolution_status, ip.resolution_reason
                    FROM injection_points ip
                    JOIN beans b ON b.id = ip.bean_id
                    JOIN classes c ON c.id = b.class_id
                    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                    ORDER BY c.class_name, ip.kind, ip.target_type, ip.field_name
                    """).map((rs, ctx) -> Map.entry(
                            rs.getString("class_name") + "#"
                                    + value(rs.getString("field_name")) + " ["
                                    + rs.getString("kind") + ":"
                                    + rs.getString("target_type") + ":"
                                    + value(rs.getString("qualifiers")) + "]",
                            rs.getString("resolution_status") + ":"
                                    + value(rs.getString("resolution_reason"))))
                    .forEach(entry -> injections.put(entry.getKey(), entry.getValue()));

        
        return new ComparisonSnapshot(classes, dependencies, beans, injections);
    }
    private void appendClassDetails(
            Map<String, StringBuilder> classes, String sql) {
        handle.createQuery(sql)
                .map((rs, ctx) -> Map.entry(
                        rs.getString("class_name"), rs.getString("detail")))
                .forEach(entry -> {
                    StringBuilder fingerprint = classes.get(entry.getKey());
                    if (fingerprint != null) fingerprint.append('\n').append(entry.getValue());
                });
    }
    private static String value(String value) {
        return value == null ? "" : value;
    }
    private record ClassValue(String name, String fingerprint) {}
    public record ComparisonSnapshot(Map<String, String> classes, Map<String, Integer> dependencies,
                            Set<String> beans, Map<String, String> injections) {}

    /** Up to 40 current build entry files: POMs, Gradle build and settings scripts, and the Maven/Gradle wrappers. */
    public List<String> findBuildEntryFiles() {
        return handle.createQuery("""
                SELECT project_path FROM files WHERE lifecycle = 'current' AND (
                    project_path LIKE '%pom.xml' OR project_path LIKE '%build.gradle' OR project_path LIKE '%build.gradle.kts'
                    OR project_path LIKE '%settings.gradle' OR project_path LIKE '%settings.gradle.kts'
                    OR project_path IN ('mvnw', 'gradlew'))
                ORDER BY project_path LIMIT 40""").mapTo(String.class).list();
    }

    /** Up to three current META-INF/beans.xml files, main before test. */
    public List<String> findBeansXmlFiles() {
        return handle.createQuery("""
                SELECT project_path FROM files WHERE lifecycle='current' AND project_path LIKE '%META-INF/beans.xml'
                ORDER BY source_set, project_path LIMIT 3""").mapTo(String.class).list();
    }
}
