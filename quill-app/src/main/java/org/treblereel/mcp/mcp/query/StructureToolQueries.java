package org.treblereel.mcp.mcp.query;

import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.errorResponse;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.isProducer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.UsageRepository;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassOccurrenceRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.ExternalBeanRecord;
import org.treblereel.mcp.model.ExternalInjectionPointRecord;
import org.treblereel.mcp.model.InjectionPointRecord;
import org.treblereel.mcp.model.ResolutionCandidate;
import org.treblereel.mcp.model.ResolutionStatus;
import org.treblereel.mcp.mcp.support.ArgumentAliases;
import org.treblereel.mcp.mcp.support.BeanTerms;
import org.treblereel.mcp.mcp.support.ClassTargetResolver;
import org.treblereel.mcp.mcp.support.ToolResponseSupport;

/** Structural code and dependency graph queries. */
public final class StructureToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_GRAPH_NODES = 200;

    public String searchClasses(ReadQueryContext reader, String pattern, int limit) {
        return searchClasses(reader, pattern, null, null, limit, 0);
    }

    public String searchClasses(ReadQueryContext reader, String pattern, String module, String sourceSet, int limit) {
        return searchClasses(reader, pattern, module, sourceSet, limit, 0);
    }

    public String searchClasses(ReadQueryContext reader, String pattern, String module, String sourceSet,
            int limit, int offset) {
        List<ClassRecord> classes = reader.symbols.searchClasses(pattern, module, sourceSet, limit, offset);
        int total = reader.symbols.countClasses(pattern, module, sourceSet);
        List<ClassRecord> limited = classes;
        Map<Integer, BeanRecord> beansByClass = reader.beans.findBeansByClassIds(limited.stream().map(ClassRecord::id).toList());
        Map<Integer, List<ClassOccurrenceRecord>> occurrencesByClass =
                reader.index.findClassOccurrencesByClassIds(limited.stream().map(ClassRecord::id).toList());

        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("classes");
        int naiveTokens = 0;
        for (ClassRecord value : limited) {
            ObjectNode node = arr.addObject();
            node.put("class", value.className());
            node.put("source", (value.sourceLine() > 0 ? value.sourceFile() + ":" + value.sourceLine() : value.sourceFile()));
            node.put("origin", value.origin());
            node.put("lifecycle", value.lifecycle());
            appendContext(node, value);
            node.put("is_bean", value.isBean());
            BeanRecord bean = beansByClass.get(value.id());
            if (bean != null) node.put("scope", BeanTerms.scope(bean));
            node.put("source_tokens", value.sourceTokens());
            appendOccurrences(node, occurrencesByClass.get(value.id()));
            naiveTokens += value.sourceTokens();
        }
        appendPage(root, limited.size(), total, limit, offset);
        appendMeta(root, reader, naiveTokens);
        return root.toString();
    }

    public String getAnnotatedClasses(ReadQueryContext reader, String annotation,
            boolean includeMetaAnnotations, int limit, int offset) {
        String requested = annotation.startsWith("@") ? annotation.substring(1) : annotation;
        List<String> names = reader.symbols.findAnnotationNames(requested);
        String resolved = names.stream().filter(requested::equals).findFirst().orElse(null);
        if (resolved == null && names.size() == 1) resolved = names.getFirst();
        if (resolved == null && names.isEmpty()) {
            return AnnotatedSymbolQueries.unindexedAnnotation(requested, "classes").toString();
        }
        if (resolved == null) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", "Ambiguous annotation name");
            error.put("annotation", requested);
            error.set("candidates", JSON.valueToTree(names));
            return error.toString();
        }

        List<ClassRecord> classes = reader.symbols.findAnnotatedClasses(resolved, includeMetaAnnotations, limit, offset);
        int total = reader.symbols.countAnnotatedClasses(resolved, includeMetaAnnotations);
        int direct = reader.symbols.countAnnotatedClasses(resolved, false);
        Map<Integer, List<ClassAnnotationRecord>> matches =
                reader.symbols.findClassAnnotations(resolved,
                        classes.stream().map(ClassRecord::id).toList(), includeMetaAnnotations);
        Map<Integer, List<ClassOccurrenceRecord>> occurrences =
                reader.index.findClassOccurrencesByClassIds(classes.stream().map(ClassRecord::id).toList());

        ObjectNode root = JSON.createObjectNode();
        root.put("annotation", resolved);
        root.put("include_meta_annotations", includeMetaAnnotations);
        root.put("annotated_class_count", total);
        root.put("direct_class_count", direct);
        root.put("meta_only_class_count", Math.max(0, total - direct));
        root.set("origin_breakdown", JSON.valueToTree(
                reader.symbols.countAnnotatedClassesByOrigin(resolved, includeMetaAnnotations)));
        ArrayNode values = root.putArray("classes");
        int naiveTokens = 0;
        for (ClassRecord cls : classes) {
            List<ClassAnnotationRecord> classMatches = matches.getOrDefault(cls.id(), List.of());
            boolean directMatch = classMatches.stream().anyMatch(ClassAnnotationRecord::direct);
            ObjectNode node = values.addObject();
            node.put("class", cls.className());
            node.put("match", directMatch ? "direct" : "meta");
            node.put("source", (cls.sourceLine() > 0 ? cls.sourceFile() + ":" + cls.sourceLine() : cls.sourceFile()));
            node.put("origin", cls.origin());
            node.put("lifecycle", cls.lifecycle());
            appendContext(node, cls);
            if (!directMatch) {
                node.set("via_annotations", JSON.valueToTree(classMatches.stream()
                        .map(ClassAnnotationRecord::viaAnnotation)
                        .filter(Objects::nonNull).distinct().sorted().toList()));
            }
            appendOccurrences(node, occurrences.get(cls.id()));
            naiveTokens += cls.sourceTokens();
        }
        appendPage(root, classes.size(), total, limit, offset);
        appendMeta(root, reader, naiveTokens);
        return root.toString();
    }

    public String getBeans(ReadQueryContext reader, String className, String scope, String kind,
            String profile, String qualifier, int limit) {
        return getBeans(reader, className, scope, kind, profile, qualifier, null, null, limit);
    }

    public String getBeans(ReadQueryContext reader, String className, String scope, String kind,
            String profile, String qualifier, String module, String sourceSet, int limit) {
        return getBeans(reader, className, scope, kind, profile, qualifier,
                module, sourceSet, limit, 0);
    }

    public String getBeans(ReadQueryContext reader, String className, String scope, String kind,
            String profile, String qualifier, String module, String sourceSet,
            int limit, int offset) {
        return getBeans(reader, className, scope, kind, profile, qualifier, module, sourceSet,
                "all", limit, offset);
    }

    public String getBeans(ReadQueryContext reader, String className, String scope, String kind,
            String profile, String qualifier, String module, String sourceSet,
            String origin, int limit, int offset) {
        String requestedOrigin = origin == null ? "all" : ArgumentAliases.origin(origin);
        if (!Set.of("all", "application", "dependency").contains(requestedOrigin)) {
            return errorResponse("Invalid origin: expected all, application, or dependency");
        }
        Map<String, String> filter = new HashMap<>();
        if (className != null) filter.put("class_name", className);
        if (scope != null) filter.put("scope", BeanTerms.storedScope(scope));
        if (kind != null) filter.put("kind", BeanTerms.storedKind(kind));
        if (profile != null) filter.put("profile", profile);
        if (qualifier != null) filter.put("qualifier", qualifier);
        if (module != null) filter.put("module", module);
        if (sourceSet != null) filter.put("source_set", sourceSet);

        List<BeanRecord> beans = List.of();
        ClassTargetResolver.Lookup lookup = null;
        if (!"dependency".equals(requestedOrigin)) {
            Map<String, String> applicationFilter = new HashMap<>(filter);
            if (className != null && !className.contains("*")) {
                lookup = ClassTargetResolver.resolve(reader, className);
                if (lookup.error() == null) {
                    applicationFilter.put("class_name", lookup.cls().className());
                } else {
                    applicationFilter.put("class_name", "__quill_no_application_match__");
                }
            }
            beans = reader.beans.findBeans(applicationFilter.isEmpty() ? null : applicationFilter);
        }
        List<ExternalBeanRecord> externalBeans = "application".equals(requestedOrigin)
                ? List.of()
                : reader.beans.findExternalBeans(filter.isEmpty() ? null : filter);
        boolean typeLookup = false;
        if (beans.isEmpty() && externalBeans.isEmpty() && className != null && !className.contains("*")) {
            Map<String, String> typeFilter = new HashMap<>(filter);
            typeFilter.remove("class_name");
            typeFilter.put("bean_type", className);
            if (!"dependency".equals(requestedOrigin)) beans = IndexReader.findBeans(reader.jdbi(), typeFilter);
            if (!"application".equals(requestedOrigin)) externalBeans = IndexReader.findExternalBeans(reader.jdbi(), typeFilter);
            typeLookup = !beans.isEmpty() || !externalBeans.isEmpty();
        }
        if (beans.isEmpty() && externalBeans.isEmpty() && lookup != null
                && lookup.error() != null) {
            String dependency = DependencyTypeAnswers.referencedType(reader, lookup, className);
            if (dependency != null) return DependencyTypeAnswers.beans(reader, dependency);
            return classLookupError(reader, lookup, className);
        }

        Map<Integer, ClassRecord> applicationClasses = reader.symbols.findClassesByIds(beans.stream().map(BeanRecord::classId).toList());
        List<BeanView> views = new ArrayList<>(beans.size() + externalBeans.size());
        beans.forEach(bean -> views.add(new BeanView(bean, null,
                applicationClasses.containsKey(bean.classId())
                        ? applicationClasses.get(bean.classId()).className()
                        : Integer.toString(bean.classId()))));
        externalBeans.forEach(bean -> views.add(new BeanView(null, bean, bean.className())));
        views.sort(Comparator.comparing(BeanView::className)
                .thenComparing(BeanView::origin)
                .thenComparing(BeanView::kind));
        int total = views.size();
        int from = Math.min(offset, total);
        int to = (int) Math.min((long) from + limit, total);
        List<BeanView> limited = views.subList(from, to);
        Map<Integer, ClassRecord> classesById = reader.symbols.findClassesByIds(limited.stream().filter(value -> value.application() != null)
                        .map(value -> value.application().classId()).toList());
        Map<Integer, List<ClassOccurrenceRecord>> occurrencesByClass =
                reader.index.findClassOccurrencesByClassIds(limited.stream().filter(value -> value.application() != null)
                                .map(value -> value.application().classId()).toList());
        List<Integer> pageBeanIds = limited.stream().filter(value -> value.application() != null)
                .map(value -> value.application().id()).toList();
        Map<Integer, Integer> injectedInto = reader.beans.countResolvedInjectionPoints(pageBeanIds);
        Map<Integer, List<ClassAnnotationRecord>> pageAnnotations = reader.symbols.findClassAnnotations(
                limited.stream().filter(value -> value.application() != null)
                        .map(value -> value.application().classId()).toList());
        Map<Integer, List<ClassMemberRecord>> producerMembers = reader.symbols.findClassMembers(
                limited.stream().filter(value -> value.application() != null
                                && value.application().memberName() != null
                                && value.application().declaringClassId() != null)
                        .map(value -> value.application().declaringClassId()).distinct().toList());
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("beans");
        if (typeLookup) root.put("lookup_kind", "provided_bean_type").put("requested_type", className)
                .put("runtime_resolution_proven", false);
        int naiveTokens = 0;
        for (BeanView view : limited) {
            ObjectNode node = arr.addObject();
            if (view.external() != null) {
                ExternalBeanRecord bean = view.external();
                node.put("class", bean.className());
                if (bean.memberName() != null) node.put("member", bean.memberName());
                if (isProducer(bean.kind()) && !bean.beanTypes().isEmpty()) {
                    node.put("produced_type", bean.beanTypes().getFirst());
                }
                node.put("kind", BeanTerms.kind(bean.framework(), bean.kind()));
                node.put("scope", BeanTerms.scope(bean.framework(), bean.scope()));
                node.put("origin", "dependency");
                node.put("framework", bean.framework());
                if (bean.artifact() != null) node.put("artifact", bean.artifact());
                if (bean.jarPath() != null) node.put("jar", bean.jarPath());
                if (bean.defaultBean()) node.put("default_bean", true);
                node.set("qualifiers", JSON.valueToTree(BeanTerms.qualifiers(bean.framework(), bean.qualifiers())));
                node.set("bean_types", JSON.valueToTree(bean.beanTypes()));
                node.set("profiles", JSON.valueToTree(bean.profiles()));
                node.set("injection_points", JSON.valueToTree(bean.injectionPoints()));
                continue;
            }
            BeanRecord bean = view.application();
            ClassRecord beanClass = classesById.get(bean.classId());
            node.put("class", beanClass != null ? beanClass.className() : "unknown");
            if (bean.memberName() != null) node.put("member", bean.memberName());
            if (isProducer(bean.kind()) && bean.beanTypes() != null
                    && !bean.beanTypes().isEmpty()) {
                node.put("produced_type", bean.beanTypes().getFirst());
            }
            node.put("kind", BeanTerms.kind(bean));
            node.put("scope", BeanTerms.scope(bean));
            node.put("origin", "application");
            if (bean.isDefault()) node.put("default_bean", true);
            List<String> shownQualifiers = BeanTerms.qualifiers(bean);
            if (!DEFAULT_QUALIFIERS.equals(bean.qualifiers()) && shownQualifiers != null && !shownQualifiers.isEmpty())
                node.set("qualifiers", JSON.valueToTree(shownQualifiers));
            appendOwnBeanTypes(node, bean, beanClass);
            // No profile list means the bean is active in every profile.
            if (bean.profiles() != null && !bean.profiles().isEmpty())
                node.set("profiles", JSON.valueToTree(bean.profiles()));
            if (beanClass != null) {
                // Line 0 means the class has no recorded declaration line; the file alone says as much.
                node.put("source", beanClass.sourceLine() > 0
                        ? beanClass.sourceFile() + ":" + beanClass.sourceLine() : beanClass.sourceFile());
                appendContext(node, beanClass);
                if ("main".equals(beanClass.sourceSet())) node.remove("source_set");
                appendOccurrences(node, occurrencesByClass.get(beanClass.id()));
                naiveTokens += beanClass.sourceTokens();
            }
            BeanEvidence.appendConditions(node, bean, pageAnnotations.getOrDefault(bean.classId(), List.of()),
                    bean.declaringClassId() == null ? List.of()
                            : producerMembers.getOrDefault(bean.declaringClassId(), List.of()));
            BeanEvidence.appendRetention(node, bean, injectedInto.getOrDefault(bean.id(), 0),
                    pageAnnotations.getOrDefault(bean.classId(), List.of()));
        }
        BeanEvidence.appendRetentionNotes(root);
        if (limited.stream().anyMatch(view -> view.application() != null)) {
            ObjectNode defaults = root.putObject("bean_defaults").put("bean_types", "Application beans list types besides the bean class "
                            + "(a producer's produced_type) and java.lang.Object, which they all have; class_is_bean_type false marks @Typed exceptions")
                    .put("source_set", "main");
            // An omitted qualifier list means @Default @Any for a CDI bean but no qualifier for a Spring bean.
            boolean cdi = limited.stream().anyMatch(view -> view.application() != null && !view.application().spring());
            boolean spring = limited.stream().anyMatch(view -> view.application() != null && view.application().spring());
            if (cdi) defaults.set("qualifiers", JSON.valueToTree(DEFAULT_QUALIFIERS));
            if (spring) defaults.set(cdi ? "spring_qualifiers" : "qualifiers", JSON.createArrayNode());
        }
        ObjectNode originBreakdown = root.putObject("origin_breakdown");
        originBreakdown.put("application", beans.size());
        originBreakdown.put("dependency", externalBeans.size());
        appendDependencyBeanSummary(root, externalBeans);
        if (total == 0 && !"application".equals(requestedOrigin)) {
            Map<String, String> metadata = reader.index.getMetadata();
            ObjectNode hint = root.putObject("hint");
            hint.put("dependency_index", metadata.getOrDefault("dependency_index", "unknown"));
            hint.put("detail", metadata.getOrDefault("dependency_index_detail",
                    "No dependency bean matched. Re-run quill update after the project classpath is available."));
        }
        appendPage(root, limited.size(), total, limit, offset);
        appendMeta(root, reader, naiveTokens);
        return root.toString();
    }

    private static final List<String> DEFAULT_QUALIFIERS = List.of("@Default", "@Any");

    /** Bean types other than the class (or produced type) and java.lang.Object, which every bean lists. */
    private static void appendOwnBeanTypes(ObjectNode node, BeanRecord bean, ClassRecord beanClass) {
        List<String> types = bean.beanTypes() == null ? List.of() : bean.beanTypes();
        String own = isProducer(bean.kind()) ? (types.isEmpty() ? null : types.getFirst())
                : "CLASS".equals(bean.kind()) && beanClass != null ? beanClass.className() : null;
        if (own == null) {
            node.set("bean_types", JSON.valueToTree(types));
            return;
        }
        if (!types.contains(own)) node.put("class_is_bean_type", false);
        List<String> rest = types.stream().filter(type -> !type.equals(own) && !type.equals("java.lang.Object")).toList();
        if (!rest.isEmpty()) node.set("bean_types", JSON.valueToTree(rest));
    }

    private static void appendDependencyBeanSummary(
            ObjectNode root, List<ExternalBeanRecord> beans) {
        ObjectNode summary = root.putObject("dependency_summary");
        summary.put("bean_count", beans.size());
        Map<String, Integer> artifactCounts = new java.util.TreeMap<>();
        for (ExternalBeanRecord bean : beans) {
            if (bean.artifact() != null) artifactCounts.merge(bean.artifact(), 1, Integer::sum);
        }
        ArrayNode artifacts = summary.putArray("artifacts");
        artifactCounts.forEach((artifact, count) -> artifacts.addObject()
                .put("artifact", artifact).put("bean_count", count));

        List<RequiredConfiguration> required = new ArrayList<>();
        int defaultedCount = 0;
        Set<String> configuredBeans = new TreeSet<>();
        for (ExternalBeanRecord bean : beans) {
            for (ExternalInjectionPointRecord injection : bean.injectionPoints()) {
                if (injection.configurationKey() == null) continue;
                configuredBeans.add(bean.className());
                if (injection.configurationRequired()) {
                    required.add(new RequiredConfiguration(bean.className(),
                            injection.member(), injection.configurationKey()));
                } else {
                    defaultedCount++;
                }
            }
        }
        required.sort(Comparator.comparing(RequiredConfiguration::key)
                .thenComparing(RequiredConfiguration::bean));
        ObjectNode configuration = root.putObject("configuration_summary");
        configuration.put("beans_with_configuration", configuredBeans.size());
        configuration.put("required_count", required.size());
        configuration.put("defaulted_count", defaultedCount);
        ArrayNode properties = configuration.putArray("required_properties");
        for (RequiredConfiguration value : required) {
            properties.addObject().put("key", value.key()).put("bean", value.bean())
                    .put("member", value.member()).put("required", true);
        }
        ObjectNode coverage = root.putObject("answer_coverage");
        coverage.put("dependency_beans", true);
        coverage.put("configuration_requirements", true);
        coverage.put("artifact_coordinates", true);
    }

    private record RequiredConfiguration(String bean, String member, String key) {}

    private record BeanView(
            BeanRecord application, ExternalBeanRecord external, String className) {
        String origin() {
            return external != null ? "dependency" : "application";
        }

        String kind() {
            return external != null ? external.kind() : application.kind();
        }
    }

    public String getDependencies(ReadQueryContext reader, String target, String direction, int depth) {
        return getDependencies(reader, target, direction, depth,
                true, MAX_GRAPH_NODES, 0, null);
    }

    public String getDependencies(ReadQueryContext reader, String target, String direction, int depth,
            boolean includeNodes, int limit, int offset, String cursor) {
        direction = ArgumentAliases.graphDirection(direction);
        if (!Set.of("inbound", "outbound", "both").contains(direction)) {
            return errorResponse("Invalid direction: expected inbound, outbound, or both");
        }
        var lookup = ClassTargetResolver.resolve(reader, target);
        if (lookup.error() != null) return classLookupError(reader, lookup, target);
        ClassRecord cls = lookup.cls();
        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("is_bean", cls.isBean());
        root.put("origin", cls.origin());
        root.put("lifecycle", cls.lifecycle());
        appendContext(root, cls);
        appendOccurrences(root, reader.index.findClassOccurrencesByClassIds(List.of(cls.id())).get(cls.id()));
        ObjectNode metrics = root.putObject("metrics");
        metrics.put("fan_in", reader.usages.countDependents(cls.id()));
        metrics.put("incoming_edges", reader.usages.countDependencyEdges(cls.id(), true));
        metrics.put("fan_out", reader.usages.countDependencies(cls.id()));
        metrics.put("outgoing_edges", reader.usages.countDependencyEdges(cls.id(), false));
        writeDependencyBreakdown(metrics.putObject("fan_in_breakdown"),
                reader.usages.dependencyBreakdown(cls.id(), true));
        writeDependencyBreakdown(metrics.putObject("fan_out_breakdown"),
                reader.usages.dependencyBreakdown(cls.id(), false));
        if (cls.isBean()) {
            reader.beans.findBeanByClassId(cls.id())
                    .ifPresent(bean -> root.put("scope", BeanTerms.scope(bean)));
        }
        if (!includeNodes) {
            root.put("nodes_included", false);
            appendMeta(root, reader, cls.sourceTokens());
            return root.toString();
        }
        if (depth == 1) {
            if (cursor != null && !cursor.isBlank()) {
                return errorResponse("cursor is only supported when depth > 1");
            }
            List<GraphRelation> relations = relationsFor(reader, cls.id(), direction, 1);
            int from = Math.min(offset, relations.size());
            int to = (int) Math.min((long) from + limit, relations.size());
            List<GraphRelation> page = relations.subList(from, to);
            int naiveTokens = cls.sourceTokens() + renderDepthOne(reader, root, page);
            root.put("pagination", "offset");
            appendPage(root, page.size(), relations.size(), limit, offset);
            appendMeta(root, reader, naiveTokens);
            return root.toString();
        }
        if (offset != 0) {
            return errorResponse("offset is only supported when depth = 1; use cursor for deeper graphs");
        }
        List<GraphRelation> graph = breadthFirstRelations(reader, cls.id(), direction, depth);
        int cursorOffset = decodeCursor(reader, cursor, cls.id(), direction, depth);
        if (cursorOffset < 0) return errorResponse("Invalid or expired dependency cursor");
        int from = Math.min(cursorOffset, graph.size());
        int to = (int) Math.min((long) from + limit, graph.size());
        List<GraphRelation> page = graph.subList(from, to);
        int naiveTokens = cls.sourceTokens() + renderBreadthFirst(reader, root, page);
        boolean hasMore = to < graph.size();
        root.put("pagination", "cursor");
        root.put("showing", page.size());
        root.put("total", graph.size());
        root.put("limit", limit);
        root.put("has_more", hasMore);
        root.put("truncated", cursorOffset > 0 || hasMore);
        if (hasMore) {
            root.put("next_cursor", encodeCursor(reader, cls.id(), direction, depth, to));
        }
        appendMeta(root, reader, naiveTokens);
        return root.toString();
    }

    public String findImplementations(ReadQueryContext reader, String target, boolean transitive,
            String module, String sourceSet, int limit, int offset) {
        var lookup = ClassTargetResolver.resolve(reader, target);
        String dependency = lookup.error() == null ? null : DependencyTypeAnswers.referencedType(reader, lookup, target);
        if (lookup.error() != null && dependency == null) return classLookupError(reader, lookup, target);
        ClassRecord base = dependency != null ? DependencyTypeAnswers.standIn(dependency) : lookup.cls();
        List<ClassRecord> allClasses = reader.symbols.findAllClasses();
        Map<String, Integer> distances = implementationDistances(
                allClasses, base.className(), transitive);
        List<ClassRecord> implementations = allClasses.stream()
                .filter(candidate -> distances.containsKey(candidate.className()))
                .sorted(Comparator
                        .comparingInt((ClassRecord value) -> distances.get(value.className()))
                        .thenComparing(ClassRecord::className))
                .toList();
        Map<Integer, List<ClassOccurrenceRecord>> occurrencesByClass =
                reader.index.findClassOccurrencesByClassIds(implementations.stream().map(ClassRecord::id).toList());

        List<ImplementationResult> results = new ArrayList<>();
        for (ClassRecord implementation : implementations) {
            List<ClassOccurrenceRecord> occurrences = filteredOccurrences(
                    implementation, occurrencesByClass.get(implementation.id()), module, sourceSet);
            if (!occurrences.isEmpty()) {
                results.add(new ImplementationResult(
                        implementation, distances.get(implementation.className()), occurrences));
            }
        }

        int from = Math.min(offset, results.size());
        int to = (int) Math.min((long) from + limit, results.size());
        List<ImplementationResult> page = results.subList(from, to);
        ImplementationSummary summary = summarizeImplementations(results);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", base.className());
        root.put("kind", base.kind());
        root.put("source", (base.sourceLine() > 0 ? base.sourceFile() + ":" + base.sourceLine() : base.sourceFile()));
        root.put("origin", base.origin());
        appendContext(root, base);
        root.put("transitive", transitive);
        root.put("implementation_class_count", results.size());
        root.put("implementation_occurrence_count", summary.occurrenceCount());
        root.put("source_implementation_count", summary.sourceCount());
        root.put("generated_implementation_count", summary.generatedCount());
        root.set("implementation_modules", JSON.valueToTree(summary.modules()));
        root.set("generated_modules", JSON.valueToTree(summary.generatedModules()));
        root.put("selection_depends_on_application_context",
                summary.occurrenceCount() > 1);
        Map<String, String> metadata = reader.index.getMetadata();
        ObjectNode scope = root.putObject("index_scope");
        scope.put("module_discovery",
                metadata.getOrDefault("module_discovery_scope", "unknown"));
        scope.put("complete", Boolean.parseBoolean(
                metadata.getOrDefault("module_discovery_complete", "false")));
        scope.put("compiled_outputs_only", true);
        scope.put("excludes_undeclared_project_directories",
                Boolean.parseBoolean(
                        metadata.getOrDefault("module_discovery_complete", "false")));

        ArrayNode array = root.putArray("implementations");
        int naiveTokens = base.sourceTokens();
        for (ImplementationResult result : page) {
            ClassRecord implementation = result.implementation();
            ObjectNode node = array.addObject();
            node.put("class", implementation.className());
            node.put("kind", implementation.kind());
            node.put("distance", result.distance());
            node.put("direct", result.distance() == 1);
            node.put("source", (implementation.sourceLine() > 0 ? implementation.sourceFile() + ":" + implementation.sourceLine() : implementation.sourceFile()));
            node.put("origin", implementation.origin());
            appendContext(node, implementation);
            appendImplementationOccurrences(node, result.occurrences());
            naiveTokens += implementation.sourceTokens();
        }
        appendPage(root, page.size(), results.size(), limit, offset);
        appendMeta(root, reader, naiveTokens);
        return root.toString();
    }

    private Map<String, Integer> implementationDistances(
            List<ClassRecord> classes, String target, boolean transitive) {
        Map<String, Integer> distances = new HashMap<>();
        distances.put(target, 0);
        boolean changed;
        do {
            changed = false;
            for (ClassRecord candidate : classes) {
                if (distances.containsKey(candidate.className())) continue;
                int distance = directParentDistance(candidate, distances);
                if (distance > 0 && (transitive || distance == 1)) {
                    distances.put(candidate.className(), distance);
                    changed = true;
                }
            }
        } while (transitive && changed);
        distances.remove(target);
        return distances;
    }

    private int directParentDistance(ClassRecord candidate, Map<String, Integer> distances) {
        int result = Integer.MAX_VALUE;
        Integer superclassDistance = distances.get(candidate.superclass());
        if (superclassDistance != null) result = superclassDistance + 1;
        for (String implemented : candidate.interfaces()) {
            Integer interfaceDistance = distances.get(implemented);
            if (interfaceDistance != null) result = Math.min(result, interfaceDistance + 1);
        }
        return result == Integer.MAX_VALUE ? -1 : result;
    }

    private List<ClassOccurrenceRecord> filteredOccurrences(ClassRecord implementation,
            List<ClassOccurrenceRecord> occurrences, String module, String sourceSet) {
        List<ClassOccurrenceRecord> available = occurrences == null || occurrences.isEmpty()
                ? List.of(new ClassOccurrenceRecord(0, implementation.id(),
                        implementation.className(), implementation.module(),
                        implementation.sourceSet(), "", "", implementation.sourceFile(),
                        implementation.origin()))
                : occurrences;
        return available.stream()
                .filter(value -> module == null || Objects.equals(module, value.module()))
                .filter(value -> sourceSet == null
                        || Objects.equals(sourceSet, value.sourceSet()))
                .toList();
    }

    private ImplementationSummary summarizeImplementations(
            List<ImplementationResult> implementations) {
        int occurrences = 0;
        int source = 0;
        int generated = 0;
        Set<String> modules = new TreeSet<>();
        Set<String> generatedModules = new TreeSet<>();
        for (ImplementationResult implementation : implementations) {
            for (ClassOccurrenceRecord occurrence : implementation.occurrences()) {
                occurrences++;
                if (occurrence.module() != null) modules.add(occurrence.module());
                if ("generated".equals(occurrence.origin())) {
                    generated++;
                    if (occurrence.module() != null) generatedModules.add(occurrence.module());
                } else {
                    source++;
                }
            }
        }
        return new ImplementationSummary(occurrences, source, generated,
                List.copyOf(modules), List.copyOf(generatedModules));
    }

    private void appendImplementationOccurrences(
            ObjectNode node, List<ClassOccurrenceRecord> occurrences) {
        node.put("occurrence_count", occurrences.size());
        ArrayNode values = node.putArray("class_occurrences");
        for (ClassOccurrenceRecord occurrence : occurrences) {
            ObjectNode value = values.addObject();
            value.put("module", occurrence.module());
            value.put("source_set", occurrence.sourceSet());
            value.put("origin", occurrence.origin());
            if (!occurrence.outputDirectory().isEmpty()) {
                value.put("output_directory", occurrence.outputDirectory());
            }
            if (!occurrence.classFile().isEmpty()) {
                value.put("class_file", occurrence.classFile());
            }
            if (occurrence.sourceFile() != null) {
                value.put("source_file", occurrence.sourceFile());
            }
        }
    }

    private List<GraphRelation> breadthFirstRelations(
            ReadQueryContext reader, int rootClassId, String direction, int maxDepth) {
        List<GraphRelation> graph = new ArrayList<>();
        ArrayDeque<Frontier> frontier = new ArrayDeque<>();
        Set<Integer> expanded = new HashSet<>();
        Set<Integer> scheduled = new HashSet<>();
        Set<DependencyKey> seen = new HashSet<>();
        frontier.add(new Frontier(rootClassId, 0));
        scheduled.add(rootClassId);
        while (!frontier.isEmpty()) {
            Frontier current = frontier.removeFirst();
            if (current.depth() >= maxDepth || !expanded.add(current.classId())) continue;
            for (GraphRelation relation : relationsFor(
                    reader, current.classId(), direction, current.depth() + 1)) {
                if (seen.add(DependencyKey.of(relation.dependency()))) graph.add(relation);
                if (scheduled.add(relation.related().id())) {
                    frontier.addLast(new Frontier(relation.related().id(), relation.depth()));
                }
            }
        }
        return graph;
    }

    private List<GraphRelation> relationsFor(
            ReadQueryContext reader, int classId, String direction, int relationDepth) {
        List<DependencyRecord> dependencies = reader.usages.findDependencies(classId, direction);
        Set<Integer> classIds = new HashSet<>();
        classIds.add(classId);
        for (DependencyRecord dependency : dependencies) {
            classIds.add(dependency.fromClassId());
            classIds.add(dependency.toClassId());
        }
        Map<Integer, ClassRecord> classes = reader.symbols.findClassesByIds(List.copyOf(classIds));
        ClassRecord current = classes.get(classId);
        List<GraphRelation> relations = new ArrayList<>();
        for (DependencyRecord dependency : dependencies) {
            boolean outbound = !"inbound".equals(direction)
                    && dependency.fromClassId() == classId;
            int relatedId = outbound ? dependency.toClassId() : dependency.fromClassId();
            ClassRecord related = classes.get(relatedId);
            if (current != null && related != null) {
                relations.add(new GraphRelation(current, related, dependency,
                        outbound ? "outbound" : "inbound", relationDepth));
            }
        }
        relations.sort(Comparator
                .comparing((GraphRelation value) -> value.related().className())
                .thenComparing(GraphRelation::relationDirection)
                .thenComparing(value -> value.dependency().kind())
                .thenComparingInt(value -> value.dependency().fromClassId())
                .thenComparingInt(value -> value.dependency().toClassId()));
        return relations;
    }

    private int renderDepthOne(ReadQueryContext reader, ObjectNode root, List<GraphRelation> relations) {
        int tokens = 0;
        Map<Integer, List<ClassOccurrenceRecord>> occurrences = occurrences(reader, relations);
        for (GraphRelation relation : relations) {
            String arrayName = "outbound".equals(relation.relationDirection())
                    ? "depends_on" : "depended_by";
            ObjectNode child = array(root, arrayName).addObject();
            renderRelation(child, relation, occurrences.get(relation.related().id()));
            tokens += relation.related().sourceTokens();
        }
        return tokens;
    }

    private int renderBreadthFirst(ReadQueryContext reader, ObjectNode root, List<GraphRelation> relations) {
        int tokens = 0;
        ArrayNode graph = root.putArray("graph");
        Map<Integer, List<ClassOccurrenceRecord>> occurrences = occurrences(reader, relations);
        for (GraphRelation relation : relations) {
            ObjectNode child = graph.addObject();
            child.put("depth", relation.depth());
            child.put("parent", relation.current().className());
            child.put("relation_direction", relation.relationDirection());
            renderRelation(child, relation, occurrences.get(relation.related().id()));
            tokens += relation.related().sourceTokens();
        }
        return tokens;
    }

    private Map<Integer, List<ClassOccurrenceRecord>> occurrences(
            ReadQueryContext reader, List<GraphRelation> relations) {
        return reader.index.findClassOccurrencesByClassIds(relations.stream().map(value -> value.related().id()).distinct().toList());
    }

    private void renderRelation(ObjectNode child, GraphRelation relation,
            List<ClassOccurrenceRecord> occurrences) {
        ClassRecord related = relation.related();
        DependencyRecord dependency = relation.dependency();
        child.put("class", related.className());
        child.put("kind", dependency.kind());
        if (dependency.occurrenceCount() > 1) {
            child.put("occurrences", dependency.occurrenceCount());
        }
        ClassRecord caller = "outbound".equals(relation.relationDirection())
                ? relation.current() : related;
        appendEvidence(child, caller, dependency);
        appendContext(child, related);
        appendOccurrences(child, occurrences);
    }

    private String encodeCursor(
            ReadQueryContext reader, int classId, String direction, int depth, int offset) {
        ObjectNode value = JSON.createObjectNode();
        value.put("v", 1);
        value.put("index", reader.index.getMetadata().getOrDefault("index_id", ""));
        value.put("class_id", classId);
        value.put("direction", direction);
        value.put("depth", depth);
        value.put("offset", offset);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private int decodeCursor(
            ReadQueryContext reader, String cursor, int classId, String direction, int depth) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            var value = JSON.readTree(decoded);
            String indexId = reader.index.getMetadata().getOrDefault("index_id", "");
            if (value.path("v").asInt() != 1
                    || !Objects.equals(value.path("index").asText(), indexId)
                    || value.path("class_id").asInt() != classId
                    || !Objects.equals(value.path("direction").asText(), direction)
                    || value.path("depth").asInt() != depth
                    || value.path("offset").asInt(-1) < 0) return -1;
            return value.path("offset").asInt();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static void appendEvidence(
            ObjectNode child, ClassRecord caller, DependencyRecord dependency) {
        if (dependency.evidenceLines().isEmpty()) return;
        child.put("evidence_file", caller.sourceFile());
        child.set("evidence_lines", JSON.valueToTree(dependency.evidenceLines()));
    }

    private static ArrayNode array(ObjectNode node, String name) {
        return node.has(name) ? (ArrayNode) node.get(name) : node.putArray(name);
    }

    private static void appendOccurrences(
            ObjectNode node, List<ClassOccurrenceRecord> occurrences) {
        if (occurrences == null || occurrences.size() < 2) return;
        node.put("occurrence_count", occurrences.size());
        ArrayNode values = node.putArray("class_occurrences");
        for (ClassOccurrenceRecord occurrence : occurrences) {
            ObjectNode value = values.addObject();
            value.put("module", occurrence.module());
            value.put("source_set", occurrence.sourceSet());
            value.put("origin", occurrence.origin());
            value.put("output_directory", occurrence.outputDirectory());
            value.put("class_file", occurrence.classFile());
            if (occurrence.sourceFile() != null) {
                value.put("source_file", occurrence.sourceFile());
            }
        }
    }

    /**
     * Explains why a class has no bean record instead of a bare failure, and names the related
     * beans (enclosing class, superclass) whose injection points usually answer the question.
     */
    private static String notABean(ReadQueryContext reader, ClassRecord cls, String target) {
        ObjectNode root = JSON.createObjectNode();
        ToolResponseSupport.appendError(root, "NOT_A_BEAN", "Not a bean: " + target);
        root.put("target", cls.className());
        root.put("kind", cls.kind());
        root.put("reason", "No bean-defining annotation was indexed for this class "
                + "(abstract, nested helper, or only registered at build time)");
        ArrayNode related = root.putArray("related_beans");
        int nested = cls.className().indexOf('$');
        if (nested > 0) addRelatedBean(reader, related, cls.className().substring(0, nested), "enclosing_class");
        if (cls.superclass() != null) addRelatedBean(reader, related, cls.superclass(), "superclass");
        root.put("query_direction", "dependencies_injected_into_bean");
        root.put("consumer_guidance", "To find consumers injecting this type, use find_usages; an interface need not itself be a bean.");
        root.putObject("next_step").put("tool", "INTERFACE".equals(cls.kind()) ? "find_usages" : "get_symbol_details")
                .putObject("arguments").put("target", cls.className());
        appendMeta(root, reader, 0);
        return root.toString();
    }

    private static void addRelatedBean(ReadQueryContext reader, ArrayNode related, String className,
            String relation) {
        reader.symbols.findClassByName(className)
                .filter(candidate -> reader.beans.findBeanByClassId(candidate.id()).isPresent())
                .ifPresent(candidate -> related.addObject().put("class", candidate.className())
                        .put("relation", relation));
    }

    public String getInjectionPoints(ReadQueryContext reader, String target) {
        var lookup = ClassTargetResolver.resolve(reader, target);
        if (lookup.error() != null) {
            String dependency = DependencyTypeAnswers.referencedType(reader, lookup, target);
            return dependency != null ? DependencyTypeAnswers.beans(reader, dependency) : classLookupError(reader, lookup, target);
        }
        ClassRecord cls = lookup.cls();
        var bean = reader.beans.findBeanByClassId(cls.id());
        if (bean.isEmpty()) return notABean(reader, cls, target);
        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("scope", BeanTerms.scope(bean.get()));
        root.put("bean_kind", BeanTerms.kind(bean.get()));
        appendContext(root, cls);
        List<InjectionPointRecord> injectionPoints = reader.beans.findInjectionPoints(bean.get().id());
        Set<Integer> referencedBeanIds = new HashSet<>();
        injectionPoints.stream().map(InjectionPointRecord::resolvedBeanId)
                .filter(Objects::nonNull).forEach(referencedBeanIds::add);
        injectionPoints.stream().flatMap(point -> point.resolutionTrace().candidates().stream())
                .map(ResolutionCandidate::beanId).filter(Objects::nonNull)
                .forEach(referencedBeanIds::add);
        injectionPoints.stream().flatMap(point -> point.resolutionTrace().candidates().stream())
                .map(ResolutionCandidate::relatedBeanId).filter(Objects::nonNull)
                .forEach(referencedBeanIds::add);
        Map<Integer, BeanRecord> resolvedBeans = reader.beans.findBeansByIds(referencedBeanIds);
        Map<Integer, ClassRecord> resolvedClasses = reader.symbols.findClassesByIds(resolvedBeans.values().stream().map(BeanRecord::classId).toList());
        root.put("injection_points_total", injectionPoints.size());
        ObjectNode kinds = root.putObject("injection_points_by_kind");
        for (InjectionPointRecord point : injectionPoints)
            kinds.put(point.kind(), kinds.path(point.kind()).asInt() + 1);
        ArrayNode result = root.putArray("injection_points");
        ArrayNode unsatisfied = root.putArray("unsatisfied");
        ArrayNode ambiguous = root.putArray("ambiguous");
        ArrayNode contextRequired = root.putArray("context_required");
        ArrayNode unknown = root.putArray("unknown");
        ArrayNode unsupported = root.putArray("unsupported_mechanism");
        List<ExternalBeanRecord> providers = injectionPoints.stream()
                .anyMatch(p -> p.resolutionStatus() == ResolutionStatus.UNKNOWN)
                ? reader.beans.findExternalBeans(null) : List.of();
        Map<String, List<String>> typeArtifacts = new HashMap<>();
        for (InjectionPointRecord point : injectionPoints) {
            ObjectNode node = result.addObject();
            node.put("kind", point.kind());
            node.put("field", point.fieldName());
            node.put("required_type", point.targetType());
            node.set("qualifiers", JSON.valueToTree(BeanTerms.qualifiers(bean.get().framework(), point.qualifiers())));
            node.put("resolution", point.resolutionStatus().name().toLowerCase());
            // AQ-004: a dependency type's jar is part of "where does it come from"; the index knows it.
            if (point.resolvedBeanId() == null && reader.symbols.findClassByName(point.targetType()).isEmpty()) {
                List<String> artifacts = typeArtifacts.computeIfAbsent(point.targetType(),
                        type -> ExternalSymbolQueries.artifactsOf(reader, type));
                if (!artifacts.isEmpty()) node.set("required_type_artifacts", JSON.valueToTree(artifacts));
            }
            node.put("resolution_strategy", point.resolutionStrategy());
            node.put("reason", point.resolutionReason().name());
            node.put("confidence", point.resolutionConfidence().name().toLowerCase());
            node.set("limitations", JSON.valueToTree(point.limitations()));
            appendResolutionTrace(node, point, resolvedBeans, resolvedClasses);
            if (point.resolvedBeanId() == null && (point.resolutionStatus() == ResolutionStatus.UNKNOWN
                    || point.resolutionStatus() == ResolutionStatus.UNSATISFIED)) {
                appendProviderEvidence(reader, node, point, providers);
            }
            if (point.resolutionStatus() == ResolutionStatus.AMBIGUOUS) {
                node.putNull("resolved_to");
                ambiguous.add(point.fieldName());
            } else if (point.resolvedBeanId() != null) {
                BeanRecord resolved = resolvedBeans.get(point.resolvedBeanId());
                if (resolved != null) {
                    node.put("resolved_scope", BeanTerms.scope(resolved));
                    node.put("resolved_kind", BeanTerms.kind(resolved));
                    ClassRecord resolvedClass = resolvedClasses.get(resolved.classId());
                    if (resolvedClass != null) node.put("resolved_to", resolvedClass.className());
                    if (resolved.memberName() != null) {
                        node.put("resolved_member", resolved.memberName());
                    }
                    if (resolved.isDefault()) node.put("resolved_fallback", "@DefaultBean: used unless an eligible non-default bean exists");
                    if (isProducer(resolved.kind()) && resolved.beanTypes() != null
                            && !resolved.beanTypes().isEmpty()) {
                        node.put("resolved_produced_type", resolved.beanTypes().getFirst());
                    }
                    appendContextNote(node, resolved);
                }
            } else {
                node.putNull("resolved_to");
                switch (point.resolutionStatus()) {
                    case UNSATISFIED -> unsatisfied.add(point.fieldName());
                    case CONTEXT_REQUIRED -> contextRequired.add(point.fieldName());
                    case UNKNOWN -> unknown.add(point.fieldName());
                    case UNSUPPORTED_MECHANISM -> unsupported.add(point.fieldName());
                    default -> { }
                }
            }
        }
        shareResolutionContext(root, result);
        InterceptionEvidence.append(root, reader, cls, bean.get());
        appendMeta(root, reader, cls.sourceTokens());
        return root.toString();
    }

    private static final Set<String> CONTEXT_BOUND_SCOPES = Set.of(
            "@RequestScoped", "@SessionScoped", "@ConversationScoped");

    /**
     * Injection into a request- or session-scoped bean goes through a client proxy whose context
     * must be active when the proxy is used. Static analysis cannot prove that for asynchronous
     * code, so the answer states the unknown instead of claiming availability or failure.
     */
    private static void appendContextNote(ObjectNode node, BeanRecord bean) {
        String scope = bean.scope();
        if (scope == null || !CONTEXT_BOUND_SCOPES.contains(scope)) return;
        ObjectNode context = node.putObject("context_requirement");
        context.put("scope", BeanTerms.scope(bean));
        context.put("async_availability", "unproven");
        if (bean.spring()) {
            context.put("note", "Calls through this scoped proxy need an active " + BeanTerms.scope(bean)
                    + " (RequestContextHolder bound to the calling thread). Startup code, schedulers, "
                    + "executors and @Async methods run without one unless it is propagated; Quill does not track it");
            return;
        }
        context.put("note", "Calls through this proxy need an active " + scope.substring(1)
                + " context. On other threads (Mutiny, CompletableFuture, executors, worker pools) "
                + "availability depends on context propagation (for example SmallRye Context "
                + "Propagation or Vert.x duplicated contexts); Quill does not track it");
    }

    private static void appendProviderEvidence(ReadQueryContext reader, ObjectNode node, InjectionPointRecord point,
            List<ExternalBeanRecord> providers) {
        List<ExternalBeanRecord> matches = providers.stream()
                .filter(b -> IndexReader.matchesBeanType(b.beanTypes(), point.targetType())).toList();
        ObjectNode evidence = node.putObject("provider_evidence");
        evidence.put("scope", "indexed declarations by provided type; not full container eligibility or runtime resolution")
                .put("runtime_resolution_proven", false).put("total", matches.size())
                .put("showing", Math.min(5, matches.size())).put("has_more", matches.size() > 5);
        ArrayNode declarations = evidence.putArray("declarations");
        for (ExternalBeanRecord bean : matches.stream().limit(5).toList()) {
            ObjectNode declaration = declarations.addObject().put("declaring_class", bean.className())
                    .put("kind", BeanTerms.kind(bean.framework(), bean.kind()))
                    .put("bean_scope", BeanTerms.scope(bean.framework(), bean.scope())).put("framework", bean.framework());
            if (bean.memberName() != null) declaration.put("member", bean.memberName());
            if (bean.artifact() != null) declaration.put("artifact", bean.artifact());
            declaration.set("qualifiers", JSON.valueToTree(BeanTerms.qualifiers(bean.framework(), bean.qualifiers())));
            declaration.set("profiles", JSON.valueToTree(bean.profiles()));
            declaration.put("qualifier_names_match", point.qualifiers().stream().allMatch(q -> {
                int end = q.indexOf('(');
                return IndexReader.matchesQualifier(bean.qualifiers(), end < 0 ? q : q.substring(0, end));
            }));
        }
        // V66 CQ-050: one matching CacheProducer#produce went unstated, and Codex read its bytecode to find the supplier.
        List<ExternalBeanRecord> qualified = matches.stream().filter(bean -> point.qualifiers().stream().allMatch(q -> {
            int end = q.indexOf('(');
            return IndexReader.matchesQualifier(bean.qualifiers(), end < 0 ? q : q.substring(0, end));
        })).toList();
        if (qualified.size() == 1) {
            ExternalBeanRecord only = qualified.getFirst();
            evidence.put("single_match", only.className() + (only.memberName() == null ? "" : "#" + only.memberName())
                    + " (" + only.kind() + " " + only.scope() + (only.artifact() == null ? "" : ", " + only.artifact())
                    + (only.profiles() == null || only.profiles().isEmpty() ? "" : ", profiles " + only.profiles())
                    + ") is the one indexed provider matching the type and qualifier names; no application bean is a candidate, "
                    + "so it supplies this injection point unless the build excludes or replaces it");
        }
        var lookup = ClassTargetResolver.resolve(reader, point.targetType());
        if (lookup.error() == null && reader.symbols.findClassAnnotations(lookup.cls().id()).stream()
                .anyMatch(a -> a.direct() && a.annotationName().equals("io.smallrye.config.ConfigMapping"))) {
            evidence.putObject("configuration_mapping_declaration")
                    .put("class", lookup.cls().className()).put("annotation", "io.smallrye.config.ConfigMapping")
                    .put("source", lookup.cls().sourceFile()).put("generated_bean_scope", "not_proven");
        }
    }

    private static void shareResolutionContext(ObjectNode root, ArrayNode points) {
        if (points.size() < 2) return;
        ObjectNode context = JSON.createObjectNode();
        for (String key : List.of("limitations", "applied_rules", "unsupported_rules")) {
            Set<JsonNode> common = null;
            for (JsonNode point : points) {
                JsonNode values = key.equals("limitations") ? point.path(key) : point.path("resolution_trace").path(key);
                Set<JsonNode> entries = new LinkedHashSet<>(); values.forEach(entries::add);
                if (common == null) common = entries; else common.retainAll(entries);
            }
            if (common == null || common.isEmpty()) continue;
            ArrayNode shared = context.putArray(key); common.forEach(shared::add);
            for (JsonNode point : points) {
                ArrayNode values = (ArrayNode) (key.equals("limitations") ? point.get(key) : point.path("resolution_trace").get(key));
                List<JsonNode> extra = new ArrayList<>();
                for (JsonNode value : values) if (!common.contains(value)) extra.add(value);
                values.removeAll(); extra.forEach(values::add);
            }
        }
        if (!context.isEmpty()) {
            context.put("applies_to", "All injection_points; per-point lists are additional.");
            root.set("resolution_context", context);
        }
    }

    private static void appendResolutionTrace(ObjectNode node, InjectionPointRecord point,
            Map<Integer, BeanRecord> beans, Map<Integer, ClassRecord> classes) {
        ObjectNode trace = node.putObject("resolution_trace");
        trace.set("applied_rules", JSON.valueToTree(point.resolutionTrace().appliedRules()));
        trace.set("unsupported_rules",
                JSON.valueToTree(point.resolutionTrace().unsupportedRules()));
        ArrayNode candidates = trace.putArray("candidates");
        for (ResolutionCandidate candidate : point.resolutionTrace().candidates()) {
            ObjectNode candidateNode = candidates.addObject();
            if (candidate.beanId() == null) candidateNode.putNull("bean_id");
            else candidateNode.put("bean_id", candidate.beanId());
            BeanRecord bean = candidate.beanId() == null ? null : beans.get(candidate.beanId());
            ClassRecord candidateClass = bean == null ? null : classes.get(bean.classId());
            String className = candidateClass == null
                    ? candidate.className() : candidateClass.className();
            if (className == null) candidateNode.putNull("class");
            else candidateNode.put("class", className);
            if (candidateClass != null) {
                candidateNode.put("file", candidateClass.sourceFile());
                candidateNode.put("origin", candidateClass.origin());
                candidateNode.put("lifecycle", candidateClass.lifecycle());
                appendContext(candidateNode, candidateClass);
            }
            if (bean != null) {
                candidateNode.put("kind", BeanTerms.kind(bean));
                candidateNode.put("scope", BeanTerms.scope(bean));
                if (bean.memberName() != null) candidateNode.put("member", bean.memberName());
                candidateNode.set("qualifiers", JSON.valueToTree(BeanTerms.qualifiers(bean)));
            }
            candidateNode.put("disposition", candidate.disposition().name().toLowerCase());
            candidateNode.put("reason", candidate.reason().name());
            if (candidate.relatedBeanId() != null) {
                candidateNode.put("related_bean_id", candidate.relatedBeanId());
                BeanRecord related = beans.get(candidate.relatedBeanId());
                ClassRecord relatedClass = related == null ? null : classes.get(related.classId());
                if (relatedClass != null) {
                    candidateNode.put("related_class", relatedClass.className());
                }
            }
            candidateNode.set("rules", JSON.valueToTree(candidate.rules()));
        }
    }

    private static void writeDependencyBreakdown(ObjectNode result,
            List<UsageRepository.DependencyBreakdown> breakdown) {
        for (UsageRepository.DependencyBreakdown entry : breakdown) {
            ObjectNode origin = result.putObject(entry.origin());
            origin.put("classes", entry.classes());
            origin.put("edges", entry.edges());
            if (entry.origin().equals("orphan_output")) origin.put("excluded_from_score", true);
        }
    }

    private static void appendContext(ObjectNode node, ClassRecord cls) {
        if (cls.module() == null) node.putNull("module");
        else node.put("module", cls.module());
        if (cls.sourceSet() == null) node.putNull("source_set");
        else node.put("source_set", cls.sourceSet());
    }

    private record Frontier(int classId, int depth) {}

    private record ImplementationResult(
            ClassRecord implementation, int distance,
            List<ClassOccurrenceRecord> occurrences) {}

    private record ImplementationSummary(
            int occurrenceCount, int sourceCount, int generatedCount,
            List<String> modules, List<String> generatedModules) {}

    private record GraphRelation(
            ClassRecord current,
            ClassRecord related,
            DependencyRecord dependency,
            String relationDirection,
            int depth) {}

    private record DependencyKey(
            int fromClassId, int toClassId, String kind, Integer injectionPointId) {

        private static DependencyKey of(DependencyRecord dependency) {
            return new DependencyKey(dependency.fromClassId(), dependency.toClassId(),
                    dependency.kind(), dependency.injectionPointId());
        }
    }
}
