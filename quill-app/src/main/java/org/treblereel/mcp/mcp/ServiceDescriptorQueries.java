package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;

/** Returns the ordered provider declarations captured from META-INF/services. */
final class ServiceDescriptorQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String inspect(Jdbi jdbi, String serviceFilter) {
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        String detail = metadata.get("service_registrations_detail");
        if (detail == null) {
            return errorResponse("Service descriptor details are unavailable; re-run 'quill init'");
        }
        JsonNode registrations;
        try {
            registrations = JSON.readTree(detail);
        } catch (Exception error) {
            return errorResponse("Indexed service descriptor metadata is invalid");
        }
        Map<String, Descriptor> descriptors = new LinkedHashMap<>();
        for (JsonNode registration : registrations) {
            String service = registration.path("serviceType").asText();
            if (serviceFilter != null && !service.equals(serviceFilter)
                    && !service.endsWith("." + serviceFilter)) continue;
            String path = registration.path("descriptorPath").asText();
            Descriptor descriptor = descriptors.computeIfAbsent(path,
                    ignored -> new Descriptor(service, path));
            int position = descriptor.providers.size() + 1;
            descriptor.providers.addObject()
                    .put("provider", registration.path("providerType").asText())
                    .put("line", registration.path("line").asInt())
                    .put("position", position);
        }
        ObjectNode root = JSON.createObjectNode();
        ArrayNode result = root.putArray("descriptors");
        for (Descriptor descriptor : descriptors.values()) {
            ObjectNode item = result.addObject();
            item.put("file", descriptor.path);
            item.put("service", descriptor.service);
            item.set("providers", descriptor.providers);
            item.put("provider_count", descriptor.providers.size());
            boolean annotationProcessors = descriptor.service.endsWith("annotation.processing.Processor")
                    && descriptor.providers.size() > 1;
            item.put("order_can_affect_execution", annotationProcessors);
            item.put("order_sensitivity", annotationProcessors
                    ? "potentially_significant" : "not_inferred");
            item.put("reason", annotationProcessors
                    ? "Multiple annotation processors may exchange generated types across processing rounds; preserve the declared provider order when diagnosing pipeline behavior."
                    : "Quill preserves provider order but does not infer ordering semantics for this service type.");
        }
        root.put("showing", result.size());
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    private static final class Descriptor {
        private final String service;
        private final String path;
        private final ArrayNode providers = JSON.createArrayNode();

        private Descriptor(String service, String path) {
            this.service = service;
            this.path = path;
        }
    }
}
