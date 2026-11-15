package org.treblereel.mcp.mcp.config;

import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.errorNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.ReferenceRepository.ConfigurationReference;
import org.treblereel.mcp.db.ReferenceRepository.Resolution;
import org.treblereel.mcp.db.ReferenceRepository.ResolutionKey;
import org.treblereel.mcp.diagnostics.QuillLog;
import org.treblereel.mcp.mcp.support.ArgumentAliases;

/** Searches normalized configuration definitions and their consumers. */
public final class ConfigurationReferenceQueries {
    /** Documented MicroProfile Config and Quarkus behaviour, not read from the project. */
    static final String SOURCE_PRECEDENCE =
            "MicroProfile Config: of the sources defining a key, the one with the higher config_ordinal wins "
                    + "(documented, not read from dependency bytecode). Quarkus defaults: system properties 400, environment variables 300, "
                    + ".env in the working directory 295, config/application.properties in the working directory 260, "
                    + "application.properties on the classpath 250, META-INF/microprofile-config.properties 100; a source may set its own config_ordinal";

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final Set<String> KINDS = Set.of("all", "property", "prefix", "persistence");

    public ObjectNode find(
            org.treblereel.mcp.db.ReadQueryContext reader,
            String key,
            String className,
            String kind,
            String module,
            int limit,
            int offset) {
        return find(reader, key, className, kind, module, limit, offset, "index");
    }

    public ObjectNode find(
            Jdbi jdbi,
            String key,
            String className,
            String kind,
            String module,
            int limit,
            int offset) {
        return find(jdbi, key, className, kind, module, limit, offset, "index");
    }

    public ObjectNode find(
            Jdbi jdbi,
            String key,
            String className,
            String kind,
            String module,
            int limit,
            int offset,
            String view) {
        try (var reader = org.treblereel.mcp.db.ReadQueryContext.open(jdbi)) {
            return find(reader, key, className, kind, module, limit, offset, view);
        }
    }

    public ObjectNode find(
            org.treblereel.mcp.db.ReadQueryContext reader,
            String key,
            String className,
            String kind,
            String module,
            int limit,
            int offset,
            String view) {
        view = view == null ? "index" : ArgumentAliases.configurationView(view);
        if (!Set.of("index", "declared").contains(view))
            return errorNode("view must be index or declared");
        if (view.equals("declared")
                && (key == null || key.isBlank())
                && (className == null || className.isBlank()))
            return errorNode("declared view requires a key or consumer class");
        String normalizedKind = kind == null ? "all" : kind.strip().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(normalizedKind)) {
            return errorNode("Invalid kind: expected all, property, prefix, or persistence");
        }
        try {
            var page =
                    reader.references.findConfiguration(
                            key, className, normalizedKind, module, limit, offset);
            int definitionCount = page.definitionCount(), usageCount = page.usageCount();
            int total = definitionCount + usageCount;
            List<ConfigurationReference> references = page.references();
            Map<ResolutionKey, Resolution> resolutions = page.resolutions();

            ObjectNode result = JSON.createObjectNode();
            putNullable(result, "key", key);
            putNullable(result, "class", className);
            result.put("kind", normalizedKind);
            putNullable(result, "module", module);
            result.put("view", view)
                    .put("resolution_scope", "indexed declarations, not effective runtime values");
            // V41/V62 CQ-038: without the rule Codex read SmallRye's comparator bytecode to confirm
            // it.
            if (view.equals("declared") && key != null && !key.isBlank())
                result.put("source_precedence", SOURCE_PRECEDENCE);
            ConfigurationSourceEvidence source =
                    view.equals("declared") ? new ConfigurationSourceEvidence(reader) : null;
            ArrayNode values = result.putArray("references");
            for (ConfigurationReference reference : references) {
                ObjectNode node = values.addObject();
                node.put("entry_type", reference.entryType());
                node.put("key", text(reference.key()));
                node.put("kind", text(reference.kind()));
                putNullable(node, "module", reference.module());
                putNullable(node, "sourceSet", reference.sourceSet());
                if (reference.definition()) {
                    node.put("file", text(reference.file()));
                    node.put("line", number(reference.line()));
                    if (text(reference.kind()).startsWith("quarkus_test_profile")) {
                        ObjectNode declaration =
                                (ObjectNode) JSON.readTree(text(reference.evidence()));
                        for (String field :
                                List.of(
                                        "declaring_class",
                                        "method",
                                        "status",
                                        "activation",
                                        "profile_count"))
                            if (declaration.has(field)) node.set(field, declaration.get(field));
                        var profiles = node.putArray("profile_classes");
                        declaration
                                .path("profile_classes")
                                .valueStream()
                                .limit(3)
                                .forEach(profiles::add);
                        node.put(
                                "configuration_scope",
                                "test-profile declaration, not a production-profile default or proven active override");
                    }
                    if (source != null)
                        source.append(
                                node,
                                text(reference.key()),
                                text(reference.file()),
                                number(reference.line()),
                                text(reference.kind()),
                                text(reference.evidence()));
                } else {
                    node.put("classId", number(reference.classId()));
                    node.put("className", text(reference.className()));
                    putNullable(node, "member", reference.member());
                    putNullable(node, "parameterIndex", reference.parameterIndex());
                    node.put("annotation", text(reference.annotation()));
                    putNullable(node, "source", reference.source());
                    String usageKind = text(reference.kind());
                    boolean dynamic = usageKind.equals("dynamic_config_key");
                    Resolution resolution =
                            dynamic
                                    ? Resolution.NONE
                                    : resolutions.getOrDefault(
                                            new ResolutionKey(text(reference.key()), usageKind),
                                            Resolution.NONE);
                    node.put("resolved", !dynamic && resolution.count() > 0);
                    node.put(
                            "resolution_status",
                            dynamic
                                    ? "unknown"
                                    : resolution.count() > 0
                                            ? "resolved"
                                            : view.equals("declared")
                                                    ? "no_indexed_definition"
                                                    : "unsatisfied");
                    node.put(
                            "resolution_strategy",
                            dynamic
                                    ? "dynamic_programmatic_lookup"
                                    : text(reference.annotation()).contains("#")
                                            ? "constant_programmatic_lookup"
                                            : "annotation_lookup");
                    node.put("confidence", dynamic ? 0.0 : 1.0);
                    node.put("definition_count", resolution.count());
                    ArrayNode files = node.putArray("definition_files");
                    resolution.files().forEach(files::add);
                }
            }
            ConfigurationCacheNames.append(reader, result, className, source);
            if (source != null) {
                source.finish(result);
                ConfigurationReadEvidence.append(reader, result);
                if (key != null && !key.isBlank())
                    result.set(
                            "configuration_resources",
                            ConfigurationResourceInventory.collect(reader, key));
                frameworkKeyPhases(reader, key, result);
                ApplicationKeyPhase.append(reader, key, result);
            }
            int unknownProfiles = reader.references.unknownTestProfiles(module);
            result.putObject("test_profile_coverage")
                    .put(
                            "supported",
                            "straight-line literal Map.of/ofEntries, singletonMap and emptyMap declarations")
                    .put("unknown_declarations", unknownProfiles)
                    .put("activation_proven", false)
                    .put("runtime_values_proven", false);
            result.put("definition_count", definitionCount);
            result.put("usage_count", usageCount);
            result.put("values_indexed", false);
            result.putArray("limitations")
                    .add(
                            "Values are not indexed; declared view emits only bounded non-sensitive scalar source declarations, never environment or evaluated expressions")
                    .add("Consumers cover supported annotations and common programmatic APIs")
                    .add("Environment-variable and YAML list lookups are not resolved")
                    .add(
                            "Dynamic programmatic keys are reported as unknown without guessing their value");
            appendPage(result, references.size(), total, limit, offset);
            appendMeta(result, reader, 0);
            return result;
        } catch (Exception error) {
            QuillLog.swallowed(error);
            return errorNode("Could not query indexed configuration references");
        }
    }

    private static final java.util.regex.Pattern FRAMEWORK_KEY =
            java.util.regex.Pattern.compile("quarkus\\.[a-z0-9][a-z0-9.\\-]*[a-z0-9]");

    /**
     * V41 CQ-039: whether a framework key named here is fixed at build time, from the classpath's
     * extension config models; application keys read by @ConfigProperty injection are read when the
     * bean is created.
     */
    public static void frameworkKeyPhases(
            org.treblereel.mcp.db.ReadQueryContext reader, String key, ObjectNode result) {
        String root = reader.index.getMetadata().get("project_root");
        if (root == null) return;
        java.util.Set<String> keys = new java.util.TreeSet<>();
        if (key != null && !key.contains("*")) keys.add(key);
        var matcher = FRAMEWORK_KEY.matcher(result.toString());
        while (matcher.find()) keys.add(matcher.group());
        List<String> phases = QuarkusConfigPhases.lookup(reader, Path.of(root), keys);
        if (phases.isEmpty()) return;
        result.set("framework_key_phases", JSON.valueToTree(phases));
        result.put(
                "framework_key_phases_basis",
                "phase and default from each extension's quarkus-config-model.json on the "
                        + "project classpath: RUN_TIME changes without a rebuild, BUILD_TIME and BUILD_AND_RUN_TIME_FIXED need one");
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int number(Integer value) {
        return value == null ? 0 : value;
    }

    private static void putNullable(ObjectNode node, String key, Object value) {
        if (value == null || value.toString().isBlank()) node.putNull(key);
        else if (value instanceof Number number) node.put(key, number.intValue());
        else node.put(key, value.toString());
    }
}
