package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes what a prefetch fact restates: index bookkeeping, caveats that are the same in every
 * packet, zero counts and empty sections whose absence already says the same. In V21 these were 43%
 * of a packet. Gaps, non-zero counts, empty caller lists (no callers is an answer) and the
 * completeness verdict stay. Runs before delivered-fact identities are taken.
 */
public final class PrefetchRestatements {

    /**
     * Index bookkeeping and constant caveats; the packet instructions state their substance once.
     */
    private static final Set<String> ALWAYS =
            Set.of(
                    "symbol_id",
                    "source_sha256",
                    "_meta",
                    "limitations",
                    "language",
                    "source_tokens",
                    "members_included",
                    "source_name",
                    "runtime_execution_proven",
                    "limit",
                    "offset",
                    "call_site_definition");

    private static final Map<String, String> DEFAULTS =
            Map.of("lifecycle", "current", "origin", "source", "source_set", "main", "module", ".");
    private static final List<String> SCOPE_NOTES =
            List.of("scope", "answer_scope", "test_scope", "answer_complete_scope");
    private static final String IMPACT_SCOPE =
            "direct static consumers in indexed bytecode; runtime dispatch, reflection and transitive reach not proven";

    private PrefetchRestatements() {}

    static void strip(ObjectNode fact) {
        fact.remove(ALWAYS);
        DEFAULTS.forEach(
                (field, value) -> {
                    if (value.equals(fact.path(field).asText(null))) fact.remove(field);
                });
        if (fact.path("jvm_name").equals(fact.path("class"))) fact.remove("jvm_name");
        for (String flag : List.of("has_more", "truncated"))
            if (fact.path(flag).isBoolean() && !fact.path(flag).asBoolean()) fact.remove(flag);
        if (fact.path("total").isIntegralNumber()
                && fact.path("total").equals(fact.path("showing")))
            fact.remove(List.of("total", "showing"));
        if (!"direct_method_impact".equals(fact.path("kind").asText())) {
            stripClass(fact);
            return;
        }

        if (fact.path("coverage") instanceof ObjectNode coverage) {
            coverage.remove("not_covered");
            if (coverage.path("scan_complete").asBoolean()
                    && coverage.path("incomplete_reasons").isEmpty()) fact.remove("coverage");
        }
        if (fact.has("counts") && allZero(fact.get("counts"), "files_including_declaration"))
            fact.remove("counts");
        if (fact.path("override_evidence").path("overrides").isEmpty())
            fact.remove("override_evidence");
        if (fact.path("interface_contracts").isEmpty()
                && fact.path("interface_contracts_complete").asBoolean(true))
            fact.remove(List.of("interface_contracts", "interface_contracts_complete"));
        if (fact.path("unindexed_supertypes").isEmpty()) fact.remove("unindexed_supertypes");
        if (fact.path("indirect_production").isEmpty())
            fact.remove(List.of("indirect_counts", "indirect_scope"));
        if (SCOPE_NOTES.stream().anyMatch(fact::has)) {
            fact.remove(SCOPE_NOTES);
            fact.put("scope", IMPACT_SCOPE);
        }
        JsonNode body = fact.path("selected_method_source");
        if (body.has("source")) fact.remove("lambda_evidence_gap");
        if (fact.path("class").equals(fact.path("target"))) fact.remove("class");
        if (fact.path("location") instanceof ObjectNode location
                && location.path("line").isNull()) {
            location.remove("basis");
            if (body.path("source_file").equals(location.path("path"))
                    && body.path("source_line").isInt())
                location.put("line", body.path("source_line").asInt());
            else location.remove("line");
        }
        stripMethodTallies(fact);
        String file = fact.path("declaration_file").asText(null);
        if (file != null
                && (file.equals(fact.path("location").path("path").asText())
                        || file.equals(body.path("source_file").asText())))
            fact.remove("declaration_file");
    }

    /**
     * What every class fact would otherwise say: Object as supertype and bean type, the JDK's
     * annotation plumbing, one source occurrence, tallies of a listed dependency set, an unknown
     * line.
     */
    private static void stripClass(ObjectNode fact) {
        if ("java.lang.Object".equals(fact.path("superclass").asText(null)))
            fact.remove("superclass");
        removeEmpty(fact, "interfaces", "direct_implementations");
        if (fact.path("direct_implementation_count").asInt(-1) == 0
                && !fact.has("direct_implementations"))
            fact.remove(List.of("direct_implementation_count", "direct_implementations_truncated"));
        if (fact.path("meta_annotations") instanceof ArrayNode meta) {
            meta.removeIf(name -> name.asText().startsWith("java.lang.annotation."));
            if (meta.isEmpty()) fact.remove("meta_annotations");
        }
        if (fact.path("class_occurrences") instanceof ArrayNode occurrences
                && occurrences.size() == 1
                && "source".equals(occurrences.get(0).path("origin").asText()))
            fact.remove("class_occurrences");
        if (fact.path("external_dependencies").isArray()
                && !fact.path("external_dependencies_truncated").asBoolean())
            fact.remove(
                    List.of(
                            "external_dependency_breakdown",
                            "external_dependency_reference_count",
                            "external_dependency_type_count",
                            "external_dependencies_truncated"));
        if (fact.path("location") instanceof ObjectNode location
                && location.path("line").isNull()
                && location.path("path").equals(fact.path("source"))) fact.remove("location");
        if (fact.path("bean") instanceof ObjectNode bean) {
            if (bean.path("bean_types") instanceof ArrayNode types)
                types.removeIf(type -> type.asText().equals("java.lang.Object"));
            removeEmpty(bean, "stereotypes", "profiles");
            if (bean.path("alternative").isBoolean() && !bean.path("alternative").asBoolean())
                bean.remove("alternative");
        }
        JsonNode basis = fact.path("generated_classes_basis");
        for (JsonNode evidence : fact.path("generated_creation_evidence"))
            if (evidence instanceof ObjectNode object)
                for (String field : List.of("jar_modified", "jar_older_than_compiled_classes"))
                    if (basis.has(field) && basis.get(field).equals(object.get(field)))
                        object.remove(field);
    }

    /**
     * A complete answer's lists already say what its counts tally; JDK callees say nothing about
     * the change.
     */
    private static void stripMethodTallies(ObjectNode fact) {
        if (fact.hasNonNull("signature")) fact.remove("descriptor");
        boolean complete =
                fact.path("answer_complete").asBoolean() && !fact.path("has_more").asBoolean();
        if (complete) fact.remove("counts");
        if (fact.path("indirect_scope") instanceof ObjectNode scope) {
            for (String flag : List.of("has_more", "traversal_truncated"))
                if (scope.path(flag).isBoolean() && !scope.path(flag).asBoolean())
                    scope.remove(flag);
            if (!scope.has("has_more") && !scope.has("traversal_truncated"))
                fact.remove("indirect_counts");
        }
        if (fact.path("calls") instanceof ArrayNode calls) {
            calls.removeIf(
                    call ->
                            call.asText().endsWith("[special]")
                                    || call.asText().endsWith("[external]")
                                            && silent(call.asText()));
            if (calls.isEmpty()) fact.remove(List.of("calls", "calls_basis"));
        }
        if (fact.path("name_in_source") instanceof ObjectNode names
                && names.path("other").isEmpty()) names.remove("basis");
    }

    /**
     * JDK and logging-facade owners; other library callees, such as a JSON mapper, say what the
     * path runs.
     */
    private static final List<String> SILENT_OWNERS =
            List.of(
                    "java.",
                    "jdk.",
                    "sun.",
                    "com.sun.",
                    "org.jboss.logging.",
                    "org.slf4j.",
                    "org.apache.logging.log4j.",
                    "org.apache.commons.logging.");

    /** A call line is "depth owner#method...". */
    private static boolean silent(String call) {
        String owner = call.substring(call.indexOf(' ') + 1);
        return SILENT_OWNERS.stream().anyMatch(owner::startsWith);
    }

    private static void removeEmpty(ObjectNode node, String... fields) {
        for (String field : fields)
            if (node.path(field).isArray() && node.path(field).isEmpty()) node.remove(field);
    }

    /**
     * Added to the instructions of a packet the budget cut. A cut that does not say where the rest
     * is sends the agent searching for it (Anthropic, "Writing tools for agents": steer on
     * truncation); in every packet the same sentence sent Codex to get_context on a target it had
     * whole (Cryostat CQ-028: 0 calls and 33k tokens became 6 calls and 73k).
     */
    static final String CUT_POINTER =
            " For fields in context_omitted_fields and lists with a _truncated count, get_context or find_usages on the same target returns them.";

    /**
     * The packet's reading rules; caveats appear only when a fact they qualify is in the packet.
     */
    public static String instructions(JsonNode facts) {
        boolean framework = false, configuration = false;
        for (JsonNode fact : facts) {
            framework |= fact.has("framework_contract");
            configuration |= "configuration".equals(fact.path("kind").asText());
        }
        // The directive wording is deliberate. Rewritten as a bare fact ("repeating them returns
        // the
        // same facts", after the Claude Code hooks advice to state context as facts) it stopped
        // limiting re-checks: on the 20 Cryostat cases whose packet was otherwise byte-identical
        // Codex spent 12% more input tokens and made 129 Quill calls instead of 87, and Claude went
        // from answering CQ-035 off the packet to five more reads. Where the rest of a cut is goes
        // in
        // only when the budget cuts (PrefetchContext): CUT_POINTER.
        String text =
                "These are completed Quill reads for this index; reuse them without repeating. Query or read source only for missing, stale, ambiguous or truncated evidence.";
        if (framework)
            text +=
                    " framework_contract is documented framework behaviour that dependency jars do not add to.";
        if (framework || configuration)
            text += " Source declarations do not prove deployed values.";
        return text;
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
