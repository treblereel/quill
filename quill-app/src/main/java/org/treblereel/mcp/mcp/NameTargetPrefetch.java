package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * Facts for a qualified name that is not an application class: an application package (the
 * beans declared directly in it) or a bean type supplied from dependencies (its providers).
 */
public final class NameTargetPrefetch {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMIT = 40;

    private NameTargetPrefetch() {
    }

    /** Beans declared in classes directly in {@code name}, without subpackages; null if no class is there. */
    static ObjectNode packageFact(ReadQueryContext db, String name) {
        List<Map<String, Object>> rows = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.class_name, c.source_set, b.kind, b.scope, b.qualifiers, b.member_name, b.bean_types
                FROM beans b JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE c.class_name LIKE :prefix AND c.lifecycle = 'current'
                  AND instr(substr(c.class_name, length(:package) + 2), '.') = 0
                ORDER BY c.source_set, b.kind, c.class_name, b.member_name""")
                .bind("prefix", name + ".%").bind("package", name).mapToMap().list());
        int classes = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT count(*) FROM classes WHERE class_name LIKE :prefix AND lifecycle = 'current'
                  AND instr(substr(class_name, length(:package) + 2), '.') = 0""")
                .bind("prefix", name + ".%").bind("package", name).mapTo(Integer.class).one());
        if (classes == 0) return null;
        ObjectNode fact = JSON.createObjectNode().put("kind", "package").put("package", name)
                .put("scope_note", "beans of classes directly in the package; subpackages excluded")
                .put("classes_in_package", classes);
        ArrayNode beans = fact.putArray("beans");
        rows.stream().limit(LIMIT).forEach(row -> beans.add(row.get("source_set") + " " + row.get("kind") + " "
                + row.get("class_name") + (row.get("member_name") == null ? "" : "#" + row.get("member_name"))
                + " " + row.get("scope") + " qualifiers " + row.get("qualifiers")
                + (row.get("member_name") == null ? "" : " types " + row.get("bean_types"))));
        if (rows.size() > LIMIT) fact.putObject("beans_truncated").put("shown", LIMIT).put("total", rows.size());
        return fact;
    }

    /** Application and dependency beans whose bean types include {@code type}; null if none. */
    static ObjectNode typeProviders(ReadQueryContext db, String type) {
        String quoted = "%\"" + type + "\"%";
        List<Map<String, Object>> external = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT class_name, kind, member_name, scope, qualifiers, artifact, framework, profiles
                FROM external_beans WHERE bean_types LIKE :type ORDER BY artifact, class_name, member_name""")
                .bind("type", quoted).mapToMap().list());
        List<Map<String, Object>> application = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.class_name, b.kind, b.member_name, b.scope, b.qualifiers
                FROM beans b JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE b.bean_types LIKE :type ORDER BY c.class_name, b.member_name""")
                .bind("type", quoted).mapToMap().list());
        if (external.isEmpty() && application.isEmpty()) return null;
        ObjectNode fact = JSON.createObjectNode().put("kind", "bean_type_providers").put("bean_type", type)
                .put("scope_note", "indexed bean declarations by type; eligibility and runtime selection not proven");
        if (!application.isEmpty()) {
            ArrayNode values = fact.putArray("application_providers");
            application.stream().limit(LIMIT).forEach(row -> values.add(provider(row) + " " + row.get("scope")
                    + " qualifiers " + row.get("qualifiers")));
        }
        if (!external.isEmpty()) {
            ArrayNode values = fact.putArray("dependency_providers");
            external.stream().limit(LIMIT).forEach(row -> values.add(provider(row) + " " + row.get("scope")
                    + " qualifiers " + row.get("qualifiers") + " from " + row.get("artifact")
                    + " (" + row.get("framework") + ")"
                    + ("[]".equals(String.valueOf(row.get("profiles"))) ? "" : " profiles " + row.get("profiles"))));
        }
        return fact;
    }

    /**
     * What a prompt naming no indexed target can still start from: modules with their class counts,
     * build entry files, and the direct dependencies of the indexed classpath.
     */
    public static ObjectNode projectFact(ReadQueryContext db, java.nio.file.Path root) throws Exception {
        ObjectNode fact = JSON.createObjectNode().put("kind", "project")
                .put("scope_note", "indexed modules, build files and direct dependencies; no question-specific facts");
        ArrayNode modules = fact.putArray("modules");
        db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT COALESCE(module, '.') AS module, COALESCE(source_set, 'unknown') AS source_set, count(*) AS classes
                FROM classes WHERE lifecycle = 'current' AND origin <> 'orphan_output'
                GROUP BY 1, 2 ORDER BY 1, 2""").mapToMap().list())
                .forEach(row -> modules.add(row.get("module") + " " + row.get("source_set") + " " + row.get("classes") + " classes"));
        ArrayNode builds = fact.putArray("build_files");
        db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT project_path FROM files WHERE lifecycle = 'current' AND (
                    project_path LIKE '%pom.xml' OR project_path LIKE '%build.gradle' OR project_path LIKE '%build.gradle.kts'
                    OR project_path LIKE '%settings.gradle' OR project_path LIKE '%settings.gradle.kts'
                    OR project_path IN ('mvnw', 'gradlew'))
                ORDER BY project_path LIMIT 40""").mapTo(String.class).list()).forEach(builds::add);
        var dependencies = JSON.readTree(new ProjectDependencyQueries().getProjectDependencies(
                db, root, null, null, null, "direct", null, null, null, 200, 0));
        ArrayNode direct = fact.putArray("direct_dependencies");
        for (var dependency : dependencies.path("dependencies")) {
            List<String> scopes = new java.util.ArrayList<>();
            dependency.path("declared_scopes").forEach(scope -> scopes.add(scope.asText()));
            direct.add(dependency.path("id").asText() + (scopes.isEmpty() ? "" : " " + scopes));
        }
        if (dependencies.path("total").asInt() > direct.size())
            fact.putObject("direct_dependencies_truncated").put("shown", direct.size()).put("total", dependencies.path("total").asInt());
        return fact;
    }

    private static String provider(Map<String, Object> row) {
        return row.get("kind") + " " + row.get("class_name")
                + (row.get("member_name") == null ? "" : "#" + row.get("member_name"));
    }
}
