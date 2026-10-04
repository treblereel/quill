package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

/** Builds a compact, stateless snapshot of the change workflow. */
final class ChangeSessionQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContextQueries contexts = new ContextQueries();
    private final ChangePlanQueries plans = new ChangePlanQueries();
    private final ChangeVerificationQueries verification = new ChangeVerificationQueries();
    private final WorktreeStatusQueries worktree = new WorktreeStatusQueries();

    String snapshot(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit) {
        if (change == null || change.isBlank()) {
            return plans.planChange(jdbi, projectRoot, targets, change, limit);
        }
        ObjectNode changes = parse(worktree.getWorktreeStatus(
                jdbi, projectRoot, null, limit, 0));
        boolean inferred = targets == null || targets.isEmpty();
        List<String> candidates = inferred
                ? ChangeVerificationQueries.inferredTargets(changes) : List.copyOf(targets);
        boolean inferenceTruncated = inferred && candidates.size() > 10;
        List<String> effectiveTargets = candidates.stream().limit(10).toList();
        ObjectNode context = parse(contexts.getContext(
                jdbi, effectiveTargets, true, limit));
        if (context.has("error_code")) return context.toString();
        ObjectNode plan = plans.planFromContext(projectRoot, context, change);
        ObjectNode verify = parse(verification.verifyChangeWithContext(
                jdbi, projectRoot, effectiveTargets, limit, context, changes,
                inferred, inferenceTruncated));
        if (verify.has("error_code")) return verify.toString();
        List<String> canonicalTargets = canonicalTargets(context);

        ObjectNode result = JSON.createObjectNode();
        result.put("schema_version", 1);
        result.put("session_id", sessionId(projectRoot, canonicalTargets, change));
        result.put("state_model", "stateless_snapshot");
        result.put("change", change.strip());
        result.put("target_source", inferred ? "dirty_worktree" : "explicit");
        result.put("target_inference_truncated", inferenceTruncated);
        result.set("requested_targets", JSON.valueToTree(effectiveTargets));
        result.set("targets", JSON.valueToTree(canonicalTargets));
        result.put("phase", phase(verify));

        ObjectNode planSummary = result.putObject("plan");
        copy(plan, planSummary, "plan_status");
        copy(plan, planSummary, "answer_complete");
        copy(plan, planSummary, "primary_changes");
        copy(plan, planSummary, "dependency_review");
        copy(plan, planSummary, "test_plan");
        copy(plan, planSummary, "warnings");
        copy(plan, planSummary, "sequence");

        ObjectNode verificationSummary = result.putObject("verification");
        copy(verify, verificationSummary, "verdict");
        copy(verify, verificationSummary, "verified");
        copy(verify, verificationSummary, "target_source");
        copy(verify, verificationSummary, "worktree");
        copy(verify, verificationSummary, "build");
        copy(verify, verificationSummary, "diagnostics");
        copy(verify, verificationSummary, "test_evidence");
        copy(verify, verificationSummary, "blockers");

        copy(verify, result, "verification_plan");
        copy(verify, result, "next_actions");
        copy(verify, result, "_meta");
        return result.toString();
    }

    private static String phase(ObjectNode verification) {
        String verdict = verification.path("verdict").asText();
        if (verdict.equals("ready")) return "complete";
        if (verdict.equals("blocked")) return "blocked";
        if (!verification.path("worktree").path("dirty").asBoolean()) return "planned";
        return "verification_required";
    }

    private static String sessionId(Path root, List<String> targets, String change) {
        String identity = root.toAbsolutePath().normalize() + "\n"
                + String.join("\n", targets) + "\n" + change.strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static List<String> canonicalTargets(ObjectNode context) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : context.path("contexts")) {
            String className = item.path("resolution").path("class").asText();
            if (!className.isBlank() && !result.contains(className)) result.add(className);
        }
        for (JsonNode item : context.path("unresolved_targets")) {
            String target = item.path("target").asText().strip();
            if (!target.isBlank() && !result.contains(target)) result.add(target);
        }
        return List.copyOf(result);
    }

    private static void copy(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null) target.set(field, value.deepCopy());
    }

    private static ObjectNode parse(String json) {
        try {
            JsonNode parsed = JSON.readTree(json);
            if (parsed instanceof ObjectNode object) return object;
            throw new IllegalStateException("Expected a JSON object");
        } catch (Exception error) {
            throw new IllegalStateException("Cannot compose change session", error);
        }
    }
}
