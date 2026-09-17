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
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
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

    String findEntryPoints(Jdbi jdbi, String kind, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset) {
        String normalizedKind = normalizeKind(kind);
        if (normalizedKind != null && !KINDS.contains(normalizedKind)) {
            return errorResponse("Invalid kind: expected main, rest_resource, rest_endpoint, "
                    + "observer, scheduled, message_consumer, annotation_processor, "
                    + "service_provider, or all");
        }
        List<ClassRecord> classes = IndexReader.findAllClasses(jdbi);
        Map<String, ClassRecord> classesByName = new HashMap<>();
        for (ClassRecord cls : classes) {
            classesByName.put(cls.className(), cls);
        }
        List<Integer> ids = classes.stream().map(ClassRecord::id).toList();
        Map<Integer, List<ClassMemberRecord>> members = IndexReader.findClassMembers(jdbi, ids);
        Map<Integer, List<ClassAnnotationRecord>> classAnnotations =
                IndexReader.findClassAnnotations(jdbi, ids);
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
                jdbi, entries, classesByName, module, includeGenerated, includeTests);
        List<Candidate> result = entries.values().stream()
                .filter(candidate -> normalizedKind == null
                        || normalizedKind.equals(candidate.kind()))
                .sorted(Comparator.comparing(Candidate::kind)
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
        limitations.add("Annotation values such as route paths, topics, channels, and schedules are not retained in the current member index");
        limitations.add("Composed method annotations are reported only when their concrete annotation type is one of the supported framework entry-point annotations");

        ArrayNode values = root.putArray("entry_points");
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
            if (cls.sourceFile() == null) node.putNull("source");
            else node.put("source", cls.sourceFile() + ":" + cls.sourceLine());
            node.put("origin", cls.origin());
            if (cls.module() == null) node.putNull("module");
            else node.put("module", cls.module());
            if (cls.sourceSet() == null) node.putNull("source_set");
            else node.put("source_set", cls.sourceSet());
            if (countedClasses.add(cls.id())) naiveTokens += cls.sourceTokens();
        }
        appendPage(root, page.size(), result.size(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
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

    private static int addServiceProviders(Jdbi jdbi, Map<EntryKey, Candidate> entries,
            Map<String, ClassRecord> classesByName, String module,
            boolean includeGenerated, boolean includeTests) {
        String detail = IndexReader.getMetadata(jdbi).get("service_registrations_detail");
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
        return includeTests || !"test".equals(cls.sourceSet());
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank() || "all".equalsIgnoreCase(kind)) return null;
        return kind.trim().toLowerCase(Locale.ROOT);
    }

    private record EntryKey(int classId, String signature, String kind) {}

    private record Candidate(
            ClassRecord owner,
            ClassMemberRecord member,
            String kind,
            String rule,
            List<String> annotations,
            String confidence,
            ObjectNode detail) {}
}
