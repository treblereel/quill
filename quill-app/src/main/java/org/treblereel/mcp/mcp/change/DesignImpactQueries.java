package org.treblereel.mcp.mcp.change;

import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.mcp.support.ClassTargetResolver;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.diagnostics.QuillLog;

/** Compares existing classes as candidate hosts for a proposed responsibility. */
public final class DesignImpactQueries {

    private static final ObjectMapper JSON = Json.MAPPER;
    private final ChangeRiskQueries risks = new ChangeRiskQueries();
    private final TestImpactQueries tests = new TestImpactQueries();

    public String compare(ReadQueryContext reader, List<String> candidates, int testDepth) {
        if (candidates == null || candidates.size() < 2) {
            return errorResponse("At least two candidates are required");
        }
        if (candidates.size() > 10) return errorResponse("At most 10 candidates are allowed");

        List<CandidateImpact> impacts = new ArrayList<>();
        for (String candidate : candidates) {
            ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(reader, candidate);
            if (lookup.error() != null) {
                ObjectNode error = JSON.createObjectNode();
                error.put("error", "Could not resolve design candidate: " + candidate);
                error.set("detail", ClassTargetResolver.errorResponse(JSON, lookup, candidate));
                appendMeta(error, reader, 0);
                return error.toString();
            }
            ClassRecord cls = lookup.cls();
            JsonNode risk = parse(risks.getRisk(reader, cls.className()));
            JsonNode impacted = parse(tests.findImpactedTests(reader,
                    List.of(cls.className()), true, testDepth, 200, 0));
            int fanIn = reader.usages.countDependents(cls.id());
            int fanOut = reader.usages.countDependencies(cls.id());
            int testCount = impacted.path("total").asInt();
            double riskScore = risk.path("risk_score").asDouble();
            double comparisonScore = Math.round((riskScore * 0.6
                    + Math.min(10, fanIn / 2.0) * 0.25
                    + Math.min(10, testCount / 5.0) * 0.15) * 10.0) / 10.0;
            impacts.add(new CandidateImpact(cls, riskScore, risk.path("risk_level").asText(),
                    fanIn, fanOut, testCount, comparisonScore, impacted));
        }
        impacts.sort(Comparator.comparingDouble(CandidateImpact::comparisonScore)
                .thenComparing(value -> value.cls().className()));

        ObjectNode root = JSON.createObjectNode();
        root.put("comparison_semantics", "lower_score_means_smaller_existing_change_surface");
        root.put("test_depth", testDepth);
        ArrayNode values = root.putArray("candidates");
        int naiveTokens = 0;
        for (int rank = 0; rank < impacts.size(); rank++) {
            CandidateImpact impact = impacts.get(rank);
            ObjectNode node = values.addObject();
            node.put("rank", rank + 1);
            node.put("class", impact.cls().className());
            node.put("source", impact.cls().sourceFile());
            if (impact.cls().module() != null) node.put("module", impact.cls().module());
            node.put("risk_score", impact.riskScore());
            node.put("risk_level", impact.riskLevel());
            node.put("dependent_classes", impact.fanIn());
            node.put("dependency_classes", impact.fanOut());
            node.put("impacted_tests", impact.testCount());
            node.put("comparison_score", impact.comparisonScore());
            node.put("test_evidence_complete", impact.tests().path("answer_complete").asBoolean());
            ArrayNode samples = node.putArray("test_samples");
            impact.tests().path("tests").valueStream().limit(10)
                    .map(test -> test.path("class").asText(test.path("file").asText()))
                    .forEach(samples::add);
            naiveTokens += impact.cls().sourceTokens();
        }
        CandidateImpact preferred = impacts.getFirst();
        root.put("smallest_change_surface", preferred.cls().className());
        root.put("recommendation",
                "Prefer the lowest-ranked existing host only when its lifecycle and responsibility match the proposed state; otherwise introduce a separate type.");
        root.putArray("limitations")
                .add("This compares existing blast radius, not semantic cohesion or a hypothetical new type")
                .add("Scores combine change risk, dependents, and impacted tests and are comparative, not probabilities")
                .add("Use trace_state_lifecycle to verify lifecycle alignment before selecting a host");
        appendMeta(root, reader, naiveTokens);
        return root.toString();
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception error) {
            QuillLog.swallowed(error);
            return JSON.createObjectNode();
        }
    }

    private record CandidateImpact(ClassRecord cls, double riskScore, String riskLevel,
            int fanIn, int fanOut, int testCount, double comparisonScore, JsonNode tests) {}
}
