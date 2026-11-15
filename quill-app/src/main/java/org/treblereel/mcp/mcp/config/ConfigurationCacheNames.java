package org.treblereel.mcp.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * Quarkus caches a consumer class names with @CacheName or @CacheResult/@CacheInvalidate*, linked to their
 * quarkus.cache.caffeine.NAME.* declarations. V65 CQ-050: the class query for AnalysisReportAggregator came back empty
 * although its reports-aggregator cache expires by configuration.
 */
final class ConfigurationCacheNames {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> ANNOTATIONS = Map.of(
            "io.quarkus.cache.CacheName", "value",
            "io.quarkus.cache.CacheResult", "cacheName",
            "io.quarkus.cache.CacheInvalidate", "cacheName",
            "io.quarkus.cache.CacheInvalidateAll", "cacheName");
    private static final int MAX_DEFINITIONS = 8;

    private ConfigurationCacheNames() {
    }

    static void append(ReadQueryContext db, ObjectNode result, String className, ConfigurationSourceEvidence source) throws Exception {
        if (className == null || className.isBlank()) return;
        // V66 CQ-050: a simple name, as the usage filter accepts it, found no cache and Claude grepped for it.
        var members = db.symbols.findCacheAnnotatedMembers(className.strip());
        Map<String, String> caches = new LinkedHashMap<>();
        for (var member : members) {
            for (JsonNode annotation : JSON.readTree(String.valueOf(member.annotationDetails()))) {
                String attribute = ANNOTATIONS.get(annotation.path("annotationName").asText());
                String name = attribute == null ? "" : annotation.path("values").path(attribute).asText("");
                String label = annotation.path("annotationName").asText().substring("io.quarkus.cache.".length());
                if (!name.isBlank()) caches.merge(name, member.name() + " @" + label, (a, b) -> a + ", " + b);
            }
        }
        if (caches.isEmpty()) return;
        ArrayNode out = result.putArray("cache_configuration");
        for (var cache : caches.entrySet()) {
            ObjectNode node = out.addObject().put("cache", cache.getKey()).put("member", cache.getValue());
            var definitions = db.references.findCaffeineCacheDeclarations(cache.getKey());
            ArrayNode rows = node.putArray("definitions");
            for (var definition : definitions.subList(0, Math.min(MAX_DEFINITIONS, definitions.size()))) {
                ObjectNode row = rows.addObject().put("key", String.valueOf(definition.key()))
                        .put("file", String.valueOf(definition.file())).put("line", definition.line().intValue());
                if (!"main".equals(definition.sourceSet())) row.put("sourceSet", String.valueOf(definition.sourceSet()));
                if (source != null) source.append(row, row.path("key").asText(), row.path("file").asText(), row.path("line").asInt(),
                        String.valueOf(definition.kind()), String.valueOf(definition.evidence()));
            }
            if (definitions.size() > MAX_DEFINITIONS) node.put("definitions_truncated", true);
            if (definitions.isEmpty()) node.put("status", "no indexed quarkus.cache.caffeine." + cache.getKey() + ".* declaration; extension defaults apply");
        }
    }
}
