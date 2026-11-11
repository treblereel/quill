package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes what a call hierarchy restates about its own edges: the two-step path of a direct edge
 * (it is the caller and callee), the declared target of every dispatch entry (it is the callee),
 * a resolved declaration equal to it, and dispatch entries whose target is fixed to that callee.
 * Default endpoint origin and module are stated once. In V32 CQ-030 a transitive answer was 25K,
 * a third of it such restatement. Routes deeper than one step, other declarations, candidates and
 * non-default endpoints stay.
 */
final class CallHierarchyCompaction {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> ENDPOINT_DEFAULTS = Map.of("origin", "source", "module", ".");
    private static final String BASIS = "bytecode_symbolic_reference";

    private CallHierarchyCompaction() {
    }

    static String compact(String json) {
        try {
            if (!(JSON.readTree(json) instanceof ObjectNode root) || root.has("error_code") || root.has("error")) return json;
            compact(root);
            return root.toString();
        } catch (JsonProcessingException invalid) {
            return json;
        }
    }

    static void compact(ObjectNode root) {
        Set<String> fixed = dispatch(root);
        boolean defaulted = false;
        for (JsonNode node : root.path("calls")) {
            if (!(node instanceof ObjectNode edge)) continue;
            if (edge.path("depth").asInt() == 1 && edge.path("path").size() == 2) edge.remove("path");
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
        if (defaulted) {
            ObjectNode defaults = root.putObject("endpoint_defaults");
            ENDPOINT_DEFAULTS.forEach(defaults::put);
        }
    }

    /** Compacts dispatch_targets in place and returns the refs of entries removed as fixed to the callee. */
    private static Set<String> dispatch(ObjectNode root) {
        Set<String> fixed = new HashSet<>();
        if (!(root.path("dispatch_targets") instanceof ObjectNode table)) return fixed;
        for (var entry : List.copyOf(table.properties())) {
            if (!(entry.getValue() instanceof ObjectNode target)) continue;
            if (BASIS.equals(target.path("target_basis").asText(null))) target.remove("target_basis");
            JsonNode declared = target.remove("declared_target");
            JsonNode resolved = target.path("resolved_declaration");
            if (declared != null && resolved.path("class").equals(declared.path("class"))
                    && resolved.path("name").equals(declared.path("name"))
                    && resolved.path("descriptor").equals(declared.path("descriptor"))
                    && resolved.path("lookup_path").size() == 1
                    && resolved.path("lookup_path").get(0).equals(declared.path("class")))
                target.remove("resolved_declaration");
            if (target.path("possible_target_count").asInt(-1) == 0) target.remove("limit");
            if ("fixed_symbolic_target".equals(target.path("status").asText()) && "declared".equals(target.path("declaration_resolution").asText())
                    && !target.has("resolved_declaration") && target.path("possible_target_count").asInt(0) == 0
                    && fieldsOnly(target, "status", "declaration_resolution", "possible_target_count")) {
                table.remove(entry.getKey());
                fixed.add(entry.getKey());
            }
        }
        if (table.isEmpty()) root.remove("dispatch_targets");
        root.put("dispatch_basis", "Bytecode symbolic targets of the callee shown; edges without dispatch_ref call exactly that callee");
        return fixed;
    }

    private static boolean fieldsOnly(ObjectNode node, String... allowed) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.of(allowed).containsAll(names);
    }
}
