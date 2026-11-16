package org.treblereel.mcp.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * Application methods that add property sources to the Spring Environment and whether they schedule
 * work: a value such a source supplies can change without a restart. Apollo AQ-039: asked whether
 * emergencyPublish.supported.envs can change in a running portal, Claude read three classes to find
 * RefreshableConfig#setup's addLast and its scheduled refresh.
 */
public final class ProgrammaticPropertySources {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int MAX_LINES = 4;
    private static final Set<String> ADDS =
            Set.of("addFirst", "addLast", "addBefore", "addAfter", "replace");

    private ProgrammaticPropertySources() {}

    /**
     * One line per production method; {@code modules} restricts them to those modules when not
     * empty.
     */
    public static List<String> describe(ReadQueryContext reader, Set<String> modules) {
        List<Map<String, Object>> rows =
                reader.jdbi()
                        .withHandle(
                                handle ->
                                        handle.createQuery(
                                                        """
                SELECT c.class_name, c.module, e.from_method, e.caller_external_calls
                FROM method_evidence e JOIN classes c ON c.id = e.from_class_id
                WHERE e.caller_external_calls LIKE '%org.springframework.core.env.MutablePropertySources|%'
                  AND COALESCE(c.source_set, 'main') NOT IN ('test', 'integration-test', 'integrationTest')
                ORDER BY c.class_name, e.from_method""")
                                                .mapToMap()
                                                .list());
        List<String> lines = new ArrayList<>();
        for (var row : rows) {
            if (lines.size() == MAX_LINES) break;
            String module = row.get("module") == null ? null : String.valueOf(row.get("module"));
            if (!modules.isEmpty() && module != null && !modules.contains(module)) continue;
            List<String> adds = new ArrayList<>();
            List<String> schedules = new ArrayList<>();
            try {
                for (var call : JSON.readTree(String.valueOf(row.get("caller_external_calls")))) {
                    String[] parts = call.asText().split("\\|", -1);
                    if (parts.length < 4) continue;
                    String owner = parts[2].substring(parts[2].lastIndexOf('.') + 1);
                    String text = owner + "#" + parts[3] + " line " + parts[1];
                    if (parts[2].equals("org.springframework.core.env.MutablePropertySources")
                            && ADDS.contains(parts[3])) adds.add(text);
                    else if (parts[3].startsWith("schedule")) schedules.add(text);
                }
            } catch (Exception unreadable) {
                continue;
            }
            if (adds.isEmpty()) continue;
            String line =
                    row.get("class_name")
                            + "#"
                            + row.get("from_method")
                            + " adds property sources ("
                            + String.join(", ", adds)
                            + ")";
            if (!schedules.isEmpty())
                line += " and schedules work (" + String.join(", ", schedules) + ")";
            lines.add(line);
        }
        return lines;
    }
}
