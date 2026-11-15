package org.treblereel.mcp.mcp.config;

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
        List<String> guards = new ArrayList<>();
        reader.symbols.findClassAnnotationsNamingKey(key).forEach(row -> {
            String label = BUILD_GUARDS.get(String.valueOf(row.annotationName()));
            if (label != null) guards.add(label + " on " + row.className());
        });
        reader.symbols.findMembersNamingKey(key).forEach(row -> BUILD_GUARDS.forEach((annotation, label) -> {
            if (String.valueOf(row.annotationDetails()).contains("\"" + annotation + "\""))
                guards.add(label + " on " + row.className() + "#" + row.name());
        }));
        var consumers = reader.references.findMainKeyConsumers(key);
        if (guards.isEmpty() && consumers.isEmpty()) return;
        List<String> injected = new ArrayList<>();
        List<String> programmatic = new ArrayList<>();
        for (var consumer : consumers) {
            String annotation = String.valueOf(consumer.annotation());
            String target = consumer.className() + "#" + consumer.member();
            if (annotation.contains("#")) programmatic.add(target + " via " + annotation);
            else if ("DECORATOR".equals(consumer.kind())) injected.add(target + " (decorator, created with each decorated bean instance)");
            else injected.add(target + (consumer.scope() == null ? "" : " (" + consumer.scope() + ")"));
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
