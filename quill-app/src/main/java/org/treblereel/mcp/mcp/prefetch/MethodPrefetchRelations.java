package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.BeanRepository;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.diagnostics.QuillLog;
import org.treblereel.mcp.mcp.config.ConfigurationSourceEvidence;
import org.treblereel.mcp.mcp.config.FrameworkContracts;
import org.treblereel.mcp.mcp.query.EntryPointQueries;
import org.treblereel.mcp.mcp.query.ExternalSymbolQueries;
import org.treblereel.mcp.mcp.query.MethodContextQueries;
import org.treblereel.mcp.mcp.support.BeanTerms;

/**
 * What a method fact needs beyond its callers for a first-turn answer, one line per relation: the
 * method's annotations with their set values, what it calls two levels down (lambda bodies count as
 * the method that declares them), and for a producer method the bean it produces, its parameters
 * and the injection points it resolves. Static bytecode evidence only.
 */
public final class MethodPrefetchRelations {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int LIMIT = 40;
    private static final int DEPTH = 2;

    private MethodPrefetchRelations() {}

    /**
     * Callers become one line per class (class, file, calling methods) and coverage keeps its
     * verdict, gaps and compact caller line references.
     */
    /**
     * src/main|test/java/{package path}/{top-level class}.java, which the class name already gives.
     */
    static boolean conventionalSource(String className, String source) {
        String path = className.split("\\$", 2)[0].replace('.', '/') + ".java";
        return source.equals("src/main/java/" + path) || source.equals("src/test/java/" + path);
    }

    public static void compactImpact(ObjectNode fact) {
        // Apollo AQ-041: one line per SpEL reference, so the Java callers and tests the question
        // also asks for fit.
        if (fact.path("expression_references") instanceof ObjectNode expressions
                && expressions.path("references") instanceof ArrayNode references) {
            ArrayNode lines = JSON.createArrayNode();
            for (JsonNode reference : references) {
                if (reference.isTextual()) {
                    lines.add(reference);
                    continue;
                }
                String owner = reference.path("class").asText();
                String annotation = reference.path("annotation").asText();
                lines.add(
                        owner.substring(owner.lastIndexOf('.') + 1)
                                + (reference.has("method")
                                        ? "#" + reference.path("method").asText()
                                        : " (class)")
                                + " @"
                                + annotation.substring(annotation.lastIndexOf('.') + 1)
                                + "("
                                + reference.path("expression").asText()
                                + ")");
            }
            expressions.set("references", lines);
        }
        // Its own field, so the budget drops it before halving callers (V63 CQ-046: test_callers
        // were the answer).
        ArrayNode resultUse = JSON.createArrayNode();
        for (JsonNode caller : fact.path("production_callers"))
            for (JsonNode method : caller.path("methods"))
                if (method.hasNonNull("result_use"))
                    resultUse.add(
                            caller.path("class").asText()
                                    + "#"
                                    + method.path("method").asText()
                                    + " "
                                    + method.get("result_use").asText());
        for (String field :
                List.of(
                        "production_callers",
                        "test_callers",
                        "unclassified_callers",
                        "indirect_production")) {
            if (!(fact.get(field) instanceof ArrayNode callers)) continue;
            ArrayNode lines = JSON.createArrayNode();
            for (JsonNode caller : callers) {
                Set<String> methods = new LinkedHashSet<>();
                caller.path("methods")
                        .forEach(
                                method ->
                                        methods.add(
                                                method.path("method").asText()
                                                        + (method.has("depth")
                                                                ? "@" + method.path("depth").asInt()
                                                                : "")
                                                        + (method.hasNonNull("evidence_lines")
                                                                ? " lines "
                                                                        + (method.get(
                                                                                                "evidence_lines")
                                                                                        .isTextual()
                                                                                ? method.get(
                                                                                                "evidence_lines")
                                                                                        .asText()
                                                                                : method.get(
                                                                                                "evidence_lines")
                                                                                        .toString())
                                                                : "")));
                String source =
                        conventionalSource(
                                        caller.path("class").asText(),
                                        caller.path("source").asText())
                                ? ""
                                : " " + caller.path("source").asText();
                lines.add(
                        caller.path("class").asText()
                                + source
                                + (methods.isEmpty() ? "" : " via " + String.join(", ", methods)));
            }
            fact.set(field, lines);
        }
        if (!resultUse.isEmpty()) fact.set("caller_result_use", resultUse);
        // A declaring-class test that also calls the method is already a test caller.
        if (fact.get("declaring_class_tests") instanceof ArrayNode declaring) {
            Set<String> callers = new java.util.HashSet<>();
            fact.path("test_callers").forEach(line -> callers.add(line.asText().split(" ", 2)[0]));
            for (int i = declaring.size() - 1; i >= 0; i--)
                if (callers.contains(declaring.get(i).asText())) declaring.remove(i);
        }
        if (fact.get("coverage") instanceof ObjectNode coverage) {
            ObjectNode compact = JSON.createObjectNode();
            for (String field : List.of("scan_complete", "incomplete_reasons", "not_covered"))
                if (coverage.has(field)) compact.set(field, coverage.get(field));
            fact.set("coverage", compact);
        }
        if (fact.get("override_evidence") instanceof ObjectNode overrides) {
            overrides.remove("selected_declarations");
        }
        fact.remove(List.of("reference_page_counts", "evidence_detail"));
    }

    static void append(ReadQueryContext db, ObjectNode fact) throws Exception {
        append(db, null, fact);
    }

    /**
     * With {@code root}, a callee inherited from a dependency class names the class that declares
     * it.
     */
    static void append(ReadQueryContext db, java.nio.file.Path root, ObjectNode fact)
            throws Exception {
        String owner = fact.path("class").asText(null);
        String name = fact.path("name").asText(null);
        String descriptor = fact.path("descriptor").asText(null);
        if (owner == null || name == null || descriptor == null) return;
        Integer classId = db.symbols.findCurrentClassId(owner).orElse(null);
        if (classId == null) return;
        annotations(db, fact, classId, name, descriptor);
        FrameworkContracts.append(db, fact, classId, name, descriptor);
        referencedMembers(db, fact, classId, name, descriptor);
        interceptors(db, fact, classId, name, descriptor);
        callees(db, root, fact, classId, name, descriptor);
        delegateTests(db, fact, classId, name, descriptor);
        MethodImplementations.append(db, fact, classId, name, descriptor);
        var hops = ThreadHops.of(db, root, classId, name, descriptor);
        if (hops != null) fact.set("thread_hops", hops);
        sameEventObservers(db, fact, classId, name, descriptor);
        producer(db, root, fact, classId, name);
        createdByBeanMethods(db, fact, classId);
        if (root == null) return;
        boolean lambdaGap = missingLambdaEvidence(db, classId, name, descriptor);
        if (lambdaGap) fact.put("lambda_evidence_gap", true);
        // Without the body, recorded agents opened the whole file to read this one method.
        var cls = db.symbols.findClassByName(owner).orElse(null);
        if (cls == null) return;
        nameInSource(root, cls.sourceFile(), name, fact);
        JsonNode source = new MethodContextQueries().get(db, cls, name + descriptor);
        int budget = lambdaGap ? LAMBDA_SOURCE_BUDGET : SOURCE_BUDGET;
        if (!source.path("source_complete").asBoolean()) {
            fact.put(
                    "selected_method_source_status",
                    source.path("status").asText("source_unavailable"));
        } else if (source.path("source").asText().length() > budget) {
            fact.put("selected_method_source_status", "source_budget");
        } else {
            fact.putObject("selected_method_source")
                    .put("source_file", source.path("source_file").asText())
                    .put("source_line", source.path("declaration").path("source_line").asInt())
                    .put(
                            "source_end_line",
                            source.path("declaration").path("source_end_line").asInt())
                    .put("source", source.path("source").asText());
        }
    }

    private static final int MENTION_LIMIT = 20;

    /**
     * Lines of the declaring file where the method name appears, split into code and the comments
     * and strings a bytecode index cannot see. In V24-V27 CQ-049 Claude grepped for parse 8 times
     * after Quill had listed every call, looking for javadoc and string mentions before renaming.
     */
    static void nameInSource(
            java.nio.file.Path root, String sourceFile, String name, ObjectNode fact) {
        if (sourceFile == null) return;
        List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(root.resolve(sourceFile));
        } catch (java.io.IOException | RuntimeException unreadable) {
            QuillLog.swallowed(unreadable);
            return;
        }
        java.util.regex.Pattern word =
                java.util.regex.Pattern.compile(
                        "(?<![\\w$])" + java.util.regex.Pattern.quote(name) + "(?![\\w$])");
        ArrayNode code = JSON.createArrayNode();
        Set<String> other = new LinkedHashSet<>();
        boolean block = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            char[] kinds = new char[line.length()];
            boolean string = false, character = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i), next = i + 1 < line.length() ? line.charAt(i + 1) : 0;
                if (block) {
                    kinds[i] = 'm';
                    if (c == '*' && next == '/') {
                        kinds[++i] = 'm';
                        block = false;
                    }
                } else if (string || character) {
                    kinds[i] = 's';
                    if (c == '\\' && i + 1 < line.length()) kinds[++i] = 's';
                    else if (string && c == '"') string = false;
                    else if (character && c == '\'') character = false;
                } else if (c == '/' && next == '/') {
                    for (int j = i; j < line.length(); j++) kinds[j] = 'm';
                    break;
                } else if (c == '/' && next == '*') {
                    kinds[i] = 'm';
                    kinds[++i] = 'm';
                    block = true;
                } else {
                    kinds[i] = 'c';
                    if (c == '"') {
                        string = true;
                        kinds[i] = 's';
                    } else if (c == '\'') {
                        character = true;
                        kinds[i] = 's';
                    }
                }
            }
            var match = word.matcher(line);
            boolean codeLine = false;
            while (match.find()) {
                char kind = kinds[match.start()];
                if (kind == 'c') codeLine = true;
                else other.add((index + 1) + (kind == 'm' ? " comment" : " string"));
            }
            if (codeLine) code.add(index + 1);
        }
        if (code.isEmpty() && other.isEmpty()) return;
        ObjectNode mentions = fact.putObject("name_in_source");
        java.nio.file.Path file = root.resolve(sourceFile).normalize();
        mentions.put(
                "file",
                file.startsWith(root)
                        ? root.relativize(file).toString().replace('\\', '/')
                        : sourceFile);
        mentions.set("code_lines", code);
        ArrayNode rest = mentions.putArray("other");
        other.stream().limit(MENTION_LIMIT).forEach(rest::add);
        if (other.size() > MENTION_LIMIT) mentions.put("other_truncated", true);
        mentions.put(
                "basis",
                "word matches in the declaring file; comments and strings are not in the bytecode index");
    }

    private static final int OBSERVER_LIMIT = 12;
    private static final int DEFAULT_OBSERVER_PRIORITY = 2500;

    /**
     * For an observer method, the application's other observers of the same event type in @Priority
     * order: what the start or stop order between them is, and where it is unspecified.
     */
    private static void sameEventObservers(
            ReadQueryContext db, ObjectNode fact, int classId, String name, String descriptor) {
        String details =
                db.symbols.findMemberAnnotationDetails(classId, name, descriptor).orElse("[]");
        String event = null;
        try {
            for (JsonNode annotation : JSON.readTree(details))
                if (List.of("jakarta.enterprise.event.Observes", "javax.enterprise.event.Observes")
                        .contains(annotation.path("annotationName").asText()))
                    event = annotation.path("parameterType").asText(null);
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
            return;
        }
        if (event == null || event.isBlank()) return;
        String type = event;
        record Observer(String name, int priority, boolean explicit) {}
        List<Observer> observers = new java.util.ArrayList<>();
        for (var row : db.symbols.findObserverMembers(type)) {
            if (row.classId() == classId
                    && name.equals(row.name())
                    && descriptor.equals(row.descriptor())) continue;
            Integer priority = null;
            try {
                for (JsonNode annotation : JSON.readTree(String.valueOf(row.annotationDetails())))
                    if (annotation.path("annotationName").asText().endsWith(".annotation.Priority")
                            && annotation.path("targetKind").asText().equals("METHOD_PARAMETER"))
                        priority =
                                annotation
                                        .path("values")
                                        .path("value")
                                        .asInt(DEFAULT_OBSERVER_PRIORITY);
            } catch (Exception unreadable) {
                QuillLog.swallowed(unreadable);
                // An unreadable row keeps the default priority.
            }
            observers.add(
                    new Observer(
                            row.className() + "#" + row.name(),
                            priority == null ? DEFAULT_OBSERVER_PRIORITY : priority,
                            priority != null));
        }
        if (observers.isEmpty()) return;
        Set<String> related = relatedClasses(db, fact, classId);
        observers.sort(
                java.util.Comparator.comparingInt(Observer::priority)
                        .thenComparing(Observer::name));
        // Listed: those with a stated @Priority and those of classes this method uses.
        List<Observer> named =
                observers.stream()
                        .filter(
                                o ->
                                        o.explicit()
                                                || related.contains(
                                                        o.name()
                                                                .substring(
                                                                        0, o.name().indexOf('#'))))
                        .limit(OBSERVER_LIMIT)
                        .toList();
        boolean selfDefault =
                !db.symbols
                        .findMemberAnnotationDetails(classId, name, descriptor)
                        .orElse("")
                        .contains(".annotation.Priority\"");
        long unordered = observers.stream().filter(o -> !o.explicit()).count();
        // One field, so the context budget can drop it whole before it cuts the method's own
        // evidence.
        ObjectNode summary = fact.putObject("same_event_observers");
        summary.put("event", type).put("others", observers.size());
        if (!named.isEmpty()) {
            ArrayNode lines = summary.putArray("listed");
            named.forEach(
                    o ->
                            lines.add(
                                    o.name()
                                            + (o.explicit()
                                                    ? " @Priority(" + o.priority() + ")"
                                                    : "")));
        }
        if (selfDefault && unordered > 0)
            summary.put(
                    "order",
                    unordered
                            + " share default @Priority "
                            + DEFAULT_OBSERVER_PRIORITY
                            + " with this one: order unspecified");
    }

    /** Classes of the method's callees and of the fields it reads. */
    private static Set<String> relatedClasses(ReadQueryContext db, ObjectNode fact, int classId) {
        Set<String> related = new java.util.HashSet<>();
        for (JsonNode call : fact.path("calls")) {
            String text = call.asText();
            int space = text.indexOf(' '), hash = text.indexOf('#');
            if (space > 0 && hash > space) related.add(text.substring(space + 1, hash));
        }
        for (JsonNode member : fact.path("referenced_members")) {
            String text = member.asText();
            int colon = text.indexOf(": ");
            if (colon > 0) related.add(text.substring(colon + 2).split("[ <;]")[0]);
        }
        return related;
    }

    private static final int MEMBER_LIMIT = 8;
    private static final int USER_LIMIT = 6;

    /**
     * Fields of the method's own class that its body or its lambdas use, with their declared type
     * and the class's other methods that touch them. With the body alone, recorded agents still
     * opened the file for exactly these declarations.
     */
    private static void referencedMembers(
            ReadQueryContext db, ObjectNode fact, int classId, String name, String descriptor) {
        var rows = db.usages.findOwnFieldAccesses(classId);
        Set<String> used = new LinkedHashSet<>();
        Map<String, java.util.TreeSet<String>> users = new java.util.LinkedHashMap<>();
        for (var row : rows) {
            String method = String.valueOf(row.fromMethod()),
                    field = String.valueOf(row.fieldName());
            String declarer =
                    method.startsWith("lambda$") && method.indexOf('$', 7) > 7
                            ? method.substring(7, method.indexOf('$', 7))
                            : method;
            boolean selected =
                    declarer.equals(name)
                            && (method.startsWith("lambda$")
                                    || descriptor.equals(row.fromDescriptor()));
            if (selected) used.add(field);
            else users.computeIfAbsent(field, key -> new java.util.TreeSet<>()).add(declarer);
        }
        if (used.isEmpty()) return;
        ArrayNode lines = JSON.createArrayNode();
        for (String field : used.stream().limit(MEMBER_LIMIT).toList()) {
            var member = db.symbols.findField(classId, field).orElse(null);
            if (member == null) continue;
            StringBuilder line = new StringBuilder(field).append(": ").append(member.typeName());
            String modifiers = String.valueOf(member.modifiers());
            if (!modifiers.isBlank()) line.append(' ').append(modifiers);
            try {
                for (JsonNode annotation : JSON.readTree(String.valueOf(member.annotations())))
                    line.append(" @").append(annotation.asText());
            } catch (Exception unreadable) {
                QuillLog.swallowed(unreadable);
                // Annotations are a convenience here; the type and users still stand.
            }
            // V41 CQ-035: a config-injected field's key and main value, which agents otherwise grep
            // for.
            db.references
                    .findMemberConfigKey(classId, field)
                    .ifPresent(
                            key ->
                                    line.append(" key ")
                                            .append(key)
                                            .append(
                                                    ConfigMappingKeys.mainDeclaration(
                                                            db,
                                                            new ConfigurationSourceEvidence(db),
                                                            key)));
            var others = users.getOrDefault(field, new java.util.TreeSet<>());
            if (!others.isEmpty())
                line.append("; also used by ")
                        .append(String.join(", ", others.stream().limit(USER_LIMIT).toList()))
                        .append(
                                others.size() > USER_LIMIT
                                        ? " and " + (others.size() - USER_LIMIT) + " more"
                                        : "");
            lines.add(line.toString());
        }
        if (!lines.isEmpty()) fact.set("referenced_members", lines);
    }

    /** Chars of the selected body; the prefetch packet as a whole is bounded separately. */
    private static final int SOURCE_BUDGET = 2500;

    private static final int LAMBDA_SOURCE_BUDGET = 4000;

    private static boolean missingLambdaEvidence(
            ReadQueryContext db, int id, String name, String descriptor) {
        return db.usages.hasLambdaCallWithoutEvidence(id, name, descriptor);
    }

    private static void annotations(
            ReadQueryContext db, ObjectNode fact, int classId, String name, String descriptor)
            throws Exception {
        String details =
                db.symbols.findMemberAnnotationDetails(classId, name, descriptor).orElse("[]");
        ArrayNode lines = JSON.createArrayNode();
        for (JsonNode annotation : JSON.readTree(details)) {
            if (annotation.path("parameterIndex").asInt(-1) >= 0) continue;
            Set<String> defaults = new LinkedHashSet<>();
            annotation
                    .path("values")
                    .path("$defaults")
                    .forEach(value -> defaults.add(value.asText()));
            StringBuilder line =
                    new StringBuilder("@").append(annotation.path("annotationName").asText());
            List<String> values = new java.util.ArrayList<>();
            annotation
                    .path("values")
                    .properties()
                    .forEach(
                            entry -> {
                                if (!entry.getKey().startsWith("$")
                                        && !defaults.contains(entry.getKey()))
                                    values.add(entry.getKey() + "=" + text(entry.getValue()));
                            });
            if (!values.isEmpty()) line.append('(').append(String.join(", ", values)).append(')');
            lines.add(line.toString());
        }
        if (!lines.isEmpty()) fact.set("annotations", lines);
    }

    /**
     * Dependency interceptors bound by an annotation on the method or its class. A binding member
     * the interceptor declares without @Nonbinding must equal the method's explicit value; a value
     * left at its default is not compared.
     */
    private static void interceptors(
            ReadQueryContext db, ObjectNode fact, int classId, String name, String descriptor)
            throws Exception {
        Map<String, JsonNode> carried = new java.util.LinkedHashMap<>();
        String details =
                db.symbols.findMemberAnnotationDetails(classId, name, descriptor).orElse("[]");
        for (JsonNode annotation : JSON.readTree(details))
            if (annotation.path("parameterIndex").asInt(-1) < 0)
                carried.put(annotation.path("annotationName").asText(), annotation.path("values"));
        for (var row : db.symbols.findDirectClassAnnotations(classId))
            carried.putIfAbsent(
                    String.valueOf(row.annotationName()),
                    row.annotationValues() == null
                            ? JSON.createObjectNode()
                            : JSON.readTree(row.annotationValues()));
        if (carried.isEmpty()) return;
        Set<String> registered = NameTargetPrefetch.registeredDependencyBeans(db);
        ArrayNode lines = JSON.createArrayNode();
        for (var row : db.beans.findBoundExternalInterceptors()) {
            for (JsonNode binding : JSON.readTree(String.valueOf(row.interceptorBindings()))) {
                String text = binding.asText();
                int open = text.indexOf('(');
                String annotation = text.substring(1, open < 0 ? text.length() : open);
                JsonNode values = carried.get(annotation);
                if (values == null || !bindingMatches(text, values)) continue;
                if (registered != null && !registered.contains(String.valueOf(row.className())))
                    continue;
                lines.add(
                        row.className()
                                + " @Priority("
                                + (row.priority() == null ? "none" : row.priority())
                                + ") via "
                                + text
                                + " from "
                                + row.artifact());
            }
        }
        if (lines.isEmpty()) return;
        fact.set("dependency_interceptors", lines);
        fact.put(
                "dependency_interceptors_basis",
                "interceptors in dependency jars bound by an annotation on this method "
                        + "or its class; a lower priority runs first; "
                        + (registered == null
                                ? "whether each jar is a bean archive in this application is not verified"
                                : "only interceptors the Quarkus build registered (an X_Bean in generated-bytecode.jar)"));
    }

    private static boolean bindingMatches(String binding, JsonNode values) {
        int open = binding.indexOf('(');
        if (open < 0) return true;
        for (String member : binding.substring(open + 1, binding.length() - 1).split(", ")) {
            if (member.endsWith(" @Nonbinding")) continue;
            int equals = member.indexOf('=');
            if (equals < 0) continue;
            JsonNode value = values.get(member.substring(0, equals));
            if (value != null && !text(value).equals(member.substring(equals + 1))) return false;
        }
        return true;
    }

    /**
     * Callees breadth-first to {@link #DEPTH}; a lambda body is expanded at its declarer's depth.
     */
    private static void callees(
            ReadQueryContext db,
            java.nio.file.Path root,
            ObjectNode fact,
            int classId,
            String name,
            String descriptor) {
        record Method(int classId, String name, String descriptor, int depth) {}
        Set<String> seen = new LinkedHashSet<>();
        Set<String> lines = new LinkedHashSet<>();
        ArrayDeque<Method> queue =
                new ArrayDeque<>(List.of(new Method(classId, name, descriptor, 1)));
        seen.add(classId + "#" + name + descriptor);
        List<String[]> visited = new java.util.ArrayList<>();
        while (!queue.isEmpty() && lines.size() < LIMIT * 2) {
            Method method = queue.removeFirst();
            if (method.classId() == classId)
                visited.add(new String[] {method.name(), method.descriptor()});
            for (var call :
                    db.usages.findCallees(method.classId(), method.name(), method.descriptor())) {
                String callee = String.valueOf(call.toMethod());
                int calleeClass = call.toClassId();
                String calleeDescriptor = String.valueOf(call.toDescriptor());
                boolean lambda = callee.startsWith("lambda$");
                if (!lambda)
                    lines.add(
                            method.depth()
                                    + " "
                                    + call.className()
                                    + "#"
                                    + callee
                                    + " ["
                                    + call.invocationKind()
                                    + (declares(db, calleeClass, callee, calleeDescriptor)
                                            ? ""
                                            : ", inherited: "
                                                    + inheritedFrom(db, root, calleeClass, callee))
                                    + "]");
                int next = lambda ? method.depth() : method.depth() + 1;
                if (next <= DEPTH && seen.add(calleeClass + "#" + callee + calleeDescriptor))
                    queue.addLast(new Method(calleeClass, callee, calleeDescriptor, next));
            }
            String external =
                    db.usages
                            .findCallerExternalCalls(
                                    method.classId(), method.name(), method.descriptor())
                            .orElse("[]");
            try {
                for (JsonNode call : JSON.readTree(external)) {
                    // line|ordinal|owner|method|descriptor|...
                    String[] parts = call.asText().split("\\|", -1);
                    if (parts.length > 3)
                        lines.add(
                                method.depth()
                                        + " "
                                        + parts[2]
                                        + "#"
                                        + parts[3]
                                        + (parts.length > 4 ? javaSignature(parts[4]) : "")
                                        + " [external]");
                }
            } catch (Exception unreadable) {
                QuillLog.swallowed(unreadable);
                // Evidence rows are written by the indexer; an unreadable row only drops its
                // external calls.
            }
        }
        if (lines.isEmpty()) return;
        Set<String> dispatch = new LinkedHashSet<>();
        for (String line : lines) {
            if (line.contains(".event.Event#fireAsync")) dispatch.add("fireAsync");
            else if (line.contains(".event.Event#fire")) dispatch.add("fire");
        }
        if (!dispatch.isEmpty()) firedEventObservers(db, fact, classId, visited, dispatch);
        ArrayNode values = fact.putArray("calls");
        // A lambda body is walked after its declarer's deeper callees; depth order keeps nearer
        // calls first when cut.
        lines.stream()
                .sorted(
                        java.util.Comparator.comparingInt(
                                line -> Integer.parseInt(line.substring(0, line.indexOf(' ')))))
                .limit(LIMIT)
                .forEach(values::add);
        fact.put("calls_basis", "depth, callee, invocation; depth 2 = called by a depth-1 callee");
        if (lines.size() > LIMIT)
            fact.putObject("calls_truncated").put("shown", LIMIT).put("total", lines.size());
    }

    /**
     * The nearest superclass declaring {@code name}, searched through application and dependency
     * bytecode.
     */
    private static String inheritedFrom(
            ReadQueryContext db, java.nio.file.Path root, int classId, String name) {
        String type = db.symbols.findSuperclass(classId).orElse(null);
        ExternalSymbolQueries external = new ExternalSymbolQueries();
        for (int depth = 0;
                type != null && !type.equals("java.lang.Object") && depth < 8;
                depth++) {
            String current = type;
            Integer local = db.symbols.findCurrentClassId(current).orElse(null);
            if (local != null) {
                boolean found = db.symbols.hasMemberNamed(local, name);
                if (found) return "declared by " + current;
                type = db.symbols.findSuperclass(local).orElse(null);
                continue;
            }
            if (root == null) break;
            try {
                JsonNode details = external.details(db, root, current, 1000, 0);
                if (details.has("error")) break;
                for (JsonNode member : details.path("members"))
                    if (member.path("signature").asText().contains(" " + name + "("))
                        return "declared by "
                                + current
                                + " ("
                                + details.path("artifact").asText()
                                + ")";
                type = details.path("superclass").asText(null);
            } catch (Exception unreadable) {
                QuillLog.swallowed(unreadable);
                break;
            }
        }
        return "not declared in this class";
    }

    private static boolean declares(
            ReadQueryContext db, int classId, String name, String descriptor) {
        // Constructors are indexed under the class's simple name and are never inherited.
        if (name.equals("<init>")) return true;
        return db.symbols.declaresMember(classId, name, descriptor);
    }

    /**
     * The method fires an event: observers of each Event<T> field the walked methods of its class
     * read, or of every Event<T> its class injects when no such read is indexed.
     */
    private static void firedEventObservers(
            ReadQueryContext db,
            ObjectNode fact,
            int classId,
            List<String[]> visited,
            Set<String> dispatch) {
        Set<String> read = new java.util.HashSet<>();
        for (String[] method : visited)
            read.addAll(db.usages.findOwnInstanceFieldReads(classId, method[0], method[1]));
        List<BeanRepository.ClassInjectionPoint> points =
                db.beans.findClassInjectionPoints(classId).stream()
                        .filter(
                                point ->
                                        ClassPrefetchRelations.argument(
                                                        String.valueOf(point.targetType()),
                                                        List.of(
                                                                "jakarta.enterprise.event.Event",
                                                                "javax.enterprise.event.Event"))
                                                != null)
                        .toList();
        List<BeanRepository.ClassInjectionPoint> fired =
                points.stream()
                        .filter(point -> read.contains(String.valueOf(point.fieldName())))
                        .toList();
        boolean narrowed = !fired.isEmpty();
        ArrayNode observers = JSON.createArrayNode();
        List<BeanRepository.ClassInjectionPoint> selected = narrowed ? fired : points;
        ArrayNode events = JSON.createArrayNode();
        for (var point : selected) {
            String type = String.valueOf(point.targetType());
            ClassPrefetchRelations.eventObservers(db, type)
                    .forEach(
                            observer ->
                                    observers.add(
                                            point.fieldName()
                                                    + " "
                                                    + ClassPrefetchRelations.source(type)
                                                    + " -> "
                                                    + observer));
            if (events.size() >= 3) continue;
            String event =
                    ClassPrefetchRelations.argument(
                            type,
                            List.of(
                                    "jakarta.enterprise.event.Event",
                                    "javax.enterprise.event.Event"));
            ObjectNode value =
                    events.addObject()
                            .put("field", String.valueOf(point.fieldName()))
                            .put("event_type", event);
            value.set("dispatch_methods", JSON.valueToTree(dispatch));
            value.put("field_to_fire_binding_proven", false);
            try {
                ArrayNode qualifiers =
                        (ArrayNode) JSON.readTree(String.valueOf(point.qualifiers()));
                if (qualifiers.isEmpty()) qualifiers.add("@Default");
                if (qualifiers
                        .valueStream()
                        .noneMatch(
                                q ->
                                        q.asText().equals("@Any")
                                                || q.asText()
                                                        .equals("@jakarta.enterprise.inject.Any")
                                                || q.asText()
                                                        .equals("@javax.enterprise.inject.Any")))
                    qualifiers.add("@Any");
                value.set("event_qualifiers", qualifiers);
            } catch (Exception unreadable) {
                value.put("qualifier_evidence_status", "unreadable");
            }
            try {
                JsonNode candidates =
                        new EntryPointQueries()
                                .findEntryPoints(db, "observer", null, false, true, 10, 0, event);
                for (String key :
                        List.of(
                                "entry_points",
                                "observer_selection",
                                "showing",
                                "total",
                                "has_more",
                                "truncated",
                                "next_page_request"))
                    if (candidates.has(key)) value.set(key, candidates.get(key));
            } catch (Exception unreadable) {
                value.put("observer_evidence_status", "unreadable");
            }
        }
        if (events.isEmpty()) return;
        fact.set("event_dispatches", events);
        if (selected.size() > events.size())
            fact.putObject("event_dispatches_truncated")
                    .put("shown", events.size())
                    .put("total", selected.size());
        fact.put("event_delivery_proven", false);
        fact.put(
                "answer_complete_scope",
                "Direct indexed method consumers/contracts only; event candidates do not prove qualifier eligibility, bean activation or delivery");
        fact.put(
                "event_delivery_semantics",
                "CDI contract: fire notifies synchronous @Observes; fireAsync notifies @ObservesAsync. Without an active transaction, transactional observers run immediately. With an active transaction, AFTER_SUCCESS runs only after successful completion, not rollback; if synchronization cannot be registered it is skipped. Other transaction phases retain their declared semantics. Runtime transaction state and observer activation are not observed.");
        if (!observers.isEmpty()) fact.set("observers_of_fired_events", observers);
        fact.put(
                "observers_basis",
                narrowed
                        ? "Event fields this method or its walked callees read"
                        : "every Event field of the class; which one is fired is not indexed");
    }

    /**
     * What creating the declaring bean instance takes. V62 CQ-018: every session called
     * list_injection_points on Producers only to learn that its class bean injects nothing.
     */
    private static ArrayNode declaringClassInjects(
            ReadQueryContext db, int classId, String name, String descriptor) {
        ArrayNode lines = JSON.createArrayNode();
        List<Boolean> statics =
                db.symbols.findMethodModifiers(classId, name, descriptor).stream()
                        .map(modifiers -> (" " + modifiers + " ").contains(" static "))
                        .toList();
        // Without a descriptor every overload matches: only when all are static is the instance
        // never needed.
        if (!statics.isEmpty() && !statics.contains(false))
            return lines.add(
                    "not needed: static producer; the declaring bean is not instantiated to call it");
        if (statics.contains(true))
            lines.add(
                    "overloads differ: some are static and need no declaring bean instance; the others need what follows");
        int shown = 8;
        // Apollo AQ-005: other producer methods' parameters are theirs, not the declaring bean's;
        // this
        // method's own are in producer_parameters.
        Set<String> producers =
                new java.util.HashSet<>(db.beans.findDeclaredBeanMemberNames(classId));
        var points =
                db.beans.findClassBeanInjectionPoints(classId, 200).stream()
                        .filter(
                                row ->
                                        !("METHOD_PARAM".equals(row.kind())
                                                && producers.contains(row.fieldName())))
                        .limit(shown + 1)
                        .toList();
        points.stream()
                .limit(shown)
                .forEach(
                        row ->
                                lines.add(
                                        row.kind()
                                                + " "
                                                + row.fieldName()
                                                + ": "
                                                + row.targetType()
                                                + " "
                                                + BeanTerms.qualifiersJson(
                                                        row.ownerFramework(), row.qualifiers())
                                                + " -> "
                                                + (row.resolvedClass() == null
                                                        ? "not resolved by the application index"
                                                        : row.resolvedClass()
                                                                + (row.resolvedMember() == null
                                                                        ? ""
                                                                        : "#"
                                                                                + row
                                                                                        .resolvedMember())
                                                                + (row.resolvedScope() == null
                                                                        ? ""
                                                                        : " "
                                                                                + BeanTerms.scope(
                                                                                        row
                                                                                                .resolvedFramework(),
                                                                                        row
                                                                                                .resolvedScope())))
                                                + " ["
                                                + row.resolutionStatus()
                                                + "]"));
        if (points.size() > shown)
            lines.add(
                    "truncated: more than "
                            + shown
                            + " injection points; list_injection_points shows all of them");
        if (points.isEmpty()) lines.add("none: the declaring bean has no injection points");
        return lines;
    }

    private static void producer(
            ReadQueryContext db,
            java.nio.file.Path root,
            ObjectNode fact,
            int classId,
            String name) {
        var beans = db.beans.findProducerMethodBeans(classId, name);
        if (beans.isEmpty()) return;
        ArrayNode produces = fact.putArray("produces");
        ArrayNode parameters = JSON.createArrayNode();
        ArrayNode injectedInto = JSON.createArrayNode();
        for (var bean : beans) {
            int id = bean.id();
            // Apollo v1: CDI words on a Spring bean (@Default, @Singleton) were repeated as fact.
            produces.add(
                    BeanTerms.kind(bean.framework(), bean.kind())
                            + " "
                            + BeanTerms.scope(bean.framework(), bean.scope())
                            + " qualifiers "
                            + BeanTerms.qualifiersJson(bean.framework(), bean.qualifiers())
                            + " types "
                            + bean.beanTypes());
            var ownParameters = db.beans.findProducerParameters(id);
            // Apollo AQ-018: Spring records a @Bean method's parameters on the declaring class
            // bean.
            if (ownParameters.isEmpty())
                ownParameters = db.beans.findMethodParameters(classId, name);
            ownParameters.forEach(
                    row ->
                            parameters.add(
                                    row.fieldName()
                                            + ": "
                                            + row.targetType()
                                            + " "
                                            + BeanTerms.qualifiersJson(
                                                    bean.framework(), row.qualifiers())
                                            + " -> "
                                            + (row.resolved() == null
                                                    ? "not resolved by the application index"
                                                            + dependencyProviders(
                                                                    db,
                                                                    String.valueOf(
                                                                            row.targetType()),
                                                                    row.qualifiers())
                                                            + springDataRepository(
                                                                    db,
                                                                    String.valueOf(
                                                                            row.targetType()))
                                                    : row.resolved())
                                            + " ["
                                            + row.resolutionStatus()
                                            + "]"));
            db.beans
                    .findBeanConsumers(id)
                    .forEach(
                            row ->
                                    injectedInto.add(
                                            row.owner()
                                                    + "#"
                                                    + row.fieldName()
                                                    + " "
                                                    + BeanTerms.qualifiersJson(
                                                            bean.framework(), row.qualifiers())
                                                    + " ["
                                                    + row.resolutionStatus()
                                                    + "] "
                                                    + row.sourceFile()));
        }
        // The declaring class is a bean of its own, whose scope is not the produced bean's.
        db.beans
                .findClassBeanScope(classId)
                .ifPresent(
                        scope -> {
                            String framework = beans.getFirst().framework();
                            fact.put(
                                    "declaring_class_bean",
                                    "CLASS " + BeanTerms.scope(framework, scope));
                            fact.set(
                                    "declaring_class_injects",
                                    declaringClassInjects(
                                            db, classId, name, fact.path("descriptor").asText("")));
                        });
        if (!parameters.isEmpty()) fact.set("producer_parameters", parameters);
        // Apollo AQ-018: an ambiguous parameter said only "not resolved [AMBIGUOUS]".
        db.symbols
                .findClassById(classId)
                .ifPresent(
                        owner -> ClassPrefetchRelations.ambiguousCandidates(db, fact, owner, name));
        if (!injectedInto.isEmpty()) fact.set("produced_bean_injected_into", injectedInto);
        sameTypeProviders(db, fact, classId, beans);
        frameworkLookups(db, root, fact, beans);
    }

    /**
     * Framework code fetching a bean of a produced dependency type by type; no injection point
     * names it.
     */
    private static void frameworkLookups(
            ReadQueryContext db,
            java.nio.file.Path root,
            ObjectNode fact,
            List<BeanRepository.ProducerBean> beans) {
        if (root == null) return;
        Set<String> types = new java.util.LinkedHashSet<>();
        for (var bean : beans) {
            try {
                for (var type : JSON.readTree(String.valueOf(bean.beanTypes())))
                    types.add(type.asText());
            } catch (Exception unreadable) {
                // Bean types are written by the indexer; an unreadable row only drops its lookups.
            }
        }
        List<String> lines = new ArrayList<>();
        Set<String> artifacts = new java.util.LinkedHashSet<>();
        for (String type : types) {
            if (type.startsWith("java.")
                    || db.symbols.findAllClasses().stream()
                            .anyMatch(c -> c.className().equals(type))) continue;
            for (String line : ExternalSymbolQueries.typeLookups(db, root, type)) {
                if (lines.size() >= 4) break;
                // Short form: simple class name, the artifact once in the basis.
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile(
                                        "^([\\w.$]+)#(\\S+) looks it up by type via (\\w+) \\((.+)\\)$")
                                .matcher(line);
                if (!m.matches()) continue;
                String owner = m.group(1).substring(m.group(1).lastIndexOf('.') + 1);
                lines.add(
                        type.substring(type.lastIndexOf('.') + 1)
                                + ": "
                                + owner
                                + "#"
                                + m.group(2)
                                + " via "
                                + m.group(3));
                artifacts.add(m.group(4));
            }
        }
        if (lines.isEmpty()) return;
        fact.set("framework_lookups", JSON.valueToTree(lines));
        fact.put(
                "framework_lookups_basis",
                "dependency methods ("
                        + String.join(", ", artifacts)
                        + ") passing the type's class literal to a bean lookup: framework consumers no injection "
                        + "point lists; whether they run depends on configuration");
    }

    /**
     * The @Bean methods constructing a class that is no bean itself. Apollo AQ-023: the fact on
     * SpringSecurityUserInfoHolder#getUser() named only its Supplier field, and Claude read
     * AuthConfiguration whole to find the ObjectProvider the holder is built from.
     */
    static void createdByBeanMethods(ReadQueryContext db, ObjectNode fact, int classId) {
        if (db.beans.findBeanByClassId(classId).isPresent()) return;
        var cls = db.symbols.findClassById(classId).orElse(null);
        if (cls == null) return;
        ObjectNode created = JSON.createObjectNode();
        org.treblereel.mcp.mcp.query.StructureToolQueries.appendCreatingBeanMethods(
                db, created, cls);
        if (!created.has("created_by_bean_methods")) return;
        fact.set("created_by_bean_methods", created.get("created_by_bean_methods"));
        // The first creator's ambiguous parameters (an ObjectProvider looks up among these), marked
        // under the prompt's profiles later.
        var first = db.beans.findBeanMethodsConstructing(classId, 1);
        if (!first.isEmpty() && !fact.has("ambiguous_candidates"))
            db.symbols
                    .findClassById(first.getFirst().classId())
                    .ifPresent(
                            config ->
                                    ClassPrefetchRelations.ambiguousCandidates(
                                            db, fact, config, first.getFirst().memberName()));
    }

    /**
     * Tests of the implementations a method delegates to: calls of the same method name on another
     * type, then that type's application implementations (one level of subclassing) and the test
     * classes referencing them. Apollo v1-v3 AQ-046: asked which tests to run after changing
     * UnifiedPermissionValidator#hasReleaseNamespacePermission, Claude never named the delegate
     * validators' tests.
     */
    private static void delegateTests(
            ReadQueryContext db, ObjectNode fact, int classId, String name, String descriptor) {
        var targets = db.usages.findSameNamedCalleeClasses(classId, name, descriptor);
        if (targets.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        for (String target : targets) {
            var implementations = db.symbols.findProductionImplementations(target);
            for (var implementation : implementations) {
                if (lines.size() >= 6) break;
                String impl = implementation.className();
                String own = impl.substring(impl.lastIndexOf('.') + 1) + "Test";
                // One test class per implementation, its own <Impl>Test first.
                List<String> tests =
                        db.usages.findTestClassesDependingOn(implementation.id()).stream()
                                .filter(t -> t.matches(".*(Test|Tests|IT)$"))
                                .sorted(
                                        java.util.Comparator.comparing(
                                                (String t) -> !t.endsWith("." + own)))
                                .limit(1)
                                .toList();
                for (String test : tests) {
                    String line =
                            test + " (tests " + impl.substring(impl.lastIndexOf('.') + 1) + ")";
                    if (lines.size() < 6 && !lines.contains(line)) lines.add(line);
                }
            }
        }
        if (lines.isEmpty()) return;
        fact.set("delegate_tests", JSON.valueToTree(lines));
        fact.put(
                "delegate_tests_basis",
                "heuristic: tests referencing the implementations of "
                        + String.join(
                                ", ",
                                targets.stream()
                                        .map(t -> t.substring(t.lastIndexOf('.') + 1) + "#" + name)
                                        .toList())
                        + ", which this method calls; they cover the delegates, not this method");
    }

    /** Apollo AQ-018: a repository parameter left "not resolved" with no word on its bean. */
    private static String springDataRepository(ReadQueryContext db, String type) {
        String text = org.treblereel.mcp.mcp.query.SpringDataRepositories.describe(db, type);
        return text == null ? "" : "; " + text;
    }

    private static final int MAX_PROVIDERS = 6;

    /**
     * The other beans of the produced type, each with its registration conditions, and those of the
     * declaring class. Apollo v3 AQ-005: asked whether a {@code @ConditionalOnMissingBean} producer
     * registers under given profiles, Claude read every configuration class for its
     * {@code @Profile}.
     */
    private static void sameTypeProviders(
            ReadQueryContext db,
            ObjectNode fact,
            int classId,
            List<BeanRepository.ProducerBean> beans) {
        List<String> own = classConditions(db, classId);
        if (!own.isEmpty()) fact.set("declaring_class_conditions", JSON.valueToTree(own));
        Set<Integer> self = new java.util.HashSet<>();
        Set<String> types = new java.util.LinkedHashSet<>();
        for (var bean : beans) {
            self.add(bean.id());
            try {
                for (var type : JSON.readTree(String.valueOf(bean.beanTypes())))
                    if (!type.asText().equals("java.lang.Object")) types.add(type.asText());
            } catch (Exception unreadable) {
                // Bean types are written by the indexer; an unreadable row only drops its
                // providers.
            }
        }
        if (types.isEmpty()) return;
        List<String> main = new ArrayList<>();
        List<String> test = new ArrayList<>();
        Set<Integer> seen = new java.util.HashSet<>(self);
        for (String type : types) {
            for (var provider : db.beans.findTypeProviders(type)) {
                if (!seen.add(provider.beanId())) continue;
                String owner =
                        provider.className()
                                + (provider.memberName() == null
                                        ? ""
                                        : "#" + provider.memberName());
                if ("test".equals(provider.sourceSet())) {
                    test.add(owner);
                    continue;
                }
                StringBuilder line = new StringBuilder(owner);
                String profiles = provider.profiles();
                if (profiles != null && !profiles.equals("[]") && !profiles.isBlank())
                    line.append(" profiles ").append(profiles.replace("\"", "").replace(",", ", "));
                List<String> conditions =
                        classConditions(db, provider.classId()).stream()
                                .filter(c -> !c.startsWith("@Profile("))
                                .toList();
                if (!conditions.isEmpty())
                    line.append(" class ").append(String.join(", ", conditions));
                if (provider.memberName() != null) {
                    List<String> method =
                            memberConditions(db, provider.classId(), provider.memberName());
                    if (!method.isEmpty())
                        line.append(" method ").append(String.join(", ", method));
                }
                if (profiles == null
                        && conditions.isEmpty()
                        && !line.toString().contains(" method ")) line.append(" unconditional");
                main.add(line.toString());
            }
        }
        if (main.isEmpty() && test.isEmpty()) return;
        ArrayNode lines = fact.putArray("same_type_providers");
        main.stream().limit(MAX_PROVIDERS).forEach(lines::add);
        if (main.size() > MAX_PROVIDERS)
            lines.add("truncated: " + (main.size() - MAX_PROVIDERS) + " more");
        if (!test.isEmpty())
            lines.add(
                    "test sources only: "
                            + String.join(", ", test.subList(0, Math.min(3, test.size())))
                            + (test.size() > 3 ? " (+" + (test.size() - 3) + " more)" : ""));
        fact.put(
                "same_type_providers_basis",
                "other indexed beans of the produced type with their profiles and conditions; "
                        + "a provider whose @Profile or condition does not match the active profiles is not registered, and "
                        + "@ConditionalOnMissingBean skips this method only if a matching bean is registered before it");
    }

    /**
     * A bean's registration conditions other than @Profile (stated apart): its class's and, for a
     * bean method, the method's. list_injection_points states them on candidates.
     */
    public static List<String> registrationConditions(
            ReadQueryContext db, int classId, String member) {
        List<String> result = new ArrayList<>();
        for (String condition : classConditions(db, classId))
            if (!condition.startsWith("@Profile(")) result.add(condition);
        if (member != null)
            for (String condition : memberConditions(db, classId, member))
                if (!condition.startsWith("@Profile(") && !result.contains(condition))
                    result.add(condition);
        return result;
    }

    private static List<String> classConditions(ReadQueryContext db, int classId) {
        return db.symbols.findDirectClassAnnotationsByName(classId).stream()
                .filter(row -> isCondition(row.annotationName()))
                .map(
                        row ->
                                "@"
                                        + simple(row.annotationName())
                                        + "("
                                        + ("ConditionalOnProperty"
                                                        .equals(simple(row.annotationName()))
                                                ? propertyCondition(row.annotationValues())
                                                : values(row.annotationValues()))
                                        + ")")
                .toList();
    }

    /**
     * Spring Boot's rule for @ConditionalOnProperty with the property names joined to the prefix:
     * without havingValue it matches a present value other than {@code false}. Apollo AQ-035: the
     * raw attributes left the name to assemble, and answers named it in one run of three.
     */
    static String propertyCondition(Object raw) {
        try {
            var node = JSON.readTree(String.valueOf(raw));
            String prefix = node.path("prefix").asText("");
            List<String> names = new ArrayList<>();
            for (String attribute : List.of("name", "value"))
                for (var name : node.path(attribute))
                    names.add(prefix.isBlank() ? name.asText() : prefix + "." + name.asText());
            if (names.isEmpty()) return values(raw);
            String having = node.path("havingValue").asText("");
            return String.join(", ", names)
                    + (having.isEmpty() ? " present and not false" : " = " + having)
                    + (node.path("matchIfMissing").asBoolean(false) ? ", or missing" : "");
        } catch (Exception unreadable) {
            return values(raw);
        }
    }

    private static List<String> memberConditions(ReadQueryContext db, int classId, String member) {
        List<String> found = new ArrayList<>();
        for (String json : db.symbols.findMethodAnnotationNames(classId, member)) {
            try {
                for (var name : JSON.readTree(json)) {
                    String condition = "@" + simple(name.asText());
                    if (isCondition(name.asText()) && !found.contains(condition))
                        found.add(condition);
                }
            } catch (Exception unreadable) {
                // Annotations are written by the indexer; an unreadable row only drops its
                // conditions.
            }
        }
        return found;
    }

    private static boolean isCondition(String annotation) {
        String simple = simple(annotation);
        return simple.equals("Profile")
                || simple.startsWith("Conditional")
                || simple.startsWith("IfBuild")
                || simple.startsWith("UnlessBuild")
                || simple.startsWith("LookupIf")
                || simple.startsWith("LookupUnless");
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String values(Object raw) {
        if (raw == null) return "";
        try {
            var node = JSON.readTree(String.valueOf(raw));
            if (node.size() == 1 && node.has("value")) {
                var value = node.get("value");
                if (value.isArray()) {
                    List<String> items = new ArrayList<>();
                    value.forEach(item -> items.add(item.asText()));
                    return items.toString();
                }
                return value.asText();
            }
            return node.toString().replace("\"", "");
        } catch (Exception unreadable) {
            return String.valueOf(raw);
        }
    }

    /**
     * Dependency beans providing {@code type} whose qualifiers include every required one, for an
     * injection point the application index cannot resolve.
     */
    static String dependencyProviders(ReadQueryContext db, String type, Object requiredQualifiers) {
        Set<String> required = new java.util.HashSet<>();
        try {
            if (requiredQualifiers != null)
                JSON.readTree(String.valueOf(requiredQualifiers))
                        .forEach(q -> required.add(q.asText()));
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
            // Unreadable qualifiers leave the providers unfiltered.
        }
        List<String> providers =
                db.beans.findExternalProviders(type).stream()
                        .filter(
                                row -> {
                                    try {
                                        Set<String> offered = new java.util.HashSet<>();
                                        JSON.readTree(String.valueOf(row.qualifiers()))
                                                .forEach(q -> offered.add(q.asText()));
                                        return offered.containsAll(required);
                                    } catch (Exception unreadable) {
                                        QuillLog.swallowed(unreadable);
                                        return true;
                                    }
                                })
                        .limit(5)
                        .map(
                                row ->
                                        row.className()
                                                + (row.memberName() == null
                                                        ? ""
                                                        : "#" + row.memberName())
                                                + " from "
                                                + (row.artifact() == null
                                                        ? "unknown"
                                                        : row.artifact()))
                        .toList();
        return providers.isEmpty() ? "" : "; dependency providers: " + String.join(", ", providers);
    }

    /**
     * (Ljava/nio/file/Path;[I)Z reads (java.nio.file.Path, int[]):boolean; malformed input reads as
     * empty.
     */
    static String javaSignature(String descriptor) {
        int close = descriptor.indexOf(')');
        if (!descriptor.startsWith("(") || close < 0) return "";
        List<String> parameters = new java.util.ArrayList<>();
        int[] at = {1};
        while (at[0] < close) {
            String type = javaType(descriptor, at);
            if (type == null) return "";
            parameters.add(type);
        }
        at[0] = close + 1;
        String result = javaType(descriptor, at);
        return result == null ? "" : "(" + String.join(", ", parameters) + "):" + result;
    }

    private static String javaType(String descriptor, int[] at) {
        if (at[0] >= descriptor.length()) return null;
        char kind = descriptor.charAt(at[0]++);
        switch (kind) {
            case '[':
                {
                    String element = javaType(descriptor, at);
                    return element == null ? null : element + "[]";
                }
            case 'L':
                {
                    int end = descriptor.indexOf(';', at[0]);
                    if (end < 0) return null;
                    String name = descriptor.substring(at[0], end).replace('/', '.');
                    at[0] = end + 1;
                    return ClassPrefetchRelations.source(name);
                }
            case 'Z':
                return "boolean";
            case 'B':
                return "byte";
            case 'C':
                return "char";
            case 'S':
                return "short";
            case 'I':
                return "int";
            case 'J':
                return "long";
            case 'F':
                return "float";
            case 'D':
                return "double";
            case 'V':
                return "void";
            default:
                return null;
        }
    }

    private static String text(JsonNode value) {
        return value.isTextual() ? value.asText() : value.toString();
    }
}
