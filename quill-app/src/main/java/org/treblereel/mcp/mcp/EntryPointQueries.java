package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/** Discovers statically identifiable application and framework entry points. */
final class EntryPointQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KINDS = Set.of(
            "main", "rest_resource", "rest_endpoint", "observer", "scheduled",
            "message_consumer", "annotation_processor", "service_provider");
    private static final Set<String> REST_CLASS_ANNOTATIONS = Set.of(
            "jakarta.ws.rs.Path", "javax.ws.rs.Path",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.web.bind.annotation.RequestMapping");
    private static final Set<String> REST_METHOD_ANNOTATIONS = Set.of(
            "jakarta.ws.rs.Path", "javax.ws.rs.Path",
            "jakarta.ws.rs.GET", "jakarta.ws.rs.POST", "jakarta.ws.rs.PUT",
            "jakarta.ws.rs.DELETE", "jakarta.ws.rs.PATCH", "jakarta.ws.rs.HEAD",
            "jakarta.ws.rs.OPTIONS", "javax.ws.rs.GET", "javax.ws.rs.POST",
            "javax.ws.rs.PUT", "javax.ws.rs.DELETE", "javax.ws.rs.PATCH",
            "javax.ws.rs.HEAD", "javax.ws.rs.OPTIONS",
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping");
    private static final Set<String> OBSERVER_ANNOTATIONS = Set.of(
            "jakarta.enterprise.event.Observes", "jakarta.enterprise.event.ObservesAsync",
            "javax.enterprise.event.Observes", "javax.enterprise.event.ObservesAsync");
    private static final Set<String> SCHEDULED_ANNOTATIONS = Set.of(
            "io.quarkus.scheduler.Scheduled", "io.quarkus.scheduler.Scheduled.ConcurrentExecution",
            "org.springframework.scheduling.annotation.Scheduled",
            "jakarta.ejb.Schedule", "jakarta.ejb.Schedules",
            "javax.ejb.Schedule", "javax.ejb.Schedules",
            "io.micronaut.scheduling.annotation.Scheduled");
    private static final Set<String> MESSAGE_ANNOTATIONS = Set.of(
            "org.eclipse.microprofile.reactive.messaging.Incoming",
            "org.springframework.kafka.annotation.KafkaListener",
            "org.springframework.jms.annotation.JmsListener",
            "org.springframework.amqp.rabbit.annotation.RabbitListener",
            "org.springframework.pulsar.annotation.PulsarListener",
            "io.quarkus.vertx.ConsumeEvent");
    private static final Set<String> PROCESSOR_TYPES = Set.of(
            "javax.annotation.processing.Processor",
            "javax.annotation.processing.AbstractProcessor");

    String findEntryPoints(ReadQueryContext reader, String kind, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset) {
        return findEntryPoints(reader, kind, module, includeGenerated, includeTests, limit, offset, null);
    }

    String findEntryPoints(ReadQueryContext reader, String kind, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset, String eventType) {
        return findEntryPoints(reader, kind, module, includeGenerated, includeTests, limit, offset, eventType, "summary");
    }

    String findEntryPoints(ReadQueryContext reader, String kind, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset, String eventType, String detail) {
        if (!Set.of("summary", "full").contains(detail)) return errorResponse("detail must be summary or full");
        boolean summary = detail.equals("summary");
        String normalizedKind = normalizeKind(kind);
        if (eventType != null && (!"observer".equals(normalizedKind) || eventType.isBlank()))
            return errorResponse("event_type requires kind=observer and a nonblank event type");
        if (normalizedKind != null && !KINDS.contains(normalizedKind)) {
            return errorResponse("Invalid kind: expected main, rest_resource, rest_endpoint, "
                    + "observer, scheduled, message_consumer, annotation_processor, "
                    + "service_provider, or all");
        }
        List<ClassRecord> classes = reader.symbols.findAllClasses();
        ObserverEventFilter eventFilter = eventType == null ? null : new ObserverEventFilter(eventType, classes);
        Map<String, ClassRecord> classesByName = new HashMap<>();
        for (ClassRecord cls : classes) {
            classesByName.put(cls.className(), cls);
        }
        List<Integer> ids = classes.stream().map(ClassRecord::id).toList();
        Map<Integer, List<ClassMemberRecord>> members = reader.symbols.findClassMembers(ids);
        Map<Integer, List<ClassAnnotationRecord>> classAnnotations =
                reader.symbols.findClassAnnotations(ids);
        Set<String> processors = processorClasses(classes);
        Map<EntryKey, Candidate> entries = new LinkedHashMap<>();

        for (ClassRecord cls : classes) {
            if (!inScope(cls, module, includeGenerated, includeTests)) continue;
            List<ClassAnnotationRecord> annotations =
                    classAnnotations.getOrDefault(cls.id(), List.of());
            addClassAnnotationEntry(entries, cls, annotations, REST_CLASS_ANNOTATIONS,
                    "rest_resource", "framework_resource_annotation");
            if (processors.contains(cls.className())) {
                add(entries, cls, null, "annotation_processor", "processor_type_hierarchy",
                        List.of(), "high", null);
            }
            for (ClassMemberRecord member : members.getOrDefault(cls.id(), List.of())) {
                if (!"METHOD".equals(member.kind())) continue;
                if (isMain(member)) {
                    add(entries, cls, member, "main", "public_static_main_signature",
                            List.of(), "high", null);
                }
                addMethodAnnotationEntry(entries, cls, member, REST_METHOD_ANNOTATIONS,
                        "rest_endpoint", "http_or_route_annotation");
                addMethodAnnotationEntry(entries, cls, member, OBSERVER_ANNOTATIONS,
                        "observer", "event_observer_parameter_annotation");
                addMethodAnnotationEntry(entries, cls, member, SCHEDULED_ANNOTATIONS,
                        "scheduled", "scheduler_annotation");
                addMethodAnnotationEntry(entries, cls, member, MESSAGE_ANNOTATIONS,
                        "message_consumer", "messaging_consumer_annotation");
            }
        }

        int unavailableProviders = addServiceProviders(
                reader, entries, classesByName, module, includeGenerated, includeTests);
        List<Candidate> scoped = entries.values().stream()
                .filter(candidate -> normalizedKind == null
                        || normalizedKind.equals(candidate.kind()))
                .toList();
        List<Candidate> result = scoped.stream()
                .filter(candidate -> eventFilter == null || eventFilter.match(observedType(candidate))
                        != ObserverEventFilter.Match.INCOMPATIBLE)
                .sorted(Comparator.comparing(Candidate::kind)
                        .thenComparingInt(candidate -> eventFilter == null ? 0 : eventFilter.match(observedType(candidate)).ordinal())
                        .thenComparing(candidate -> candidate.owner().className())
                        .thenComparing(candidate -> candidate.member() == null
                                ? "" : candidate.member().signature()))
                .toList();
        int from = Math.min(offset, result.size());
        int to = (int) Math.min((long) from + limit, result.size());
        List<Candidate> page = result.subList(from, to);

        ObjectNode root = JSON.createObjectNode();
        if (normalizedKind == null) root.putNull("kind_filter");
        else root.put("kind_filter", normalizedKind);
        if (module == null) root.putNull("module_filter");
        else root.put("module_filter", module);
        root.put("include_generated", includeGenerated);
        root.put("include_tests", includeTests);
        root.put("detail", detail);
        if (eventFilter != null) {
            root.put("event_type_filter", eventType);
            ObjectNode selection = root.putObject("observer_selection");
            selection.put("scanned", scoped.size());
            selection.put("type_compatible", result.stream().filter(candidate -> eventFilter.match(observedType(candidate))
                    == ObserverEventFilter.Match.COMPATIBLE).count());
            selection.put("uncertain", result.stream().filter(candidate -> eventFilter.match(observedType(candidate))
                    == ObserverEventFilter.Match.UNKNOWN).count());
            selection.set("unindexed_types", JSON.valueToTree(eventFilter.missingTypes()));
            selection.put("scope", "Type candidates only; qualifiers, activation and runtime delivery are not resolved. Generic or incomplete hierarchy matches remain uncertain candidates.");
        }
        root.put("unresolved_service_providers", unavailableProviders);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String supportedKind : KINDS.stream().sorted().toList()) {
            long count = result.stream().filter(value -> value.kind().equals(supportedKind)).count();
            if (count > 0) counts.put(supportedKind, count);
        }
        root.set("counts_by_kind", JSON.valueToTree(counts));
        ArrayNode limitations = root.putArray("limitations");
        limitations.add("Only compiled signatures, indexed annotations, type hierarchy, and project-local META-INF/services descriptors are analyzed");
        limitations.add("XML, YAML, programmatic route registration, build extensions, reflection, and external deployment descriptors may define additional entry points");
        limitations.add("Annotation evidence contains indexed values; resolved defaults, when available, are marked by $defaults");
        limitations.add("Composed method annotations are reported only when their concrete annotation type is one of the supported framework entry-point annotations");

        ArrayNode values = root.putArray("entry_points");
        if (normalizedKind == null || normalizedKind.equals("observer")) {
            root.put("observer_semantics", "No observed qualifiers means any qualifiers are accepted; otherwise every observed qualifier must match. Non-qualifier parameter annotations are ignored. @Observes is synchronous; @ObservesAsync is asynchronous. Transaction phase does not prove actual delivery.");
            if (summary) root.put("full_detail_available", true);
        }
        Set<Integer> countedClasses = new HashSet<>();
        int naiveTokens = 0;
        for (Candidate candidate : page) {
            ClassRecord cls = candidate.owner();
            ObjectNode node = values.addObject();
            node.put("kind", candidate.kind());
            node.put("class", cls.className());
            node.put("symbol_kind", candidate.member() == null ? "class" : "method");
            if (candidate.member() == null) {
                node.putNull("method");
                node.putNull("signature");
            } else {
                node.put("method", candidate.member().name());
                node.put("signature", candidate.member().signature());
            }
            node.put("detection_rule", candidate.rule());
            node.put("confidence", candidate.confidence());
            node.set("annotations", JSON.valueToTree(candidate.annotations()));
            if (candidate.detail() != null) node.set("service", candidate.detail());
            if (candidate.member() != null) appendAnnotationEvidence(node, candidate);
            if (eventFilter != null) node.put("type_match", eventFilter.match(observedType(candidate)).name().toLowerCase(Locale.ROOT));
            if (cls.sourceFile() == null) node.putNull("source");
            else node.put("source", (cls.sourceLine() > 0 ? cls.sourceFile() + ":" + cls.sourceLine() : cls.sourceFile()));
            node.put("origin", cls.origin());
            if (cls.module() == null) node.putNull("module");
            else node.put("module", cls.module());
            if (cls.sourceSet() == null) node.putNull("source_set");
            else node.put("source_set", cls.sourceSet());
            if (summary && candidate.kind().equals("observer")) {
                node.remove(List.of("symbol_kind", "detection_rule", "confidence", "annotations", "annotation_values", "origin", "module"));
                if (node.get("observer") instanceof ObjectNode observer) observer.remove("qualifier_rule");
            }
            if (summary && values.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 6000) {
                values.remove(values.size() - 1);
                root.put("entry_budget_bytes", 6000);
                root.put("budget_limited", true);
                break;
            }
            if (countedClasses.add(cls.id())) naiveTokens += cls.sourceTokens();
        }
        appendPage(root, values.size(), result.size(), limit, offset);
        if (root.path("has_more").asBoolean()) {
            ObjectNode next = root.putObject("next_page_request");
            if (kind != null) next.put("kind", kind);
            if (module != null) next.put("module", module);
            if (eventType != null) next.put("event_type", eventType);
            next.put("include_generated", includeGenerated);
            next.put("include_tests", includeTests);
            next.put("limit", limit);
            next.put("offset", offset + values.size());
            next.put("detail", values.isEmpty() ? "full" : detail);
        }
        appendMeta(root, reader, naiveTokens);
        return root.toString();
    }

    private static void addClassAnnotationEntry(Map<EntryKey, Candidate> entries,
            ClassRecord cls, List<ClassAnnotationRecord> annotations, Set<String> accepted,
            String kind, String rule) {
        List<String> matches = annotations.stream()
                .filter(annotation -> accepted.contains(annotation.annotationName()))
                .map(ClassAnnotationRecord::annotationName).distinct().sorted().toList();
        if (matches.isEmpty()) return;
        boolean direct = annotations.stream().anyMatch(annotation -> annotation.direct()
                && matches.contains(annotation.annotationName()));
        add(entries, cls, null, kind, rule, matches, direct ? "high" : "medium", null);
    }

    private static void addMethodAnnotationEntry(Map<EntryKey, Candidate> entries,
            ClassRecord cls, ClassMemberRecord member, Set<String> accepted,
            String kind, String rule) {
        List<String> matches = member.annotations().stream()
                .filter(accepted::contains).distinct().sorted().toList();
        if (!matches.isEmpty()) {
            add(entries, cls, member, kind, rule, matches, "high", null);
        }
    }

    private static void add(Map<EntryKey, Candidate> entries, ClassRecord cls,
            ClassMemberRecord member, String kind, String rule, List<String> annotations,
            String confidence, ObjectNode detail) {
        EntryKey key = new EntryKey(cls.id(), member == null ? null : member.signature(), kind);
        Candidate candidate = new Candidate(
                cls, member, kind, rule, annotations, confidence, detail);
        entries.compute(key, (ignored, existing) -> existing == null
                || (existing.detail() == null && detail != null) ? candidate : existing);
    }

    private static int addServiceProviders(ReadQueryContext reader, Map<EntryKey, Candidate> entries,
            Map<String, ClassRecord> classesByName, String module,
            boolean includeGenerated, boolean includeTests) {
        String detail = reader.index.getMetadata().get("service_registrations_detail");
        if (detail == null) return 0;
        int unresolved = 0;
        try {
            for (JsonNode registration : JSON.readTree(detail)) {
                String providerName = registration.path("providerType").asText(null);
                ClassRecord provider = classesByName.get(providerName);
                if (provider == null) {
                    unresolved++;
                    continue;
                }
                if (!inScope(provider, module, includeGenerated, includeTests)) continue;
                String serviceType = registration.path("serviceType").asText();
                String kind = serviceType.endsWith("annotation.processing.Processor")
                        ? "annotation_processor" : "service_provider";
                ObjectNode service = JSON.createObjectNode();
                service.put("type", serviceType);
                service.put("descriptor", registration.path("descriptorPath").asText());
                service.put("line", registration.path("line").asInt());
                add(entries, provider, null, kind, "service_descriptor_registration",
                        List.of(), "high", service);
            }
        } catch (Exception ignored) {
            return unresolved;
        }
        return unresolved;
    }

    private static Set<String> processorClasses(List<ClassRecord> classes) {
        Set<String> result = new LinkedHashSet<>();
        boolean changed;
        do {
            changed = false;
            for (ClassRecord cls : classes) {
                if (result.contains(cls.className())) continue;
                boolean processor = (cls.superclass() != null
                        && PROCESSOR_TYPES.contains(cls.superclass()))
                        || cls.interfaces().stream().anyMatch(PROCESSOR_TYPES::contains)
                        || (cls.superclass() != null && result.contains(cls.superclass()))
                        || cls.interfaces().stream().anyMatch(result::contains);
                if (processor && result.add(cls.className())) changed = true;
            }
        } while (changed);
        return result;
    }

    private static boolean isMain(ClassMemberRecord member) {
        return "main".equals(member.name())
                && "void".equals(member.typeName())
                && member.parameterTypes().equals(List.of("java.lang.String[]"))
                && hasModifier(member, "public")
                && hasModifier(member, "static");
    }

    private static boolean hasModifier(ClassMemberRecord member, String modifier) {
        return Set.of(member.modifiers().split("\\s+")).contains(modifier);
    }

    private static boolean inScope(ClassRecord cls, String module,
            boolean includeGenerated, boolean includeTests) {
        if (module != null && !module.equals(cls.module())) return false;
        if ("dependency".equals(cls.origin())) return false;
        if (!includeGenerated && "generated".equals(cls.origin())) return false;
        return includeTests || !cls.inTestSourceSet();
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank() || "all".equalsIgnoreCase(kind)) return null;
        return kind.trim().toLowerCase(Locale.ROOT);
    }

    private record EntryKey(int classId, String signature, String kind) {}

    private static String observedType(Candidate candidate) {
        return candidate.member().annotationDetails().stream()
                .filter(detail -> OBSERVER_ANNOTATIONS.contains(detail.annotationName()) && detail.parameterIndex() != null)
                .map(detail -> detail.parameterType()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * Route paths, schedules, topics and observer settings come from the indexed annotation
     * values. Observers also report the observed event type and qualifiers: an observer without
     * qualifiers is notified for every event of its type, whatever qualifiers the event has.
     */
    private static void appendAnnotationEvidence(ObjectNode node, Candidate candidate) {
        var details = candidate.member().annotationDetails();
        ObjectNode values = JSON.createObjectNode();
        details.stream().filter(detail -> candidate.annotations().contains(detail.annotationName()))
                .filter(detail -> !detail.values().isEmpty())
                .forEach(detail -> values.set(detail.annotationName(), JSON.valueToTree(detail.values())));
        if (!values.isEmpty()) node.set("annotation_values", values);
        if (!"observer".equals(candidate.kind())) return;
        details.stream().filter(detail -> OBSERVER_ANNOTATIONS.contains(detail.annotationName()))
                .filter(detail -> detail.parameterIndex() != null).findFirst().ifPresent(observes -> {
                    ObjectNode observer = node.putObject("observer");
                    observer.put("event_type", observes.parameterType());
                    observer.put("asynchronous", observes.annotationName().endsWith("ObservesAsync"));
                    List<String> parameterAnnotations = details.stream()
                            .filter(detail -> observes.parameterIndex().equals(detail.parameterIndex()))
                            .map(detail -> detail.annotationName())
                            .filter(name -> !OBSERVER_ANNOTATIONS.contains(name)).distinct().sorted().toList();
                    observer.set("observed_parameter_annotations", JSON.valueToTree(parameterAnnotations));
                    ObjectNode parameterValues = JSON.createObjectNode();
                    details.stream().filter(detail -> observes.parameterIndex().equals(detail.parameterIndex()))
                            .filter(detail -> !OBSERVER_ANNOTATIONS.contains(detail.annotationName()) && !detail.values().isEmpty())
                            .forEach(detail -> parameterValues.set(detail.annotationName(), JSON.valueToTree(detail.values())));
                    if (!parameterValues.isEmpty()) observer.set("parameter_annotation_values", parameterValues);
                    observer.put("during", String.valueOf(observes.values().getOrDefault("during", "IN_PROGRESS")));
                    observer.put("notify_observer", String.valueOf(
                            observes.values().getOrDefault("notifyObserver", "ALWAYS")));
                    observer.put("qualifier_rule", parameterAnnotations.isEmpty()
                            ? "No observed qualifiers: notified for every event of this type; no @Default is required"
                            : "Notified when the event carries every observed qualifier; non-qualifier annotations in this list are ignored");
                    // during and notifyObserver are stated above; keep only which of them were defaulted.
                    if (node.get("annotation_values") instanceof ObjectNode annotationValues) {
                        JsonNode defaults = annotationValues.path(observes.annotationName()).path("$defaults");
                        if (!defaults.isMissingNode()) observer.set("defaults", defaults);
                        annotationValues.remove(OBSERVER_ANNOTATIONS);
                        if (annotationValues.isEmpty()) node.remove("annotation_values");
                    }
                });
    }

    private record Candidate(
            ClassRecord owner,
            ClassMemberRecord member,
            String kind,
            String rule,
            List<String> annotations,
            String confidence,
            ObjectNode detail) {}
}
