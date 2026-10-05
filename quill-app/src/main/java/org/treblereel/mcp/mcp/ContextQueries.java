package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.model.ClassRecord;

/** Composes the most useful evidence for understanding or changing a small set of classes. */
final class ContextQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TARGETS = 10;
    private static final List<String> SYMBOL_FIELDS = List.of(
            "symbol_id", "language", "source_name", "jvm_name", "jvm_descriptor",
            "class", "kind", "superclass", "interfaces", "source", "origin", "lifecycle",
            "module", "source_set", "annotations", "meta_annotations", "bean",
            "direct_implementation_count", "direct_implementations",
            "direct_implementations_truncated", "external_dependency_reference_count",
            "external_dependency_type_count", "external_dependency_breakdown",
            "external_dependencies", "external_dependencies_truncated", "members_included",
            "members", "showing", "total", "has_more", "next_offset");

    private final SymbolToolQueries symbols = new SymbolToolQueries();
    private final UsageToolQueries usages = new UsageToolQueries();
    private final TestImpactQueries tests = new TestImpactQueries();
    private final ChangeRiskQueries risks = new ChangeRiskQueries();

    String getContext(Jdbi jdbi, List<String> targets, boolean includeMembers, int limit) {
        if (targets == null || targets.isEmpty()) {
            return errorResponse("At least one target is required");
        }
        if (targets.size() > MAX_TARGETS) {
            return errorResponse("At most " + MAX_TARGETS + " targets are allowed");
        }

        ObjectNode root = JSON.createObjectNode();
        root.set("requested_targets", JSON.valueToTree(targets));
        ArrayNode contexts = root.putArray("contexts");
        ArrayNode unresolved = root.putArray("unresolved_targets");
        List<String> resolvedNames = new ArrayList<>();
        int naiveTokens = 0;

        for (String target : targets) {
            ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
            if (lookup.error() != null) {
                ObjectNode failure = unresolved.addObject();
                failure.put("target", target == null ? "" : target);
                failure.set("resolution", ClassTargetResolver.errorResponse(JSON, lookup, target));
                continue;
            }
            ClassRecord cls = lookup.cls();
            if (resolvedNames.contains(cls.className())) continue;
            resolvedNames.add(cls.className());
            naiveTokens += cls.sourceTokens();

            ObjectNode context = contexts.addObject();
            context.put("requested_target", target);
            ObjectNode resolution = context.putObject("resolution");
            resolution.put("status", "resolved");
            resolution.put("class", cls.className());
            resolution.put("source", cls.sourceFile() + ":" + cls.sourceLine());
            putNullable(resolution, "module", cls.module());
            putNullable(resolution, "source_set", cls.sourceSet());
            resolution.put("origin", cls.origin());
            resolution.put("lifecycle", cls.lifecycle());

            ObjectNode details = parse(symbols.getSymbolDetails(
                    jdbi, cls.className(), includeMembers, null, limit, 0));
            ObjectNode symbol = context.putObject("symbol");
            SYMBOL_FIELDS.forEach(field -> copy(details, symbol, field));
            JsonNode metrics = details.get("dependency_metrics");
            if (metrics != null) context.set("coupling", metrics.deepCopy());

            ObjectNode usage = parse(usages.findUsages(
                    jdbi, cls.className(), null, null, limit, 0, false));
            context.set("usages", compact(usage, List.of(
                    "usage_group_count", "usage_occurrence_count", "usages", "limitations",
                    "showing", "total", "has_more", "next_offset")));

            ObjectNode risk = parse(risks.getRisk(jdbi, cls.className()));
            context.set("risk", compact(risk, List.of(
                    "risk_score", "risk_level", "signals", "recommendation")));
        }

        root.put("resolved_target_count", resolvedNames.size());
        root.put("unresolved_target_count", unresolved.size());
        root.put("answer_complete", unresolved.isEmpty());
        if (!resolvedNames.isEmpty()) {
            ObjectNode impact = parse(tests.findImpactedTests(
                    jdbi, resolvedNames, true, 3, limit, 0));
            root.set("impacted_tests", compact(impact, List.of(
                    "targets", "test_index_coverage", "answer_complete", "limitations", "tests",
                    "showing", "total", "has_more", "next_offset")));
        }
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static ObjectNode compact(ObjectNode source, List<String> fields) {
        ObjectNode result = JSON.createObjectNode();
        fields.forEach(field -> copy(source, result, field));
        return result;
    }

    private static void copy(ObjectNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null) target.set(field, value.deepCopy());
    }

    private static ObjectNode parse(String json) {
        try {
            JsonNode parsed = JSON.readTree(json);
            if (parsed instanceof ObjectNode object) return object;
            throw new IllegalStateException("Expected a JSON object");
        } catch (Exception error) {
            throw new IllegalStateException("Cannot compose context response", error);
        }
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field);
        else node.put(field, value);
    }
}
