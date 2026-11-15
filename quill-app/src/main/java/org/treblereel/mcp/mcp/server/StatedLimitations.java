package org.treblereel.mcp.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.treblereel.mcp.core.Json;

/**
 * Each caveat text reaches a client once per server: later answers leave out the caveats this
 * server already sent and count them in {@code limitations_stated_earlier}. A full-detail request
 * gets every caveat.
 */
final class StatedLimitations {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final Map<Object, StatedLimitations> SERVERS = new WeakHashMap<>();
    private static final int MAX_TEXTS = 512;
    private final Set<String> stated = new HashSet<>();

    /** One record per tools instance, which is one server and so one client. */
    static synchronized StatedLimitations of(Object server) {
        return SERVERS.computeIfAbsent(server, key -> new StatedLimitations());
    }

    synchronized String stateOnce(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (Exception notJson) {
            return text;
        }
        if (!(root instanceof ObjectNode object) || object.has("error_code") || object.has("error")) return text;
        return stateOnce(object) ? object.toString() : text;
    }

    /** Removes limitations this client already saw from a parsed answer; true when any were removed. */
    synchronized boolean stateOnce(ObjectNode object) {
        if (object.has("error_code") || object.has("error")) return false;
        int[] removed = {0};
        walk(object, removed);
        if (removed[0] == 0) return false;
        object.put("limitations_stated_earlier", removed[0]);
        return true;
    }

    private void walk(JsonNode node, int[] removed) {
        if (node instanceof ObjectNode object) {
            if (object.get("limitations") instanceof ArrayNode limitations) {
                for (int i = limitations.size() - 1; i >= 0; i--) {
                    String caveat = limitations.get(i).asText(null);
                    if (caveat == null) continue;
                    if (stated.contains(caveat)) {
                        limitations.remove(i);
                        removed[0]++;
                    } else if (stated.size() < MAX_TEXTS) {
                        stated.add(caveat);
                    }
                }
                if (limitations.isEmpty() && removed[0] > 0) object.remove("limitations");
            }
            for (var entry : object.properties()) if (!entry.getKey().equals("limitations")) walk(entry.getValue(), removed);
        } else if (node instanceof ArrayNode list) {
            for (JsonNode item : list) walk(item, removed);
        }
    }
}
