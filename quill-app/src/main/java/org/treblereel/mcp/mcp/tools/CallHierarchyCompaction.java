package org.treblereel.mcp.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.core.Json;

/**
 * Removes what a call hierarchy restates about its own edges: the two-step path of a direct edge
 * (it is the caller and callee), the declared target of every dispatch entry (it is the callee), a
 * resolved declaration equal to it, and dispatch entries whose target is fixed to that callee.
 * Default endpoint origin and module are stated once. In V32 CQ-030 a transitive answer was 25K, a
 * third of it such restatement. Routes deeper than one step, other declarations, candidates and
 * non-default endpoints stay.
 */
final class CallHierarchyCompaction {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final Map<String, String> ENDPOINT_DEFAULTS =
            Map.of("origin", "source", "module", ".");
    private static final String BASIS = "bytecode_symbolic_reference";

    private CallHierarchyCompaction() {}

    /** Compacts a call hierarchy answer in place; error answers are returned as they are. */
    static ObjectNode compactAnswer(ObjectNode root) {
        if (root.has("error_code") || root.has("error")) return root;
        compact(root);
        return root;
    }

    static void compact(ObjectNode root) {
        referenceRoutes(root);
        edgeRestatements(root);
        Set<String> fixed = dispatch(root);
        boolean defaulted = false;
        for (JsonNode node : root.path("calls")) {
            if (!(node instanceof ObjectNode edge)) continue;
            if (edge.path("depth").asInt() == 1 && edge.path("path").size() == 2)
                edge.remove("path");
            if (fixed.contains(edge.path("dispatch_ref").asText(null))) edge.remove("dispatch_ref");
            for (String side : List.of("caller", "callee")) {
                if (!(edge.path(side) instanceof ObjectNode endpoint)) continue;
                for (var entry : ENDPOINT_DEFAULTS.entrySet()) {
                    if (entry.getValue().equals(endpoint.path(entry.getKey()).asText(null))) {
                        endpoint.remove(entry.getKey());
                        defaulted = true;
                    }
                }
            }
        }
        if (defaulted || root.has("endpoint_source_default")) {
            ObjectNode defaults = root.putObject("endpoint_defaults");
            if (defaulted) ENDPOINT_DEFAULTS.forEach(defaults::put);
            JsonNode source = root.remove("endpoint_source_default");
            if (source != null) defaults.set("source", source);
        }
    }

    private static final String CONVENTIONAL_SOURCE =
            "<module>/src/main/java/<outer class path>.java when an endpoint states no source";

    /**
     * Apollo AQ-027 (Codex): a 25-edge transitive answer was 28 KB. Each edge restated: an endpoint
     * source the module and class already give, a direction shared by every edge, the evidence
     * status and generation shared by every location, the caller's file as the location file, the
     * call usage and range kinds, and a single-line location's line, which evidence_lines lists.
     */
    private static void edgeRestatements(ObjectNode root) {
        var calls = root.path("calls");
        if (calls.isEmpty()) return;
        java.util.Set<String> directions = new java.util.HashSet<>();
        java.util.Set<String> statuses = new java.util.HashSet<>();
        java.util.Set<String> generations = new java.util.HashSet<>();
        for (JsonNode edge : calls) {
            directions.add(edge.path("traversal_direction").asText(""));
            JsonNode evidence = edge.path("source_evidence");
            if (evidence.isObject()) {
                statuses.add(evidence.path("status").asText(""));
                generations.add(evidence.path("generation").asText(""));
            }
        }
        boolean oneDirection = directions.size() == 1 && !directions.contains("");
        boolean oneStatus = statuses.size() == 1 && !statuses.contains("");
        boolean oneGeneration = generations.size() == 1 && !generations.contains("");
        boolean conventional = false;
        for (JsonNode node : calls) {
            if (!(node instanceof ObjectNode edge)) continue;
            if (oneDirection) edge.remove("traversal_direction");
            String callerFile = edge.path("caller").path("source").asText(null);
            if (edge.path("source_evidence") instanceof ObjectNode evidence) {
                if (oneStatus) evidence.remove("status");
                if (oneGeneration) evidence.remove("generation");
                JsonNode lines = edge.path("evidence_lines");
                for (JsonNode item : evidence.path("locations")) {
                    if (!(item instanceof ObjectNode location)) continue;
                    if (callerFile != null && callerFile.equals(location.path("file").asText()))
                        location.remove("file");
                    if ("call".equals(location.path("usage_kind").asText()))
                        location.remove("usage_kind");
                    if ("reference_expression".equals(location.path("range_kind").asText()))
                        location.remove("range_kind");
                    boolean oneLine =
                            location.path("end_line").asInt(-1) == location.path("line").asInt(-2);
                    if (oneLine) location.remove("end_line");
                    if (oneLine
                            && evidence.path("locations").size() == 1
                            && lines.size() == 1
                            && lines.get(0).asInt() == location.path("line").asInt())
                        location.remove("line");
                }
            }
            for (String side : List.of("caller", "callee")) {
                if (!(edge.path(side) instanceof ObjectNode endpoint)) continue;
                String className = endpoint.path("class").asText("");
                String outer =
                        className.contains("$")
                                ? className.substring(0, className.indexOf('$'))
                                : className;
                String module = endpoint.path("module").asText(".");
                String expected =
                        (module.equals(".") ? "" : module + "/")
                                + "src/main/java/"
                                + outer.replace('.', '/')
                                + ".java";
                if (!outer.isEmpty() && expected.equals(endpoint.path("source").asText())) {
                    endpoint.remove("source");
                    conventional = true;
                }
            }
        }
        if (oneDirection) {
            ObjectNode defaults =
                    root.path("edge_defaults") instanceof ObjectNode existing
                            ? existing
                            : root.putObject("edge_defaults");
            defaults.put("traversal_direction", directions.iterator().next());
        }
        if (oneStatus || oneGeneration) {
            ObjectNode defaults =
                    root.path("source_evidence_defaults") instanceof ObjectNode existing
                            ? existing
                            : root.putObject("source_evidence_defaults");
            if (oneStatus) defaults.put("status", statuses.iterator().next());
            if (oneGeneration)
                defaults.put("generation", Long.parseLong(generations.iterator().next()));
            defaults.put(
                    "location_defaults",
                    "file is the caller's source, usage_kind call, range_kind reference_expression,"
                            + " end_line equals line, and line is the edge's single evidence line, when absent");
        }
        if (conventional) root.put("endpoint_source_default", CONVENTIONAL_SOURCE);
    }

    /**
     * A route deeper than one step is the route of the edge reaching its previous node plus this
     * edge's reached endpoint; when that edge is on the page, it is named by index instead.
     */
    private static void referenceRoutes(ObjectNode root) {
        Map<String, Integer> byRoute = new java.util.HashMap<>();
        var calls = root.path("calls");
        for (int i = 0; i < calls.size(); i++)
            if (calls.get(i).path("path").isArray())
                byRoute.putIfAbsent(calls.get(i).path("path").toString(), i);
        boolean referenced = false;
        for (int i = 0; i < calls.size(); i++) {
            if (!(calls.get(i) instanceof ObjectNode edge) || edge.path("path").size() < 3)
                continue;
            var route = (com.fasterxml.jackson.databind.node.ArrayNode) edge.path("path");
            String reached =
                    "outbound".equals(edge.path("traversal_direction").asText())
                            ? "callee"
                            : "caller";
            JsonNode end = edge.path(reached);
            if (!route.get(route.size() - 1)
                    .asText()
                    .equals(
                            end.path("class").asText()
                                    + "#"
                                    + end.path("method").asText()
                                    + end.path("descriptor").asText())) continue;
            var parent = route.deepCopy();
            parent.remove(parent.size() - 1);
            Integer via = byRoute.get(parent.toString());
            if (via == null || via == i) continue;
            edge.remove("path");
            edge.put("via", via);
            referenced = true;
        }
        if (referenced)
            root.put(
                    "route_basis",
                    "via is the index in calls of the edge this one continues; "
                            + "its route is that edge's route plus this edge's caller (inbound) or callee (outbound)");
    }

    /**
     * Compacts dispatch_targets in place and returns the refs of entries removed as fixed to the
     * callee.
     */
    private static Set<String> dispatch(ObjectNode root) {
        Set<String> fixed = new HashSet<>();
        if (!(root.path("dispatch_targets") instanceof ObjectNode table)) return fixed;
        for (var entry : List.copyOf(table.properties())) {
            if (!(entry.getValue() instanceof ObjectNode target)) continue;
            if (BASIS.equals(target.path("target_basis").asText(null)))
                target.remove("target_basis");
            JsonNode declared = target.remove("declared_target");
            JsonNode resolved = target.path("resolved_declaration");
            if (declared != null
                    && resolved.path("class").equals(declared.path("class"))
                    && resolved.path("name").equals(declared.path("name"))
                    && resolved.path("descriptor").equals(declared.path("descriptor"))
                    && resolved.path("lookup_path").size() == 1
                    && resolved.path("lookup_path").get(0).equals(declared.path("class")))
                target.remove("resolved_declaration");
            if (target.path("possible_target_count").asInt(-1) == 0) target.remove("limit");
            if ("fixed_symbolic_target".equals(target.path("status").asText())
                    && "declared".equals(target.path("declaration_resolution").asText())
                    && !target.has("resolved_declaration")
                    && target.path("possible_target_count").asInt(0) == 0
                    && fieldsOnly(
                            target, "status", "declaration_resolution", "possible_target_count")) {
                table.remove(entry.getKey());
                fixed.add(entry.getKey());
            }
        }
        if (table.isEmpty()) root.remove("dispatch_targets");
        root.put(
                "dispatch_basis",
                "Bytecode symbolic targets of the callee shown; edges without dispatch_ref call exactly that callee");
        return fixed;
    }

    private static boolean fieldsOnly(ObjectNode node, String... allowed) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.of(allowed).containsAll(names);
    }
}
