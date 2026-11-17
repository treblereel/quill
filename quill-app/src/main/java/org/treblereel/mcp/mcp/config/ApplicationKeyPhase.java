package org.treblereel.mcp.mcp.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * When an application key is read: by its build-property guards at build time, or by its consumers
 * at run time. V66 CQ-039: framework_key_phases covered only quarkus.cache.enabled, and Codex read
 * ConfigProducer, CacheBuildConfig and CacheProcessor bytecode to prove that
 * cryostat.services.reports.memory-cache.enabled needs no rebuild.
 */
final class ApplicationKeyPhase {

    private static final Map<String, String> BUILD_GUARDS =
            Map.of(
                    "io.quarkus.arc.properties.IfBuildProperty", "@IfBuildProperty",
                    "io.quarkus.arc.properties.UnlessBuildProperty", "@UnlessBuildProperty");
    private static final int MAX_CONSUMERS = 4;

    private ApplicationKeyPhase() {}

    static void append(ReadQueryContext reader, String key, ObjectNode result) {
        if (key == null || key.isBlank() || key.contains("*") || key.startsWith("quarkus.")) return;
        List<String> guards = new ArrayList<>();
        reader.symbols
                .findClassAnnotationsNamingKey(key)
                .forEach(
                        row -> {
                            String label = BUILD_GUARDS.get(String.valueOf(row.annotationName()));
                            if (label != null) guards.add(label + " on " + row.className());
                        });
        reader.symbols
                .findMembersNamingKey(key)
                .forEach(
                        row ->
                                BUILD_GUARDS.forEach(
                                        (annotation, label) -> {
                                            if (String.valueOf(row.annotationDetails())
                                                    .contains("\"" + annotation + "\""))
                                                guards.add(
                                                        label
                                                                + " on "
                                                                + row.className()
                                                                + "#"
                                                                + row.name());
                                        }));
        var consumers = reader.references.findMainKeyConsumers(key);
        if (guards.isEmpty() && consumers.isEmpty()) return;
        List<String> injected = new ArrayList<>();
        List<String> programmatic = new ArrayList<>();
        java.util.Set<String> labels = new java.util.TreeSet<>();
        for (var consumer : consumers) {
            String annotation = String.valueOf(consumer.annotation());
            if (!annotation.contains("#"))
                labels.add("@" + annotation.substring(annotation.lastIndexOf('.') + 1));
            String target = consumer.className() + "#" + consumer.member();
            if (annotation.contains("#")) programmatic.add(target + " via " + annotation);
            else if ("DECORATOR".equals(consumer.kind()))
                injected.add(target + " (decorator, created with each decorated bean instance)");
            else
                injected.add(
                        target + (consumer.scope() == null ? "" : " (" + consumer.scope() + ")"));
        }
        List<String> parts = new ArrayList<>();
        if (!guards.isEmpty())
            parts.add(
                    "fixed at build time by "
                            + String.join(", ", guards)
                            + ": changing it there needs a rebuild");
        if (!injected.isEmpty())
            parts.add(
                    "read by "
                            + String.join("/", labels)
                            + " injection into "
                            + limited(injected)
                            + " at bean creation: runtime, no rebuild; a bean created once reads it once (restart to apply)");
        if (!programmatic.isEmpty())
            parts.add("read by " + limited(programmatic) + " each time that call runs");
        // Quarkus build guards only; Apollo v2: on a Spring @Value key the clause was noise.
        if (guards.isEmpty() && labels.contains("@ConfigProperty"))
            parts.add("no @IfBuildProperty or @UnlessBuildProperty names it");
        result.put("application_key_phase", key + ": " + String.join("; ", parts));
        appendReaderCallers(reader, consumers, result);
    }

    private static final int MAX_CALLERS = 6;

    /**
     * Production callers of each method reading the key programmatically: where the setting takes
     * effect. Apollo AQ-036 (Codex) grepped for the controllers calling
     * PortalConfig#isEmergencyPublishAllowed.
     */
    private static void appendReaderCallers(
            ReadQueryContext reader,
            List<org.treblereel.mcp.db.ReferenceRepository.KeyConsumer> consumers,
            ObjectNode result) {
        List<String> lines = new ArrayList<>();
        for (var consumer : consumers) {
            if (!String.valueOf(consumer.annotation()).contains("#") || consumer.member() == null)
                continue;
            List<String> callers =
                    reader.jdbi()
                            .withHandle(
                                    handle ->
                                            handle.createQuery(
                                                            """
                SELECT DISTINCT fc.class_name || '#' || mc.from_method FROM method_calls mc
                JOIN classes tc ON tc.id = mc.to_class_id JOIN classes fc ON fc.id = mc.from_class_id
                WHERE tc.class_name = :owner AND mc.to_method = :member
                  AND COALESCE(fc.source_set, 'main') NOT IN ('test', 'integration-test', 'integrationTest')
                ORDER BY 1""")
                                                    .bind("owner", consumer.className())
                                                    .bind("member", consumer.member())
                                                    .mapTo(String.class)
                                                    .list());
            if (callers.isEmpty()) continue;
            // One line per reader: the budget halves lists, and the reader repeated on every caller
            // made Apollo AQ-036 lose three of its six (Apollo AQ-036).
            String shown =
                    String.join(", ", callers.subList(0, Math.min(MAX_CALLERS, callers.size())));
            lines.add(
                    consumer.className()
                            + "#"
                            + consumer.member()
                            + " <- "
                            + shown
                            + (callers.size() > MAX_CALLERS
                                    ? " (+" + (callers.size() - MAX_CALLERS) + " more)"
                                    : ""));
        }
        if (!lines.isEmpty())
            result.set("reader_callers", org.treblereel.mcp.core.Json.MAPPER.valueToTree(lines));
    }

    private static String limited(List<String> values) {
        return String.join(", ", values.subList(0, Math.min(MAX_CONSUMERS, values.size())))
                + (values.size() > MAX_CONSUMERS
                        ? " (+" + (values.size() - MAX_CONSUMERS) + " more)"
                        : "");
    }
}
