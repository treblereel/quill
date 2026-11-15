package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.BeanRepository.ClassInjection;
import org.treblereel.mcp.db.BeanRepository.InjectingPoint;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.ReferenceRepository.ClassConfiguration;
import org.treblereel.mcp.diagnostics.QuillLog;
import org.treblereel.mcp.mcp.config.ConfigurationSourceEvidence;
import org.treblereel.mcp.mcp.query.BeanEvidence;
import org.treblereel.mcp.mcp.support.BeanTerms;
import org.treblereel.mcp.model.ClassRecord;

/**
 * The CDI and test relations a class fact needs for a first-turn answer, one line per relation:
 * what the class injects, which injection points require or resolve to it, and which test classes
 * depend on it. Related source files are the production files that inject, implement or call the
 * class. Lines are indexed static evidence; runtime selection is not implied.
 */
final class ClassPrefetchRelations {

    private static final int LIMIT = 30;

    private ClassPrefetchRelations() {}

    /**
     * Declared versus inherited injection points; the index names an inherited field
     * Superclass.field. Null when nothing is inherited. V62 CQ-009: Claude read both superclasses
     * to tell them apart.
     */
    static String injectionOrigin(List<ClassInjection> rows) {
        List<String> declared = new ArrayList<>();
        Map<String, List<String>> inherited = new LinkedHashMap<>();
        for (var row : rows) {
            String name = String.valueOf(row.fieldName());
            int dot = name.lastIndexOf('.');
            if ("FIELD".equals(row.kind()) && dot > 0)
                inherited
                        .computeIfAbsent(name.substring(0, dot), ignored -> new ArrayList<>())
                        .add(name.substring(dot + 1));
            else if (!declared.contains(name)) declared.add(name);
        }
        if (inherited.isEmpty()) return null;
        List<String> owners = new ArrayList<>();
        inherited.forEach(
                (owner, fields) -> owners.add(owner + " (" + String.join(", ", fields) + ")"));
        return "declared here: "
                + (declared.isEmpty() ? "none" : String.join(", ", declared))
                + "; inherited: "
                + String.join(", ", owners);
    }

    private static final List<String> LIFECYCLE =
            List.of(
                    "jakarta.annotation.PostConstruct",
                    "jakarta.annotation.PreDestroy",
                    "javax.annotation.PostConstruct",
                    "javax.annotation.PreDestroy");

    /**
     * Instance fields and @PostConstruct/@PreDestroy methods: what a scope change keeps per
     * instance and when it is released. V62 CQ-045: Claude read SsarClientCache for its Cache field
     * and @PreDestroy shutdown().
     */
    static void appendStateAndLifecycle(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        var members = db.symbols.findFieldsAndMethods(cls.id());
        ArrayNode state = JSON.createArrayNode();
        ArrayNode lifecycle = JSON.createArrayNode();
        for (var member : members) {
            String modifiers = " " + member.modifiers() + " ";
            if ("FIELD".equals(member.kind())
                    && !modifiers.contains(" static ")
                    && !String.valueOf(member.name()).startsWith("$")) {
                if (state.size() < 8)
                    state.add(
                            member.name()
                                    + " "
                                    + source(member.typeName())
                                    + (modifiers.contains(" final ") ? " final" : ""));
            } else if ("METHOD".equals(member.kind())) {
                String annotations = String.valueOf(member.annotations());
                for (String annotation : LIFECYCLE)
                    if (annotations.contains("\"" + annotation + "\""))
                        lifecycle.add(
                                member.name()
                                        + "() @"
                                        + annotation.substring(annotation.lastIndexOf('.') + 1));
            }
        }
        if (!state.isEmpty()) fact.set("instance_state", state);
        if (!lifecycle.isEmpty()) fact.set("lifecycle_callbacks", lifecycle);
    }

    static void append(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        List<ClassInjection> injectionRows = db.beans.findClassInjections(cls.id());
        lines(fact, "injects", injectionRows, row -> injects(db, row));
        appendStateAndLifecycle(db, fact, cls);
        var expressions = org.treblereel.mcp.mcp.query.ExpressionReferences.forClass(db, cls);
        if (expressions != null) fact.set("bean_name_expressions", expressions);
        String origin = injectionOrigin(injectionRows);
        if (origin != null && fact.has("injects")) fact.put("injects_origin", origin);
        // A synthetic bean's own dependencies describe the bean; keep them in it, where the budget
        // never drops them.
        if (fact.path("bean") instanceof ObjectNode bean
                && "SYNTHETIC".equals(bean.path("kind").asText())
                && fact.remove("injects")
                        instanceof com.fasterxml.jackson.databind.node.ArrayNode injects)
            bean.set("injects", injects);
        lines(
                fact,
                "injected_into",
                db.beans.findInjectionPointsOf(cls.id(), cls.className()),
                ClassPrefetchRelations::injectedInto);
        lines(
                fact,
                "decorators",
                db.beans.findDecoratorsOf(cls.id(), cls.className()),
                row ->
                        row.className()
                                + " @Priority("
                                + (row.priority() == null ? "none" : row.priority())
                                + ")");
        if (fact.has("decorators"))
            fact.put(
                    "decorators_basis",
                    "@Decorator beans with this bean type; a lower priority is invoked first, around the decorated bean");
        lines(
                fact,
                "test_classes",
                db.usages.findTestSourceFilesDependingOn(cls.id()),
                String::valueOf);
        lines(
                fact,
                "related_source_files",
                db.usages.findRelatedSourceFiles(cls.id(), cls.className()),
                String::valueOf);
        ConfigurationSourceEvidence values = new ConfigurationSourceEvidence(db);
        lines(
                fact,
                "configuration",
                db.references.findClassConfiguration(cls.className()),
                row -> configuration(db, values, row));
        testInjections(db, fact, cls);
        hierarchy(db, fact, cls);
        producerHosts(db, fact, cls);
        if ("INTERFACE".equals(cls.kind())) {
            var callers = db.usages.findContractCallers(cls.id(), cls.className());
            lines(
                    fact,
                    "contract_callers",
                    callers,
                    row ->
                            row.className()
                                    + " calls "
                                    + compactCalls(String.valueOf(row.methods())));
            fact.put("contract_caller_classes", callers.size());
            var beans = db.beans.findCurrentBeansOfType(cls.className());
            lines(
                    fact,
                    "contract_beans",
                    beans,
                    row ->
                            row.className()
                                    + (row.memberName() == null ? "" : "#" + row.memberName())
                                    + " "
                                    + BeanTerms.kind((Object) row.framework(), row.kind())
                                    + " "
                                    + BeanTerms.scope((Object) row.framework(), row.scope())
                                    + " qualifiers "
                                    + BeanTerms.qualifiersJson(row.framework(), row.qualifiers())
                                    + " profiles "
                                    + row.profiles()
                                    + " priority "
                                    + row.priority());
            fact.put(
                    "contract_application_producer_count",
                    beans.stream()
                            .filter(row -> String.valueOf(row.kind()).startsWith("PRODUCER_"))
                            .count());
            fact.put(
                    "contract_beans_scope",
                    "All matching declarations in current application index; profile eligibility, dependency producers and runtime extensions not established");

            fact.put(
                    "contract_callers_scope",
                    "Distinct current production classes calling this interface or matching instance methods on direct implementors, including self-calls; inherited implementors and runtime dispatch are not covered");
        }
        ConfigMappingKeys.append(db, fact, cls);
        List<String> types = db.beans.findClassBeanInjectionTypes(cls.id());
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
        appendCandidateConditions(fact, candidates.valueStream().map(JsonNode::asText).toList());
    }

    /**
     * V62 CQ-014: a lookup condition alone sent Codex into ArC bytecode to learn what
     * Instance.get() does without the bean.
     */
    static void appendCandidateConditions(ObjectNode fact, List<String> candidates) {
        ObjectNode meanings = BeanEvidence.conditionMeanings(candidates);
        if (!meanings.isEmpty()) fact.set("candidate_conditions", meanings);
    }

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final List<String> EVENT =
            List.of("jakarta.enterprise.event.Event", "javax.enterprise.event.Event");
    private static final List<String> INSTANCE =
            List.of(
                    "jakarta.enterprise.inject.Instance",
                    "javax.enterprise.inject.Instance",
                    "io.quarkus.arc.InjectableInstance");
    private static final List<String> CONDITIONS =
            List.of(
                    "io.quarkus.arc.lookup.LookupIfProperty",
                    "io.quarkus.arc.lookup.LookupUnlessProperty",
                    "io.quarkus.arc.profile.IfBuildProfile",
                    "io.quarkus.arc.profile.UnlessBuildProfile",
                    "io.quarkus.arc.properties.IfBuildProperty",
                    "io.quarkus.arc.properties.UnlessBuildProperty",
                    "io.quarkus.arc.DefaultBean",
                    "jakarta.enterprise.inject.Alternative",
                    "jakarta.annotation.Priority");

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
        if (open < 0 || !type.endsWith(">") || !wrappers.contains(type.substring(0, open)))
            return null;
        return type.substring(open + 1, type.length() - 1).trim();
    }

    /**
     * Observer methods whose @Observes/@ObservesAsync parameter has exactly {@code event}'s type.
     */
    static List<String> observers(ReadQueryContext db, String event) {
        List<String> lines = new java.util.ArrayList<>();
        db.symbols
                .findEventObserverMembers(event)
                .forEach(
                        row -> {
                            try {
                                for (var annotation :
                                        JSON.readTree(String.valueOf(row.annotationDetails()))) {
                                    String name = annotation.path("annotationName").asText();
                                    if (!name.endsWith(".event.Observes")
                                            && !name.endsWith(".event.ObservesAsync")) continue;
                                    if (!sameType(annotation.path("parameterType").asText(), event))
                                        continue;
                                    String during =
                                            annotation.path("values").path("during").asText("");
                                    lines.add(
                                            row.className()
                                                    + "#"
                                                    + row.name()
                                                    + "(@"
                                                    + name.substring(name.lastIndexOf('.') + 1)
                                                    + " "
                                                    + source(event)
                                                    + (during.isEmpty()
                                                                    || "IN_PROGRESS".equals(during)
                                                            ? ""
                                                            : " during=" + during)
                                                    + ")");
                                }
                            } catch (Exception unreadable) {
                                QuillLog.swallowed(unreadable);
                                // An unreadable member row only drops that observer.
                            }
                        });
        return lines;
    }

    /**
     * Application beans whose bean types include {@code type}, with their lookup and enablement
     * conditions.
     */
    static List<String> typeCandidates(ReadQueryContext db, String type) {
        List<String> lines = new java.util.ArrayList<>();
        db.beans
                .findTypeCandidates(type)
                .forEach(
                        row -> {
                            List<String> conditions =
                                    db
                                            .symbols
                                            .findDirectClassAnnotationsByName(row.classId())
                                            .stream()
                                            .filter(
                                                    annotation ->
                                                            CONDITIONS.contains(
                                                                    String.valueOf(
                                                                            annotation
                                                                                    .annotationName())))
                                            .map(
                                                    annotation ->
                                                            "@"
                                                                    + annotation.annotationName()
                                                                    + values(
                                                                            annotation
                                                                                    .annotationValues()))
                                            .toList();
                            lines.add(
                                    BeanTerms.kind((Object) row.framework(), row.kind())
                                            + " "
                                            + row.className()
                                            + (row.memberName() == null
                                                    ? ""
                                                    : "#" + row.memberName())
                                            + " "
                                            + BeanTerms.scope((Object) row.framework(), row.scope())
                                            + " qualifiers "
                                            + BeanTerms.qualifiersJson(
                                                    row.framework(), row.qualifiers())
                                            + (conditions.isEmpty()
                                                    ? ""
                                                    : " " + String.join(" ", conditions))
                                            + (row.isDefault()
                                                            && conditions.stream()
                                                                    .noneMatch(
                                                                            c ->
                                                                                    c.contains(
                                                                                            "DefaultBean"))
                                                    ? " @DefaultBean"
                                                    : ""));
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
            node.properties()
                    .forEach(
                            entry -> {
                                if (!entry.getKey().startsWith("$")
                                        && !defaults.contains(entry.getKey()))
                                    set.add(
                                            entry.getKey()
                                                    + "="
                                                    + (entry.getValue().isTextual()
                                                            ? entry.getValue().asText()
                                                            : entry.getValue()));
                            });
            return set.isEmpty() ? "" : "(" + String.join(", ", set) + ")";
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
            return "";
        }
    }

    private static boolean sameType(String indexed, String wanted) {
        return indexed.equals(wanted) || indexed.replace('$', '.').equals(wanted.replace('$', '.'));
    }

    /**
     * Source spelling of a binary type name: Outer$Inner reads Outer.Inner; anonymous $1 stays.
     * Types only.
     */
    static String source(Object type) {
        return type == null ? "null" : String.valueOf(type).replaceAll("\\$(?=[A-Za-z_])", ".");
    }

    private static String configuration(
            ReadQueryContext db, ConfigurationSourceEvidence values, ClassConfiguration row) {
        if (row.file() != null) {
            String key = String.valueOf(row.key());
            boolean profile = "quarkus_test_profile".equals(row.kind());
            String value =
                    profile
                            ? declaredValue(
                                    values,
                                    key,
                                    String.valueOf(row.file()),
                                    row.line().intValue(),
                                    "quarkus_test_profile",
                                    String.valueOf(row.evidence()))
                            : null;
            StringBuilder line =
                    new StringBuilder(key)
                            .append(value == null ? "" : "=" + value)
                            .append(" [")
                            .append(row.kind())
                            .append(" declared")
                            .append(
                                    row.annotation() == null
                                            ? ""
                                            : " by " + source(row.annotation()))
                            .append("] ")
                            .append(row.file())
                            .append(':')
                            .append(row.line());
            // V32/V33 CQ-047: what a test profile overrides is half the answer; the production
            // declaration is the other.
            if (profile)
                for (var main : db.references.findMainPropertyDeclarations(key)) {
                    String declared =
                            declaredValue(
                                    values,
                                    key,
                                    String.valueOf(main.file()),
                                    main.line().intValue(),
                                    String.valueOf(main.kind()),
                                    String.valueOf(main.evidence()));
                    line.append("; main ")
                            .append(main.file())
                            .append(':')
                            .append(main.line())
                            .append(declared == null ? "" : "=" + declared);
                }
            return line.toString();
        }
        return row.key()
                + " ["
                + row.kind()
                + " read"
                + (row.member() == null ? "" : " by " + row.member())
                + " via @"
                + row.annotation()
                + "]";
    }

    /**
     * A safe declared scalar from hash-checked source or compiled profile, or null when it cannot
     * be shown.
     */
    static String declaredValue(
            ConfigurationSourceEvidence values,
            String key,
            String file,
            int line,
            String kind,
            String evidence) {
        ObjectNode reference = JSON.createObjectNode();
        values.append(
                reference,
                key,
                file,
                line,
                kind,
                evidence == null || "null".equals(evidence) ? "{}" : evidence);
        JsonNode declared = reference.path("declared_source");
        return "declared_scalar".equals(declared.path("status").asText())
                ? declared.path("declared_value").asText()
                : null;
    }

    private static final java.util.Set<String> TEST_INJECTIONS =
            java.util.Set.of(
                    "jakarta.inject.Inject",
                    "javax.inject.Inject",
                    "io.quarkus.test.InjectMock",
                    "io.quarkus.test.junit.mockito.InjectMock",
                    "io.quarkus.test.InjectSpy",
                    "io.quarkus.test.junit.mockito.InjectSpy",
                    "org.mockito.Mock",
                    "org.mockito.Spy");

    /**
     * For a test class, the fields the test container fills or replaces: injected beans, mocks and
     * spies, its own first, then those of its indexed superclasses with the declaring class.
     */
    private static void testInjections(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        if (!"test".equals(cls.sourceSet())) return;
        List<String> fields = new java.util.ArrayList<>();
        ClassRecord current = cls;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (current != null && seen.add(current.className())) {
            String owner = current == cls ? "" : " (" + simpleName(current.className()) + ")";
            for (var member : db.symbols.findClassMembers(current.id())) {
                if (!"FIELD".equals(member.kind())) continue;
                var marks =
                        member.annotations().stream()
                                .filter(TEST_INJECTIONS::contains)
                                .map(a -> "@" + simpleName(a))
                                .toList();
                if (marks.isEmpty()) continue;
                boolean replaces =
                        member.annotations().stream()
                                .anyMatch(
                                        a ->
                                                a.startsWith("io.quarkus.test.")
                                                        && (a.endsWith(".InjectMock")
                                                                || a.endsWith(".InjectSpy")));
                fields.add(
                        String.join(" ", marks)
                                + " "
                                + simpleName(member.typeName())
                                + " "
                                + member.name()
                                + owner
                                + (replaces
                                        ? replacedBean(db, member.typeName(), marks.getFirst())
                                        : ""));
            }
            String parent = current.superclass();
            current =
                    parent == null || "java.lang.Object".equals(parent)
                            ? null
                            : db.symbols.findClassesByName(parent).stream()
                                    .findFirst()
                                    .orElse(null);
        }
        strings(fact, "test_injections", fields);
    }

    private static final java.util.Set<String> PSEUDO_SCOPES =
            java.util.Set.of("@Singleton", "@Dependent");

    /**
     * The default-qualified bean of that type a Quarkus mock or spy replaces, application beans
     * first, and the documented rule that a pseudo-scoped bean needs convertScopes (V41/V62 CQ-047:
     * Codex called list_beans for each mocked type in every session).
     */
    private static String replacedBean(ReadQueryContext db, String type, String mark) {
        var rows = db.beans.findDefaultProviders(type);
        if (rows.isEmpty()) return " replaces no indexed default bean";
        var row = rows.getFirst();
        String scope = row.scope() == null ? "@Dependent" : String.valueOf(row.scope());
        return " replaces "
                + BeanTerms.kind((Object) row.framework(), row.kind())
                + " "
                + row.className()
                + (row.memberName() == null ? "" : "#" + row.memberName())
                + " "
                + BeanTerms.scope((Object) row.framework(), scope)
                + (row.artifact() == null ? "" : " from " + row.artifact())
                + (rows.size() > 1 ? " (+" + (rows.size() - 1) + " other default providers)" : "")
                + (PSEUDO_SCOPES.contains(scope) && mark.equals("@InjectMock")
                        ? "; "
                                + scope
                                + " is a pseudo-scope: @InjectMock needs @MockitoConfig(convertScopes = true)"
                        : "");
    }

    private static String injects(ReadQueryContext db, ClassInjection row) {
        String resolved =
                "BUILTIN_BEAN".equals(row.resolutionReason())
                        ? BeanTerms.spring(String.valueOf(row.ownerFramework()))
                                ? "supplied by the Spring context itself (resolvable dependency, no bean definition)"
                                : "built-in bean"
                        : row.resolvedClass() == null
                                ? "not resolved by the application index"
                                        + MethodPrefetchRelations.dependencyProviders(
                                                db,
                                                String.valueOf(row.targetType()),
                                                row.qualifiers())
                                : row.resolvedClass()
                                        + (row.resolvedMember() == null
                                                ? ""
                                                : "#" + row.resolvedMember())
                                        + " ("
                                        + BeanTerms.kind(
                                                (Object) row.resolvedFramework(),
                                                row.resolvedKind())
                                        + (row.resolvedScope() == null
                                                ? ""
                                                : " "
                                                        + BeanTerms.scope(
                                                                (Object) row.resolvedFramework(),
                                                                row.resolvedScope()))
                                        + ")"
                                        + (row.resolvedDefault() ? " @DefaultBean" : "");
        return row.kind()
                + " "
                + row.fieldName()
                + ": "
                + source(row.targetType())
                + qualifiers(row.ownerFramework(), row.qualifiers())
                + " -> "
                + resolved
                + " ["
                + row.resolutionStatus()
                + "]";
    }

    /**
     * Called methods as name(ShortTypes), at about half the cost of descriptors; a selector
     * matching two overloads is rejected.
     */
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
            org.objectweb.asm.Type[] types =
                    org.objectweb.asm.Type.getArgumentTypes(call.substring(open));
            for (int i = 0; i < types.length; i++) {
                String type = types[i].getClassName();
                compact.append(i == 0 ? "" : ",")
                        .append(
                                type.substring(
                                        Math.max(type.lastIndexOf('.'), type.lastIndexOf('$'))
                                                + 1));
            }
            return compact.append(')').toString();
        } catch (RuntimeException unreadable) {
            QuillLog.swallowed(unreadable);
            return call;
        }
    }

    /**
     * The owner's scope and the owner methods that call into this class: in V24-V27 CQ-045 Claude
     * read SsarAuthorizer about once per session to learn both before answering what a narrower
     * scope would change.
     */
    private static String injectedInto(InjectingPoint row) {
        String owner = row.owner() + (row.ownerMember() == null ? "" : "#" + row.ownerMember());
        boolean decorator = "DECORATOR".equals(row.ownerKind());
        Object scope = row.ownerScope();
        java.util.TreeSet<String> methods = new java.util.TreeSet<>();
        if (row.usedIn() != null) {
            for (String method : String.valueOf(row.usedIn()).split(",")) {
                // A lambda body belongs to the method that declares it.
                java.util.regex.Matcher lambda = LAMBDA.matcher(method);
                methods.add(lambda.matches() ? lambda.group(1) : method);
            }
        }
        return owner
                + "#"
                + row.fieldName()
                + " ("
                + row.kind()
                + " "
                + source(row.targetType())
                + ")"
                + qualifiers(row.ownerFramework(), row.qualifiers())
                + " ["
                + row.resolutionStatus()
                + "]"
                + (!decorator && scope != null
                        ? " owner " + BeanTerms.scope((Object) row.ownerFramework(), scope)
                        : "")
                + (methods.isEmpty() ? "" : ", uses it in " + String.join(", ", methods))
                + (decorator
                        ? " in a decorator: a delegate point resolves to the decorated bean"
                        : "");
    }

    private static final java.util.regex.Pattern LAMBDA =
            java.util.regex.Pattern.compile("lambda\\$(.+?)\\$\\d+");

    private static String qualifiers(String framework, String qualifiers) {
        Object value = BeanTerms.qualifiersJson(framework, qualifiers);
        return value == null || "[]".equals(value) ? "" : " " + value;
    }

    /**
     * What a class takes from its indexed superclass chain: each own method that redeclares an
     * inherited one (and where it was declared, abstract or not), and each inherited instance
     * method it keeps unchanged, from the nearest superclass declaring it; stated only when it
     * overrides something. Matched by name and erased descriptor; interfaces and unindexed
     * superclasses are not walked.
     */
    private static void hierarchy(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        record Method(String name, String descriptor, String signature, boolean isAbstract) {
            String key() {
                return name + descriptor;
            }
        }
        List<String> chain = new java.util.ArrayList<>();
        Map<String, List<Method>> declared = new java.util.LinkedHashMap<>();
        java.util.function.Function<String, List<Method>> methods =
                name ->
                        db.symbols.findInheritableMethods(name).stream()
                                .map(
                                        row ->
                                                new Method(
                                                        String.valueOf(row.name()),
                                                        String.valueOf(row.descriptor()),
                                                        String.valueOf(row.signature()),
                                                        String.valueOf(row.modifiers())
                                                                .contains("abstract")))
                                .toList();
        String parent = cls.superclass();
        java.util.Set<String> seen = new java.util.HashSet<>(List.of(cls.className()));
        while (parent != null && !"java.lang.Object".equals(parent) && seen.add(parent)) {
            String name = parent;
            List<String> superclasses = db.symbols.findCurrentSuperclasses(name);
            if (superclasses.isEmpty()) break;
            chain.add(name);
            declared.put(name, methods.apply(name));
            parent = superclasses.getFirst();
        }
        if (chain.isEmpty()) return;
        List<Method> own = methods.apply(cls.className());
        java.util.Set<String> ownKeys = new java.util.HashSet<>();
        own.forEach(method -> ownKeys.add(method.key()));
        List<String> overrides = new java.util.ArrayList<>();
        for (Method method : own) {
            List<String> sources = new java.util.ArrayList<>();
            for (String ancestor : chain)
                declared.get(ancestor).stream()
                        .filter(other -> other.key().equals(method.key()))
                        .findFirst()
                        .ifPresent(
                                other ->
                                        sources.add(
                                                simpleName(ancestor)
                                                        + (other.isAbstract() ? " abstract" : "")));
            if (!sources.isEmpty())
                overrides.add(
                        shortSignature(method.signature())
                                + " overrides "
                                + String.join(", ", sources));
        }
        ObjectNode inherited = JSON.createObjectNode();
        java.util.Set<String> taken = new java.util.HashSet<>(ownKeys);
        for (String ancestor : chain) {
            List<String> kept = new java.util.ArrayList<>();
            for (Method method : declared.get(ancestor))
                if (taken.add(method.key()) && !method.isAbstract())
                    kept.add(shortSignature(method.signature()));
            if (!kept.isEmpty()) inherited.put(simpleName(ancestor), String.join(", ", kept));
        }
        // A class that redeclares nothing has no override story; its inherited API is one detail
        // call away.
        if (overrides.isEmpty()) return;
        var names = fact.putArray("superclass_chain");
        chain.forEach(name -> names.add(source(name)));
        strings(fact, "overrides", overrides);
        if (!inherited.isEmpty()) fact.set("inherited_methods", inherited);
        java.util.Set<String> overridden = new java.util.HashSet<>();
        for (Method method : own)
            if (chain.stream()
                    .anyMatch(
                            ancestor ->
                                    declared.get(ancestor).stream()
                                            .anyMatch(other -> other.key().equals(method.key()))))
                overridden.add(method.key());
        strings(fact, "declared_methods", declaredMethods(db, cls, overridden));
        fact.put(
                "overrides_basis",
                "Same name and erased descriptor in the indexed superclass chain, nearest first; interfaces not walked");
    }

    /**
     * The class's own methods that override nothing: with the overrides and inherited methods they
     * complete what the class declares, so no member listing is needed. Annotations, including
     * parameter annotations such as @Observes, and private/static stay; synthetic methods do not.
     */
    private static List<String> declaredMethods(
            ReadQueryContext db, ClassRecord cls, java.util.Set<String> overridden) {
        List<String> result = new java.util.ArrayList<>();
        for (var member : db.symbols.findClassMembers(cls.id())) {
            String modifiers = member.modifiers() == null ? "" : member.modifiers();
            if (!"METHOD".equals(member.kind())
                    || overridden.contains(member.name() + member.descriptor())
                    || member.name().startsWith("lambda$")
                    || member.name().startsWith("access$")
                    || modifiers.contains("synthetic")
                    || modifiers.contains("bridge")) continue;
            String signature = shortSignature(member.signature());
            int open = signature.indexOf('(');
            List<String> parameters =
                    splitParameters(signature.substring(open + 1, signature.length() - 1));
            for (var annotation : member.annotationDetails())
                if (annotation.parameterIndex() != null
                        && annotation.parameterIndex() < parameters.size())
                    parameters.set(
                            annotation.parameterIndex(),
                            "@"
                                    + simpleName(annotation.annotationName())
                                    + " "
                                    + parameters.get(annotation.parameterIndex()));
            StringBuilder line = new StringBuilder();
            java.util.Set<String> onParameters = new java.util.HashSet<>();
            member.annotationDetails().stream()
                    .filter(annotation -> annotation.parameterIndex() != null)
                    .forEach(annotation -> onParameters.add(annotation.annotationName()));
            // The index also lists parameter annotations among the method's; state each once, where
            // it is.
            member.annotations().stream()
                    .filter(annotation -> !onParameters.contains(annotation))
                    .forEach(
                            annotation ->
                                    line.append('@').append(simpleName(annotation)).append(' '));
            line.append(signature, 0, open + 1).append(String.join(",", parameters)).append(')');
            for (String modifier : List.of("private", "static", "abstract"))
                if (modifiers.contains(modifier)) line.append(' ').append(modifier);
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
            else if (c == ',' && depth == 0) {
                result.add(parameters.substring(start, i).trim());
                start = i + 1;
            }
        }
        result.add(parameters.substring(start).trim());
        return result;
    }

    /**
     * For a class bean, the application classes that already declare producers, with how many:
     * where a producer for this bean would follow the project's convention. Production source only,
     * most producers first.
     */
    private static void producerHosts(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        boolean classBean = db.beans.hasOwnClassBean(cls.id());
        if (!classBean) return;
        var rows = db.beans.findTopProducerHosts();
        List<String> hosts = new java.util.ArrayList<>();
        for (var row : rows)
            hosts.add(source(String.valueOf(row.className())) + " (" + row.total() + ")");
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
        return head.replaceAll(
                "(?:[\\p{L}_$][\\p{L}\\p{N}_$]*\\.)+([\\p{L}_$][\\p{L}\\p{N}_$]*)", "$1");
    }

    private static void strings(ObjectNode fact, String field, List<String> values) {
        if (values.isEmpty()) return;
        ArrayNode array = fact.putArray(field);
        values.stream().limit(LIMIT).forEach(array::add);
        if (values.size() > LIMIT)
            fact.putObject(field + "_truncated").put("shown", LIMIT).put("total", values.size());
    }

    private static <T> void lines(
            ObjectNode fact,
            String field,
            List<T> rows,
            java.util.function.Function<T, String> line) {
        if (rows.isEmpty()) return;
        ArrayNode values = fact.putArray(field);
        rows.stream().limit(LIMIT).map(line).forEach(values::add);
        if (rows.size() > LIMIT)
            fact.putObject(field + "_truncated").put("shown", LIMIT).put("total", rows.size());
    }
}
