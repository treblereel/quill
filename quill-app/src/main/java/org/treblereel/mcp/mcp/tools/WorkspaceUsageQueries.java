package org.treblereel.mcp.mcp.tools;

import static org.treblereel.mcp.mcp.tools.WorkspaceResponses.*;

import org.treblereel.mcp.index.BuildStatusInspector;
import org.treblereel.mcp.workspace.WorkspaceProjectScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.DependencyIndexer;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.workspace.WorkspaceCoordinateCatalog;
import org.treblereel.mcp.workspace.WorkspaceDependencyGraph;
import org.treblereel.mcp.mcp.support.BeanTerms;
import org.treblereel.mcp.mcp.support.ProjectAvailabilityResponses;
import org.treblereel.mcp.mcp.project.ProjectRegistry;
import org.treblereel.mcp.mcp.semantic.SymbolContract;
import org.treblereel.mcp.mcp.support.ToolResponseSupport;
import org.treblereel.mcp.mcp.query.UsageToolQueries;

/** Cross-repository usages of a provider class or member, read from each consumer index. */
final class WorkspaceUsageQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectRegistry registry;
    private final WorkspaceProviders providerLookup;

    WorkspaceUsageQueries(ProjectRegistry registry, WorkspaceProviders providers) {
        this.registry = registry;
        this.providerLookup = providers;
    }

    String findUsages(String target, String providerRepository, String usageKind,
            int limit, int consumerLimit, int offset) {
        return findUsages(target, providerRepository, usageKind, limit, consumerLimit,
                offset, 0);
    }

    String findUsages(String target, String providerRepository, String usageKind,
            int limit, int consumerLimit, int offset, int consumerOffset) {
        return findUsages(target, providerRepository, usageKind, limit, consumerLimit, offset, consumerOffset, false);
    }

    private String findUsages(String target, String providerRepository, String usageKind,
            int limit, int consumerLimit, int offset, int consumerOffset, boolean nested) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return workspaceRequired();
        SymbolContract.Reference member;
        try {
            member = SymbolContract.parse(target).orElse(null);
        } catch (SymbolContract.ParseException invalid) {
            return ToolResponseSupport.errorResponse(invalid.errorCode(), invalid.getMessage());
        }
        if (member != null && Set.of("METHOD", "CONSTRUCTOR", "FIELD").contains(member.kind())) {
            return findMemberUsages(member, target, providerRepository, usageKind,
                    limit, consumerLimit, offset, consumerOffset, scope);
        }
        if (member != null && "PROPERTY".equals(member.kind())) {
            return ToolResponseSupport.errorResponse("UNSUPPORTED_SYMBOL_KIND",
                    "Workspace property usages require a resolved JVM field or accessor symbol_id");
        }
        if (consumerOffset > 0) {
            return ToolResponseSupport.errorResponse("INVALID_ARGUMENT",
                    "consumer_offset applies only to method, constructor, or field symbol_id queries");
        }
        ProjectRegistry.Resolution providerAvailability = null;
        if (providerRepository != null && !providerRepository.isBlank()) {
            providerAvailability = registry.resolve(providerRepository);
        }
        List<ProviderClass> providers = providerLookup.findProviderClasses(target, providerRepository);
        if (providers.isEmpty()) {
            if (providerAvailability != null && providerAvailability.projects().isEmpty()
                    && !providerAvailability.issues().isEmpty()) {
                return ProjectAvailabilityResponses.error(providerAvailability.issues());
            }
            return error("Workspace class not found: " + target);
        }
        if (providers.size() > 1) {
            ObjectNode ambiguous = JSON.createObjectNode();
            ambiguous.put("error", "ambiguous_workspace_class");
            ambiguous.put("target", target);
            ArrayNode values = ambiguous.putArray("candidates");
            providers.forEach(provider -> values.add(providerJson(provider)));
            return ambiguous.toString();
        }

        ProviderClass provider = providers.getFirst();
        WorkspaceCoordinateCatalog.Result catalog =
                WorkspaceCoordinateCatalog.discover(scope.manifest());
        WorkspaceCoordinateCatalog.Module providerModule = catalog.modules().stream()
                .filter(module -> module.repository().equals(provider.repository())
                        && module.module().equals(provider.module()))
                .findFirst().orElse(null);
        if (providerModule == null || providerModule.ga() == null) {
            return error("No build coordinate for provider module " + provider.repository()
                    + ":" + provider.module());
        }

        WorkspaceDependencyGraph.Result graph =
                WorkspaceDependencyGraph.discover(scope.manifest());
        List<WorkspaceDependencyGraph.Edge> consumers = graph.edges().stream()
                .filter(WorkspaceDependencyGraph.Edge::crossRepository)
                .filter(edge -> edge.providerRepository().equals(provider.repository())
                        && edge.providerModule().equals(provider.module()))
                .toList();
        Map<String, WorkspaceDependencyGraph.Edge> consumerEdges = new LinkedHashMap<>();
        consumers.forEach(edge -> consumerEdges.putIfAbsent(edge.consumerRepository(), edge));
        List<ConsumerUsage> usages = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        int unavailableConsumers = 0;
        UsageToolQueries usageQueries = new UsageToolQueries();
        List<ProjectRegistry.ProjectEntry> consumerProjects = new ArrayList<>();
        for (var consumer : consumerEdges.entrySet()) {
            ProjectRegistry.Resolution resolved = registry.resolve(consumer.getKey());
            if (resolved.projects().isEmpty()) {
                unavailableConsumers++;
                diagnostics.addAll(resolved.errors());
                continue;
            }
            consumerProjects.add(resolved.projects().getFirst());
        }
        List<ProjectQueryExecutor.QueryResult> queryResults =
                new ProjectQueryExecutor(registry).executeProjects(consumerProjects,
                        (consumer, context) -> consumerUsages(usageQueries, context,
                                provider.className(), usageKind, consumerLimit,
                                consumerEdges.get(consumer.name())), nested);
        int failedConsumers = 0;
        int truncatedConsumers = 0;
        int resolvedConsumers = 0;
        List<ConsumerResolutionFailure> unresolvedConsumers = new ArrayList<>();
        for (ProjectQueryExecutor.QueryResult result : queryResults) {
            if (!result.successful()) {
                failedConsumers++;
                diagnostics.add("Repository '" + result.project()
                        + "' query failed: " + result.error());
                continue;
            }
            try {
                var data = JSON.readTree(result.json());
                if (hasToolError(data)) {
                    unresolvedConsumers.add(new ConsumerResolutionFailure(
                            result.project(), data));
                    continue;
                }
                resolvedConsumers++;
                if (data.path("usage_group_count").asInt() > 0) {
                    if (data.path("truncated").asBoolean()) truncatedConsumers++;
                    usages.add(new ConsumerUsage(result.project(),
                            consumerEdges.get(result.project()), data));
                }
            } catch (Exception invalid) {
                failedConsumers++;
                diagnostics.add("Repository '" + result.project()
                        + "' returned invalid usage data: " + invalid.getMessage());
            }
        }
        diagnostics.addAll(graph.diagnostics());

        usages.sort(java.util.Comparator.comparing(ConsumerUsage::repository));
        int total = usages.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        ObjectNode root = JSON.createObjectNode();
        root.put("target", provider.className());
        ObjectNode providerNode = root.putObject("provider");
        providerNode.put("repository", provider.repository());
        providerNode.put("module", provider.module());
        providerNode.put("coordinate", providerModule.ga());
        providerNode.put("version", providerModule.version());
        ArrayNode values = root.putArray("consumers");
        for (ConsumerUsage usage : usages.subList(from, to)) {
            ObjectNode node = values.addObject();
            node.put("repository", usage.repository());
            node.put("resolved_binary_version", usage.edge().resolvedBinaryVersion());
            node.put("version_status", usage.edge().status());
            node.set("usage", usage.data());
        }
        ToolResponseSupport.appendPage(root, to - from, total, limit, offset);
        root.put("candidate_consumer_count", consumerEdges.size());
        root.put("queried_consumer_count", consumerProjects.size());
        root.put("resolved_consumer_count", resolvedConsumers);
        root.put("unresolved_consumer_count", unresolvedConsumers.size());
        root.put("unavailable_consumer_count", unavailableConsumers);
        root.put("failed_consumer_count", failedConsumers);
        root.put("truncated_consumer_count", truncatedConsumers);
        root.put("consumer_usage_limit", consumerLimit);
        root.put("complete", graph.complete() && unavailableConsumers == 0
                && failedConsumers == 0 && unresolvedConsumers.isEmpty()
                && truncatedConsumers == 0);
        ArrayNode unresolved = root.putArray("unresolved_consumers");
        unresolvedConsumers.stream().limit(20).forEach(value -> {
            ObjectNode node = unresolved.addObject();
            node.put("repository", value.repository());
            node.put("status", errorCode(value.data(), "UNKNOWN_ERROR"));
            node.put("message", errorMessage(value.data(), "unknown error"));
            copyIfPresent(value.data(), node, "target_resolution");
            copyIfPresent(value.data(), node, "resolution_strategies_checked");
            copyIfPresent(value.data(), node, "dependency_evidence");
            copyIfPresent(value.data(), node, "limitations");
        });
        root.put("unresolved_consumers_truncated", unresolvedConsumers.size() > 20);
        appendDiagnostics(root, diagnostics);
        if (providerAvailability != null && !providerAvailability.issues().isEmpty()) {
            ProjectAvailabilityResponses.append(root, "provider_warnings",
                    providerAvailability.issues());
        }
        root.putArray("limitations").add(
                "Only repositories declaring the provider artifact are queried");
        return root.toString();
    }

    private String findMemberUsages(SymbolContract.Reference reference, String symbolId,
            String providerRepository, String usageKind, int limit, int consumerLimit,
            int offset, int consumerOffset, WorkspaceProjectScope scope) {
        String expectedKind = "FIELD".equals(reference.kind()) ? "field_access"
                : "CONSTRUCTOR".equals(reference.kind()) ? "constructor_call" : "method_call";
        if (usageKind != null && !usageKind.isBlank() && !"all".equalsIgnoreCase(usageKind)
                && !expectedKind.equalsIgnoreCase(usageKind)) {
            return ToolResponseSupport.errorResponse("INVALID_ARGUMENT",
                    "usage_kind for this symbol must be " + expectedKind + " or all");
        }
        List<ProviderClass> providers = providerLookup.findProviderClasses(reference.className(), providerRepository)
                .stream().filter(value -> reference.matchesSource(value.sourceFile())).toList();
        if (providers.size() != 1) {
            ObjectNode error = JSON.createObjectNode();
            ToolResponseSupport.appendError(error, providers.isEmpty()
                    ? "SYMBOL_NOT_FOUND" : "AMBIGUOUS_WORKSPACE_SYMBOL",
                    providers.isEmpty() ? "Provider declaration is not indexed"
                            : "Select one provider_repository for this symbol_id");
            error.put("symbol_id", symbolId);
            providers.forEach(value -> error.putArray("candidates").add(providerJson(value)));
            return error.toString();
        }
        ProviderClass provider = providers.getFirst();
        ProjectRegistry.Resolution providerResolution = registry.resolve(provider.repository());
        if (providerResolution.projects().isEmpty()) {
            return ToolResponseSupport.errorResponse("PROVIDER_INDEX_UNAVAILABLE",
                    "Provider index is unavailable");
        }
        try (ReadQueryContext providerDb = ReadQueryContext.open(providerResolution.projects().getFirst().jdbi())) {
        if (org.treblereel.mcp.index.BuildStatusInspector.freshness(providerDb, 0, 0).structureStale()) {
            return ToolResponseSupport.errorResponse("PROVIDER_INDEX_STALE",
                    "Provider index is stale; refresh it before matching member usages");
        }
        List<ClassRecord> providerClasses = providerDb.symbols.findClassesByName(reference.className()).stream()
                .filter(cls -> !"dependency".equals(cls.origin())
                        && provider.module().equals(cls.module())
                        && (reference.sourceFile().isBlank()
                            || reference.sourceFile().equals(cls.sourceFile())))
                .toList();
        if (providerClasses.size() != 1) {
            return ToolResponseSupport.errorResponse("PROVIDER_SYMBOL_AMBIGUOUS",
                    "Provider class identity is not unique in the index");
        }
        List<ClassMemberRecord> providerMembers = matchingMembers(providerDb,
                providerClasses.getFirst(), reference);
        if (providerMembers.size() != 1 || reference.descriptor().isBlank()) {
            return ToolResponseSupport.errorResponse("SYMBOL_NOT_FOUND",
                    "The exact member descriptor is not present in the provider index");
        }
        WorkspaceCoordinateCatalog.Result catalog = WorkspaceCoordinateCatalog.discover(scope.manifest());
        WorkspaceCoordinateCatalog.Module providerModule = catalog.modules().stream()
                .filter(value -> value.repository().equals(provider.repository())
                        && value.module().equals(provider.module())).findFirst().orElse(null);
        if (providerModule == null || providerModule.ga() == null) {
            return ToolResponseSupport.errorResponse("PROVIDER_COORDINATE_UNKNOWN",
                    "Provider module has no unambiguous build coordinate");
        }
        WorkspaceDependencyGraph.Result graph = WorkspaceDependencyGraph.discover(scope.manifest());
        List<WorkspaceDependencyGraph.Edge> edges = graph.edges().stream()
                .filter(WorkspaceDependencyGraph.Edge::crossRepository)
                .filter(edge -> edge.providerRepository().equals(provider.repository())
                        && edge.providerModule().equals(provider.module())
                        && edge.coordinate().equals(providerModule.ga())).toList();
        edges = expandTestSourceSets(edges, catalog);
        List<ConsumerUsage> usages = new ArrayList<>();
        ArrayNode unresolved = JSON.createArrayNode();
        int resolved = 0, queried = 0, truncated = 0;
        for (WorkspaceDependencyGraph.Edge edge : edges) {
            ObjectNode issue = unresolved.addObject();
            issue.put("repository", edge.consumerRepository());
            issue.put("module", edge.consumerModule());
            issue.put("source_set", edge.sourceSet());
            issue.put("resolved_binary_version", edge.resolvedBinaryVersion());
            issue.put("version_status", edge.status());
            if (!"version_match".equals(edge.status())) {
                issue.put("status", "binary_behind_checkout".equals(edge.status())
                        ? "version_mismatch" : "version_unknown");
                continue;
            }
            ProjectRegistry.Resolution consumerResolution = registry.resolve(edge.consumerRepository());
            if (consumerResolution.projects().isEmpty()) {
                issue.put("status", "consumer_index_unavailable");
                continue;
            }
            queried++;
            try (ReadQueryContext consumerDb = ReadQueryContext.open(consumerResolution.projects().getFirst().jdbi())) {
            if (!"complete".equals(consumerDb.index.getMetadata().get("dependency_index"))) {
                issue.put("status", "dependency_index_incomplete");
                continue;
            }
            if (org.treblereel.mcp.index.BuildStatusInspector.freshness(consumerDb, 0, 0).structureStale()) {
                issue.put("status", "consumer_index_stale");
                continue;
            }
            WorkspaceCoordinateCatalog.Module consumerModule = catalog.modules().stream()
                    .filter(value -> value.repository().equals(edge.consumerRepository())
                            && value.module().equals(edge.consumerModule()))
                    .findFirst().orElse(null);
            String binaryStatus = binaryOwnership(consumerResolution.projects().getFirst().root(),
                    consumerModule, edge, reference.className());
            if (binaryStatus != null) {
                issue.put("status", binaryStatus);
                continue;
            }
            List<ClassRecord> binaryClasses = consumerDb.symbols.findClassesByName(reference.className()).stream()
                    .filter(cls -> "dependency".equals(cls.origin())).toList();
            if (binaryClasses.size() != 1) {
                issue.put("status", binaryClasses.isEmpty()
                        ? "consumer_binary_class_missing" : "consumer_binary_class_ambiguous");
                continue;
            }
            ClassRecord binaryClass = binaryClasses.getFirst();
            if (matchingMembers(consumerDb, binaryClass, reference).size() != 1) {
                issue.put("status", "consumer_binary_member_missing");
                continue;
            }
            ObjectNode data = memberUsages(consumerDb, binaryClass, reference, edge,
                    consumerLimit, consumerOffset);
            resolved++;
            if (data.path("has_more").asBoolean()) truncated++;
            if (data.path("usage_group_count").asInt() > 0) {
                usages.add(new ConsumerUsage(edge.consumerRepository(), edge, data));
            }
            unresolved.remove(unresolved.size() - 1);
        }
}
        usages.sort(java.util.Comparator.comparing(ConsumerUsage::repository)
                .thenComparing(value -> value.edge().consumerModule())
                .thenComparing(value -> value.edge().sourceSet()));
        int from = Math.min(offset, usages.size());
        int to = (int) Math.min((long) from + limit, usages.size());
        ObjectNode root = JSON.createObjectNode();
        root.put("target", reference.className());
        root.put("symbol_id", symbolId);
        root.put("granularity", "member");
        root.put("kind", reference.kind().toLowerCase(java.util.Locale.ROOT));
        root.put("name", reference.jvmName());
        root.put("descriptor", reference.descriptor());
        ObjectNode providerNode = root.putObject("provider");
        providerNode.put("repository", provider.repository());
        providerNode.put("module", provider.module());
        providerNode.put("coordinate", providerModule.ga());
        providerNode.put("version", providerModule.version());
        ArrayNode values = root.putArray("consumers");
        for (ConsumerUsage usage : usages.subList(from, to)) {
            ObjectNode node = values.addObject();
            node.put("repository", usage.repository());
            node.put("module", usage.edge().consumerModule());
            node.put("source_set", usage.edge().sourceSet());
            node.put("resolved_binary_version", usage.edge().resolvedBinaryVersion());
            node.put("version_status", usage.edge().status());
            node.set("usage", usage.data());
        }
        ToolResponseSupport.appendPage(root, to - from, usages.size(), limit, offset);
        root.put("candidate_consumer_count", edges.size());
        root.put("queried_consumer_count", queried);
        root.put("resolved_consumer_count", resolved);
        root.put("unresolved_consumer_count", unresolved.size());
        root.put("truncated_consumer_count", truncated);
        root.put("consumer_usage_limit", consumerLimit);
        root.put("consumer_usage_offset", consumerOffset);
        root.put("complete", graph.complete() && unresolved.isEmpty() && truncated == 0);
        root.put("unresolved_consumers_truncated", unresolved.size() > 20);
        ArrayNode reportedUnresolved = JSON.createArrayNode();
        for (int i = 0; i < Math.min(20, unresolved.size()); i++) {
            reportedUnresolved.add(unresolved.get(i));
        }
        root.set("unresolved_consumers", reportedUnresolved);
        if (truncated > 0) {
            int nextOffset = (int) Math.min((long) consumerOffset + consumerLimit,
                    Integer.MAX_VALUE);
            ObjectNode next = root.putObject("next_usage_page_request");
            next.put("tool", "find_workspace_usages");
            ObjectNode args = next.putObject("arguments");
            args.put("target", symbolId);
            args.put("provider_repository", provider.repository());
            if (usageKind != null) args.put("usage_kind", usageKind);
            args.put("limit", limit);
            args.put("consumer_limit", consumerLimit);
            args.put("consumer_offset", nextOffset);
            args.put("offset", offset);
        }
        appendDiagnostics(root, graph.diagnostics());
        root.putArray("limitations")
                .add("Only indexed bytecode calls and field accesses in the matched consumer source set are reported")
                .add("Reflection and dynamically selected targets are not inferred")
                .add("Classpath order and indexed binary identity are checked; binary content hashes are not compared");
        return root.toString();
    }
}

    private List<WorkspaceDependencyGraph.Edge> expandTestSourceSets(
            List<WorkspaceDependencyGraph.Edge> edges, WorkspaceCoordinateCatalog.Result catalog) {
        List<WorkspaceDependencyGraph.Edge> expanded = new ArrayList<>(edges);
        for (WorkspaceDependencyGraph.Edge edge : edges) {
            if (!"main".equals(edge.sourceSet())) continue;
            ProjectRegistry.Resolution resolution = registry.resolve(edge.consumerRepository());
            if (resolution.projects().isEmpty()) continue;
            try (ReadQueryContext db = ReadQueryContext.open(resolution.projects().getFirst().jdbi())) {
            boolean hasTestClasses = db.symbols.hasTestClasses(edge.consumerModule());
            if (!hasTestClasses) continue;
            WorkspaceCoordinateCatalog.Module module = catalog.modules().stream()
                    .filter(value -> value.repository().equals(edge.consumerRepository())
                            && value.module().equals(edge.consumerModule()))
                    .findFirst().orElse(null);
            String version = null;
            if (module != null) {
                Path root = resolution.projects().getFirst().root();
                Path moduleRoot = ".".equals(module.module()) ? root
                        : root.resolve(module.module()).normalize();
                BuildSystem system = BuildSystem.valueOf(
                        module.buildSystem().toUpperCase(java.util.Locale.ROOT));
                version = WorkspaceDependencyGraph.resolvedVersion(
                        DependencyIndexer.parseClasspathFile(system.testClasspathFile(moduleRoot)),
                        edge.coordinate());
            }
            String status = version == null ? "binary_version_unknown"
                    : version.equals(edge.checkoutVersion()) ? "version_match"
                    : "binary_behind_checkout";
            expanded.add(new WorkspaceDependencyGraph.Edge(edge.consumerRepository(),
                    edge.consumerModule(), edge.providerRepository(), edge.providerModule(),
                    edge.coordinate(), edge.scopes(), "test", "captured_test_runtime_classpath",
                    edge.checkoutVersion(), version, status, edge.crossRepository(),
                    edge.ambiguousProvider()));
        }
}
        return expanded;
    }

    private static List<ClassMemberRecord> matchingMembers(ReadQueryContext db, ClassRecord cls,
            SymbolContract.Reference reference) {
        return db.symbols.findClassMembers(cls.id()).stream()
                .filter(member -> member.kind().equals(reference.kind())
                        && member.name().equals(reference.jvmName())
                        && member.descriptor().equals(reference.descriptor())).toList();
    }

    private static String binaryOwnership(Path repositoryRoot,
            WorkspaceCoordinateCatalog.Module module, WorkspaceDependencyGraph.Edge edge,
            String className) {
        if (module == null) return "consumer_module_unknown";
        Path moduleRoot = ".".equals(module.module()) ? repositoryRoot
                : repositoryRoot.resolve(module.module()).normalize();
        BuildSystem system = BuildSystem.valueOf(module.buildSystem().toUpperCase(java.util.Locale.ROOT));
        Path classpath = "test".equals(edge.sourceSet())
                ? system.testClasspathFile(moduleRoot) : system.classpathFile(moduleRoot);
        if (!Files.isRegularFile(classpath)) return "consumer_classpath_missing";
        String entry = className.replace('.', '/') + ".class";
        try {
            String contents = Files.readString(classpath).strip();
            if (contents.isEmpty()) return "consumer_binary_class_unattributed";
            for (String item : contents.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                Path candidate = Path.of(item).toAbsolutePath().normalize();
                if (Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve(entry))) {
                    return "consumer_binary_class_shadowed";
                }
                if (!item.endsWith(".jar")) continue;
                Path jar = Path.of(item).toAbsolutePath().normalize();
                if (!Files.isRegularFile(jar)) return "consumer_classpath_missing";
                try (JarFile archive = new JarFile(jar.toFile(), false)) {
                    if (archive.getJarEntry(entry) == null) continue;
                }
                String version = WorkspaceDependencyGraph.resolvedVersion(List.of(jar), edge.coordinate());
                return edge.resolvedBinaryVersion().equals(version)
                        ? null : "consumer_binary_class_shadowed";
            }
            return "consumer_binary_class_unattributed";
        } catch (IOException invalid) {
            return "consumer_classpath_unreadable";
        }
    }

    private static ObjectNode memberUsages(ReadQueryContext db, ClassRecord target,
            SymbolContract.Reference reference, WorkspaceDependencyGraph.Edge edge,
            int limit, int offset) {
        boolean field = "FIELD".equals(reference.kind());
        String bytecodeName = "CONSTRUCTOR".equals(reference.kind()) ? "<init>" : reference.jvmName();
        var page = db.usages.findWorkspaceMemberUsages(target.id(), field, bytecodeName,
                reference.descriptor(), edge.consumerModule(), edge.sourceSet(), limit, offset);
        int[] count = {page.groups(), page.occurrences()};
            ObjectNode result = JSON.createObjectNode();
            result.put("usage_group_count", count[0]);
            result.put("usage_occurrence_count", count[1]);
            ArrayNode rows = result.putArray("usages");
            boolean classifiedDynamic = "2".equals(db.index.getMetadata().get("invokedynamic_evidence_protocol"));
            page.rows().stream().map(row -> {
                        ObjectNode node = JSON.createObjectNode();
                        node.put("class", row.className());
                        node.put("caller_method", row.fromMethod());
                        node.put("caller_descriptor", row.fromDescriptor());
                        node.put("occurrence_count", row.occurrenceCount());
                        String kind = row.bytecodeKind();
                        node.put(field ? "access_kind" : "invocation_kind", kind);
                        node.put("usage_kind", field ? kind.startsWith("read_") ? "read" : "write" : "dynamic".equals(kind)
                                ? classifiedDynamic ? "callback_reference" : "dynamic_handle_reference" : "call");
                        if (!field) node.put("execution_semantics", "dynamic".equals(kind)
                                ? classifiedDynamic ? "callback_creation" : "bootstrap_reference_unknown" : "invocation_instruction");
                        if (!field && "dynamic".equals(kind)) node.put("reference_basis",classifiedDynamic ? "lambda_metafactory" : "legacy_unclassified");
                        try {
                            node.set("evidence_lines", JSON.readTree(row.evidenceLines()));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                            throw new IllegalStateException("Invalid indexed evidence lines", invalid);
                        }
                        String source = row.sourceFile();
                        if (source == null) node.putNull("source");
                        else node.put("source", source);
                        return node;
                    }).forEach(rows::add);
            ToolResponseSupport.appendPage(result, rows.size(), count[0], limit, offset);
            result.put("answer_complete", !result.path("has_more").asBoolean());
            Map<String, String> metadata = db.index.getMetadata();
            ObjectNode snapshot = result.putObject("index_snapshot");
            snapshot.put("index_id", metadata.getOrDefault("index_id", "unknown"));
            snapshot.put("indexed_commit", metadata.getOrDefault("last_commit", "unknown"));
            return result;
    }

    private static String consumerUsages(UsageToolQueries usageQueries, ReadQueryContext reader,
            String target, String usageKind, int limit, WorkspaceDependencyGraph.Edge edge) {
        String response = usageQueries.findUsages(
                reader, target, usageKind, null, limit, 0, false);
        try {
            JsonNode parsed = JSON.readTree(response);
            if (!isClassNotFound(parsed)) {
                return response;
            }
            List<org.treblereel.mcp.model.ExternalBeanRecord> beans =
                    reader.beans.findExternalBeans(Map.of("class_name", target));
            boolean includeDiscovery = usageKind == null || usageKind.isBlank()
                    || "all".equalsIgnoreCase(usageKind)
                    || "bean_discovery".equalsIgnoreCase(usageKind);
            List<org.treblereel.mcp.model.ExternalBeanRecord> matches = includeDiscovery
                    ? beans : List.of();
            List<org.treblereel.mcp.model.ExternalDepRecord> allReferences =
                    reader.usages.findExternalTypeUsages(target);
            List<org.treblereel.mcp.model.ExternalDepRecord> references = allReferences.stream()
                            .filter(reference -> matchesExternalUsageKind(
                                    usageKind, reference.usageKind()))
                            .toList();
            if (beans.isEmpty() && allReferences.isEmpty()) {
                return unresolvedArtifactDependency(target, edge).toString();
            }
            Map<Integer, ClassRecord> callers = reader.symbols.findClassesByIds(references.stream().map(org.treblereel.mcp.model.ExternalDepRecord::classId)
                            .distinct().toList());
            ObjectNode root = JSON.createObjectNode();
            root.put("target", target);
            root.put("granularity", beans.isEmpty() ? "dependency_type"
                    : allReferences.isEmpty() ? "dependency_bean" : "dependency_evidence");
            root.put("target_resolution", beans.isEmpty() ? "external_type_index"
                    : allReferences.isEmpty() ? "external_bean_index"
                    : "external_type_and_bean_index");
            appendResolutionStrategies(root);
            if (usageKind == null || usageKind.isBlank()) root.putNull("usage_kind");
            else root.put("usage_kind", usageKind);
            int total = matches.size() + references.size();
            root.put("usage_group_count", total);
            root.put("usage_occurrence_count", total);
            ArrayNode usages = root.putArray("usages");
            int[] remaining = {limit};
            matches.stream().limit(remaining[0]).forEach(bean -> {
                ObjectNode node = usages.addObject();
                node.put("class", bean.className());
                node.put("usage_kind", "bean_discovery");
                node.put("indexed_kind", "EXTERNAL_BEAN");
                node.put("occurrences", 1);
                node.put("origin", "dependency");
                node.put("framework", bean.framework());
                node.put("bean_kind", BeanTerms.kind(bean.framework(), bean.kind()));
                if (bean.scope() == null) node.putNull("scope");
                else node.put("scope", BeanTerms.scope(bean.framework(), bean.scope()));
                if (bean.artifact() == null) node.putNull("artifact");
                else node.put("artifact", bean.artifact());
                node.put("reason", "Framework bean discovery exposes this dependency bean "
                        + "without a direct application class reference");
                ArrayNode configuration = node.putArray("required_configuration");
                bean.injectionPoints().stream()
                        .filter(injection -> injection.configurationKey() != null
                                && injection.configurationRequired())
                        .forEach(injection -> configuration.addObject()
                                .put("key", injection.configurationKey())
                                .put("member", injection.member()));
                remaining[0]--;
            });
            references.stream().limit(remaining[0]).forEach(reference -> {
                ClassRecord caller = callers.get(reference.classId());
                if (caller == null) return;
                ObjectNode node = usages.addObject();
                node.put("class", caller.className());
                node.put("usage_kind", externalUsageKind(reference.usageKind()));
                node.put("indexed_kind", "EXTERNAL_" + reference.usageKind());
                node.put("occurrences", 1);
                node.put("origin", caller.origin());
                if (caller.sourceFile() == null) node.putNull("source");
                else node.put("source", (caller.sourceLine() > 0 ? caller.sourceFile() + ":" + caller.sourceLine() : caller.sourceFile()));
                if (caller.module() == null) node.putNull("module");
                else node.put("module", caller.module());
                if (caller.sourceSet() == null) node.putNull("source_set");
                else node.put("source_set", caller.sourceSet());
                node.put("reason", "The consumer bytecode signature references the workspace "
                        + "provider type");
            });
            ToolResponseSupport.appendPage(root, usages.size(), total, limit, 0);
            Map<String, String> metadata = reader.index.getMetadata();
            ObjectNode snapshot = root.putObject("index_snapshot");
            snapshot.put("index_id", metadata.getOrDefault("index_id", "unknown"));
            snapshot.put("indexed_at", metadata.getOrDefault("indexed_at", "unknown"));
            snapshot.put("indexed_commit", metadata.getOrDefault("last_commit", "unknown"));
            snapshot.put("live_freshness_evaluated", false);
            return root.toString();
        } catch (Exception invalid) {
            return response;
        }
    }

    private static ObjectNode unresolvedArtifactDependency(
            String target, WorkspaceDependencyGraph.Edge edge) {
        ObjectNode root = JSON.createObjectNode();
        root.put("error", "artifact_dependency_without_class_evidence");
        root.put("message", "The repository declares the provider artifact, but its index has "
                + "no application class, external bean, or bytecode signature evidence for "
                + target + ". Artifact dependency alone does not prove use of this class.");
        root.put("target", target);
        root.put("target_resolution", "artifact_dependency_only");
        appendResolutionStrategies(root);
        ObjectNode evidence = root.putObject("dependency_evidence");
        if (edge == null) {
            evidence.put("available", false);
        } else {
            evidence.put("available", true);
            evidence.put("coordinate", edge.coordinate());
            evidence.put("consumer_module", edge.consumerModule());
            evidence.set("scopes", JSON.valueToTree(edge.scopes()));
            evidence.put("source_set", edge.sourceSet());
            evidence.put("evidence", edge.evidence());
            if (edge.resolvedBinaryVersion() == null) {
                evidence.putNull("resolved_binary_version");
            } else {
                evidence.put("resolved_binary_version", edge.resolvedBinaryVersion());
            }
            evidence.put("version_status", edge.status());
        }
        root.putArray("limitations")
                .add("Declared artifact dependency identifies candidate repositories, not "
                        + "concrete class usage")
                .add("Reflective, configuration-only, generated-after-index, and dynamically "
                        + "loaded references may not leave class-level bytecode evidence");
        return root;
    }

    private static void appendResolutionStrategies(ObjectNode root) {
        root.putArray("resolution_strategies_checked")
                .add("local_class_index")
                .add("external_bean_index")
                .add("external_type_index");
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.has(field)) target.set(field, source.get(field));
    }

    private static boolean matchesExternalUsageKind(String requested, String indexed) {
        return requested == null || requested.isBlank() || "all".equalsIgnoreCase(requested)
                || requested.equalsIgnoreCase(externalUsageKind(indexed));
    }

    private static String externalUsageKind(String indexed) {
        return switch (indexed) {
            case "ANNOTATION" -> "annotation";
            case "EXTENDS", "IMPLEMENTS" -> "inheritance";
            default -> "type_reference";
        };
    }

    String routeMissingUsages(String response, String consumerRepository, String target,
            String usageKind, int limit, int offset) {
        if (registry.workspaceScope() == null) return response;
        try {
            JsonNode parsed = JSON.readTree(response);
            if (!isClassNotFound(parsed)) {
                return response;
            }
            List<ProviderClass> providers = providerLookup.findProviderClasses(target, null);
            if (providers.size() != 1) return response;
            ProviderClass provider = providers.getFirst();
            JsonNode workspaceResult = JSON.readTree(findUsages(target,
                    provider.repository(), usageKind, limit, Math.min(limit, 20), offset, 0, true));
            ObjectNode root = JSON.createObjectNode();
            root.put("target", target);
            root.put("origin", "workspace_provider");
            root.putObject("local_resolution")
                    .put("status", "not_found")
                    .put("repository", consumerRepository)
                    .put("message", errorMessage(parsed));
            root.set("workspace_usage", workspaceResult);
            root.put("answer_complete", workspaceResult.path("complete").asBoolean(false));
            return root.toString();
        } catch (Exception ignored) {
            return response;
        }
    }

    private record ConsumerUsage(String repository, WorkspaceDependencyGraph.Edge edge,
            com.fasterxml.jackson.databind.JsonNode data) {}

    private record ConsumerResolutionFailure(String repository, JsonNode data) {}
}
