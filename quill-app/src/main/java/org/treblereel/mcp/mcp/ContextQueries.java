package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final java.util.regex.Pattern LAMBDA_METHOD = java.util.regex.Pattern.compile("lambda\\$(.+?)\\$\\d+");
    private final TestImpactQueries tests = new TestImpactQueries();
    private final ChangeRiskQueries risks = new ChangeRiskQueries();

    /**
     * Direct dependents of a class split into production and test code, and the tests that reach
     * it. In V24 CQ-045 Claude asked impact for SsarClientCache in all three sessions and got an
     * error instead of SsarClientCacheTest.
     */
    private ObjectNode classImpact(ReadQueryContext reader, ClassRecord cls, int limit) {
        ObjectNode impact = JSON.createObjectNode().put("kind", "direct_class_impact")
                .put("scope", "direct class-level dependents and the tests that reach the class; use Class#method(signature) for one member")
                .put("runtime_execution_proven", false);
        impact.put("class", cls.className());
        if (cls.sourceFile() != null) impact.put("declaration_file", cls.sourceFile());
        ObjectNode usage = parse(usages.findUsages(reader, cls.className(), null, null, limit, 0, false));
        Map<String, ClassRecord> byName = new java.util.HashMap<>();
        for (ClassRecord candidate : reader.symbols.findAllClasses()) byName.putIfAbsent(candidate.className(), candidate);
        Map<String, ObjectNode> grouped = new java.util.LinkedHashMap<>();
        ArrayNode production = impact.putArray("production_dependents");
        ArrayNode testDependents = impact.putArray("test_dependents");
        for (JsonNode row : usage.path("usages")) {
            String caller = row.path("class").asText();
            ObjectNode dependent = grouped.get(caller);
            if (dependent == null) {
                ClassRecord record = byName.get(caller);
                dependent = (record != null && TestImpactQueries.isTestClass(record) ? testDependents : production).addObject();
                dependent.put("class", caller);
                if (record != null && record.sourceFile() != null) dependent.put("file", record.sourceFile());
                dependent.putArray("usage_kinds");
                grouped.put(caller, dependent);
            }
            ((ArrayNode) dependent.get("usage_kinds")).add(row.path("usage_kind").asText());
            for (JsonNode member : row.path("invoked_members")) {
                ArrayNode members = dependent.has("members") ? (ArrayNode) dependent.get("members") : dependent.putArray("members");
                String name = member.path("name").asText();
                if (members.valueStream().noneMatch(value -> value.asText().equals(name))) members.add(name);
            }
        }
        // The dependent's scope and the methods calling in answer how it uses the class without reading it.
        for (var entry : grouped.entrySet()) {
            ClassRecord record = byName.get(entry.getKey());
            if (record == null) continue;
            ObjectNode dependent = entry.getValue();
            var facts = reader.jdbi().withHandle(h -> h.createQuery("""
                    SELECT (SELECT scope FROM beans WHERE class_id = :from AND member_name IS NULL LIMIT 1) AS scope,
                           (SELECT GROUP_CONCAT(from_method, ',') FROM method_calls WHERE from_class_id = :from AND to_class_id = :to) AS used_in""")
                    .bind("from", record.id()).bind("to", cls.id()).mapToMap().one());
            if (facts.get("scope") != null) dependent.put("bean_scope", String.valueOf(facts.get("scope")));
            if (facts.get("used_in") != null) {
                java.util.TreeSet<String> methods = new java.util.TreeSet<>();
                for (String method : String.valueOf(facts.get("used_in")).split(",")) {
                    var lambda = LAMBDA_METHOD.matcher(method);
                    methods.add(lambda.matches() ? lambda.group(1) : method);
                }
                ArrayNode used = dependent.putArray("used_in");
                methods.forEach(used::add);
            }
        }
        copy(usage, impact, "usage_group_count");
        copy(usage, impact, "has_more");
        ObjectNode tested = parse(tests.findImpactedTests(reader, List.of(cls.className()), true, 3, limit, 0));
        ObjectNode impacted = compact(tested, List.of("test_index_coverage", "answer_complete", "showing", "total", "has_more"));
        ArrayNode rows = impacted.putArray("tests");
        for (JsonNode test : tested.path("tests")) rows.add(AffectedTestRows.row(test));
        impact.set("impacted_tests", impacted);
        impact.put("answer_complete", usage.path("answer_complete").asBoolean(!usage.path("has_more").asBoolean())
                && !usage.path("has_more").asBoolean() && tested.path("answer_complete").asBoolean());
        return impact;
    }

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
                    try {FrameworkContracts.append(reader,method,cls.id(),method.path("name").asText(),method.path("descriptor").asText());}
                    catch(Exception failure){throw new IllegalStateException("Cannot read framework contract",failure);}
                    method.set("outgoing_calls",parse(new CallHierarchyQueries().getCallHierarchy(reader,cls.className(),
                            method.path("name").asText(),method.path("descriptor").asText(),"outbound",false,1,limit,0)));
                    method.set("injection_points",parse(new StructureToolQueries().getInjectionPoints(reader,cls.className())));
                    method.set("test_impact",parse(tests.findImpactedTests(reader,List.of(target),false,1,limit,0)));
                    JsonNode calls=method.path("outgoing_calls");
                    method.put("answer_complete",method.path("source_complete").asBoolean() && !calls.has("error_code")
                            && !calls.has("error") && !calls.path("has_more").asBoolean() && !calls.path("traversal_truncated").asBoolean()
                            && method.path("test_impact").path("answer_complete").asBoolean());
                    embeddedRestatements(method);
                }
                if (!resolvedNames.contains(cls.className())) resolvedNames.add(cls.className());
                continue;
            }
            if(view.equals("impact")){
                if(!resolvedNames.contains(cls.className())){resolvedNames.add(cls.className());contexts.add(classImpact(reader,cls,limit));}
                continue;
            }
            if (resolvedNames.contains(cls.className())) continue;
            resolvedNames.add(cls.className());
            naiveTokens += cls.sourceTokens();

            ObjectNode context = contexts.addObject();
            context.put("requested_target", target);
            ObjectNode resolution = context.putObject("resolution");
            resolution.put("status", "resolved");
            resolution.put("class", cls.className());
            resolution.put("source", (cls.sourceLine() > 0 ? cls.sourceFile() + ":" + cls.sourceLine() : cls.sourceFile()));
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
            classRestatements(context);
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

    /**
     * The tool's own response: plan and verification read the full test list internally, but the
     * ranked list repeated method_context.test_impact and was rarely the only source of what agents
     * used, so the tool answer leaves it one follow-up request away.
     */
    static String summarizeImpactedTests(String text) {
        try {
            if (!(JSON.readTree(text) instanceof ObjectNode root)
                    || !(root.get("impacted_tests") instanceof ObjectNode impact)) return text;
            ObjectNode summary = compact(impact, List.of("answer_complete", "limitations", "total"));
            if (impact.path("targets") instanceof com.fasterxml.jackson.databind.node.ArrayNode targets && !targets.isEmpty()) {
                summary.putObject("request").put("tool", "find_impacted_tests").putObject("arguments")
                        .set("targets", targets);
            }
            root.set("impacted_tests", summary);
            return root.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            return text;
        }
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

    /** What an embedded tool answer restates about the method context it sits in, or about its own defaults. */
    private static final java.util.Set<String> RESTATED=java.util.Set.of("target","name","method","descriptor","signature",
            "jvm_name","jvm_descriptor","source_name","language","member_kind","location","evidence_defaults","limitations",
            "usage_scope","declaration_lookup","test_ranking_scope");
    private static final java.util.Map<String,String> SECTION_DEFAULTS=java.util.Map.of("direct_only","true","transitive","false",
            "max_depth","1","scope","all","suppressed_call_count","0","traversal_truncated","false","declared_method_match_count","1",
            "declared_method_found","true","module",".","source_set","main");

    /** V21 method contexts repeated the method's identity and each tool's bookkeeping in every section. */
    private static void embeddedRestatements(ObjectNode method){
        for(String section:List.of("outgoing_calls","injection_points","test_impact")){
            if(!(method.get(section) instanceof ObjectNode node) || node.has("error_code") || node.has("error"))continue;
            node.remove(RESTATED);node.remove("traversal_edge_cap");
            SECTION_DEFAULTS.forEach((field,value)->{if(node.has(field) && node.get(field).asText().equals(value))node.remove(field);});
            if(node.get("selected_method_references") instanceof ObjectNode references)references.remove(RESTATED);
            for(JsonNode point:node.path("injection_points"))
                if(point instanceof ObjectNode object)object.remove(List.of("resolution_trace","limitations"));
        }
    }

    private static final java.util.Map<String,String> LOCATION_DEFAULTS=java.util.Map.of("origin","source","lifecycle","current",
            "module",".","source_set","main");
    private static final java.util.Set<String> COVERAGE_ANSWER=java.util.Set.of("scan_complete","page_complete","incomplete_reasons");

    /** V21 class contexts restated their resolution in the symbol, each usage's file and kind twice, and index bookkeeping. */
    static void classRestatements(ObjectNode context){
        if(context.get("resolution") instanceof ObjectNode resolution)
            LOCATION_DEFAULTS.forEach((field,value)->{if(value.equals(resolution.path(field).asText(null)))resolution.remove(field);});
        if(context.get("symbol") instanceof ObjectNode symbol){
            symbol.remove(List.of("language","source_name","origin","lifecycle","module","source_set","meta_annotations"));
            if(symbol.path("jvm_name").equals(symbol.path("class")))symbol.remove("jvm_name");
            if(symbol.path("interfaces").isEmpty())symbol.remove("interfaces");
            if(symbol.path("direct_implementations").isEmpty())
                symbol.remove(List.of("direct_implementations","direct_implementation_count","direct_implementations_truncated"));
        }
        if(context.get("usages") instanceof ObjectNode usages){
            for(JsonNode usage:usages.path("usages"))if(usage instanceof ObjectNode row){
                row.remove("indexed_kind");
                if(row.path("evidence_file").equals(row.path("source")))row.remove("evidence_file");
            }
            if(usages.get("coverage") instanceof ObjectNode coverage){
                coverage.retain(COVERAGE_ANSWER);
                if(coverage.path("incomplete_reasons").isEmpty())coverage.remove("incomplete_reasons");
            }
        }
    }
}
