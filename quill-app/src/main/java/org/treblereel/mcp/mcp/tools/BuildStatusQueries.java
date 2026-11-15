package org.treblereel.mcp.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.mcp.support.ToolResponseSupport;

/** Presentation of captured build evidence; freshness decisions retain the complete observation. */
final class BuildStatusQueries {
    private static final ObjectMapper JSON = Json.MAPPER;

    private BuildStatusQueries() {}

    static ObjectNode present(
            String json,
            Path root,
            String project,
            String detail,
            String module,
            String sourceSet,
            int limit,
            int offset) {
        try {
            var result = (ObjectNode) JSON.readTree(json);
            boolean full = detail.equals("full");
            result.put("detail", detail);
            var args =
                    JSON.createObjectNode()
                            .put("detail", "full")
                            .put("limit", limit)
                            .put("offset", offset);
            if (project != null) args.put("project", project);
            if (module != null) args.put("module", module);
            if (sourceSet != null) args.put("source_set", sourceSet);
            result.set("details_request", request(args));
            // Shared offsets page each inventory independently; nested evidence stays intact.
            boolean more = false;
            for (String field :
                    List.of(
                            "java_compiler_settings",
                            "compiled_outputs.source_sets",
                            "compiled_outputs.directories",
                            "compiled_outputs.stale_modules",
                            "repository_stale_modules",
                            "stale_modules")) {
                int split = field.indexOf('.');
                JsonNode parent = split < 0 ? result : result.path(field.substring(0, split));
                String name = split < 0 ? field : field.substring(split + 1);
                if (!(parent instanceof ObjectNode object) || !object.path(name).isArray())
                    continue;
                var values = object.path(name);
                var selected = JSON.createArrayNode();
                for (var item : values) {
                    if (item.isObject()) {
                        String itemModule = item.path("module").asText();
                        if (Path.of(itemModule).isAbsolute())
                            itemModule =
                                    root.toAbsolutePath()
                                            .normalize()
                                            .relativize(Path.of(itemModule).normalize())
                                            .toString()
                                            .replace('\\', '/');
                        if (module != null && !module.equals(itemModule)) continue;
                        if (sourceSet != null
                                && !sourceSet.equals(item.path("source_set").asText())) continue;
                    }
                    selected.add(item);
                }
                object.put(name + "_count", selected.size());
                if (name.equals("source_sets") || name.equals("java_compiler_settings")) {
                    var counts = object.putObject(name + "_status_counts");
                    for (var item : selected) {
                        String status = item.path("status").asText("unknown");
                        counts.put(status, counts.path(status).asInt() + 1);
                    }
                }
                object.remove(name);
                if (!full) continue;
                var page = JSON.createArrayNode();
                selected.valueStream().skip(offset).limit(limit).forEach(page::add);
                object.set(name, page);
                var paging = object.putObject(name + "_page");
                ToolResponseSupport.appendPage(paging, page.size(), selected.size(), limit, offset);
                if (paging.path("has_more").asBoolean()) more = true;
            }
            if (full) {
                result.put("limit", limit).put("offset", offset).put("has_more", more);
                if (more)
                    result.set(
                            "next_page_request",
                            request(args.deepCopy().put("offset", offset + limit)));
            } else {
                result.put("verification_plan_available", result.hasNonNull("verification_plan"));
                result.remove("verification_plan");
                result.putArray("omitted_sections")
                        .add("java_compiler_settings")
                        .add("compiled_outputs.source_sets")
                        .add("compiled_outputs.directories")
                        .add("verification_plan")
                        .add("diagnostics");
                if (result.path("diagnostics").isObject()) {
                    var summary = result.putObject("diagnostics_summary");
                    for (String field :
                            List.of(
                                    "build_status",
                                    "build_tool",
                                    "finished_at",
                                    "total",
                                    "located_problem_count",
                                    "unlocated_problem_count",
                                    "recommended_action"))
                        if (result.path("diagnostics").has(field))
                            summary.set(field, result.path("diagnostics").path(field));
                    result.remove("diagnostics");
                }
            }
            // Keep complete rows below the transport budget instead of trimming nested evidence.
            if (full
                    && limit > 1
                    && result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            > 192 * 1024)
                return present(
                        json,
                        root,
                        project,
                        detail,
                        module,
                        sourceSet,
                        Math.max(1, limit / 2),
                        offset);
            return result;
        } catch (java.io.IOException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    private static ObjectNode request(ObjectNode arguments) {
        return JSON.createObjectNode().put("tool", "get_build_status").set("arguments", arguments);
    }
}
