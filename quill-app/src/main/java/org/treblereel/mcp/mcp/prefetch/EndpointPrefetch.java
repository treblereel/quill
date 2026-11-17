package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.config.ConfigurationReferenceQueries;
import org.treblereel.mcp.mcp.query.FrameworkEndpointQueries;
import org.treblereel.mcp.mcp.query.MethodContextQueries;
import org.treblereel.mcp.mcp.query.StructureToolQueries;
import org.treblereel.mcp.mcp.support.ClassTargetResolver;
import org.treblereel.mcp.model.ClassRecord;

/** Resolve literal HTTP operations, then reuse bounded method, DI and configuration queries. */
public final class EndpointPrefetch {
    private static final ObjectMapper JSON = Json.MAPPER;
    private static final Pattern ROUTE =
            Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(/[^\\s`\"<>]*)");

    static boolean hasRoute(String prompt) {
        return ROUTE.matcher(prompt).find();
    }

    public static ObjectNode collect(ReadQueryContext db, Path root, String prompt)
            throws Exception {
        return collect(db, root, prompt, false);
    }

    public static ObjectNode collect(
            ReadQueryContext db, Path root, String prompt, boolean alreadyDelivered)
            throws Exception {
        var metadata = db.index.getMetadata();
        var result =
                JSON.createObjectNode()
                        .put("basis", "indexed HTTP endpoint and direct method evidence")
                        .put("status", "ready")
                        .put("index_id", metadata.getOrDefault("index_id", "unknown"));
        var facts = result.putArray("facts");
        var ambiguities = result.putArray("ambiguities");
        var unavailable = result.putArray("unavailable");
        if (!root.toRealPath()
                .equals(Path.of(metadata.getOrDefault("project_root", "")).toRealPath()))
            return result.put("status", "index_root_mismatch");
        var matcher = ROUTE.matcher(prompt);
        if (!matcher.find()) return result;
        String verb = matcher.group(1).toUpperCase(Locale.ROOT), path = routePath(matcher.group(2));
        while (matcher.find())
            if (!verb.equals(matcher.group(1).toUpperCase(Locale.ROOT))
                    || !path.equals(routePath(matcher.group(2)))) {
                ambiguities.addObject().put("status", "multiple_http_operations");
                return result;
            }
        if (path.length() > 4096) {
            unavailable.addObject().put("status", "route_limit");
            return result;
        }
        var endpoints =
                new FrameworkEndpointQueries()
                        .findFrameworkEndpoints(db, null, verb, path, null, false, false, 20, 0);
        if (endpoints.has("error") || endpoints.has("error_code")) {
            unavailable.add(endpoints);
            return result;
        }
        List<JsonNode> matches = new ArrayList<>();
        for (var endpoint : endpoints.path("endpoints"))
            for (var route : endpoint.path("paths"))
                if (route.asText().equals(path)) {
                    matches.add(endpoint);
                    break;
                }
        if (matches.size() != 1 || endpoints.path("has_more").asBoolean()) {
            (matches.isEmpty() ? unavailable : ambiguities)
                    .addObject()
                    .put("http_method", verb)
                    .put("path", path)
                    .put("status", matches.isEmpty() ? "endpoint_not_found" : "endpoint_ambiguous");
            return result;
        }
        JsonNode endpoint = matches.getFirst();
        Set<String> owners = new LinkedHashSet<>();
        owners.add(endpoint.path("declaring_class").asText());
        for (var call : endpoint.path("direct_project_calls")) {
            if (owners.size() >= 3) break;
            owners.add(call.path("class").asText());
        }
        if (!sourcesCurrent(db, root, owners))
            return result.put("status", "source_hash_unavailable_or_changed");
        result.put("endpoint_anchor", verb + " " + path);
        if (alreadyDelivered) return result.put("already_delivered", true);
        String security =
                EndpointSecurity.describe(
                        db,
                        endpoint.path("declaring_class").asText(),
                        endpoint.path("method").asText(),
                        endpoint.path("descriptor").asText());
        if (security != null && endpoint instanceof ObjectNode node) node.put("security", security);
        facts.addObject().put("kind", "endpoint").set("endpoint", withoutCopies(endpoint));
        var methods = JSON.createArrayNode();
        Set<String> seen = new HashSet<>();
        addMethod(
                db,
                methods,
                seen,
                endpoint.path("declaring_class").asText(),
                endpoint.path("method").asText(),
                endpoint.path("descriptor").asText(),
                true);
        for (var call : endpoint.path("direct_project_calls")) {
            if (methods.size() >= 3) break;
            addMethod(
                    db,
                    methods,
                    seen,
                    call.path("class").asText(),
                    call.path("method").asText(),
                    call.path("descriptor").asText(),
                    false);
        }
        facts.addObject()
                .put("kind", "operation_methods")
                .put("method_context_limit", 3)
                .set("methods", methods);
        if (!sourcesCurrent(db, root, owners)) {
            facts.removeAll();
            result.remove("endpoint_anchor");
            return result.put("status", "source_hash_unavailable_or_changed");
        }
        result.putArray("limitations")
                .add(
                        "Only exact indexed production HTTP routes and at most two direct project callees; static calls do not prove runtime dispatch.")
                .add(
                        (methods.path(0).path("source_evidence").path("source_complete").asBoolean()
                                ? "Only the HTTP handler body is preloaded; callee bodies remain available on demand."
                                : "No method body is preloaded ("
                                        + methods.path(0)
                                                .path("source_evidence")
                                                .path("status")
                                                .asText("source unavailable")
                                        + "); read the handler and callees on demand."))
                // V65 CQ-032: shorter statements leave the budget for the handler body and its
                // security providers.
                .add(
                        "Injection and configuration rows cover each method's own fields and direct reads; framework settings are not inferred.")
                .add(
                        "DI uses the default indexed context, not a runtime profile. Facts already sent this session are not repeated.");
        return result;
    }

    private static String routePath(String value) {
        String path = value.replaceAll("[.,;:!?)]+$", "");
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static boolean sourcesCurrent(ReadQueryContext db, Path root, Set<String> owners)
            throws Exception {
        var hashes = JSON.readTree(db.index.getMetadata().getOrDefault("java_source_hashes", "{}"));
        for (String owner : owners) {
            var lookup = ClassTargetResolver.resolve(db, owner);
            if (lookup.error() != null || lookup.cls().sourceFile() == null) return false;
            Path file = root.resolve(lookup.cls().sourceFile()).toRealPath();
            if (!file.startsWith(root.toRealPath())) return false;
            String expected =
                    hashes.path(root.toRealPath().relativize(file).toString().replace('\\', '/'))
                            .asText();
            if (expected.isBlank() || !expected.equals(SourceFileHashes.hash(file))) return false;
        }
        return true;
    }

    private static void addMethod(
            ReadQueryContext db,
            ArrayNode methods,
            Set<String> seen,
            String owner,
            String name,
            String descriptor,
            boolean includeSource)
            throws Exception {
        if (!seen.add(owner + "#" + name + descriptor)) return;
        var lookup = ClassTargetResolver.resolve(db, owner);
        if (lookup.error() != null) return;
        ClassRecord cls = lookup.cls();
        if (db.symbols.findClassMembers(cls.id()).stream()
                .noneMatch(m -> m.name().equals(name) && m.descriptor().equals(descriptor))) return;
        var node =
                methods.addObject()
                        .put("class", owner)
                        .put("method", name)
                        .put("descriptor", descriptor);
        ObjectNode source =
                includeSource
                        ? (ObjectNode) new MethodContextQueries().get(db, cls, name + descriptor)
                        : JSON.createObjectNode()
                                .put("source_complete", false)
                                .put("status", "indexed_target_only")
                                .put("source_file", cls.sourceFile());
        if (source.has("error") || source.has("error_code")) {
            ObjectNode compact = JSON.createObjectNode().put("source_complete", false);
            for (String key : List.of("error", "error_code", "status"))
                if (source.has(key)) compact.set(key, source.get(key));
            source = compact;
        }
        for (String key : List.of("source_line", "source_end_line"))
            if (source.path("declaration").has(key))
                source.set(key, source.path("declaration").get(key));
        source.remove("declaration");
        // The method node names these already; the budget is better spent on the handler body.
        source.remove(List.of("target", "name", "descriptor", "symbol_id", "source_sha256"));
        if (source.path("source").asText().length() > 4000) {
            source.remove("source");
            source.put("source_complete", false).put("status", "prefetch_source_limit");
        }
        node.set("source_evidence", source);
        Set<String> fields = db.usages.findOwnFieldsAccessed(cls.id(), name, descriptor);
        JsonNode injection = new StructureToolQueries().getInjectionPoints(db, owner);
        var selected = node.putObject("injection");
        // resolution_context states the same rules for every bean; get_injection_points still has
        // it.
        // The method node names the class already.
        for (String key : List.of("scope", "bean_kind", "error", "error_code"))
            if (injection.has(key)) selected.set(key, injection.get(key));
        var points = selected.putArray("injection_points");
        for (var point : injection.path("injection_points"))
            if (fields.contains(point.path("field").asText())) points.add(injectionLine(point));
        var reads = db.usages.findForeignFieldAccesses(cls.id(), name, descriptor);
        if (!reads.isEmpty()) {
            var fieldReads = node.putArray("other_class_field_accesses");
            reads.forEach(
                    r ->
                            fieldReads.add(
                                    r.className()
                                            + "#"
                                            + r.fieldName()
                                            + " "
                                            + r.accessKind()
                                            + " bytecode lines "
                                            + r.evidenceLines()));
        }
        JsonNode config =
                new ConfigurationReferenceQueries().find(db, null, owner, null, null, 8, 0);
        var references =
                node.putObject("configuration_references")
                        .put("scope", "direct indexed owned-field/programmatic reads only");
        boolean uniqueMethod =
                db.symbols.findClassMembers(cls.id()).stream()
                                .filter(m -> m.kind().equals("METHOD") && m.name().equals(name))
                                .count()
                        == 1;
        for (String key : List.of("has_more", "error", "error_code"))
            if (config.has(key)) references.set(key, config.get(key));
        var entries = references.putArray("references");
        for (var reference : config.path("references")) {
            if (!reference.path("entry_type").asText().equals("usage")) continue;
            String member = reference.path("member").asText();
            boolean programmatic = reference.path("annotation").asText().contains("#");
            if (programmatic ? !uniqueMethod || !member.equals(name) : !fields.contains(member))
                continue;
            var compact = entries.addObject();
            for (String key :
                    List.of(
                            "entry_type",
                            "key",
                            "file",
                            "line",
                            "member",
                            "source",
                            "annotation",
                            "resolution_status"))
                if (reference.has(key)) compact.set(key, reference.get(key));
        }
        references
                .put("candidate_count", config.path("total").asInt())
                .put("showing", entries.size())
                .put("method_scope_complete", uniqueMethod && !config.path("has_more").asBoolean());
        // Apollo AQ-032: an empty result's bookkeeping cost the handler body its place.
        if (entries.isEmpty()
                && config.path("total").asInt() == 0
                && references.path("method_scope_complete").asBoolean()
                && !references.has("error")
                && !references.has("error_code"))
            node.put(
                    "configuration_references",
                    "none: no direct owned-field or programmatic configuration reads");
    }

    /**
     * {@code field Type [qualifiers] -> Bean scope}, with resolution and reason when it is not a
     * unique bean.
     */
    static String injectionLine(JsonNode point) {
        StringBuilder line =
                new StringBuilder(point.path("field").asText())
                        .append(' ')
                        .append(point.path("required_type").asText());
        List<String> qualifiers = new ArrayList<>();
        point.path("qualifiers")
                .forEach(
                        q -> {
                            if (!q.asText().equals("@Default")) qualifiers.add(q.asText());
                        });
        if (!qualifiers.isEmpty()) line.append(' ').append(qualifiers);
        if (point.hasNonNull("resolved_to"))
            line.append(" -> ")
                    .append(point.path("resolved_to").asText())
                    .append(
                            point.hasNonNull("resolved_scope")
                                    ? " " + point.path("resolved_scope").asText()
                                    : "");
        else if (point.path("provider_evidence").hasNonNull("single_match")) {
            String single = point.path("provider_evidence").path("single_match").asText();
            int end = single.indexOf(" is the one");
            line.append(" -> ").append(end < 0 ? single : single.substring(0, end));
        }
        if (!"resolved".equals(point.path("resolution").asText()))
            line.append(" (")
                    .append(point.path("resolution").asText())
                    .append(point.hasNonNull("reason") ? ", " + point.path("reason").asText() : "")
                    .append(')');
        return line.toString();
    }

    private EndpointPrefetch() {}

    /**
     * The endpoint without fields that only repeat another: its declaring class and source when
     * they are the handler's own, and the JVM descriptor of each mapping source. Apollo AQ-032: the
     * copies pushed the handler body out of the budget once its security line was added.
     */
    private static JsonNode withoutCopies(JsonNode endpoint) {
        if (!(endpoint instanceof ObjectNode original)) return endpoint;
        ObjectNode node = original.deepCopy();
        if (node.path("declaring_class").asText().equals(node.path("class").asText()))
            node.remove("declaring_class");
        if (node.path("declaring_source").asText().equals(node.path("source").asText()))
            node.remove("declaring_source");
        if (node.path("mapping_sources") instanceof ArrayNode sources) {
            ArrayNode compact = JSON.createArrayNode();
            for (JsonNode source : sources) {
                String text = source.asText();
                int descriptor = text.indexOf('(');
                compact.add(descriptor < 0 ? text : text.substring(0, descriptor));
            }
            node.set("mapping_sources", compact);
        }
        return node;
    }
}
