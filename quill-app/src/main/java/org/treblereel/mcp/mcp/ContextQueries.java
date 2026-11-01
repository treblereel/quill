package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/** Composes the most useful evidence for understanding or changing a small set of classes. */
final class ContextQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
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

    String getContext(ReadQueryContext reader, List<String> targets, boolean includeMembers, int limit) {
        return getContext(reader,targets,includeMembers,limit,"full");
    }

    String getContext(ReadQueryContext reader, List<String> targets, boolean includeMembers, int limit,String view) {
        if(!List.of("full","impact").contains(view))return errorResponse("view must be full or impact");
        if (targets == null || targets.isEmpty()) {
            return errorResponse("At least one target is required");
        }

        ObjectNode root = JSON.createObjectNode();
        root.set("requested_targets", JSON.valueToTree(targets));
        ArrayNode contexts = root.putArray("contexts");
        ArrayNode unresolved = root.putArray("unresolved_targets");
        List<String> resolvedNames = new ArrayList<>();
        int naiveTokens = 0;

        for (String target : targets) {
            int separator = target == null ? -1 : target.indexOf('#');
            String classTarget = separator < 0 ? target : target.substring(0,separator);
            ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(reader, classTarget);
            if (lookup.error() != null) {
                ObjectNode failure = unresolved.addObject();
                failure.put("target", target == null ? "" : target);
                failure.set("resolution", ClassTargetResolver.errorResponse(JSON, lookup, target));
                continue;
            }
            ClassRecord cls = lookup.cls();
            if (separator >= 0) {
                if(view.equals("impact")){
                    try {
                        ObjectNode impact=new MethodImpactQueries().get(reader,classTarget,target.substring(separator+1),limit);
                        if(impact.has("error") || impact.has("error_code"))unresolved.add(impact);
                        else {contexts.add(impact);resolvedNames.add(cls.className());}
                    } catch(Exception failure){throw new IllegalStateException("Cannot compose method impact",failure);}
                    continue;
                }
                ObjectNode method=(ObjectNode)new MethodContextQueries().get(reader,cls,target.substring(separator+1));
                if(method.has("error") || method.has("error_code") || !method.hasNonNull("descriptor")) {
                    unresolved.addObject().put("target",target).set("resolution",method);continue;
                }
                var context=contexts.addObject().put("requested_target",target);
                context.set("method_context",method);
                if(method.hasNonNull("descriptor") && method.hasNonNull("name")) {
                    method.set("outgoing_calls",parse(new CallHierarchyQueries().getCallHierarchy(reader,cls.className(),
                            method.path("name").asText(),method.path("descriptor").asText(),"outbound",false,1,limit,0)));
                    method.set("injection_points",parse(new StructureToolQueries().getInjectionPoints(reader,cls.className())));
                    method.set("test_impact",parse(tests.findImpactedTests(reader,List.of(target),false,1,limit,0)));
                    JsonNode calls=method.path("outgoing_calls");
                    method.put("answer_complete",method.path("source_complete").asBoolean() && !calls.has("error_code")
                            && !calls.has("error") && !calls.path("has_more").asBoolean() && !calls.path("traversal_truncated").asBoolean()
                            && method.path("test_impact").path("answer_complete").asBoolean());
                }
                if (!resolvedNames.contains(cls.className())) resolvedNames.add(cls.className());
                continue;
            }
            if(view.equals("impact")){unresolved.addObject().put("target",target).put("error","impact requires Class#method(signature)");continue;}
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
                    reader, cls.className(), includeMembers, null, limit, 0));
            ObjectNode symbol = context.putObject("symbol");
            SYMBOL_FIELDS.forEach(field -> copy(details, symbol, field));
            JsonNode metrics = details.get("dependency_metrics");
            if (metrics != null) context.set("coupling", metrics.deepCopy());

            ObjectNode usage = parse(usages.findUsages(
                    reader, cls.className(), null, null, limit, 0, false));
            context.set("usages", compact(usage, List.of(
                    "usage_group_count", "usage_occurrence_count", "usages", "limitations", "coverage", "empty_result", "answer_complete",
                    "showing", "total", "has_more", "next_offset")));

            ObjectNode risk = parse(risks.getRisk(reader, cls.className()));
            context.set("risk", compact(risk, List.of(
                    "risk_score", "risk_level", "signals", "recommendation")));
        }

        if(view.equals("full"))root.set("direct_callee_comparison",DirectCalleeComparison.compare(contexts));
        root.put("resolved_target_count", contexts.size());
        root.put("resolved_class_count",resolvedNames.size());
        root.put("unresolved_target_count", unresolved.size());
        root.put("answer_complete", unresolved.isEmpty() && java.util.stream.StreamSupport.stream(contexts.spliterator(),false)
                .allMatch(c -> view.equals("impact")?c.path("answer_complete").asBoolean():!c.has("method_context") || c.path("method_context").path("answer_complete").asBoolean()));
        root.put("view",view);
        if (view.equals("full") && !resolvedNames.isEmpty()) {
            ObjectNode impact = parse(tests.findImpactedTests(
                    reader, resolvedNames, true, 3, limit, 0));
            root.set("impacted_tests", compact(impact, List.of(
                "targets", "test_index_coverage", "answer_complete", "limitations", "tests",
                "showing", "total", "limit", "offset", "has_more", "next_offset")));
        }
        if(root.has("impacted_tests") && !root.path("impacted_tests").path("answer_complete").asBoolean()) root.put("answer_complete",false);
        appendMeta(root, reader, naiveTokens);
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
