package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;

/** Searches indexed configuration definitions and annotation-based consumers. */
final class ConfigurationReferenceQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KINDS = Set.of("all", "property", "prefix", "persistence");

    String find(Jdbi jdbi, String key, String className, String kind, String module,
            int limit, int offset) {
        String normalizedKind = kind == null ? "all" : kind.strip().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(normalizedKind)) {
            return errorResponse("Invalid kind: expected all, property, prefix, or persistence");
        }
        String detail = IndexReader.getMetadata(jdbi).get("configuration_references_detail");
        if (detail == null) {
            return errorResponse("Configuration references are unavailable; re-run 'quill init'");
        }
        try {
            JsonNode indexed = JSON.readTree(detail);
            Pattern keyPattern = wildcard(key);
            List<JsonNode> definitions = elements(indexed.path("definitions")).stream()
                    .filter(value -> matchesKey(value.path("key").asText(), keyPattern))
                    .filter(value -> matchesModule(value, module))
                    .filter(value -> matchesDefinitionKind(value, normalizedKind))
                    .toList();
            List<JsonNode> usages = elements(indexed.path("usages")).stream()
                    .filter(value -> matchesUsageKey(value, key, keyPattern))
                    .filter(value -> matchesClass(value, className))
                    .filter(value -> matchesModule(value, module))
                    .filter(value -> matchesUsageKind(value, normalizedKind))
                    .toList();

            if ((className != null && !className.isBlank())
                    || normalizedKind.equals("prefix")) {
                Set<String> usedKeys = usages.stream().map(value -> value.path("key").asText())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                definitions = definitions.stream()
                        .filter(value -> relevantDefinition(value, usedKeys, usages)).toList();
            }

            Map<String, List<JsonNode>> definitionsByKey = new HashMap<>();
            definitions.forEach(value -> definitionsByKey
                    .computeIfAbsent(value.path("key").asText(), ignored -> new ArrayList<>())
                    .add(value));
            List<Reference> references = new ArrayList<>();
            definitions.forEach(value -> references.add(new Reference("definition", value)));
            usages.forEach(value -> references.add(new Reference("usage", value)));
            references.sort(Comparator.comparing((Reference value) ->
                            value.value().path("key").asText())
                    .thenComparing(value -> value.type().equals("definition") ? 0 : 1)
                    .thenComparing(value -> value.value().path("file").asText(
                            value.value().path("className").asText())));

            int from = Math.min(offset, references.size());
            int to = Math.min(from + limit, references.size());
            ObjectNode result = JSON.createObjectNode();
            if (key == null || key.isBlank()) result.putNull("key");
            else result.put("key", key);
            if (className == null || className.isBlank()) result.putNull("class");
            else result.put("class", className);
            result.put("kind", normalizedKind);
            if (module == null || module.isBlank()) result.putNull("module");
            else result.put("module", module);
            ArrayNode values = result.putArray("references");
            for (Reference reference : references.subList(from, to)) {
                ObjectNode node = values.addObject();
                node.put("entry_type", reference.type());
                copy(reference.value(), node);
                if (reference.type().equals("usage")) {
                    List<JsonNode> matches = matchingDefinitions(
                            reference.value(), definitionsByKey, definitions);
                    node.put("resolved", !matches.isEmpty());
                    node.put("definition_count", matches.size());
                    ArrayNode files = node.putArray("definition_files");
                    matches.stream().map(value -> value.path("file").asText())
                            .distinct().sorted().forEach(files::add);
                }
            }
            result.put("definition_count", definitions.size());
            result.put("usage_count", usages.size());
            result.put("values_indexed", false);
            result.putArray("limitations")
                    .add("Configuration values are intentionally not stored")
                    .add("Consumers are limited to supported bytecode-visible annotations")
                    .add("Programmatic, environment-variable, YAML list, and dynamic key lookups are not resolved");
            appendPage(result, to - from, references.size(), limit, offset);
            appendMeta(result, jdbi, 0);
            return result.toString();
        } catch (RuntimeException | java.io.IOException error) {
            return errorResponse("Indexed configuration metadata is invalid");
        }
    }

    private static List<JsonNode> matchingDefinitions(JsonNode usage,
            Map<String, List<JsonNode>> byKey, List<JsonNode> all) {
        String key = usage.path("key").asText();
        if (usage.path("kind").asText().equals("config_prefix")) {
            return all.stream().filter(value -> {
                String candidate = value.path("key").asText();
                return candidate.equals(key) || candidate.startsWith(key + ".");
            }).toList();
        }
        return byKey.getOrDefault(key, List.of());
    }

    private static boolean relevantDefinition(
            JsonNode definition, Set<String> keys, List<JsonNode> usages) {
        String candidate = definition.path("key").asText();
        if (keys.contains(candidate)) return true;
        return usages.stream().anyMatch(usage -> usage.path("kind").asText()
                .equals("config_prefix") && (candidate.equals(usage.path("key").asText())
                        || candidate.startsWith(usage.path("key").asText() + ".")));
    }

    private static boolean matchesDefinitionKind(JsonNode value, String kind) {
        String indexed = value.path("kind").asText();
        return kind.equals("all")
                || kind.equals("property") && !indexed.equals("persistence_unit")
                || kind.equals("prefix") && !indexed.equals("persistence_unit")
                || kind.equals("persistence") && indexed.equals("persistence_unit");
    }

    private static boolean matchesUsageKind(JsonNode value, String kind) {
        String indexed = value.path("kind").asText();
        return kind.equals("all")
                || kind.equals("property") && indexed.equals("config_key")
                || kind.equals("prefix") && indexed.equals("config_prefix")
                || kind.equals("persistence") && indexed.equals("persistence_unit");
    }

    private static boolean matchesClass(JsonNode value, String className) {
        if (className == null || className.isBlank()) return true;
        String candidate = value.path("className").asText();
        return candidate.equals(className) || candidate.endsWith("." + className);
    }

    private static boolean matchesModule(JsonNode value, String module) {
        return module == null || module.isBlank() || module.equals(value.path("module").asText());
    }

    private static boolean matchesKey(String candidate, Pattern pattern) {
        return pattern == null || pattern.matcher(candidate).matches();
    }

    private static boolean matchesUsageKey(JsonNode usage, String requested, Pattern pattern) {
        String indexed = usage.path("key").asText();
        if (matchesKey(indexed, pattern)) return true;
        if (!usage.path("kind").asText().equals("config_prefix")
                || requested == null || requested.isBlank()) {
            return false;
        }
        int wildcard = requested.indexOf('*');
        String literalPrefix = (wildcard < 0 ? requested : requested.substring(0, wildcard))
                .strip();
        return literalPrefix.equals(indexed) || literalPrefix.startsWith(indexed + ".");
    }

    private static Pattern wildcard(String value) {
        if (value == null || value.isBlank()) return null;
        StringBuilder regex = new StringBuilder("^");
        for (char character : value.toCharArray()) {
            if (character == '*') regex.append(".*");
            else regex.append(Pattern.quote(String.valueOf(character)));
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static List<JsonNode> elements(JsonNode array) {
        if (!array.isArray()) return List.of();
        List<JsonNode> result = new ArrayList<>();
        array.forEach(result::add);
        return result;
    }

    private static void copy(JsonNode source, ObjectNode target) {
        source.properties().forEach(entry -> target.set(entry.getKey(), entry.getValue()));
    }

    private record Reference(String type, JsonNode value) {}
}
