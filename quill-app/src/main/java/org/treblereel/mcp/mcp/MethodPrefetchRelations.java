package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * What a method fact needs beyond its callers for a first-turn answer, one line per relation:
 * the method's annotations with their set values, what it calls two levels down (lambda bodies
 * count as the method that declares them), and for a producer method the bean it produces, its
 * parameters and the injection points it resolves. Static bytecode evidence only.
 */
final class MethodPrefetchRelations {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMIT = 40;
    private static final int DEPTH = 2;

    private MethodPrefetchRelations() {
    }

    /**
     * Callers become one line per class (class, file, calling methods) and coverage keeps its
     * verdict, gaps and compact caller line references.
     */
    static void compactImpact(ObjectNode fact) {
        for (String field : List.of("production_callers", "test_callers", "unclassified_callers",
                "indirect_production")) {
            if (!(fact.get(field) instanceof ArrayNode callers)) continue;
            ArrayNode lines = JSON.createArrayNode();
            for (JsonNode caller : callers) {
                Set<String> methods = new LinkedHashSet<>();
                caller.path("methods").forEach(method -> methods.add(method.path("method").asText()
                        + (method.has("depth") ? "@" + method.path("depth").asInt() : "")
                        + (method.hasNonNull("evidence_lines") ? " lines "
                            + (method.get("evidence_lines").isTextual() ? method.get("evidence_lines").asText()
                                : method.get("evidence_lines").toString()) : "")
                        // The line is already named; the statement and what follows it are the evidence.
                        + (method.hasNonNull("result_use") ? " uses result: "
                            + method.get("result_use").asText().replaceFirst("^line \\d+: ", "") : "")));
                lines.add(caller.path("class").asText() + " " + caller.path("source").asText()
                        + (methods.isEmpty() ? "" : " via " + String.join(", ", methods)));
            }
            fact.set(field, lines);
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

    /** With {@code root}, a callee inherited from a dependency class names the class that declares it. */
    static void append(ReadQueryContext db, java.nio.file.Path root, ObjectNode fact) throws Exception {
        String owner = fact.path("class").asText(null);
        String name = fact.path("name").asText(null);
        String descriptor = fact.path("descriptor").asText(null);
        if (owner == null || name == null || descriptor == null) return;
        Integer classId = db.jdbi().withHandle(handle -> handle.createQuery(
                "SELECT id FROM classes WHERE class_name = :name AND lifecycle = 'current' LIMIT 1")
                .bind("name", owner).mapTo(Integer.class).findOne().orElse(null));
        if (classId == null) return;
        annotations(db, fact, classId, name, descriptor);
        FrameworkContracts.append(db, fact, classId, name, descriptor);
        referencedMembers(db, fact, classId, name, descriptor);
        interceptors(db, fact, classId, name, descriptor);
        callees(db, root, fact, classId, name, descriptor);
        var hops = ThreadHops.of(db, root, classId, name, descriptor);
        if (hops != null) fact.set("thread_hops", hops);
        sameEventObservers(db, fact, classId, name, descriptor);
        producer(db, fact, classId, name);
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
            fact.put("selected_method_source_status", source.path("status").asText("source_unavailable"));
        } else if (source.path("source").asText().length() > budget) {
            fact.put("selected_method_source_status", "source_budget");
        } else {
            fact.putObject("selected_method_source")
                    .put("source_file", source.path("source_file").asText())
                    .put("source_line", source.path("declaration").path("source_line").asInt())
                    .put("source_end_line", source.path("declaration").path("source_end_line").asInt())
                    .put("source", source.path("source").asText());
        }
    }

    private static final int MENTION_LIMIT = 20;

    /**
     * Lines of the declaring file where the method name appears, split into code and the comments
     * and strings a bytecode index cannot see. In V24-V27 CQ-049 Claude grepped for parse 8 times
     * after Quill had listed every call, looking for javadoc and string mentions before renaming.
     */
    static void nameInSource(java.nio.file.Path root, String sourceFile, String name, ObjectNode fact) {
        if (sourceFile == null) return;
        List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(root.resolve(sourceFile));
        } catch (java.io.IOException | RuntimeException unreadable) {
            return;
        }
        java.util.regex.Pattern word = java.util.regex.Pattern.compile("(?<![\\w$])" + java.util.regex.Pattern.quote(name) + "(?![\\w$])");
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
                    if (c == '*' && next == '/') { kinds[++i] = 'm'; block = false; }
                } else if (string || character) {
                    kinds[i] = 's';
                    if (c == '\\' && i + 1 < line.length()) kinds[++i] = 's';
                    else if (string && c == '"') string = false;
                    else if (character && c == '\'') character = false;
                } else if (c == '/' && next == '/') {
                    for (int j = i; j < line.length(); j++) kinds[j] = 'm';
                    break;
                } else if (c == '/' && next == '*') {
                    kinds[i] = 'm'; kinds[++i] = 'm'; block = true;
                } else {
                    kinds[i] = 'c';
                    if (c == '"') { string = true; kinds[i] = 's'; }
                    else if (c == '\'') { character = true; kinds[i] = 's'; }
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
        mentions.put("file", file.startsWith(root) ? root.relativize(file).toString().replace('\\', '/') : sourceFile);
        mentions.set("code_lines", code);
        ArrayNode rest = mentions.putArray("other");
        other.stream().limit(MENTION_LIMIT).forEach(rest::add);
        if (other.size() > MENTION_LIMIT) mentions.put("other_truncated", true);
        mentions.put("basis", "word matches in the declaring file; comments and strings are not in the bytecode index");
    }

    private static final int OBSERVER_LIMIT = 12;
    private static final int DEFAULT_OBSERVER_PRIORITY = 2500;

    /**
     * For an observer method, the application's other observers of the same event type in
     * @Priority order: what the start or stop order between them is, and where it is unspecified.
     */
    private static void sameEventObservers(ReadQueryContext db, ObjectNode fact, int classId, String name, String descriptor) {
        String details = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT annotation_details FROM class_members WHERE class_id = :id AND name = :name AND descriptor = :descriptor""")
                .bind("id", classId).bind("name", name).bind("descriptor", descriptor).mapTo(String.class).findOne().orElse("[]"));
        String event = null;
        try {
            for (JsonNode annotation : JSON.readTree(details))
                if (List.of("jakarta.enterprise.event.Observes", "javax.enterprise.event.Observes").contains(annotation.path("annotationName").asText()))
                    event = annotation.path("parameterType").asText(null);
        } catch (Exception unreadable) {
            return;
        }
        if (event == null || event.isBlank()) return;
        String type = event;
        record Observer(String name, int priority, boolean explicit) {}
        List<Observer> observers = new java.util.ArrayList<>();
        for (var row : db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.id AS class_id, c.class_name, m.name, m.descriptor, m.annotation_details FROM class_members m
                JOIN classes c ON c.id = m.class_id
                WHERE c.lifecycle = 'current' AND m.annotation_details LIKE '%.event.Observes"%' AND m.annotation_details LIKE :type""")
                .bind("type", "%\"parameterType\":\"" + type + "\"%").mapToMap().list())) {
            if (((Number) row.get("class_id")).intValue() == classId && name.equals(row.get("name")) && descriptor.equals(row.get("descriptor"))) continue;
            Integer priority = null;
            try {
                for (JsonNode annotation : JSON.readTree(String.valueOf(row.get("annotation_details"))))
                    if (annotation.path("annotationName").asText().endsWith(".annotation.Priority")
                            && annotation.path("targetKind").asText().equals("METHOD_PARAMETER"))
                        priority = annotation.path("values").path("value").asInt(DEFAULT_OBSERVER_PRIORITY);
            } catch (Exception unreadable) {
                // An unreadable row keeps the default priority.
            }
            observers.add(new Observer(row.get("class_name") + "#" + row.get("name"),
                    priority == null ? DEFAULT_OBSERVER_PRIORITY : priority, priority != null));
        }
        if (observers.isEmpty()) return;
        Set<String> related = relatedClasses(db, fact, classId);
        observers.sort(java.util.Comparator.comparingInt(Observer::priority).thenComparing(Observer::name));
        // Listed: those with a stated @Priority and those of classes this method uses.
        List<Observer> named = observers.stream().filter(o -> o.explicit() || related.contains(o.name().substring(0, o.name().indexOf('#'))))
                .limit(OBSERVER_LIMIT).toList();
        boolean selfDefault = !db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT annotation_details FROM class_members WHERE class_id = :id AND name = :name AND descriptor = :descriptor""")
                .bind("id", classId).bind("name", name).bind("descriptor", descriptor).mapTo(String.class).findOne().orElse(""))
                .contains(".annotation.Priority\"");
        long unordered = observers.stream().filter(o -> !o.explicit()).count();
        // One field, so the context budget can drop it whole before it cuts the method's own evidence.
        ObjectNode summary = fact.putObject("same_event_observers");
        summary.put("event", type).put("others", observers.size());
        if (!named.isEmpty()) {
            ArrayNode lines = summary.putArray("listed");
            named.forEach(o -> lines.add(o.name() + (o.explicit() ? " @Priority(" + o.priority() + ")" : "")));
        }
        if (selfDefault && unordered > 0)
            summary.put("order", unordered + " share default @Priority " + DEFAULT_OBSERVER_PRIORITY + " with this one: order unspecified");
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
     * Fields of the method's own class that its body or its lambdas use, with their declared type and
     * the class's other methods that touch them. With the body alone, recorded agents still opened the
     * file for exactly these declarations.
     */
    private static void referencedMembers(ReadQueryContext db, ObjectNode fact, int classId, String name, String descriptor) {
        var rows = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT from_method, from_descriptor, field_name FROM field_accesses
                WHERE from_class_id = :id AND to_class_id = :id""").bind("id", classId).mapToMap().list());
        Set<String> used = new LinkedHashSet<>();
        Map<String, java.util.TreeSet<String>> users = new java.util.LinkedHashMap<>();
        for (var row : rows) {
            String method = String.valueOf(row.get("from_method")), field = String.valueOf(row.get("field_name"));
            String declarer = method.startsWith("lambda$") && method.indexOf('$', 7) > 7 ? method.substring(7, method.indexOf('$', 7)) : method;
            boolean selected = declarer.equals(name) && (method.startsWith("lambda$") || descriptor.equals(row.get("from_descriptor")));
            if (selected) used.add(field);
            else users.computeIfAbsent(field, key -> new java.util.TreeSet<>()).add(declarer);
        }
        if (used.isEmpty()) return;
        ArrayNode lines = JSON.createArrayNode();
        for (String field : used.stream().limit(MEMBER_LIMIT).toList()) {
            var member = db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT type_name, modifiers, annotations FROM class_members
                    WHERE class_id = :id AND kind = 'FIELD' AND name = :name""")
                    .bind("id", classId).bind("name", field).mapToMap().findOne().orElse(null));
            if (member == null) continue;
            StringBuilder line = new StringBuilder(field).append(": ").append(member.get("type_name"));
            String modifiers = String.valueOf(member.get("modifiers"));
            if (!modifiers.isBlank()) line.append(' ').append(modifiers);
            try {
                for (JsonNode annotation : JSON.readTree(String.valueOf(member.get("annotations")))) line.append(" @").append(annotation.asText());
            } catch (Exception unreadable) {
                // Annotations are a convenience here; the type and users still stand.
            }
            // V41 CQ-035: a config-injected field's key and main value, which agents otherwise grep for.
            db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT key FROM configuration_usages WHERE class_id = :id AND member = :name AND kind = 'config_key' LIMIT 1""")
                    .bind("id", classId).bind("name", field).mapTo(String.class).findOne())
                    .ifPresent(key -> line.append(" key ").append(key).append(ConfigMappingKeys.mainDeclaration(db, new ConfigurationSourceEvidence(db), key)));
            var others = users.getOrDefault(field, new java.util.TreeSet<>());
            if (!others.isEmpty()) line.append("; also used by ").append(String.join(", ", others.stream().limit(USER_LIMIT).toList()))
                    .append(others.size() > USER_LIMIT ? " and " + (others.size() - USER_LIMIT) + " more" : "");
            lines.add(line.toString());
        }
        if (!lines.isEmpty()) fact.set("referenced_members", lines);
    }

    /** Chars of the selected body; the prefetch packet as a whole is bounded separately. */
    private static final int SOURCE_BUDGET = 2500;
    private static final int LAMBDA_SOURCE_BUDGET = 4000;

    private static boolean missingLambdaEvidence(ReadQueryContext db, int id, String name, String descriptor) {
        return db.jdbi().withHandle(h -> h.createQuery("""
                SELECT count(*) FROM method_calls mc
                LEFT JOIN method_evidence me ON me.from_class_id = mc.to_class_id
                  AND me.from_method = mc.to_method AND me.from_descriptor = mc.to_descriptor
                WHERE mc.from_class_id = :id AND mc.from_method = :name AND mc.from_descriptor = :descriptor
                  AND mc.to_method LIKE 'lambda$%' AND me.from_class_id IS NULL""")
                .bind("id", id).bind("name", name).bind("descriptor", descriptor).mapTo(Integer.class).one()) > 0;
    }

    private static void annotations(ReadQueryContext db, ObjectNode fact, int classId, String name,
            String descriptor) throws Exception {
        String details = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT annotation_details FROM class_members
                WHERE class_id = :id AND name = :name AND descriptor = :descriptor""")
                .bind("id", classId).bind("name", name).bind("descriptor", descriptor)
                .mapTo(String.class).findOne().orElse("[]"));
        ArrayNode lines = JSON.createArrayNode();
        for (JsonNode annotation : JSON.readTree(details)) {
            if (annotation.path("parameterIndex").asInt(-1) >= 0) continue;
            Set<String> defaults = new LinkedHashSet<>();
            annotation.path("values").path("$defaults").forEach(value -> defaults.add(value.asText()));
            StringBuilder line = new StringBuilder("@").append(annotation.path("annotationName").asText());
            List<String> values = new java.util.ArrayList<>();
            annotation.path("values").properties().forEach(entry -> {
                if (!entry.getKey().startsWith("$") && !defaults.contains(entry.getKey()))
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
    private static void interceptors(ReadQueryContext db, ObjectNode fact, int classId, String name,
            String descriptor) throws Exception {
        Map<String, JsonNode> carried = new java.util.LinkedHashMap<>();
        String details = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT annotation_details FROM class_members
                WHERE class_id = :id AND name = :name AND descriptor = :descriptor""")
                .bind("id", classId).bind("name", name).bind("descriptor", descriptor)
                .mapTo(String.class).findOne().orElse("[]"));
        for (JsonNode annotation : JSON.readTree(details))
            if (annotation.path("parameterIndex").asInt(-1) < 0)
                carried.put(annotation.path("annotationName").asText(), annotation.path("values"));
        for (Map<String, Object> row : db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT annotation_name, annotation_values FROM class_annotations WHERE class_id = :id AND direct = 1""")
                .bind("id", classId).mapToMap().list()))
            carried.putIfAbsent(String.valueOf(row.get("annotation_name")),
                    row.get("annotation_values") == null ? JSON.createObjectNode() : JSON.readTree(String.valueOf(row.get("annotation_values"))));
        if (carried.isEmpty()) return;
        Set<String> registered = NameTargetPrefetch.registeredDependencyBeans(db);
        ArrayNode lines = JSON.createArrayNode();
        for (Map<String, Object> row : db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT class_name, priority, artifact, interceptor_bindings FROM external_beans
                WHERE kind = 'INTERCEPTOR' AND interceptor_bindings <> '[]'
                ORDER BY priority IS NULL, priority, class_name""").mapToMap().list())) {
            for (JsonNode binding : JSON.readTree(String.valueOf(row.get("interceptor_bindings")))) {
                String text = binding.asText();
                int open = text.indexOf('(');
                String annotation = text.substring(1, open < 0 ? text.length() : open);
                JsonNode values = carried.get(annotation);
                if (values == null || !bindingMatches(text, values)) continue;
                if (registered != null && !registered.contains(String.valueOf(row.get("class_name")))) continue;
                lines.add(row.get("class_name") + " @Priority(" + (row.get("priority") == null ? "none" : row.get("priority"))
                        + ") via " + text + " from " + row.get("artifact"));
            }
        }
        if (lines.isEmpty()) return;
        fact.set("dependency_interceptors", lines);
        fact.put("dependency_interceptors_basis", "interceptors in dependency jars bound by an annotation on this method "
                + "or its class; a lower priority runs first; " + (registered == null
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

    /** Callees breadth-first to {@link #DEPTH}; a lambda body is expanded at its declarer's depth. */
    private static void callees(ReadQueryContext db, java.nio.file.Path root, ObjectNode fact, int classId, String name,
            String descriptor) {
        record Method(int classId, String name, String descriptor, int depth) {}
        Set<String> seen = new LinkedHashSet<>();
        Set<String> lines = new LinkedHashSet<>();
        ArrayDeque<Method> queue = new ArrayDeque<>(List.of(new Method(classId, name, descriptor, 1)));
        seen.add(classId + "#" + name + descriptor);
        List<String[]> visited = new java.util.ArrayList<>();
        while (!queue.isEmpty() && lines.size() < LIMIT * 2) {
            Method method = queue.removeFirst();
            if (method.classId() == classId) visited.add(new String[] {method.name(), method.descriptor()});
            for (Map<String, Object> call : db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT mc.to_class_id, c.class_name, mc.to_method, mc.to_descriptor, mc.invocation_kind
                    FROM method_calls mc JOIN classes c ON c.id = mc.to_class_id
                    WHERE mc.from_class_id = :id AND mc.from_method = :name AND mc.from_descriptor = :descriptor
                    ORDER BY mc.evidence_lines""").bind("id", method.classId()).bind("name", method.name())
                    .bind("descriptor", method.descriptor()).mapToMap().list())) {
                String callee = String.valueOf(call.get("to_method"));
                int calleeClass = ((Number) call.get("to_class_id")).intValue();
                String calleeDescriptor = String.valueOf(call.get("to_descriptor"));
                boolean lambda = callee.startsWith("lambda$");
                if (!lambda) lines.add(method.depth() + " " + call.get("class_name") + "#" + callee
                        + " [" + call.get("invocation_kind") + (declares(db, calleeClass, callee, calleeDescriptor) ? ""
                                : ", inherited: " + inheritedFrom(db, root, calleeClass, callee)) + "]");
                int next = lambda ? method.depth() : method.depth() + 1;
                if (next <= DEPTH && seen.add(calleeClass + "#" + callee + calleeDescriptor))
                    queue.addLast(new Method(calleeClass, callee, calleeDescriptor, next));
            }
            String external = db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT caller_external_calls FROM method_evidence
                    WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor""")
                    .bind("id", method.classId()).bind("name", method.name()).bind("descriptor", method.descriptor())
                    .mapTo(String.class).findOne().orElse("[]"));
            try {
                for (JsonNode call : JSON.readTree(external)) {
                    // line|ordinal|owner|method|descriptor|...
                    String[] parts = call.asText().split("\\|", -1);
                    if (parts.length > 3) lines.add(method.depth() + " " + parts[2] + "#" + parts[3]
                            + (parts.length > 4 ? javaSignature(parts[4]) : "") + " [external]");
                }
            } catch (Exception unreadable) {
                // Evidence rows are written by the indexer; an unreadable row only drops its external calls.
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
        // A lambda body is walked after its declarer's deeper callees; depth order keeps nearer calls first when cut.
        lines.stream().sorted(java.util.Comparator.comparingInt(line -> Integer.parseInt(line.substring(0, line.indexOf(' ')))))
                .limit(LIMIT).forEach(values::add);
        fact.put("calls_basis", "depth, callee, invocation; depth 2 = called by a depth-1 callee");
        if (lines.size() > LIMIT) fact.putObject("calls_truncated").put("shown", LIMIT).put("total", lines.size());
    }

    /** The nearest superclass declaring {@code name}, searched through application and dependency bytecode. */
    private static String inheritedFrom(ReadQueryContext db, java.nio.file.Path root, int classId, String name) {
        String type = db.jdbi().withHandle(handle -> handle.createQuery("SELECT superclass FROM classes WHERE id = :id")
                .bind("id", classId).mapTo(String.class).findOne().orElse(null));
        ExternalSymbolQueries external = new ExternalSymbolQueries();
        for (int depth = 0; type != null && !type.equals("java.lang.Object") && depth < 8; depth++) {
            String current = type;
            Integer local = db.jdbi().withHandle(handle -> handle.createQuery(
                    "SELECT id FROM classes WHERE class_name = :name AND lifecycle = 'current' LIMIT 1")
                    .bind("name", current).mapTo(Integer.class).findOne().orElse(null));
            if (local != null) {
                boolean found = db.jdbi().withHandle(handle -> handle.createQuery(
                        "SELECT count(*) FROM class_members WHERE class_id = :id AND name = :name")
                        .bind("id", local).bind("name", name).mapTo(Integer.class).one()) > 0;
                if (found) return "declared by " + current;
                type = db.jdbi().withHandle(handle -> handle.createQuery("SELECT superclass FROM classes WHERE id = :id")
                        .bind("id", local).mapTo(String.class).findOne().orElse(null));
                continue;
            }
            if (root == null) break;
            try {
                JsonNode details = JSON.readTree(external.details(db, root, current, 1000, 0));
                if (details.has("error")) break;
                for (JsonNode member : details.path("members"))
                    if (member.path("signature").asText().contains(" " + name + "("))
                        return "declared by " + current + " (" + details.path("artifact").asText() + ")";
                type = details.path("superclass").asText(null);
            } catch (Exception unreadable) {
                break;
            }
        }
        return "not declared in this class";
    }

    private static boolean declares(ReadQueryContext db, int classId, String name, String descriptor) {
        // Constructors are indexed under the class's simple name and are never inherited.
        if (name.equals("<init>")) return true;
        return db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT count(*) FROM class_members WHERE class_id = :id AND name = :name AND descriptor = :descriptor""")
                .bind("id", classId).bind("name", name).bind("descriptor", descriptor).mapTo(Integer.class).one()) > 0;
    }

    /**
     * The method fires an event: observers of each Event<T> field the walked methods of its class
     * read, or of every Event<T> its class injects when no such read is indexed.
     */
    private static void firedEventObservers(ReadQueryContext db, ObjectNode fact, int classId, List<String[]> visited,
            Set<String> dispatch) {
        Set<String> read = new java.util.HashSet<>();
        for (String[] method : visited)
            read.addAll(db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT field_name FROM field_accesses
                    WHERE from_class_id = :id AND to_class_id = :id AND from_method = :name
                      AND from_descriptor = :descriptor AND access_kind = 'read_instance'""")
                    .bind("id", classId).bind("name", method[0]).bind("descriptor", method[1])
                    .mapTo(String.class).list()));
        List<Map<String, Object>> points = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT ip.field_name, ip.target_type, ip.qualifiers FROM injection_points ip JOIN beans ob ON ob.id = ip.bean_id
                WHERE ob.class_id = :id ORDER BY ip.id""").bind("id", classId).mapToMap().list())
                .stream().filter(point -> ClassPrefetchRelations.argument(String.valueOf(point.get("target_type")),
                        List.of("jakarta.enterprise.event.Event", "javax.enterprise.event.Event")) != null).toList();
        List<Map<String, Object>> fired = points.stream().filter(point -> read.contains(String.valueOf(point.get("field_name")))).toList();
        boolean narrowed = !fired.isEmpty();
        ArrayNode observers = JSON.createArrayNode();
        List<Map<String, Object>> selected = narrowed ? fired : points;
        ArrayNode events = JSON.createArrayNode();
        for (Map<String, Object> point : selected) {
            String type = String.valueOf(point.get("target_type"));
            ClassPrefetchRelations.eventObservers(db, type).forEach(observer ->
                    observers.add(point.get("field_name") + " " + ClassPrefetchRelations.source(type) + " -> " + observer));
            if (events.size() >= 3) continue;
            String event = ClassPrefetchRelations.argument(type,
                    List.of("jakarta.enterprise.event.Event", "javax.enterprise.event.Event"));
            ObjectNode value = events.addObject().put("field", String.valueOf(point.get("field_name")))
                    .put("event_type", event);
            value.set("dispatch_methods", JSON.valueToTree(dispatch));
            value.put("field_to_fire_binding_proven", false);
            try {
                ArrayNode qualifiers = (ArrayNode) JSON.readTree(String.valueOf(point.get("qualifiers")));
                if (qualifiers.isEmpty()) qualifiers.add("@Default");
                if (qualifiers.valueStream().noneMatch(q -> q.asText().equals("@Any")
                        || q.asText().equals("@jakarta.enterprise.inject.Any") || q.asText().equals("@javax.enterprise.inject.Any")))
                    qualifiers.add("@Any");
                value.set("event_qualifiers", qualifiers);
            } catch (Exception unreadable) { value.put("qualifier_evidence_status", "unreadable"); }
            try {
                JsonNode candidates = JSON.readTree(new EntryPointQueries().findEntryPoints(
                        db, "observer", null, false, true, 10, 0, event));
                for (String key : List.of("entry_points", "observer_selection", "showing", "total", "has_more", "truncated", "next_page_request"))
                    if (candidates.has(key)) value.set(key, candidates.get(key));
            } catch (Exception unreadable) { value.put("observer_evidence_status", "unreadable"); }
        }
        if (events.isEmpty()) return;
        fact.set("event_dispatches", events);
        if (selected.size() > events.size()) fact.putObject("event_dispatches_truncated")
                .put("shown", events.size()).put("total", selected.size());
        fact.put("event_delivery_proven", false);
        fact.put("answer_complete_scope", "Direct indexed method consumers/contracts only; event candidates do not prove qualifier eligibility, bean activation or delivery");
        fact.put("event_delivery_semantics", "CDI contract: fire notifies synchronous @Observes; fireAsync notifies @ObservesAsync. Without an active transaction, transactional observers run immediately. With an active transaction, AFTER_SUCCESS runs only after successful completion, not rollback; if synchronization cannot be registered it is skipped. Other transaction phases retain their declared semantics. Runtime transaction state and observer activation are not observed.");
        if (!observers.isEmpty()) fact.set("observers_of_fired_events", observers);
        fact.put("observers_basis", narrowed ? "Event fields this method or its walked callees read"
                : "every Event field of the class; which one is fired is not indexed");
    }

    private static void producer(ReadQueryContext db, ObjectNode fact, int classId, String name) {
        List<Map<String, Object>> beans = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT id, kind, scope, qualifiers, bean_types FROM beans
                WHERE declaring_class_id = :id AND member_name = :name AND kind = 'PRODUCER_METHOD'""")
                .bind("id", classId).bind("name", name).mapToMap().list());
        if (beans.isEmpty()) return;
        ArrayNode produces = fact.putArray("produces");
        ArrayNode parameters = JSON.createArrayNode();
        ArrayNode injectedInto = JSON.createArrayNode();
        for (Map<String, Object> bean : beans) {
            int id = ((Number) bean.get("id")).intValue();
            produces.add(bean.get("kind") + " " + bean.get("scope") + " qualifiers " + bean.get("qualifiers")
                    + " types " + bean.get("bean_types"));
            db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT ip.field_name, ip.target_type, ip.qualifiers, ip.resolution_status, rc.class_name AS resolved
                    FROM injection_points ip
                    LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id
                    LEFT JOIN classes rc ON rc.id = COALESCE(rb.declaring_class_id, rb.class_id)
                    WHERE ip.bean_id = :id ORDER BY ip.id""").bind("id", id).mapToMap().list())
                    .forEach(row -> parameters.add(row.get("field_name") + ": " + row.get("target_type") + " "
                            + row.get("qualifiers") + " -> " + (row.get("resolved") == null
                                    ? "not resolved by the application index" + dependencyProviders(db,
                                            String.valueOf(row.get("target_type")), row.get("qualifiers"))
                                    : row.get("resolved"))
                            + " [" + row.get("resolution_status") + "]"));
            db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT DISTINCT oc.class_name AS owner, ip.field_name, ip.qualifiers, ip.resolution_status, oc.source_file
                    FROM injection_points ip
                    JOIN beans ob ON ob.id = ip.bean_id
                    JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                    WHERE ip.resolved_bean_id = :id OR ip.resolution_candidates LIKE :candidate
                    ORDER BY oc.class_name, ip.field_name""").bind("id", id)
                    .bind("candidate", "%\"beanId\":" + id + ",%").mapToMap().list())
                    .forEach(row -> injectedInto.add(row.get("owner") + "#" + row.get("field_name") + " "
                            + row.get("qualifiers") + " [" + row.get("resolution_status") + "] " + row.get("source_file")));
        }
        // The declaring class is a bean of its own, whose scope is not the produced bean's.
        db.jdbi().withHandle(handle -> handle.createQuery("SELECT scope FROM beans WHERE class_id = :id AND kind = 'CLASS' LIMIT 1")
                .bind("id", classId).mapTo(String.class).findOne())
                .ifPresent(scope -> fact.put("declaring_class_bean", "CLASS " + scope));
        if (!parameters.isEmpty()) fact.set("producer_parameters", parameters);
        if (!injectedInto.isEmpty()) fact.set("produced_bean_injected_into", injectedInto);
    }

    /**
     * Dependency beans providing {@code type} whose qualifiers include every required one, for an
     * injection point the application index cannot resolve.
     */
    static String dependencyProviders(ReadQueryContext db, String type, Object requiredQualifiers) {
        Set<String> required = new java.util.HashSet<>();
        try {
            if (requiredQualifiers != null) JSON.readTree(String.valueOf(requiredQualifiers)).forEach(q -> required.add(q.asText()));
        } catch (Exception unreadable) {
            // Unreadable qualifiers leave the providers unfiltered.
        }
        List<String> providers = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT class_name, member_name, artifact, qualifiers FROM external_beans
                WHERE bean_types LIKE :type ORDER BY artifact, class_name, member_name""")
                .bind("type", "%\"" + type + "\"%").mapToMap().list()).stream()
                .filter(row -> {
                    try {
                        Set<String> offered = new java.util.HashSet<>();
                        JSON.readTree(String.valueOf(row.get("qualifiers"))).forEach(q -> offered.add(q.asText()));
                        return offered.containsAll(required);
                    } catch (Exception unreadable) {
                        return true;
                    }
                })
                .limit(5).map(row -> row.get("class_name") + (row.get("member_name") == null ? "" : "#" + row.get("member_name"))
                        + " from " + (row.get("artifact") == null ? "unknown" : row.get("artifact"))).toList();
        return providers.isEmpty() ? "" : "; dependency providers: " + String.join(", ", providers);
    }

    /** (Ljava/nio/file/Path;[I)Z reads (java.nio.file.Path, int[]):boolean; malformed input reads as empty. */
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
            case '[': { String element = javaType(descriptor, at); return element == null ? null : element + "[]"; }
            case 'L': {
                int end = descriptor.indexOf(';', at[0]);
                if (end < 0) return null;
                String name = descriptor.substring(at[0], end).replace('/', '.');
                at[0] = end + 1;
                return ClassPrefetchRelations.source(name);
            }
            case 'Z': return "boolean";
            case 'B': return "byte";
            case 'C': return "char";
            case 'S': return "short";
            case 'I': return "int";
            case 'J': return "long";
            case 'F': return "float";
            case 'D': return "double";
            case 'V': return "void";
            default: return null;
        }
    }

    private static String text(JsonNode value) {
        return value.isTextual() ? value.asText() : value.toString();
    }
}
