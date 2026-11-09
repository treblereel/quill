package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes what a prefetch fact restates: index bookkeeping, caveats that are the same in every
 * packet, zero counts and empty sections whose absence already says the same. In V21 these were
 * 43% of a packet. Gaps, non-zero counts, empty caller lists (no callers is an answer) and the
 * completeness verdict stay. Runs before delivered-fact identities are taken.
 */
final class PrefetchRestatements {

    /** Index bookkeeping and constant caveats; the packet instructions state their substance once. */
    private static final Set<String> ALWAYS = Set.of("symbol_id", "source_sha256", "_meta", "limitations", "language",
            "source_tokens", "members_included", "source_name", "runtime_execution_proven", "limit", "offset",
            "call_site_definition");
    private static final Map<String, String> DEFAULTS = Map.of("lifecycle", "current", "origin", "source",
            "source_set", "main", "module", ".");
    private static final List<String> SCOPE_NOTES = List.of("scope", "answer_scope", "test_scope", "answer_complete_scope");
    private static final String IMPACT_SCOPE = "direct static consumers in indexed bytecode; runtime dispatch, reflection and transitive reach not proven";

    private PrefetchRestatements() {
    }

    static void strip(ObjectNode fact) {
        fact.remove(ALWAYS);
        DEFAULTS.forEach((field, value) -> { if (value.equals(fact.path(field).asText(null))) fact.remove(field); });
        if (fact.path("jvm_name").equals(fact.path("class"))) fact.remove("jvm_name");
        for (String flag : List.of("has_more", "truncated")) if (fact.path(flag).isBoolean() && !fact.path(flag).asBoolean()) fact.remove(flag);
        if (fact.path("total").isIntegralNumber() && fact.path("total").equals(fact.path("showing"))) fact.remove(List.of("total", "showing"));
        if (!"direct_method_impact".equals(fact.path("kind").asText())) return;

        if (fact.path("coverage") instanceof ObjectNode coverage) {
            coverage.remove("not_covered");
            if (coverage.path("scan_complete").asBoolean() && coverage.path("incomplete_reasons").isEmpty()) fact.remove("coverage");
        }
        if (fact.has("counts") && allZero(fact.get("counts"), "files_including_declaration")) fact.remove("counts");
        if (fact.path("override_evidence").path("overrides").isEmpty()) fact.remove("override_evidence");
        if (fact.path("interface_contracts").isEmpty() && fact.path("interface_contracts_complete").asBoolean(true))
            fact.remove(List.of("interface_contracts", "interface_contracts_complete"));
        if (fact.path("unindexed_supertypes").isEmpty()) fact.remove("unindexed_supertypes");
        if (fact.path("indirect_production").isEmpty()) fact.remove(List.of("indirect_counts", "indirect_scope"));
        if (SCOPE_NOTES.stream().anyMatch(fact::has)) {
            fact.remove(SCOPE_NOTES);
            fact.put("scope", IMPACT_SCOPE);
        }
        JsonNode body = fact.path("selected_method_source");
        if (body.has("source")) fact.remove("lambda_evidence_gap");
        if (fact.path("class").equals(fact.path("target"))) fact.remove("class");
        if (fact.path("location") instanceof ObjectNode location && location.path("line").isNull()) {
            location.remove("basis");
            if (body.path("source_file").equals(location.path("path")) && body.path("source_line").isInt())
                location.put("line", body.path("source_line").asInt());
            else location.remove("line");
        }
        String file = fact.path("declaration_file").asText(null);
        if (file != null && (file.equals(fact.path("location").path("path").asText()) || file.equals(body.path("source_file").asText())))
            fact.remove("declaration_file");
    }

    private static boolean allZero(JsonNode node, String ignored) {
        if (node.isNumber()) return node.asLong() == 0;
        if (!node.isObject()) return true;
        for (var entry : node.properties()) {
            if (entry.getKey().equals(ignored)) continue;
            if (!allZero(entry.getValue(), ignored)) return false;
        }
        return true;
    }
}
