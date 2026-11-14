package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * When an application key is read: by its build-property guards at build time, or by its consumers at run time.
 * V66 CQ-039: framework_key_phases covered only quarkus.cache.enabled, and Codex read ConfigProducer, CacheBuildConfig
 * and CacheProcessor bytecode to prove that cryostat.services.reports.memory-cache.enabled needs no rebuild.
 */
final class ApplicationKeyPhase {

    private static final Map<String, String> BUILD_GUARDS = Map.of(
            "io.quarkus.arc.properties.IfBuildProperty", "@IfBuildProperty",
            "io.quarkus.arc.properties.UnlessBuildProperty", "@UnlessBuildProperty");
    private static final int MAX_CONSUMERS = 4;

    private ApplicationKeyPhase() {
    }

    static void append(ReadQueryContext reader, String key, ObjectNode result) {
        if (key == null || key.isBlank() || key.contains("*") || key.startsWith("quarkus.")) return;
        String needle = "%\"name\":\"" + key.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "\"%";
        List<String> guards = reader.jdbi().withHandle(h -> {
            List<String> rows = new ArrayList<>();
            h.createQuery("""
                    SELECT c.class_name, a.annotation_name FROM class_annotations a JOIN classes c ON c.id = a.class_id
                    WHERE a.annotation_values LIKE :needle ESCAPE '!' ORDER BY c.class_name""")
                    .bind("needle", needle).mapToMap()
                    .forEach(row -> {
                        String label = BUILD_GUARDS.get(String.valueOf(row.get("annotation_name")));
                        if (label != null) rows.add(label + " on " + row.get("class_name"));
                    });
            h.createQuery("""
                    SELECT c.class_name, m.name, m.annotation_details FROM class_members m JOIN classes c ON c.id = m.class_id
                    WHERE m.annotation_details LIKE :needle ESCAPE '!' ORDER BY c.class_name, m.name""")
                    .bind("needle", needle).mapToMap()
                    .forEach(row -> BUILD_GUARDS.forEach((annotation, label) -> {
                        if (String.valueOf(row.get("annotation_details")).contains("\"" + annotation + "\""))
                            rows.add(label + " on " + row.get("class_name") + "#" + row.get("name"));
                    }));
            return rows;
        });
        List<Map<String, Object>> consumers = reader.jdbi().withHandle(h -> h.createQuery("""
                SELECT DISTINCT u.class_name, u.member, u.annotation, b.scope, b.kind FROM configuration_usages u
                LEFT JOIN beans b ON b.class_id = u.class_id AND b.member_name IS NULL
                WHERE u.key = :key AND u.source_set = 'main' ORDER BY u.class_name, u.member""")
                .bind("key", key).mapToMap().list());
        if (guards.isEmpty() && consumers.isEmpty()) return;
        List<String> injected = new ArrayList<>();
        List<String> programmatic = new ArrayList<>();
        for (var consumer : consumers) {
            String annotation = String.valueOf(consumer.get("annotation"));
            String target = consumer.get("class_name") + "#" + consumer.get("member");
            if (annotation.contains("#")) programmatic.add(target + " via " + annotation);
            else if ("DECORATOR".equals(consumer.get("kind"))) injected.add(target + " (decorator, created with each decorated bean instance)");
            else injected.add(target + (consumer.get("scope") == null ? "" : " (" + consumer.get("scope") + ")"));
        }
        List<String> parts = new ArrayList<>();
        if (!guards.isEmpty())
            parts.add("fixed at build time by " + String.join(", ", guards) + ": changing it there needs a rebuild");
        if (!injected.isEmpty())
            parts.add("read by @ConfigProperty injection into " + limited(injected) + " when each bean instance is created, "
                    + "from runtime config sources: no rebuild; a normal-scoped or @Singleton bean reads it once, so a change "
                    + "applies after a restart, a @Dependent one on each new instance");
        if (!programmatic.isEmpty()) parts.add("read by " + limited(programmatic) + " each time that call runs");
        if (guards.isEmpty()) parts.add("no @IfBuildProperty or @UnlessBuildProperty names it");
        result.put("application_key_phase", key + ": " + String.join("; ", parts));
    }

    private static String limited(List<String> values) {
        return String.join(", ", values.subList(0, Math.min(MAX_CONSUMERS, values.size())))
                + (values.size() > MAX_CONSUMERS ? " (+" + (values.size() - MAX_CONSUMERS) + " more)" : "");
    }
}
