package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marks ambiguous injection candidates active or inactive under the Spring profiles the prompt
 * sets. Apollo AQ-006 (Codex): with spring.profiles.active=github,auth given, Codex read
 * AuthConfiguration three times to learn which UserInfoHolder those profiles activate.
 */
final class ProfileActivation {

    private static final Pattern ACTIVE =
            Pattern.compile("spring\\.profiles\\.active\\s*=\\s*([A-Za-z0-9_,\\-]+)");
    private static final Pattern PROFILES = Pattern.compile("profiles \\[([^\\]]*)\\]");
    private static final Pattern OTHER_MODULE = Pattern.compile("#\\S+ module \\S+");

    private static final Pattern MISSING_PROFILE =
            Pattern.compile("ConditionalOnMissingProfile\\(\\[([^\\]]*)\\]\\)");

    private ProfileActivation() {}

    static void annotate(ObjectNode packet, String prompt) {
        if (prompt == null) return;
        Matcher given = ACTIVE.matcher(prompt);
        if (!given.find()) return;
        Set<String> active = names(given.group(1));
        String label = String.join(",", active);
        for (JsonNode fact : packet.path("facts")) {
            if (!(fact.path("ambiguous_candidates") instanceof ObjectNode points)) continue;
            for (JsonNode candidates : points) {
                if (!(candidates instanceof ArrayNode lines)) continue;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).asText();
                    String status =
                            OTHER_MODULE.matcher(line).find()
                                    ? "other module: in an application assembling both, not in this module's own context"
                                    : (active(line, active) ? "active" : "inactive")
                                            + " under "
                                            + label;
                    lines.set(i, TextNode.valueOf(line + " - " + status));
                }
                // Active candidates first: the budget halves lists from the end (Apollo AQ-018).
                java.util.List<JsonNode> sorted = new java.util.ArrayList<>();
                lines.forEach(sorted::add);
                sorted.sort(java.util.Comparator.comparingInt(ProfileActivation::rank));
                lines.removeAll();
                sorted.forEach(lines::add);
            }
        }
    }

    private static int rank(JsonNode line) {
        String text = line.asText();
        return text.contains(" - active under ") ? 0 : text.contains(" - inactive under ") ? 1 : 2;
    }

    private static boolean active(String line, Set<String> active) {
        Matcher profiles = PROFILES.matcher(line);
        if (profiles.find() && names(profiles.group(1)).stream().noneMatch(active::contains))
            return false;
        Matcher missing = MISSING_PROFILE.matcher(line);
        return !(missing.find() && names(missing.group(1)).stream().anyMatch(active::contains));
    }

    private static Set<String> names(String list) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(list.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .forEach(names::add);
        return names;
    }
}
