package org.treblereel.mcp.mcp.change;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Set facts from complete bounded pages only; absence on partial pages never proves a set difference. */
final class DirectCalleeComparison {
    static ObjectNode compare(ArrayNode contexts) {
        ObjectNode result=JsonNodeFactory.instance.objectNode().put("scope","direct indexed method callees");
        Map<String,Map<String,JsonNode>> graphs=new LinkedHashMap<>();
        for(JsonNode context:contexts) if(context.has("method_context")) {
            JsonNode calls=context.path("method_context").path("outgoing_calls");
            if(!calls.path("calls").isArray() || calls.has("error_code") || calls.has("error") || calls.path("has_more").asBoolean() || calls.path("traversal_truncated").asBoolean())
                return result.put("status","incomplete_graph_pages").put("answer_complete",false);
            Map<String,JsonNode> nodes=new TreeMap<>();
            for(JsonNode edge:calls.path("calls")) {
                if(edge.has("edge_kind") && !"call".equals(edge.path("edge_kind").asText())) continue;
                JsonNode callee=edge.path("callee");String id=callee.path("symbol_id").asText();
                if(id.isBlank()) return result.put("status","callee_identity_unavailable").put("answer_complete",false);
                nodes.put(id,callee);
            }
            graphs.put(context.path("requested_target").asText(),nodes);
        }
        if(graphs.size()<2) return result.put("status","requires_multiple_methods").put("answer_complete",false);
        Set<String> common=new TreeSet<>(graphs.values().iterator().next().keySet());
        graphs.values().forEach(g->common.retainAll(g.keySet()));
        ArrayNode shared=result.putArray("common_callees_across_all");
        var first=graphs.values().iterator().next();common.forEach(id->shared.add(first.get(id)));
        ObjectNode exclusive=result.putObject("exclusive_callees_by_target");
        graphs.forEach((target,graph)->{
            ArrayNode nodes=exclusive.putArray(target);
            graph.forEach((id,node)->{if(graphs.entrySet().stream().noneMatch(other->!other.getKey().equals(target) && other.getValue().containsKey(id)))nodes.add(node);});
        });
        return result.put("status","complete").put("answer_complete",true).put("runtime_execution_proven",false);
    }
}
