package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.BeanRepository.ExternalTypeProvider;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.project.ProjectDependencyQueries;
import org.treblereel.mcp.mcp.query.ExternalSymbolQueries;
import org.treblereel.mcp.mcp.support.BeanTerms;

/**
 * Facts for a qualified name that is not an application class: an application package (the beans
 * declared directly in it) or a bean type supplied from dependencies (its providers).
 */
public final class NameTargetPrefetch {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int LIMIT = 40;

    private NameTargetPrefetch() {}

    /**
     * Beans declared in classes directly in {@code name}, without subpackages; null if no class is
     * there.
     */
    static ObjectNode packageFact(ReadQueryContext db, String name) {
        var rows = db.beans.findPackageBeans(name);
        var counts = db.symbols.findPackageClassCounts(name);
        if (counts.isEmpty()) return null;
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("kind", "package")
                        .put("package", name)
                        .put(
                                "scope_note",
                                "beans of classes directly in the package; subpackages excluded");
        ObjectNode classes = fact.putObject("classes_in_package_by_source_set");
        counts.forEach(row -> classes.put(String.valueOf(row.sourceSet()), row.classes()));
        fact.put("classes_counted", "every indexed class, nested and anonymous ones included");
        // V62 CQ-002: a zero states that no producer is declared here, so agents need not read
        // every class's members.
        ObjectNode kinds = fact.putObject("bean_kind_counts_main");
        boolean springOnly =
                !rows.isEmpty()
                        && rows.stream()
                                .allMatch(row -> BeanTerms.spring(String.valueOf(row.framework())));
        for (String kind :
                springOnly
                        ? List.of("CLASS", BeanTerms.SPRING_BEAN_METHOD)
                        : List.of("CLASS", "PRODUCER_METHOD", "PRODUCER_FIELD", "SYNTHETIC"))
            kinds.put(kind, 0);
        rows.stream()
                .filter(row -> "main".equals(row.sourceSet()))
                .map(row -> BeanTerms.kind((Object) row.framework(), row.kind()))
                .collect(
                        java.util.stream.Collectors.groupingBy(
                                kind -> kind,
                                java.util.TreeMap::new,
                                java.util.stream.Collectors.counting()))
                .forEach(kinds::put);
        ArrayNode beans = fact.putArray("beans");
        rows.stream()
                .limit(LIMIT)
                .forEach(
                        row ->
                                beans.add(
                                        row.sourceSet()
                                                + " "
                                                + BeanTerms.kind(
                                                        (Object) row.framework(), row.kind())
                                                + " "
                                                + row.className()
                                                + (row.memberName() == null
                                                        ? ""
                                                        : "#" + row.memberName())
                                                + " "
                                                + BeanTerms.scope(
                                                        (Object) row.framework(), row.scope())
                                                + " qualifiers "
                                                + BeanTerms.qualifiersJson(
                                                        row.framework(), row.qualifiers())
                                                + (row.memberName() == null
                                                        ? ""
                                                        : " types " + row.beanTypes())));
        if (rows.size() > LIMIT)
            fact.putObject("beans_truncated").put("shown", LIMIT).put("total", rows.size());
        return fact;
    }

    /** Application and dependency beans whose bean types include {@code type}; null if none. */
    static ObjectNode typeProviders(ReadQueryContext db, String type) {
        var external = db.beans.findExternalTypeProviders(type);
        var application = db.beans.findApplicationTypeProviders(type);
        if (external.isEmpty() && application.isEmpty()) return null;
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("kind", "bean_type_providers")
                        .put("bean_type", type)
                        .put(
                                "scope_note",
                                "indexed bean declarations by type, default-qualified first; eligibility and runtime selection not proven");
        fact.put("application_provider_count", application.size());
        if (!application.isEmpty()) {
            ArrayNode values = fact.putArray("application_providers");
            application.stream()
                    .limit(LIMIT)
                    .forEach(
                            row ->
                                    values.add(
                                            provider(
                                                            row.framework(),
                                                            row.kind(),
                                                            row.className(),
                                                            row.memberName())
                                                    + " "
                                                    + BeanTerms.scope(
                                                            (Object) row.framework(), row.scope())
                                                    + " qualifiers "
                                                    + BeanTerms.qualifiersJson(
                                                            row.framework(), row.qualifiers())));
        }
        if (!external.isEmpty()) {
            ArrayNode values = fact.putArray("dependency_providers");
            external.stream()
                    .limit(LIMIT)
                    .forEach(
                            row ->
                                    values.add(
                                            provider(
                                                            row.framework(),
                                                            row.kind(),
                                                            row.className(),
                                                            row.memberName())
                                                    + " "
                                                    + BeanTerms.scope(
                                                            (Object) row.framework(), row.scope())
                                                    + " qualifiers "
                                                    + BeanTerms.qualifiersJson(
                                                            row.framework(), row.qualifiers())
                                                    + " from "
                                                    + row.artifact()
                                                    + " ("
                                                    + row.framework()
                                                    + ")"
                                                    + ("[]".equals(String.valueOf(row.profiles()))
                                                            ? ""
                                                            : " profiles " + row.profiles())));
            appendArtifactDeclarations(db, fact, external.stream().limit(LIMIT).toList());
        }
        try {
            var registered = registeredDependencyBeans(db);
            ArrayNode evidence = fact.putArray("dependency_provider_evidence");
            for (var row : external.stream().limit(4).toList()) {
                String owner = String.valueOf(row.className());
                ObjectNode item = evidence.addObject().put("class", owner);
                item.set(
                        "producer_bytecode",
                        ExternalSymbolQueries.producerEvidence(
                                java.nio.file.Path.of(String.valueOf(row.jarPath())),
                                owner,
                                String.valueOf(row.memberName())));
                item.putObject("inspect")
                        .put("tool", "get_external_symbol_details")
                        .putObject("arguments")
                        .put("class_name", owner);
                if (registered != null)
                    item.put("class_registered_in_generated_build", registered.contains(owner));
                else item.put("registration_evidence", "generated_build_unavailable");
                item.put(
                        "registration_scope",
                        "indexed generated build classes; not runtime activation or method selection");
            }
            if (external.size() > 4) fact.put("dependency_provider_evidence_truncated", true);
        } catch (Exception unavailable) {
            fact.put("registration_evidence", "unavailable");
        }
        return fact;
    }

    /**
     * Whether each provider's artifact is a direct dependency, and where the project's POMs declare
     * and version it.
     */
    private static void appendArtifactDeclarations(
            ReadQueryContext db, ObjectNode fact, List<ExternalTypeProvider> providers) {
        String projectRoot = db.index.getMetadata().get("project_root");
        if (projectRoot == null) return;
        java.nio.file.Path root = java.nio.file.Path.of(projectRoot);
        if (!java.nio.file.Files.isRegularFile(root.resolve("pom.xml"))) return;
        java.util.Set<String> artifacts = new java.util.LinkedHashSet<>();
        String repositoryHint = null;
        for (var row : providers) {
            String artifact = String.valueOf(row.artifact());
            String[] parts = artifact.split(":");
            if (parts.length < 2) continue;
            artifacts.add(parts[0] + ":" + parts[1]);
            String jar = String.valueOf(row.jarPath());
            int at = jar.indexOf("/" + parts[0].replace('.', '/') + "/" + parts[1] + "/");
            if (repositoryHint == null && at > 0) repositoryHint = jar.substring(0, at);
        }
        if (artifacts.isEmpty()) return;
        java.nio.file.Path repository =
                repositoryHint != null
                        ? java.nio.file.Path.of(repositoryHint)
                        : java.nio.file.Path.of(
                                System.getProperty("user.home"), ".m2", "repository");
        List<String> modules = db.symbols.findClassModules();
        Map<String, org.treblereel.mcp.core.DependencyDeclarations.Declaration> declared =
                new java.util.LinkedHashMap<>();
        for (String module : modules.isEmpty() ? List.of(".") : modules)
            org.treblereel.mcp.core.DependencyDeclarations.locate(root, module, repository)
                    .forEach(declared::putIfAbsent);
        ArrayNode lines = fact.putArray("artifact_declarations");
        for (String ga : artifacts) {
            var declaration = declared.get(ga);
            lines.add(
                    declaration == null
                            ? ga + " not declared in the project's POMs (transitive)"
                            : ga
                                    + " direct, declared at "
                                    + String.join(", ", declaration.declaredAt())
                                    + (declaration.versionSource() == null
                                            ? ""
                                            : "; " + declaration.versionSource()));
        }
    }

    /**
     * What a prompt naming no indexed target can still start from: modules with their class counts,
     * build entry files, and the direct dependencies of the indexed classpath.
     */
    public static ObjectNode projectFact(ReadQueryContext db, java.nio.file.Path root)
            throws Exception {
        ObjectNode fact =
                JSON.createObjectNode()
                        .put("kind", "project")
                        .put(
                                "scope_note",
                                "indexed modules, build files and direct dependencies; no question-specific facts");
        ArrayNode modules = fact.putArray("modules");
        db.symbols
                .findCurrentClassCounts()
                .forEach(
                        row ->
                                modules.add(
                                        row.module()
                                                + " "
                                                + row.sourceSet()
                                                + " "
                                                + row.classes()
                                                + " classes"));
        ArrayNode builds = fact.putArray("build_files");
        db.index.findBuildEntryFiles().forEach(builds::add);
        var dependencies =
                new ProjectDependencyQueries()
                        .getProjectDependencies(
                                db, root, null, null, null, "direct", null, null, null, 200, 0);
        ArrayNode direct = fact.putArray("direct_dependencies");
        for (var dependency : dependencies.path("dependencies")) {
            List<String> scopes = new java.util.ArrayList<>();
            dependency.path("declared_scopes").forEach(scope -> scopes.add(scope.asText()));
            direct.add(dependency.path("id").asText() + (scopes.isEmpty() ? "" : " " + scopes));
        }
        if (dependencies.path("total").asInt() > direct.size())
            fact.putObject("direct_dependencies_truncated")
                    .put("shown", direct.size())
                    .put("total", dependencies.path("total").asInt());
        return fact;
    }

    /**
     * A class the Quarkus build generated, named in a prompt: its jar entry and the application
     * class it was generated for, whose source is what to edit. Null when {@code name} is none.
     */
    static ObjectNode generatedClassFact(ReadQueryContext db, String name) throws Exception {
        JsonNode generated = generated(db);
        if (generated == null) return null;
        for (JsonNode entry : generated.path("classes")) {
            if (!entry.path("class").asText().equals(name)
                    && !entry.path("class").asText().replace('$', '.').equals(name)) continue;
            ObjectNode fact =
                    JSON.createObjectNode()
                            .put("kind", "generated_class")
                            .put("class", entry.path("class").asText())
                            .put(
                                    "scope_note",
                                    "Quarkus build output; regenerated from application sources on the next build, so "
                                            + "edit the source class, not this one");
            fact.put("artifact", entry.path("jar").asText() + "!/" + entry.path("entry").asText());
            fact.put("generated_for", entry.path("source_class").asText());
            String source = sourceFile(db, entry.path("source_class").asText());
            if (source != null) fact.put("generated_for_source", source);
            fact.set("references", entry.path("references"));
            freshness(generated, entry.path("jar").asText(), fact);
            return fact;
        }
        return null;
    }

    /** Generated classes recorded for {@code className}, one line each, onto a class fact. */
    static void appendGenerated(ReadQueryContext db, ObjectNode fact, String className)
            throws Exception {
        appendGenerated(db, fact, className, null);
    }

    static void appendGenerated(
            ReadQueryContext db, ObjectNode fact, String className, java.nio.file.Path root)
            throws Exception {
        JsonNode generated = generated(db);
        if (generated == null) return;
        ArrayNode lines = JSON.createArrayNode();
        String jar = null;
        for (JsonNode entry : generated.path("classes")) {
            if (!entry.path("source_class").asText().equals(className)) continue;
            jar = entry.path("jar").asText();
            if (root != null
                    && entry.path("class").asText().endsWith("_Bean")
                    && fact.path("generated_creation_evidence").size() < 2) {
                String generatedName = entry.path("class").asText();
                var creation =
                        ExternalSymbolQueries.producerEvidence(
                                root.resolve(jar), generatedName, "doCreate");
                if (creation.path("declarations").isEmpty())
                    creation =
                            ExternalSymbolQueries.producerEvidence(
                                    root.resolve(jar), generatedName, "create");
                var resultCalls = JSON.createObjectNode();
                creation.path("method_calls")
                        .properties()
                        .forEach(
                                call -> {
                                    if (call.getValue().has("result_calls"))
                                        resultCalls.put(
                                                call.getKey(),
                                                call.getValue().path("result_calls").asText());
                                });
                creation.remove("declarations");
                creation.set("method_calls", resultCalls);
                creation.put(
                        "scope",
                        "Direct calls that construct or return the generated method result type; runtime execution not proven");
                if (!resultCalls.isEmpty()) {
                    creation.put("generated_class", generatedName);
                    freshness(generated, jar, creation);
                    fact.withArray("generated_creation_evidence").add(creation);
                }
            }
            List<String> references = new java.util.ArrayList<>();
            entry.path("references")
                    .forEach(
                            reference -> {
                                if (!reference.asText().equals(className))
                                    references.add(reference.asText());
                            });
            lines.add(
                    entry.path("jar").asText()
                            + "!/"
                            + entry.path("entry").asText()
                            + (references.isEmpty()
                                    ? ""
                                    : " references " + String.join(", ", references)));
        }
        if (lines.isEmpty()) return;
        fact.set("generated_classes", lines);
        ObjectNode note =
                fact.putObject("generated_classes_basis")
                        .put(
                                "scope",
                                "Quarkus build output generated for this class; edit this class's source, not them");
        freshness(generated, jar, note);
    }

    private static void freshness(JsonNode generated, String jar, ObjectNode target) {
        for (JsonNode node : generated.path("jars"))
            if (node.path("path").asText().equals(jar)) {
                if (node.has("modified"))
                    target.put("jar_modified", node.path("modified").asText());
                if (node.has("older_than_compiled_classes"))
                    target.put(
                            "jar_older_than_compiled_classes",
                            node.path("older_than_compiled_classes").asBoolean());
            }
    }

    /**
     * Dependency classes the Quarkus build registered as beans, or null without a
     * generated-bytecode jar.
     */
    static java.util.Set<String> registeredDependencyBeans(ReadQueryContext db) throws Exception {
        JsonNode generated = generated(db);
        if (generated == null || !generated.has("dependency_bean_classes")) return null;
        java.util.Set<String> names = new java.util.HashSet<>();
        generated.path("dependency_bean_classes").forEach(name -> names.add(name.asText()));
        return names;
    }

    private static JsonNode generated(ReadQueryContext db) throws Exception {
        String raw =
                db.index
                        .getMetadata()
                        .get(org.treblereel.mcp.core.GeneratedBytecodeScanner.METADATA_KEY);
        return raw == null ? null : JSON.readTree(raw);
    }

    private static String sourceFile(ReadQueryContext db, String className) {
        return db.symbols.findCurrentSourceFile(className).orElse(null);
    }

    private static String provider(
            String framework, String kind, String className, String memberName) {
        return BeanTerms.kind((Object) framework, kind)
                + " "
                + className
                + (memberName == null ? "" : "#" + memberName);
    }
}
