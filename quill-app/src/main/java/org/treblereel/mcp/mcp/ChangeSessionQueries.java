package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

/** Builds a compact, stateless snapshot of the change workflow. */
final class ChangeSessionQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChangePlanQueries plans = new ChangePlanQueries();
    private final ChangeVerificationQueries verification = new ChangeVerificationQueries();

    String snapshot(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit) {
        ObjectNode plan = parse(plans.planChange(jdbi, projectRoot, targets, change, limit));
        if (plan.has("error_code")) return plan.toString();
        ObjectNode verify = parse(verification.verifyChange(
                jdbi, projectRoot, targets, limit));
        if (verify.has("error_code")) return verify.toString();

        ObjectNode result = JSON.createObjectNode();
        result.put("schema_version", 1);
        result.put("session_id", sessionId(projectRoot, targets, change));
        result.put("state_model", "stateless_snapshot");
        result.put("change", change.strip());
        result.set("targets", JSON.valueToTree(targets));
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
