package org.treblereel.mcp.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.treblereel.mcp.core.Json;

/**
 * Removes what class graphs restate: a source file at the conventional path of its top-level class
 * (src/main|test/java|kotlin/&lt;package path&gt;/&lt;Outer&gt;.java|kt), source set main, and cycle edges
 * spelled out as objects. In V41 CQ-030 find_cycles and get_dependencies answers were about 30 KB.
 */
final class GraphCompaction {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final List<String> ROOTS = List.of("src/main/java/", "src/test/java/", "src/main/kotlin/", "src/test/kotlin/");

    private GraphCompaction() {
    }

    static boolean conventional(String file, String className) {
        if (file == null || className == null) return false;
        String path = className.split("\\$", 2)[0].replace('.', '/');
        for (String root : ROOTS)
            if (file.equals(root + path + (root.endsWith("kotlin/") ? ".kt" : ".java"))) return true;
        return false;
    }

    static String cycles(String json) {
        try {
            if (!(JSON.readTree(json) instanceof ObjectNode root) || !root.path("cycles").isArray()) return json;
            boolean changed = false;
            for (JsonNode node : root.path("cycles")) {
                if (!(node instanceof ObjectNode cycle)) continue;
                if (cycle.path("members") instanceof ArrayNode members) {
                    hoistShared(cycle, members);
                    ArrayNode compact = JSON.createArrayNode();
                    for (JsonNode member : members) {
                        if (member instanceof ObjectNode object && object.size() <= 2 && object.path("class").isTextual()
                                && (object.size() == 1 || conventional(object.path("source_file").asText(null), object.path("class").asText()))) {
                            compact.add(object.path("class").asText());
                            changed = true;
                        } else compact.add(member);
                    }
                    cycle.set("members", compact);
                }
                if (cycle.path("edges") instanceof ArrayNode edges) {
                    ArrayNode compact = JSON.createArrayNode();
                    for (JsonNode edge : edges) {
                        if (edge instanceof ObjectNode object && object.size() == 3 && object.path("from").isTextual()
                                && object.path("to").isTextual() && object.path("kinds").isArray()) {
                            List<String> kinds = object.path("kinds").valueStream().map(JsonNode::asText).toList();
                            compact.add(object.path("from").asText() + " -> " + object.path("to").asText() + " " + kinds);
                            changed = true;
                        } else compact.add(edge);
                    }
                    cycle.set("edges", compact);
                }
            }
            if (!changed) return json;
            root.put("graph_format", "A member named alone has its source_file at the conventional path of its top-level "
                    + "class; an edge reads 'from -> to [kinds]'");
            return root.toString();
        } catch (JsonProcessingException invalid) {
            return json;
        }
    }

    /** Values every member of a cycle shares (module, source set, origin) are stated once in members_common. */
    private static void hoistShared(ObjectNode cycle, ArrayNode members) {
        if (members.size() < 2 || !(members.get(0) instanceof ObjectNode first)) return;
        for (String field : List.copyOf(first.propertyStream().map(java.util.Map.Entry::getKey).toList())) {
            if (field.equals("class") || field.equals("source_file")) continue;
            JsonNode value = first.get(field);
            if (!members.valueStream().allMatch(member -> member instanceof ObjectNode && value.equals(member.get(field)))) continue;
            cycle.withObjectProperty("members_common").set(field, value);
            members.forEach(member -> ((ObjectNode) member).remove(field));
        }
    }

    static String dependencies(String json) {
        try {
            if (!(JSON.readTree(json) instanceof ObjectNode root) || !root.path("graph").isArray()) return json;
            boolean evidence = false, sourceSet = false;
            for (JsonNode node : root.path("graph")) {
                if (!(node instanceof ObjectNode edge)) continue;
                String holder = "inbound".equals(edge.path("relation_direction").asText())
                        ? edge.path("class").asText(null) : edge.path("parent").asText(null);
                if (conventional(edge.path("evidence_file").asText(null), holder)) {
                    edge.remove("evidence_file");
                    evidence = true;
                }
                if ("main".equals(edge.path("source_set").asText(null))) {
                    edge.remove("source_set");
                    sourceSet = true;
                }
            }
            if (!evidence && !sourceSet) return json;
            ObjectNode defaults = root.putObject("node_defaults");
            if (sourceSet) defaults.put("source_set", "main");
            if (evidence) defaults.put("evidence_file", "Omitted when it is the conventional source path of the class "
                    + "holding the reference: the parent for outbound, the class for inbound");
            return root.toString();
        } catch (JsonProcessingException invalid) {
            return json;
        }
    }
}
