package org.treblereel.mcp.mcp.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.MemberAnnotationRecord;

/**
 * Registration conditions and ArC retention signals derived from indexed annotation values. Agents
 * previously equated a configuration branch with a lookup condition that compares differently, or
 * claimed unconditional retention from a scope alone.
 */
public final class BeanEvidence {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final Map<String, String> CONDITIONS =
            Map.ofEntries(
                    Map.entry(
                            "io.quarkus.arc.lookup.LookupIfProperty",
                            "Programmatic lookup (Instance) only: available when property `name` equals `stringValue` "
                                    + "(String.equals, case-sensitive); lookupIfMissing defaults to false. With no other available bean, "
                                    + "Instance.get() throws UnsatisfiedResolutionException. @Inject resolution is unaffected"),
                    Map.entry(
                            "io.quarkus.arc.lookup.LookupUnlessProperty",
                            "Programmatic lookup (Instance) only: hidden when property `name` equals `stringValue` "
                                    + "(String.equals, case-sensitive); with no other available bean, Instance.get() throws "
                                    + "UnsatisfiedResolutionException. @Inject resolution is unaffected"),
                    Map.entry(
                            "io.quarkus.arc.properties.IfBuildProperty",
                            "Build time: the bean is removed unless property `name` equals `stringValue`; enableIfMissing defaults to false"),
                    Map.entry(
                            "io.quarkus.arc.properties.UnlessBuildProperty",
                            "Build time: the bean is removed when property `name` equals `stringValue`"),
                    Map.entry(
                            "io.quarkus.arc.profile.IfBuildProfile",
                            "Build time: kept only for the listed profiles"),
                    Map.entry(
                            "io.quarkus.arc.profile.UnlessBuildProfile",
                            "Build time: removed for the listed profiles"),
                    Map.entry(
                            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                            "Startup: registered when the property equals `havingValue` (case-insensitive; any value but false when empty); matchIfMissing defaults to false"),
                    Map.entry(
                            "org.springframework.boot.autoconfigure.condition.ConditionalOnBean",
                            "Startup: registered only if beans of the listed types or names already exist when "
                                    + "this definition is processed; order-sensitive, reliable only in auto-configuration"),
                    Map.entry(
                            "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
                            "Startup: registered only if no bean of the listed types (default: the @Bean return "
                                    + "type) exists yet; typical for overridable auto-configuration defaults"),
                    Map.entry(
                            "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
                            "Startup: registered only when the listed classes are on the classpath"),
                    Map.entry(
                            "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass",
                            "Startup: registered only when the listed classes are absent from the classpath"),
                    Map.entry(
                            "org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication",
                            "Startup: registered only in a web application of the listed type"),
                    Map.entry(
                            "org.springframework.context.annotation.Profile",
                            "Startup: registered only for the listed profiles"),
                    Map.entry(
                            "org.springframework.context.annotation.Conditional",
                            "Startup: registered when the listed Condition classes match"));
    private static final String UNREMOVABLE = "io.quarkus.arc.Unremovable";
    private static final List<String> ROOT_ANNOTATIONS =
            List.of(
                    "io.quarkus.runtime.Startup",
                    "jakarta.ws.rs.Path",
                    "io.quarkus.scheduler.Scheduled");

    /** Supertypes whose beans a Quarkus extension keeps as roots, with the extension's role. */
    private static final java.util.Map<String, String> FRAMEWORK_ROOTS =
            java.util.Map.of(
                    "io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism",
                    "Quarkus security root: HttpAuthenticationMechanism",
                    "io.quarkus.security.identity.IdentityProvider",
                    "Quarkus security root: IdentityProvider",
                    "io.quarkus.security.identity.SecurityIdentityAugmentor",
                    "Quarkus security root: SecurityIdentityAugmentor",
                    "io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy",
                    "Quarkus security root: HttpSecurityPolicy");

    private static final int CHAIN = 4;

    private BeanEvidence() {}

    /**
     * Why a class bean is discovered: its bean-defining annotation, stereotypes, beans.xml and
     * quarkus.arc.exclude-types. V62 CQ-003: Codex grepped these by hand in every session.
     */
    static String discovery(
            org.treblereel.mcp.db.ReadQueryContext db,
            BeanRecord bean,
            List<ClassAnnotationRecord> annotations) {
        // ArC discovery rules; Apollo v2 AQ-005: on a Spring bean they misled the agent.
        if (!"CLASS".equals(bean.kind())
                || bean.scope() == null
                || BeanRecord.SPRING.equals(bean.framework())) return null;
        String scope = bean.scope().substring(1);
        List<String> defining =
                annotations.stream()
                        .filter(ClassAnnotationRecord::direct)
                        .map(ClassAnnotationRecord::annotationName)
                        .filter(name -> name.substring(name.lastIndexOf('.') + 1).equals(scope))
                        .distinct()
                        .map(name -> "@" + name.substring(name.lastIndexOf('.') + 1))
                        .toList();
        List<String> stereotypes = bean.stereotypes() == null ? List.of() : bean.stereotypes();
        List<String> beansXml = db.index.findBeansXmlFiles();
        List<String> excludes = db.references.findArcExcludeTypesDeclarations();
        return (defining.isEmpty()
                        ? "scope "
                                + bean.scope()
                                + " not declared directly on the class (inherited or via stereotype)"
                        : "bean-defining annotation "
                                + String.join(", ", defining)
                                + " on the class")
                + "; stereotypes: "
                + (stereotypes.isEmpty() ? "none" : String.join(", ", stereotypes))
                + "; ArC uses annotated discovery and indexes application classes as one bean archive, "
                + (beansXml.isEmpty()
                        ? "no META-INF/beans.xml needed"
                        : String.join(", ", beansXml)
                                + " present (its bean-discovery-mode is ignored)")
                + "; "
                + (excludes.isEmpty()
                        ? "no quarkus.arc.exclude-types declared"
                        : "quarkus.arc.exclude-types: " + String.join(", ", excludes));
    }

    /**
     * Whether ArC's unused-bean removal can drop a class bean: the indexed reasons it is kept, the
     * removal setting and whether the last indexed build generated its _Bean. V66 CQ-005: Codex
     * read deployment processors and searched the web for these rules in every session.
     */
    static String removal(
            org.treblereel.mcp.db.ReadQueryContext db,
            BeanRecord bean,
            List<ClassAnnotationRecord> annotations,
            String className) {
        if (!"CLASS".equals(bean.kind()) || BeanRecord.SPRING.equals(bean.framework())) return null;
        List<String> owners = db.beans.findInjectionPointOwners(bean.id());
        List<String> kept = new ArrayList<>();
        if (!owners.isEmpty())
            kept.add(
                    "injected into "
                            + String.join(", ", owners.subList(0, Math.min(3, owners.size())))
                            + (owners.size() > 3 ? " (+" + (owners.size() - 3) + " more)" : ""));
        var members = db.symbols.findObserverAndProducerMembers(bean.classId());
        for (var member : members) {
            String role =
                    String.valueOf(member.annotations()).contains(".event.Observes")
                            ? "observer method "
                            : "producer member ";
            if (kept.size() < 4) kept.add(role + member.name());
        }
        if (annotations.stream()
                .anyMatch(annotation -> annotation.annotationName().equals(UNREMOVABLE)))
            kept.add("@Unremovable");
        if (bean.qualifiers() != null
                && bean.qualifiers().stream().anyMatch(q -> q.startsWith("@Named")))
            kept.add("@Named");
        annotations.stream()
                .map(ClassAnnotationRecord::annotationName)
                .filter(ROOT_ANNOTATIONS::contains)
                .distinct()
                .forEach(name -> kept.add("@" + name.substring(name.lastIndexOf('.') + 1)));
        String chain = owners.isEmpty() ? null : injectorChain(db, bean);
        if (chain != null) kept.add("injector chain: " + chain);
        List<String> setting = db.references.findArcRemoveUnusedBeansDeclarations();
        String text =
                (kept.isEmpty()
                                ? "removal not ruled out: no indexed injection point, observer or producer member, @Named, @Unremovable "
                                        + "or root annotation keeps it; Instance/@All lookups and extension-registered roots also keep a bean"
                                : "kept: "
                                        + String.join("; ", kept)
                                        + " (ArC removes only a bean nothing injects, observes through, produces "
                                        + "from, names, marks @Unremovable or registers as a root)")
                        + "; "
                        + (setting.isEmpty()
                                ? "quarkus.arc.remove-unused-beans not declared (default all)"
                                : "quarkus.arc.remove-unused-beans: " + String.join(", ", setting));
        String generated =
                db.index
                        .getMetadata()
                        .get(org.treblereel.mcp.core.GeneratedBytecodeScanner.METADATA_KEY);
        if (generated != null) {
            String beanClass = className + "_Bean";
            text +=
                    generated.contains("\"" + beanClass + "\"")
                            ? "; the last indexed Quarkus build generated "
                                    + beanClass
                                    + ", so that build kept it"
                            : "; no generated "
                                    + beanClass
                                    + " in the last indexed Quarkus build output";
        }
        return text;
    }

    /**
     * The injectors above a bean, followed until one is kept for its own sake: "A <- B (why)", or
     * the last one nothing keeps. Cryostat CQ-005: "injected into SsarAuthorizer" left open whether
     * the injector survives, and Codex read deployment processor bytecode to find out.
     */
    private static String injectorChain(org.treblereel.mcp.db.ReadQueryContext db, BeanRecord bean) {
        List<String> path = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>(List.of(bean.id()));
        BeanRecord current = bean;
        for (int step = 0; step < CHAIN; step++) {
            List<String> owners = db.beans.findInjectionPointOwners(current.id());
            BeanRecord next = null;
            String nextName = null;
            for (String owner : owners) {
                String className = owner.contains("#") ? owner.substring(0, owner.indexOf('#')) : owner;
                var cls = db.symbols.findClassByName(className).orElse(null);
                var ownerBean = cls == null ? null : db.beans.findBeanByClassId(cls.id()).orElse(null);
                if (ownerBean == null || !seen.add(ownerBean.id())) continue;
                String reason = ownRoot(db, ownerBean, cls);
                if (reason != null) {
                    path.add(simple(className));
                    return String.join(" <- ", path) + " (" + reason + ")";
                }
                if (next == null) {
                    next = ownerBean;
                    nextName = className;
                }
            }
            if (next == null)
                return path.isEmpty() ? null
                        : String.join(" <- ", path) + ", which no indexed injector or root keeps";
            path.add(simple(nextName));
            current = next;
        }
        return String.join(" <- ", path) + " (no root within " + CHAIN + " injectors)";
    }

    /** Why ArC keeps a bean without any injector, or null. */
    private static String ownRoot(
            org.treblereel.mcp.db.ReadQueryContext db, BeanRecord bean, org.treblereel.mcp.model.ClassRecord cls) {
        for (String type : cls.interfaces()) if (FRAMEWORK_ROOTS.containsKey(type)) return FRAMEWORK_ROOTS.get(type);
        if (FRAMEWORK_ROOTS.containsKey(cls.superclass())) return FRAMEWORK_ROOTS.get(cls.superclass());
        for (var annotation : db.symbols.findClassAnnotations(cls.id())) {
            String name = annotation.annotationName();
            if (name.equals(UNREMOVABLE) || ROOT_ANNOTATIONS.contains(name))
                return "@" + name.substring(name.lastIndexOf('.') + 1);
        }
        if (bean.qualifiers() != null && bean.qualifiers().stream().anyMatch(q -> q.startsWith("@Named"))) return "@Named";
        for (var member : db.symbols.findObserverAndProducerMembers(cls.id()))
            if (String.valueOf(member.annotations()).contains(".event.Observes")) return "observer method " + member.name();
        return null;
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /**
     * {@code @Simple -> meaning} for each condition annotation a candidate line names, once per
     * answer.
     */
    public static ObjectNode conditionMeanings(Iterable<String> lines) {
        ObjectNode meanings = JSON.createObjectNode();
        for (String line : lines) {
            CONDITIONS.forEach(
                    (annotation, meaning) -> {
                        if (line.contains("@" + annotation + "("))
                            meanings.put(
                                    "@" + annotation.substring(annotation.lastIndexOf('.') + 1),
                                    meaning);
                    });
        }
        return meanings;
    }

    /**
     * @param declaringMembers members of the class declaring a producer; its conditions sit on the
     *     producer method or field, not on the bean class
     */
    static void appendConditions(
            ObjectNode node,
            BeanRecord bean,
            List<ClassAnnotationRecord> annotations,
            List<ClassMemberRecord> declaringMembers) {
        ArrayNode conditions = JSON.createArrayNode();
        if (bean.memberName() == null) {
            for (ClassAnnotationRecord annotation : annotations) {
                if (annotation.direct())
                    addCondition(
                            conditions, annotation.annotationName(), annotation.values(), "class");
            }
        } else {
            for (ClassMemberRecord member : declaringMembers) {
                if (!member.name().equals(bean.memberName())) continue;
                for (MemberAnnotationRecord annotation : member.annotationDetails()) {
                    if (annotation.parameterIndex() == null) {
                        addCondition(
                                conditions,
                                annotation.annotationName(),
                                annotation.values(),
                                member.kind().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        }
        if (!conditions.isEmpty()) node.set("registration_conditions", conditions);
    }

    private static void addCondition(
            ArrayNode conditions,
            String annotation,
            Map<String, Object> values,
            String declaredOn) {
        String meaning = CONDITIONS.get(annotation);
        if (meaning == null) return;
        ObjectNode condition =
                conditions.addObject().put("annotation", annotation).put("declared_on", declaredOn);
        condition.set("values", JSON.valueToTree(values));
        condition.put("meaning", meaning);
    }

    /**
     * Positive reasons ArC keeps the bean when unused-bean removal is enabled (Quarkus default).
     */
    static void appendRetention(
            ObjectNode node,
            BeanRecord bean,
            int injectedInto,
            List<ClassAnnotationRecord> annotations) {
        if (bean.memberName() != null || "SYNTHETIC".equals(bean.kind())) return;
        List<String> signals = new ArrayList<>();
        if (injectedInto > 0) signals.add("injected_into:" + injectedInto);
        if (annotations.stream()
                .anyMatch(annotation -> annotation.annotationName().equals(UNREMOVABLE)))
            signals.add("unremovable");
        if (bean.qualifiers() != null
                && bean.qualifiers().stream().anyMatch(q -> q.startsWith("@Named")))
            signals.add("named");
        annotations.stream()
                .map(ClassAnnotationRecord::annotationName)
                .filter(ROOT_ANNOTATIONS::contains)
                .distinct()
                .forEach(name -> signals.add("root:" + name));
        node.set("arc_retention", JSON.valueToTree(signals));
    }

    /** What removal_ruled_out means, written once per response instead of on every bean. */
    static void appendRetentionNotes(ObjectNode root) {
        if (root.path("beans").valueStream().noneMatch(bean -> bean.has("arc_retention"))) return;
        root.putObject("arc_retention_notes")
                .put(
                        "listed",
                        "Indexed reasons ArC keeps the bean; any one rules out removal of an unused bean")
                .put(
                        "empty",
                        "No indexed retention signal; removal not ruled out. ArC also keeps beans with observer or "
                                + "producer members, beans used through Instance/@All, and extension-registered "
                                + "roots; a normal scope or client proxy alone does not keep a bean");
    }
}
