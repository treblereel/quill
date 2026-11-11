package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/**
 * The CDI and test relations a class fact needs for a first-turn answer, one line per relation:
 * what the class injects, which injection points require or resolve to it, and which test
 * classes depend on it. Related source files are the production files that inject, implement or
 * call the class. Lines are indexed static evidence; runtime selection is not implied.
 */
final class ClassPrefetchRelations {

    private static final int LIMIT = 30;

    private ClassPrefetchRelations() {
    }

    static void append(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        lines(fact, "injects", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT ip.kind, ip.field_name, ip.target_type, ip.qualifiers, ip.resolution_status,
                       rc.class_name AS resolved_class, rb.member_name AS resolved_member, rb.kind AS resolved_kind,
                       rb.is_default AS resolved_default
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id
                LEFT JOIN classes rc ON rc.id = COALESCE(rb.declaring_class_id, rb.class_id)
                WHERE ob.class_id = :id AND ob.member_name IS NULL
                ORDER BY ip.id""").bind("id", cls.id()).mapToMap().list()), row -> injects(db, row));
        lines(fact, "injected_into", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT DISTINCT oc.class_name AS owner, ob.member_name AS owner_member, ob.kind AS owner_kind, ip.kind,
                       ip.field_name, ip.target_type, ip.qualifiers, ip.resolution_status, ob.scope AS owner_scope,
                       (SELECT GROUP_CONCAT(mc.from_method, ',') FROM method_calls mc
                        WHERE mc.from_class_id = oc.id AND mc.to_class_id = :id) AS used_in
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                WHERE ip.target_type = :name OR ip.target_type LIKE :generic
                   OR ip.resolved_bean_id IN (SELECT id FROM beans WHERE class_id = :id AND member_name IS NULL)
                ORDER BY oc.class_name, ip.field_name""")
                .bind("name", cls.className()).bind("generic", "%<" + cls.className() + ">%")
                .bind("id", cls.id()).mapToMap().list()), ClassPrefetchRelations::injectedInto);
        lines(fact, "decorators", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.class_name, b.priority FROM beans b JOIN classes c ON c.id = b.class_id
                WHERE b.kind = 'DECORATOR' AND b.bean_types LIKE :type AND c.id <> :id
                ORDER BY b.priority IS NULL, b.priority, c.class_name""")
                .bind("type", "%\"" + cls.className() + "\"%").bind("id", cls.id()).mapToMap().list()),
                row -> row.get("class_name") + " @Priority(" + (row.get("priority") == null ? "none" : row.get("priority")) + ")");
        if (fact.has("decorators"))
            fact.put("decorators_basis", "@Decorator beans with this bean type; a lower priority is invoked first, around the decorated bean");
        lines(fact, "test_classes", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT DISTINCT tc.source_file
                FROM dependencies d JOIN classes tc ON tc.id = d.from_class_id
                WHERE d.to_class_id = :id AND tc.source_set = 'test' AND tc.source_file IS NOT NULL
                ORDER BY tc.source_file""").bind("id", cls.id()).mapToMap().list()),
                row -> String.valueOf(row.get("source_file")));
        lines(fact, "related_source_files", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT DISTINCT oc.source_file
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                WHERE (ip.target_type = :name OR ip.target_type LIKE :generic
                   OR ip.resolved_bean_id IN (SELECT id FROM beans WHERE class_id = :id AND member_name IS NULL))
                  AND oc.source_file IS NOT NULL
                UNION
                SELECT source_file FROM classes
                WHERE lifecycle = 'current' AND source_file IS NOT NULL
                  AND (superclass = :name OR interfaces LIKE :interface)
                UNION
                SELECT c.source_file FROM method_calls mc JOIN classes c ON c.id = mc.from_class_id
                WHERE mc.to_class_id = :id AND mc.from_class_id <> :id AND c.lifecycle = 'current'
                  AND c.source_file IS NOT NULL AND c.source_set = 'main'
                ORDER BY 1""").bind("name", cls.className()).bind("generic", "%<" + cls.className() + ">%")
                .bind("interface", "%\"" + cls.className() + "\"%").bind("id", cls.id()).mapToMap().list()),
                row -> String.valueOf(row.values().iterator().next()));
        lines(fact, "configuration", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT key, kind, member, annotation, NULL AS file, NULL AS line FROM configuration_usages
                WHERE class_name = :name OR class_name LIKE :nested
                UNION ALL
                SELECT key, kind, NULL, json_extract(evidence, '$.declaring_class'), file, line FROM configuration_definitions
                WHERE evidence LIKE :declared OR evidence LIKE :declaredNested
                ORDER BY 1""").bind("name", cls.className()).bind("nested", cls.className() + "$%")
                .bind("declared", "%\"declaring_class\":\"" + cls.className() + "\"%")
                .bind("declaredNested", "%\"declaring_class\":\"" + cls.className() + "$%")
                .mapToMap().list()), ClassPrefetchRelations::configuration);
        hierarchy(db, fact, cls);
        producerHosts(db, fact, cls);
        if ("INTERFACE".equals(cls.kind())) {
            var callers = db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT caller.class_name, caller.source_file, group_concat(DISTINCT mc.to_method || mc.to_descriptor) AS methods
                    FROM method_calls mc JOIN classes caller ON caller.id = mc.from_class_id
                    JOIN classes callee ON callee.id = mc.to_class_id
                    WHERE caller.lifecycle = 'current' AND caller.source_set = 'main'
                      AND (callee.id = :id OR (callee.lifecycle = 'current' AND callee.interfaces LIKE :type
                        AND EXISTS (SELECT 1 FROM class_members cm WHERE cm.class_id = :id
                          AND cm.name = mc.to_method AND cm.descriptor = mc.to_descriptor
                          AND COALESCE(cm.modifiers, '') NOT LIKE '%static%')))
                    GROUP BY caller.class_name, caller.source_file
                    ORDER BY caller.class_name, caller.source_file""")
                    .bind("id", cls.id()).bind("type", "%\"" + cls.className() + "\"%")
                    .mapToMap().list());
            lines(fact, "contract_callers", callers, row -> row.get("class_name") + " calls " + compactCalls(String.valueOf(row.get("methods"))));
            fact.put("contract_caller_classes", callers.size());
            var beans = db.jdbi().withHandle(h -> h.createQuery("""
                    SELECT c.class_name, b.kind, b.member_name, b.scope, b.qualifiers, b.profiles, b.priority
                    FROM beans b JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                    WHERE c.lifecycle = 'current' AND b.bean_types LIKE :type
                    ORDER BY c.class_name, b.member_name""")
                    .bind("type", "%\"" + cls.className() + "\"%").mapToMap().list());
            lines(fact, "contract_beans", beans, row -> row.get("class_name")
                    + (row.get("member_name") == null ? "" : "#" + row.get("member_name"))
                    + " " + row.get("kind") + " " + row.get("scope") + " qualifiers " + row.get("qualifiers")
                    + " profiles " + row.get("profiles") + " priority " + row.get("priority"));
            fact.put("contract_application_producer_count", beans.stream()
                    .filter(row -> String.valueOf(row.get("kind")).startsWith("PRODUCER_")).count());
            fact.put("contract_beans_scope", "All matching declarations in current application index; profile eligibility, dependency producers and runtime extensions not established");

            fact.put("contract_callers_scope", "Distinct current production classes calling this interface or matching instance methods on direct implementors, including self-calls; inherited implementors and runtime dispatch are not covered");
        }
        ConfigMappingKeys.append(db, fact, cls);
        List<String> types = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT ip.target_type FROM injection_points ip JOIN beans ob ON ob.id = ip.bean_id
                WHERE ob.class_id = :id AND ob.member_name IS NULL ORDER BY ip.id""")
                .bind("id", cls.id()).mapTo(String.class).list());
        ArrayNode observers = JSON.createArrayNode();
        ArrayNode candidates = JSON.createArrayNode();
        for (String type : types) {
            String event = argument(type, EVENT);
            if (event != null) observers(db, event).forEach(observers::add);
            String instance = argument(type, INSTANCE);
            if (instance != null) typeCandidates(db, instance).forEach(candidates::add);
        }
        if (!observers.isEmpty()) fact.set("observers_of_injected_events", observers);
        if (!candidates.isEmpty()) fact.set("instance_candidates", candidates);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> EVENT = List.of("jakarta.enterprise.event.Event", "javax.enterprise.event.Event");
    private static final List<String> INSTANCE = List.of("jakarta.enterprise.inject.Instance",
            "javax.enterprise.inject.Instance", "io.quarkus.arc.InjectableInstance");
    private static final List<String> CONDITIONS = List.of("io.quarkus.arc.lookup.LookupIfProperty",
            "io.quarkus.arc.lookup.LookupUnlessProperty", "io.quarkus.arc.profile.IfBuildProfile",
            "io.quarkus.arc.profile.UnlessBuildProfile", "io.quarkus.arc.properties.IfBuildProperty",
            "io.quarkus.arc.properties.UnlessBuildProperty", "io.quarkus.arc.DefaultBean",
            "jakarta.enterprise.inject.Alternative", "jakarta.annotation.Priority");

    /** Candidates of an {@code Instance<T>} required type, or none for any other type. */
    static List<String> instanceCandidates(ReadQueryContext db, String requiredType) {
        String type = argument(requiredType, INSTANCE);
        return type == null ? List.of() : typeCandidates(db, type);
    }

    /** Observers of an {@code Event<T>} required type, or none for any other type. */
    static List<String> eventObservers(ReadQueryContext db, String requiredType) {
        String type = argument(requiredType, EVENT);
        return type == null ? List.of() : observers(db, type);
    }

    /** The type argument of {@code Wrapper<T>} when the wrapper is one of {@code wrappers}. */
    static String argument(String type, List<String> wrappers) {
        if (type == null) return null;
        int open = type.indexOf('<');
        if (open < 0 || !type.endsWith(">") || !wrappers.contains(type.substring(0, open))) return null;
        return type.substring(open + 1, type.length() - 1).trim();
    }

    /** Observer methods whose @Observes/@ObservesAsync parameter has exactly {@code event}'s type. */
    static List<String> observers(ReadQueryContext db, String event) {
        List<String> lines = new java.util.ArrayList<>();
        db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.class_name, cm.name, cm.annotation_details FROM class_members cm
                JOIN classes c ON c.id = cm.class_id
                WHERE c.lifecycle = 'current' AND cm.annotation_details LIKE '%.event.Observes%'
                  AND (cm.annotation_details LIKE :binary OR cm.annotation_details LIKE :spelled)""")
                .bind("binary", "%\"parameterType\":\"" + event + "\"%")
                .bind("spelled", "%\"parameterType\":\"" + event.replace('.', '$') + "\"%")
                .mapToMap().list()).forEach(row -> {
                    try {
                        for (var annotation : JSON.readTree(String.valueOf(row.get("annotation_details")))) {
                            String name = annotation.path("annotationName").asText();
                            if (!name.endsWith(".event.Observes") && !name.endsWith(".event.ObservesAsync")) continue;
                            if (!sameType(annotation.path("parameterType").asText(), event)) continue;
                            String during = annotation.path("values").path("during").asText("");
                            lines.add(row.get("class_name") + "#" + row.get("name") + "(@" + name.substring(name.lastIndexOf('.') + 1)
                                    + " " + source(event) + (during.isEmpty() || "IN_PROGRESS".equals(during) ? "" : " during=" + during) + ")");
                        }
                    } catch (Exception unreadable) {
                        // An unreadable member row only drops that observer.
                    }
                });
        return lines;
    }

    /** Application beans whose bean types include {@code type}, with their lookup and enablement conditions. */
    static List<String> typeCandidates(ReadQueryContext db, String type) {
        List<String> lines = new java.util.ArrayList<>();
        db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT b.class_id, c.class_name, b.kind, b.scope, b.member_name, b.qualifiers, b.is_default
                FROM beans b JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE b.bean_types LIKE :type ORDER BY c.class_name, b.member_name""")
                .bind("type", "%\"" + type + "\"%").mapToMap().list()).forEach(row -> {
                    List<String> conditions = db.jdbi().withHandle(handle -> handle.createQuery("""
                            SELECT annotation_name, annotation_values FROM class_annotations
                            WHERE class_id = :id AND direct = 1 ORDER BY annotation_name""")
                            .bind("id", row.get("class_id")).mapToMap().list()).stream()
                            .filter(annotation -> CONDITIONS.contains(String.valueOf(annotation.get("annotation_name"))))
                            .map(annotation -> "@" + annotation.get("annotation_name") + values(annotation.get("annotation_values")))
                            .toList();
                    lines.add(row.get("kind") + " " + row.get("class_name") + (row.get("member_name") == null ? "" : "#" + row.get("member_name"))
                            + " " + row.get("scope") + " qualifiers " + row.get("qualifiers")
                            + (conditions.isEmpty() ? "" : " " + String.join(" ", conditions))
                            + (isSet(row.get("is_default")) && conditions.stream().noneMatch(c -> c.contains("DefaultBean"))
                                    ? " @DefaultBean" : ""));
                });
        return lines;
    }

    private static String values(Object raw) {
        if (raw == null) return "";
        try {
            var node = JSON.readTree(String.valueOf(raw));
            java.util.Set<String> defaults = new java.util.HashSet<>();
            node.path("$defaults").forEach(value -> defaults.add(value.asText()));
            List<String> set = new java.util.ArrayList<>();
            node.properties().forEach(entry -> {
                if (!entry.getKey().startsWith("$") && !defaults.contains(entry.getKey()))
                    set.add(entry.getKey() + "=" + (entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue()));
            });
            return set.isEmpty() ? "" : "(" + String.join(", ", set) + ")";
        } catch (Exception unreadable) {
            return "";
        }
    }

    private static boolean sameType(String indexed, String wanted) {
        return indexed.equals(wanted) || indexed.replace('$', '.').equals(wanted.replace('$', '.'));
    }

    /** Source spelling of a binary type name: Outer$Inner reads Outer.Inner; anonymous $1 stays. Types only. */
    static String source(Object type) {
        return type == null ? "null" : String.valueOf(type).replaceAll("\\$(?=[A-Za-z_])", ".");
    }

    private static String configuration(Map<String, Object> row) {
        if (row.get("file") != null)
            return row.get("key") + " [" + row.get("kind") + " declared"
                    + (row.get("annotation") == null ? "" : " by " + source(row.get("annotation"))) + "] "
                    + row.get("file") + ":" + row.get("line");
        return row.get("key") + " [" + row.get("kind") + " read" + (row.get("member") == null ? "" : " by " + row.get("member"))
                + " via @" + row.get("annotation") + "]";
    }

    private static String injects(ReadQueryContext db, Map<String, Object> row) {
        String resolved = row.get("resolved_class") == null ? "not resolved by the application index"
                + MethodPrefetchRelations.dependencyProviders(db, String.valueOf(row.get("target_type")), row.get("qualifiers"))
                : row.get("resolved_class") + (row.get("resolved_member") == null ? ""
                        : "#" + row.get("resolved_member")) + " (" + row.get("resolved_kind") + ")"
                        + (isSet(row.get("resolved_default")) ? " @DefaultBean" : "");
        return row.get("kind") + " " + row.get("field_name") + ": " + source(row.get("target_type"))
                + qualifiers(row) + " -> " + resolved + " [" + row.get("resolution_status") + "]";
    }

    /** Called methods as name(ShortTypes), at about half the cost of descriptors; a selector matching two overloads is rejected. */
    static String compactCalls(String methods) {
        java.util.Set<String> calls = new java.util.LinkedHashSet<>();
        for (String call : methods.split(",")) calls.add(compactCall(call));
        return String.join(",", calls);
    }

    private static String compactCall(String call) {
        int open = call.indexOf('(');
        if (open < 0) return call;
        try {
            StringBuilder compact = new StringBuilder(call.substring(0, open + 1));
            org.objectweb.asm.Type[] types = org.objectweb.asm.Type.getArgumentTypes(call.substring(open));
            for (int i = 0; i < types.length; i++) {
                String type = types[i].getClassName();
                compact.append(i == 0 ? "" : ",").append(type.substring(Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1));
            }
            return compact.append(')').toString();
        } catch (RuntimeException unreadable) {
            return call;
        }
    }

    private static boolean isSet(Object flag) {
        return flag instanceof Number number && number.intValue() != 0;
    }

    /**
     * The owner's scope and the owner methods that call into this class: in V24-V27 CQ-045 Claude
     * read SsarAuthorizer about once per session to learn both before answering what a narrower
     * scope would change.
     */
    private static String injectedInto(Map<String, Object> row) {
        String owner = row.get("owner") + (row.get("owner_member") == null ? "" : "#" + row.get("owner_member"));
        boolean decorator = "DECORATOR".equals(row.get("owner_kind"));
        Object scope = row.get("owner_scope");
        java.util.TreeSet<String> methods = new java.util.TreeSet<>();
        if (row.get("used_in") != null) {
            for (String method : String.valueOf(row.get("used_in")).split(",")) {
                // A lambda body belongs to the method that declares it.
                java.util.regex.Matcher lambda = LAMBDA.matcher(method);
                methods.add(lambda.matches() ? lambda.group(1) : method);
            }
        }
        return owner + "#" + row.get("field_name") + " (" + row.get("kind") + " " + source(row.get("target_type"))
                + ")" + qualifiers(row) + " [" + row.get("resolution_status") + "]"
                + (!decorator && scope != null ? " owner " + scope : "")
                + (methods.isEmpty() ? "" : ", uses it in " + String.join(", ", methods))
                + (decorator ? " in a decorator: a delegate point resolves to the decorated bean" : "");
    }

    private static final java.util.regex.Pattern LAMBDA = java.util.regex.Pattern.compile("lambda\\$(.+?)\\$\\d+");

    private static String qualifiers(Map<String, Object> row) {
        Object value = row.get("qualifiers");
        return value == null || "[]".equals(value) ? "" : " " + value;
    }

    /**
     * What a class takes from its indexed superclass chain: each own method that redeclares an
     * inherited one (and where it was declared, abstract or not), and each inherited instance method
     * it keeps unchanged, from the nearest superclass declaring it; stated only when it overrides
     * something. Matched by name and erased
     * descriptor; interfaces and unindexed superclasses are not walked.
     */
    private static void hierarchy(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        record Method(String name, String descriptor, String signature, boolean isAbstract) {
            String key() { return name + descriptor; }
        }
        List<String> chain = new java.util.ArrayList<>();
        Map<String, List<Method>> declared = new java.util.LinkedHashMap<>();
        java.util.function.Function<String, List<Method>> methods = name -> db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT cm.name, cm.descriptor, cm.signature, cm.modifiers FROM class_members cm JOIN classes c ON c.id = cm.class_id
                WHERE c.class_name = :name AND c.lifecycle = 'current' AND cm.kind = 'METHOD'
                  AND COALESCE(cm.modifiers, '') NOT LIKE '%private%' AND COALESCE(cm.modifiers, '') NOT LIKE '%static%'
                  AND COALESCE(cm.modifiers, '') NOT LIKE '%synthetic%' AND COALESCE(cm.modifiers, '') NOT LIKE '%bridge%'
                ORDER BY cm.name, cm.descriptor""").bind("name", name).mapToMap().list()).stream()
                .map(row -> new Method(String.valueOf(row.get("name")), String.valueOf(row.get("descriptor")),
                        String.valueOf(row.get("signature")), String.valueOf(row.get("modifiers")).contains("abstract"))).toList();
        String parent = cls.superclass();
        java.util.Set<String> seen = new java.util.HashSet<>(List.of(cls.className()));
        while (parent != null && !"java.lang.Object".equals(parent) && seen.add(parent)) {
            String name = parent;
            var parentClass = db.jdbi().withHandle(handle -> handle.createQuery(
                    "SELECT superclass FROM classes WHERE class_name = :name AND lifecycle = 'current'")
                    .bind("name", name).mapToMap().findFirst());
            if (parentClass.isEmpty()) break;
            chain.add(name);
            declared.put(name, methods.apply(name));
            parent = (String) parentClass.get().get("superclass");
        }
        if (chain.isEmpty()) return;
        List<Method> own = methods.apply(cls.className());
        java.util.Set<String> ownKeys = new java.util.HashSet<>();
        own.forEach(method -> ownKeys.add(method.key()));
        List<String> overrides = new java.util.ArrayList<>();
        for (Method method : own) {
            List<String> sources = new java.util.ArrayList<>();
            for (String ancestor : chain)
                declared.get(ancestor).stream().filter(other -> other.key().equals(method.key())).findFirst()
                        .ifPresent(other -> sources.add(simpleName(ancestor) + (other.isAbstract() ? " abstract" : "")));
            if (!sources.isEmpty()) overrides.add(shortSignature(method.signature()) + " overrides " + String.join(", ", sources));
        }
        ObjectNode inherited = JSON.createObjectNode();
        java.util.Set<String> taken = new java.util.HashSet<>(ownKeys);
        for (String ancestor : chain) {
            List<String> kept = new java.util.ArrayList<>();
            for (Method method : declared.get(ancestor))
                if (taken.add(method.key()) && !method.isAbstract()) kept.add(shortSignature(method.signature()));
            if (!kept.isEmpty()) inherited.put(simpleName(ancestor), String.join(", ", kept));
        }
        // A class that redeclares nothing has no override story; its inherited API is one detail call away.
        if (overrides.isEmpty()) return;
        var names = fact.putArray("superclass_chain");
        chain.forEach(name -> names.add(source(name)));
        strings(fact, "overrides", overrides);
        if (!inherited.isEmpty()) fact.set("inherited_methods", inherited);
        java.util.Set<String> overridden = new java.util.HashSet<>();
        for (Method method : own)
            if (chain.stream().anyMatch(ancestor -> declared.get(ancestor).stream().anyMatch(other -> other.key().equals(method.key()))))
                overridden.add(method.key());
        strings(fact, "declared_methods", declaredMethods(db, cls, overridden));
        fact.put("overrides_basis", "Same name and erased descriptor in the indexed superclass chain, nearest first; interfaces not walked");
    }

    /**
     * The class's own methods that override nothing: with the overrides and inherited methods they
     * complete what the class declares, so no member listing is needed. Annotations, including
     * parameter annotations such as @Observes, and private/static stay; synthetic methods do not.
     */
    private static List<String> declaredMethods(ReadQueryContext db, ClassRecord cls, java.util.Set<String> overridden) {
        List<String> result = new java.util.ArrayList<>();
        for (var member : db.symbols.findClassMembers(cls.id())) {
            String modifiers = member.modifiers() == null ? "" : member.modifiers();
            if (!"METHOD".equals(member.kind()) || overridden.contains(member.name() + member.descriptor()) || member.name().startsWith("lambda$")
                    || member.name().startsWith("access$") || modifiers.contains("synthetic") || modifiers.contains("bridge")) continue;
            String signature = shortSignature(member.signature());
            int open = signature.indexOf('(');
            List<String> parameters = splitParameters(signature.substring(open + 1, signature.length() - 1));
            for (var annotation : member.annotationDetails())
                if (annotation.parameterIndex() != null && annotation.parameterIndex() < parameters.size())
                    parameters.set(annotation.parameterIndex(), "@" + simpleName(annotation.annotationName()) + " " + parameters.get(annotation.parameterIndex()));
            StringBuilder line = new StringBuilder();
            java.util.Set<String> onParameters = new java.util.HashSet<>();
            member.annotationDetails().stream().filter(annotation -> annotation.parameterIndex() != null).forEach(annotation -> onParameters.add(annotation.annotationName()));
            // The index also lists parameter annotations among the method's; state each once, where it is.
            member.annotations().stream().filter(annotation -> !onParameters.contains(annotation))
                    .forEach(annotation -> line.append('@').append(simpleName(annotation)).append(' '));
            line.append(signature, 0, open + 1).append(String.join(",", parameters)).append(')');
            for (String modifier : List.of("private", "static", "abstract")) if (modifiers.contains(modifier)) line.append(' ').append(modifier);
            result.add(line.toString());
        }
        result.sort(java.util.Comparator.comparing(line -> line.replaceFirst("^(@\\S+ )+", "")));
        return result;
    }

    private static List<String> splitParameters(String parameters) {
        List<String> result = new java.util.ArrayList<>();
        if (parameters.isBlank()) return result;
        int depth = 0, start = 0;
        for (int i = 0; i < parameters.length(); i++) {
            char c = parameters.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { result.add(parameters.substring(start, i).trim()); start = i + 1; }
        }
        result.add(parameters.substring(start).trim());
        return result;
    }

    /**
     * For a class bean, the application classes that already declare producers, with how many:
     * where a producer for this bean would follow the project's convention. Production source
     * only, most producers first.
     */
    private static void producerHosts(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        boolean classBean = db.jdbi().withHandle(handle -> handle.createQuery(
                "SELECT COUNT(*) FROM beans WHERE class_id = :id AND kind = 'CLASS' AND declaring_class_id IS NULL")
                .bind("id", cls.id()).mapTo(Integer.class).one()) > 0;
        if (!classBean) return;
        var rows = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.class_name, COUNT(DISTINCT b.member_name) AS total
                FROM beans b JOIN classes c ON c.id = b.declaring_class_id
                WHERE b.kind LIKE 'PRODUCER_%' AND b.member_name IS NOT NULL AND c.lifecycle = 'current'
                  AND COALESCE(c.source_set, 'main') = 'main' AND COALESCE(c.origin, 'source') = 'source'
                GROUP BY c.class_name ORDER BY total DESC, c.class_name LIMIT 3""").mapToMap().list());
        List<String> hosts = new java.util.ArrayList<>();
        for (var row : rows) hosts.add(source(String.valueOf(row.get("class_name"))) + " (" + row.get("total") + ")");
        strings(fact, "application_producer_hosts", hosts);
    }

    static String simpleName(String className) {
        String name = source(className);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** buildPayload(io.x.Event<E, S>):java.lang.Object -> buildPayload(Event<E, S>) */
    static String shortSignature(String signature) {
        int close = signature.lastIndexOf(')');
        String head = close < 0 ? signature : signature.substring(0, close + 1);
        return head.replaceAll("(?:[\\p{L}_$][\\p{L}\\p{N}_$]*\\.)+([\\p{L}_$][\\p{L}\\p{N}_$]*)", "$1");
    }

    private static void strings(ObjectNode fact, String field, List<String> values) {
        if (values.isEmpty()) return;
        ArrayNode array = fact.putArray(field);
        values.stream().limit(LIMIT).forEach(array::add);
        if (values.size() > LIMIT) fact.putObject(field + "_truncated").put("shown", LIMIT).put("total", values.size());
    }

    private static void lines(ObjectNode fact, String field, List<Map<String, Object>> rows,
            java.util.function.Function<Map<String, Object>, String> line) {
        if (rows.isEmpty()) return;
        ArrayNode values = fact.putArray(field);
        rows.stream().limit(LIMIT).map(line).forEach(values::add);
        if (rows.size() > LIMIT) fact.putObject(field + "_truncated").put("shown", LIMIT).put("total", rows.size());
    }
}
