package org.treblereel.mcp.db;

import static org.treblereel.mcp.db.SymbolRepository.fromJson;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.treblereel.mcp.model.*;

/** Read-only bean queries; the caller owns the handle and transaction. */
public final class BeanRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Handle handle;

    public BeanRepository(Handle handle) { this.handle = Objects.requireNonNull(handle); }

    public List<BeanRecord> findBeans(Map<String, String> filter) {
            var sb = new StringBuilder("SELECT b.*, c.class_name FROM beans b "
                    + "JOIN classes c ON b.class_id = c.id "
                    + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'");

            if (filter != null) {
                if (filter.containsKey("class_name")) sb.append(" AND c.class_name LIKE :className");
                if (filter.containsKey("scope")) sb.append(" AND b.scope = :scope");
                if (filter.containsKey("kind")) sb.append(" AND b.kind = :kind");
                if (filter.containsKey("module")) {
                    sb.append(" AND (EXISTS (SELECT 1 FROM class_occurrences co "
                            + "WHERE co.class_id = c.id AND co.module = :module) OR "
                            + "(NOT EXISTS (SELECT 1 FROM class_occurrences co "
                            + "WHERE co.class_id = c.id) AND c.module = :module))");
                }
                if (filter.containsKey("source_set")) {
                    sb.append(" AND (EXISTS (SELECT 1 FROM class_occurrences co "
                            + "WHERE co.class_id = c.id AND co.source_set = :sourceSet) OR "
                            + "(NOT EXISTS (SELECT 1 FROM class_occurrences co "
                            + "WHERE co.class_id = c.id) AND c.source_set = :sourceSet))");
                }
            }

            var q = handle.createQuery(sb.toString());
            if (filter != null) {
                if (filter.containsKey("class_name")) q.bind("className", filter.get("class_name").replace("*", "%"));
                if (filter.containsKey("scope")) q.bind("scope", filter.get("scope"));
                if (filter.containsKey("kind")) q.bind("kind", filter.get("kind"));
                if (filter.containsKey("module")) q.bind("module", filter.get("module"));
                if (filter.containsKey("source_set")) q.bind("sourceSet", filter.get("source_set"));
            }

            List<BeanRecord> results = q.map((rs, ctx) -> mapBean(rs)).list();

            if (filter != null && filter.containsKey("bean_type")) {
                results = results.stream().filter(b -> matchesBeanType(
                        b.beanTypes(), filter.get("bean_type"))).toList();
            }

            if (filter != null && filter.containsKey("profile")) {
                String requestedProfile = filter.get("profile");
                results = results.stream()
                        .filter(b -> matchesProfile(b.profiles(), requestedProfile))
                        .toList();
            }

            return filter != null && filter.containsKey("qualifier") ? results.stream()
                    .filter(b -> matchesQualifier(b.qualifiers(), filter.get("qualifier"))).toList() : results;
    }

    public List<ExternalBeanRecord> findExternalBeans(Map<String, String> filter) {
            StringBuilder sql = new StringBuilder("SELECT * FROM external_beans WHERE 1=1");
            if (filter != null) {
                if (filter.containsKey("class_name")) {
                    String name = filter.get("class_name");
                    if (name.contains("*")) sql.append(" AND class_name LIKE :className");
                    else if (name.contains(".")) sql.append(" AND class_name = :className");
                    else sql.append(" AND (class_name = :className OR substr(class_name, -length(:className)-1) IN ('.' || :className, '$' || :className))");
                }
                if (filter.containsKey("scope")) sql.append(" AND scope = :scope");
                if (filter.containsKey("kind")) sql.append(" AND kind = :kind");
            }
            sql.append(" ORDER BY class_name, framework, kind, member_name");
            var query = handle.createQuery(sql.toString());
            if (filter != null) {
                if (filter.containsKey("class_name")) {
                    String value = filter.get("class_name");
                    String pattern = value.replace('*', '%');
                    query.bind("className", pattern);
                }
                if (filter.containsKey("scope")) query.bind("scope", filter.get("scope"));
                if (filter.containsKey("kind")) query.bind("kind", filter.get("kind"));
            }
            var beans = query.map((rs, ctx) -> new ExternalBeanRecord(
                    rs.getInt("id"), rs.getString("class_name"), rs.getString("kind"),
                    rs.getString("scope"), fromJson(rs.getString("qualifiers")),
                    fromJson(rs.getString("stereotypes")),
                    rs.getInt("is_alternative") != 0, rs.getInt("is_default") != 0,
                    rs.getObject("priority") == null ? null : rs.getInt("priority"),
                    fromJson(rs.getString("profiles")), rs.getString("member_name"),
                    fromJson(rs.getString("bean_types")), rs.getString("framework"),
                    rs.getString("artifact"), rs.getString("jar_path"),
                    externalInjectionsFromJson(rs.getString("injection_points")),
                    fromJson(rs.getString("interceptor_bindings")))).list();
            return beans.stream()
                    .filter(b -> filter == null || !filter.containsKey("bean_type") || matchesBeanType(b.beanTypes(), filter.get("bean_type")))
                    .filter(b -> filter == null || !filter.containsKey("profile") || matchesProfile(b.profiles(), filter.get("profile")))
                    .filter(b -> filter == null || !filter.containsKey("qualifier") || matchesQualifier(b.qualifiers(), filter.get("qualifier"))).toList();
    }

    public static boolean matchesBeanType(List<String> types, String requested) {
        if (types == null || requested == null) return false;
        return types.stream().anyMatch(type -> type.equals(requested)
                || !requested.contains(".") && (type.endsWith("." + requested) || type.endsWith("$" + requested)));
    }

    public static boolean matchesQualifier(List<String> available, String requested) {
        if (available == null || requested == null) return false;
        String selector = requested.startsWith("@") ? requested.substring(1) : requested;
        for (String value : available) {
            String actual = value.startsWith("@") ? value.substring(1) : value;
            int end = actual.indexOf('(');
            String name = end < 0 ? actual : actual.substring(0,end);
            String simple = name.substring(Math.max(name.lastIndexOf('.'),name.lastIndexOf('$')) + 1);
            if (selector.equals(actual) || selector.equals(name) || selector.equals(simple)
                    || end >= 0 && selector.equals(simple + actual.substring(end))) return true;
        }
        return false;
    }

    public List<InjectionPointRecord> findInjectionPoints(int beanId) {
        return handle.createQuery("SELECT * FROM injection_points WHERE bean_id = :beanId")
                        .bind("beanId", beanId)
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list();
    }

    public Optional<BeanRecord> findBeanByClassId(int classId) {
        return handle.createQuery("SELECT * FROM beans WHERE class_id = :classId")
                        .bind("classId", classId)
                        .map((rs, ctx) -> mapBean(rs))
                        .findFirst();
    }

    public Optional<BeanRecord> findBeanById(int id) {
        return handle.createQuery("SELECT * FROM beans WHERE id = :id")
                        .bind("id", id)
                        .map((rs, ctx) -> mapBean(rs))
                        .findFirst();
    }

    public Map<Integer, BeanRecord> findBeansByIds(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        
            Map<Integer, BeanRecord> result = new LinkedHashMap<>();
            handle.createQuery("SELECT * FROM beans WHERE id IN (<ids>) ORDER BY id")
                    .bindList("ids", new LinkedHashSet<>(ids))
                    .map((rs, ctx) -> mapBean(rs))
                    .forEach(record -> result.put(record.id(), record));
            return result;
        
    }

    /** Number of injection points resolved to each bean. */
    public Map<Integer, Integer> countResolvedInjectionPoints(Collection<Integer> beanIds) {
        if (beanIds == null || beanIds.isEmpty()) return Map.of();
        Map<Integer, Integer> result = new LinkedHashMap<>();
        handle.createQuery("SELECT resolved_bean_id, count(*) AS points FROM injection_points "
                        + "WHERE resolved_bean_id IN (<ids>) GROUP BY resolved_bean_id")
                .bindList("ids", new LinkedHashSet<>(beanIds))
                .map((rs, ctx) -> Map.entry(rs.getInt("resolved_bean_id"), rs.getInt("points")))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    public Map<Integer, BeanRecord> findBeansByClassIds(Collection<Integer> classIds) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        
            Map<Integer, BeanRecord> result = new LinkedHashMap<>();
            handle.createQuery("SELECT * FROM beans WHERE class_id IN (<ids>) ORDER BY id")
                    .bindList("ids", new LinkedHashSet<>(classIds))
                    .map((rs, ctx) -> mapBean(rs))
                    .forEach(record -> result.putIfAbsent(record.classId(), record));
            return result;
        
    }

    static BeanRecord mapBean(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BeanRecord(
                rs.getInt("id"), rs.getInt("class_id"), rs.getString("kind"),
                rs.getString("scope"), fromJson(rs.getString("qualifiers")),
                fromJson(rs.getString("stereotypes")), rs.getInt("is_alternative") == 1,
                rs.getInt("is_default") == 1,
                rs.getObject("priority") != null ? rs.getInt("priority") : null,
                fromJson(rs.getString("profiles")),
                rs.getObject("declaring_class_id") != null ? rs.getInt("declaring_class_id") : null,
                rs.getString("member_name"), fromJson(rs.getString("bean_types")),
                rs.getString("framework"));
    }

    static InjectionPointRecord mapInjectionPoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InjectionPointRecord(
                rs.getInt("id"), rs.getInt("bean_id"), rs.getString("kind"),
                rs.getString("target_type"), fromJson(rs.getString("qualifiers")),
                rs.getString("field_name"),
                rs.getObject("resolved_bean_id") != null ? rs.getInt("resolved_bean_id") : null,
                ResolutionStatus.valueOf(rs.getString("resolution_status")),
                rs.getString("resolution_strategy"), ResolutionReason.valueOf(rs.getString("resolution_reason")),
                ResolutionConfidence.valueOf(rs.getString("resolution_confidence")),
                fromJson(rs.getString("limitations")),
                new ResolutionTrace(
                        candidatesFromJson(rs.getString("resolution_candidates")),
                        fromJson(rs.getString("applied_rules")).stream().map(ResolutionRule::valueOf).toList(),
                        fromJson(rs.getString("unsupported_rules")).stream().map(ResolutionRule::valueOf).toList()));
    }

    public int countBeans() {
        return countQuery("SELECT COUNT(*) FROM beans b JOIN classes c ON c.id = b.class_id "
                + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'");
    }

    /** Bean count per stored scope and the framework that discovered the beans. */
    public record FrameworkCount(String framework, String value, int count) {}

    public List<FrameworkCount> countBeansByScope() {
        return frameworkCountQuery("scope");
    }

    public List<FrameworkCount> countBeansByKind() {
        return frameworkCountQuery("kind");
    }

    private List<FrameworkCount> frameworkCountQuery(String column) {
        return handle.createQuery("SELECT b.framework, b." + column + ", COUNT(*) AS cnt FROM beans b "
                        + "JOIN classes c ON c.id = b.class_id "
                        + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output' "
                        + "GROUP BY b.framework, b." + column + " ORDER BY cnt DESC")
                .map((rs, ctx) -> new FrameworkCount(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .list();
    }

    public List<InjectionPointRecord> findUnsatisfiedInjectionPoints() {
        return handle.createQuery("SELECT ip.* FROM injection_points ip "
                                + "JOIN beans b ON b.id = ip.bean_id "
                                + "JOIN classes c ON c.id = b.class_id "
                                + "WHERE ip.resolution_status = 'UNSATISFIED' "
                                + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list();
    }

    public List<InjectionPointRecord> findAmbiguousInjectionPoints() {
        return handle.createQuery("SELECT ip.* FROM injection_points ip "
                                + "JOIN beans b ON b.id = ip.bean_id "
                                + "JOIN classes c ON c.id = b.class_id "
                                + "WHERE ip.resolution_status = 'AMBIGUOUS' "
                                + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list();
    }

    public List<InjectionPointRecord> findUnknownInjectionPoints() {
        return findInjectionPointsByStatus(ResolutionStatus.UNKNOWN);
    }

    public List<InjectionPointRecord> findContextRequiredInjectionPoints() {
        return findInjectionPointsByStatus(ResolutionStatus.CONTEXT_REQUIRED);
    }

    public List<InjectionPointRecord> findUnsupportedInjectionPoints() {
        return findInjectionPointsByStatus(ResolutionStatus.UNSUPPORTED_MECHANISM);
    }

    private List<InjectionPointRecord> findInjectionPointsByStatus(ResolutionStatus status) {
        return handle.createQuery("SELECT ip.* FROM injection_points ip "
                                + "JOIN beans b ON b.id = ip.bean_id "
                                + "JOIN classes c ON c.id = b.class_id "
                                + "WHERE ip.resolution_status = :status "
                                + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                        .bind("status", status.name())
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list();
    }

    public List<CdiProblem> findCdiProblems() {
            return handle.createQuery("SELECT * FROM cdi_problems ORDER BY id")
                            .map((rs, ctx) -> new CdiProblem(
                                    rs.getInt("id"),
                                    rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                                    rs.getString("class_name"),
                                    rs.getString("problem_type"),
                                    rs.getString("message")))
                            .list();

    }

    private static List<ExternalInjectionPointRecord> externalInjectionsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<ResolutionCandidate> candidatesFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
    private int countQuery(String sql) {
        return handle.createQuery(sql).mapTo(Integer.class).one();
    }

    private Map<String, Integer> groupCountQuery(String sql) {
        
            Map<String, Integer> result = new LinkedHashMap<>();
            handle.createQuery(sql)
                    .map((rs, ctx) -> Map.entry(rs.getString(1), rs.getInt(2)))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        
    }

    static boolean matchesProfile(List<String> beanProfiles, String requestedProfile) {
        if (beanProfiles == null || beanProfiles.isEmpty()) return true;
        boolean restricted = false;
        boolean included = false;
        for (String p : beanProfiles) {
            if (p.startsWith("+")) {
                if (!p.substring(1).equals(requestedProfile)) return false;
            } else if (p.startsWith("!")) {
                if (p.substring(1).equals(requestedProfile)) return false;
            } else {
                restricted = true;
                included |= p.equals(requestedProfile);
            }
        }
        return !restricted || included;
    }

    /** A dependency interceptor with its bindings (JSON array of annotation texts). */
    public record ExternalInterceptor(String className, Integer priority, String artifact, String interceptorBindings) {}
    /** A dependency bean offering a type, with its qualifiers (JSON array). */
    public record ExternalProvider(String className, String memberName, String artifact, String qualifiers) {}
    /** An injection point of a class's beans: field, required type and qualifiers (JSON array). */
    public record ClassInjectionPoint(String fieldName, String targetType, String qualifiers) {}
    /** An injection point of a class bean with the bean it resolved to, if any. */
    public record ResolvedInjectionPoint(String kind, String fieldName, String targetType, String qualifiers,
            String resolutionStatus, String resolvedClass, String resolvedMember, String resolvedScope,
            String resolvedFramework, String ownerFramework) {}
    public record ProducerBean(int id, String kind, String scope, String qualifiers, String beanTypes) {}
    /** A producer parameter and the class of the bean it resolved to. */
    public record ProducerParameter(String fieldName, String targetType, String qualifiers, String resolutionStatus, String resolved) {}
    /** An injection point that receives a bean, resolved or as a candidate. */
    public record BeanConsumer(String owner, String fieldName, String qualifiers, String resolutionStatus, String sourceFile) {}

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** Dependency interceptors that declare bindings, lowest priority first. */
    public List<ExternalInterceptor> findBoundExternalInterceptors() {
        return handle.createQuery("""
                SELECT class_name, priority, artifact, interceptor_bindings FROM external_beans
                WHERE kind = 'INTERCEPTOR' AND interceptor_bindings <> '[]'
                ORDER BY priority IS NULL, priority, class_name""")
                .map((rs, ctx) -> new ExternalInterceptor(rs.getString("class_name"), nullableInt(rs, "priority"),
                        rs.getString("artifact"), rs.getString("interceptor_bindings"))).list();
    }

    /** Dependency beans whose bean types name {@code type}. */
    public List<ExternalProvider> findExternalProviders(String type) {
        return handle.createQuery("""
                SELECT class_name, member_name, artifact, qualifiers FROM external_beans
                WHERE bean_types LIKE :type ORDER BY artifact, class_name, member_name""")
                .bind("type", "%\"" + type + "\"%")
                .map((rs, ctx) -> new ExternalProvider(rs.getString("class_name"), rs.getString("member_name"),
                        rs.getString("artifact"), rs.getString("qualifiers"))).list();
    }

    /** Injection points of every bean whose bean class is {@code classId}, in index order. */
    public List<ClassInjectionPoint> findClassInjectionPoints(int classId) {
        return handle.createQuery("""
                SELECT ip.field_name, ip.target_type, ip.qualifiers FROM injection_points ip JOIN beans ob ON ob.id = ip.bean_id
                WHERE ob.class_id = :id ORDER BY ip.id""")
                .bind("id", classId)
                .map((rs, ctx) -> new ClassInjectionPoint(rs.getString("field_name"), rs.getString("target_type"), rs.getString("qualifiers"))).list();
    }

    /** Up to {@code limit} injection points of the class bean of {@code classId}, with what each resolved to. */
    public List<ResolvedInjectionPoint> findClassBeanInjectionPoints(int classId, int limit) {
        return handle.createQuery("""
                SELECT ip.kind, ip.field_name, ip.target_type, ip.qualifiers, ip.resolution_status,
                       rc.class_name AS resolved_class, rb.member_name AS resolved_member, rb.scope AS resolved_scope,
                       rb.framework AS resolved_framework, ob.framework AS owner_framework
                FROM injection_points ip JOIN beans ob ON ob.id = ip.bean_id
                LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id
                LEFT JOIN classes rc ON rc.id = COALESCE(rb.declaring_class_id, rb.class_id)
                WHERE ob.class_id = :id AND ob.kind = 'CLASS' ORDER BY ip.id LIMIT :limit""")
                .bind("id", classId).bind("limit", limit)
                .map((rs, ctx) -> new ResolvedInjectionPoint(rs.getString("kind"), rs.getString("field_name"),
                        rs.getString("target_type"), rs.getString("qualifiers"), rs.getString("resolution_status"),
                        rs.getString("resolved_class"), rs.getString("resolved_member"), rs.getString("resolved_scope"),
                        rs.getString("resolved_framework"), rs.getString("owner_framework"))).list();
    }

    /** Producer method beans that a class declares under one method name. */
    public List<ProducerBean> findProducerMethodBeans(int declaringClassId, String memberName) {
        return handle.createQuery("""
                SELECT id, kind, scope, qualifiers, bean_types FROM beans
                WHERE declaring_class_id = :id AND member_name = :name AND kind = 'PRODUCER_METHOD'""")
                .bind("id", declaringClassId).bind("name", memberName)
                .map((rs, ctx) -> new ProducerBean(rs.getInt("id"), rs.getString("kind"), rs.getString("scope"),
                        rs.getString("qualifiers"), rs.getString("bean_types"))).list();
    }

    /** A bean's own injection points (a producer's parameters) with the class each resolved to. */
    public List<ProducerParameter> findProducerParameters(int beanId) {
        return handle.createQuery("""
                SELECT ip.field_name, ip.target_type, ip.qualifiers, ip.resolution_status, rc.class_name AS resolved
                FROM injection_points ip
                LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id
                LEFT JOIN classes rc ON rc.id = COALESCE(rb.declaring_class_id, rb.class_id)
                WHERE ip.bean_id = :id ORDER BY ip.id""")
                .bind("id", beanId)
                .map((rs, ctx) -> new ProducerParameter(rs.getString("field_name"), rs.getString("target_type"),
                        rs.getString("qualifiers"), rs.getString("resolution_status"), rs.getString("resolved"))).list();
    }

    /** Injection points resolved to the bean or listing it as a resolution candidate. */
    public List<BeanConsumer> findBeanConsumers(int beanId) {
        return handle.createQuery("""
                SELECT DISTINCT oc.class_name AS owner, ip.field_name, ip.qualifiers, ip.resolution_status, oc.source_file
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                WHERE ip.resolved_bean_id = :id OR ip.resolution_candidates LIKE :candidate
                ORDER BY oc.class_name, ip.field_name""")
                .bind("id", beanId).bind("candidate", "%\"beanId\":" + beanId + ",%")
                .map((rs, ctx) -> new BeanConsumer(rs.getString("owner"), rs.getString("field_name"),
                        rs.getString("qualifiers"), rs.getString("resolution_status"), rs.getString("source_file"))).list();
    }

    /** The scope of the class bean of {@code classId}. */
    public Optional<String> findClassBeanScope(int classId) {
        return handle.createQuery("SELECT scope FROM beans WHERE class_id = :id AND kind = 'CLASS' LIMIT 1")
                .bind("id", classId).mapTo(String.class).findOne();
    }

    /** An injection point of a class's own beans with what it resolved to. */
    public record ClassInjection(String kind, String fieldName, String targetType, String qualifiers,
            String resolutionStatus, String resolutionReason, String resolvedClass, String resolvedMember,
            String resolvedKind, boolean resolvedDefault, String resolvedScope, String resolvedFramework,
            String ownerFramework) {}
    /** An injection point that requires or resolves to a class, with its owner and the owner methods calling the class. */
    public record InjectingPoint(String owner, String ownerMember, String ownerKind, String kind, String fieldName,
            String targetType, String qualifiers, String resolutionStatus, String ownerScope, String ownerFramework,
            String usedIn) {}
    public record Decorator(String className, Integer priority) {}
    /** An application bean offering a type, located by its declaring (or bean) class. */
    public record TypedBean(String className, String kind, String memberName, String scope, String qualifiers,
            String profiles, Integer priority, String framework) {}
    /** An application bean offering a type, with its bean class for lookup conditions. */
    public record TypeCandidate(Integer classId, String className, String kind, String scope, String memberName,
            String qualifiers, boolean isDefault, String framework) {}
    /** A {@code @Default} provider of a type: an application bean (no artifact) or a dependency bean. */
    public record DefaultProvider(String kind, String className, String memberName, String scope, String artifact, String framework) {}
    /** An application class declaring producers, with how many distinct producer members. */
    public record ProducerHost(String className, int total) {}

    /** Injection points of a class's own (not producer) beans and of its synthetic beans, in index order. */
    public List<ClassInjection> findClassInjections(int classId) {
        return handle.createQuery("""
                SELECT ip.kind, ip.field_name, ip.target_type, ip.qualifiers, ip.resolution_status, ip.resolution_reason,
                       rc.class_name AS resolved_class, rb.member_name AS resolved_member, rb.kind AS resolved_kind,
                       rb.is_default AS resolved_default, rb.scope AS resolved_scope,
                       rb.framework AS resolved_framework, ob.framework AS owner_framework
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id
                LEFT JOIN classes rc ON rc.id = COALESCE(rb.declaring_class_id, rb.class_id)
                WHERE ob.class_id = :id AND (ob.member_name IS NULL OR ob.kind = 'SYNTHETIC')
                ORDER BY ip.id""")
                .bind("id", classId)
                .map((rs, ctx) -> new ClassInjection(rs.getString("kind"), rs.getString("field_name"),
                        rs.getString("target_type"), rs.getString("qualifiers"), rs.getString("resolution_status"),
                        rs.getString("resolution_reason"), rs.getString("resolved_class"), rs.getString("resolved_member"),
                        rs.getString("resolved_kind"), rs.getInt("resolved_default") != 0, rs.getString("resolved_scope"),
                        rs.getString("resolved_framework"), rs.getString("owner_framework"))).list();
    }

    /** Injection points requiring the class (as itself or a type argument) or resolved to its class bean. */
    public List<InjectingPoint> findInjectionPointsOf(int classId, String className) {
        return handle.createQuery("""
                SELECT DISTINCT oc.class_name AS owner, ob.member_name AS owner_member, ob.kind AS owner_kind, ip.kind,
                       ip.field_name, ip.target_type, ip.qualifiers, ip.resolution_status, ob.scope AS owner_scope,
                       ob.framework AS owner_framework,
                       (SELECT GROUP_CONCAT(mc.from_method, ',') FROM method_calls mc
                        WHERE mc.from_class_id = oc.id AND mc.to_class_id = :id) AS used_in
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                WHERE ip.target_type = :name OR ip.target_type LIKE :generic
                   OR ip.resolved_bean_id IN (SELECT id FROM beans WHERE class_id = :id AND member_name IS NULL)
                ORDER BY oc.class_name, ip.field_name""")
                .bind("name", className).bind("generic", "%<" + className + ">%").bind("id", classId)
                .map((rs, ctx) -> new InjectingPoint(rs.getString("owner"), rs.getString("owner_member"),
                        rs.getString("owner_kind"), rs.getString("kind"), rs.getString("field_name"),
                        rs.getString("target_type"), rs.getString("qualifiers"), rs.getString("resolution_status"),
                        rs.getString("owner_scope"), rs.getString("owner_framework"), rs.getString("used_in"))).list();
    }

    /** Other classes' {@code @Decorator} beans with the class among their bean types, lowest priority first. */
    public List<Decorator> findDecoratorsOf(int classId, String className) {
        return handle.createQuery("""
                SELECT c.class_name, b.priority FROM beans b JOIN classes c ON c.id = b.class_id
                WHERE b.kind = 'DECORATOR' AND b.bean_types LIKE :type AND c.id <> :id
                ORDER BY b.priority IS NULL, b.priority, c.class_name""")
                .bind("type", "%\"" + className + "\"%").bind("id", classId)
                .map((rs, ctx) -> new Decorator(rs.getString("class_name"), nullableInt(rs, "priority"))).list();
    }

    /** Current application beans offering {@code type}. */
    public List<TypedBean> findCurrentBeansOfType(String type) {
        return handle.createQuery("""
                SELECT c.class_name, b.kind, b.member_name, b.scope, b.qualifiers, b.profiles, b.priority, b.framework
                FROM beans b JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE c.lifecycle = 'current' AND b.bean_types LIKE :type
                ORDER BY c.class_name, b.member_name""")
                .bind("type", "%\"" + type + "\"%")
                .map((rs, ctx) -> new TypedBean(rs.getString("class_name"), rs.getString("kind"), rs.getString("member_name"),
                        rs.getString("scope"), rs.getString("qualifiers"), rs.getString("profiles"),
                        nullableInt(rs, "priority"), rs.getString("framework"))).list();
    }

    /** Required types of the injection points of a class's own (not producer) beans, in index order. */
    public List<String> findClassBeanInjectionTypes(int classId) {
        return handle.createQuery("""
                SELECT ip.target_type FROM injection_points ip JOIN beans ob ON ob.id = ip.bean_id
                WHERE ob.class_id = :id AND ob.member_name IS NULL ORDER BY ip.id""")
                .bind("id", classId).mapTo(String.class).list();
    }

    /** Application beans offering {@code type}, any lifecycle. */
    public List<TypeCandidate> findTypeCandidates(String type) {
        return handle.createQuery("""
                SELECT b.class_id, c.class_name, b.kind, b.scope, b.member_name, b.qualifiers, b.is_default, b.framework
                FROM beans b JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE b.bean_types LIKE :type ORDER BY c.class_name, b.member_name""")
                .bind("type", "%\"" + type + "\"%")
                .map((rs, ctx) -> new TypeCandidate(nullableInt(rs, "class_id"), rs.getString("class_name"), rs.getString("kind"),
                        rs.getString("scope"), rs.getString("member_name"), rs.getString("qualifiers"),
                        rs.getInt("is_default") != 0, rs.getString("framework"))).list();
    }

    /** {@code @Default} providers of {@code type}: application beans first, then dependency beans. */
    public List<DefaultProvider> findDefaultProviders(String type) {
        return handle.createQuery("""
                SELECT b.kind, c.class_name, b.member_name, b.scope, NULL AS artifact, b.framework FROM beans b
                JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE b.bean_types LIKE :type AND EXISTS (SELECT 1 FROM json_each(b.qualifiers) WHERE value = '@Default')
                UNION ALL
                SELECT kind, class_name, member_name, scope, artifact, framework FROM external_beans
                WHERE bean_types LIKE :type AND EXISTS (SELECT 1 FROM json_each(qualifiers) WHERE value = '@Default')""")
                .bind("type", "%\"" + type + "\"%")
                .map((rs, ctx) -> new DefaultProvider(rs.getString("kind"), rs.getString("class_name"), rs.getString("member_name"),
                        rs.getString("scope"), rs.getString("artifact"), rs.getString("framework"))).list();
    }

    /** Whether the class has a class bean of its own (not a producer-declared one). */
    public boolean hasOwnClassBean(int classId) {
        return handle.createQuery("SELECT COUNT(*) FROM beans WHERE class_id = :id AND kind = 'CLASS' AND declaring_class_id IS NULL")
                .bind("id", classId).mapTo(Integer.class).one() > 0;
    }

    /** The three current production source classes declaring the most producer members. */
    public List<ProducerHost> findTopProducerHosts() {
        return handle.createQuery("""
                SELECT c.class_name, COUNT(DISTINCT b.member_name) AS total
                FROM beans b JOIN classes c ON c.id = b.declaring_class_id
                WHERE b.kind LIKE 'PRODUCER_%' AND b.member_name IS NOT NULL AND c.lifecycle = 'current'
                  AND COALESCE(c.source_set, 'main') = 'main' AND COALESCE(c.origin, 'source') = 'source'
                GROUP BY c.class_name ORDER BY total DESC, c.class_name LIMIT 3""")
                .map((rs, ctx) -> new ProducerHost(rs.getString("class_name"), rs.getInt("total"))).list();
    }

    /** A bean declared by a class directly in a package. */
    public record PackageBean(String className, String sourceSet, String kind, String scope, String qualifiers,
            String memberName, String beanTypes, String framework) {}
    /** A dependency bean offering a type, with where its jar is. */
    public record ExternalTypeProvider(String className, String kind, String memberName, String scope, String qualifiers,
            String artifact, String framework, String profiles, String jarPath) {}
    /** An application bean offering a type. */
    public record ApplicationTypeProvider(String className, String kind, String memberName, String scope,
            String qualifiers, String framework) {}

    /** Beans declared by current classes directly in {@code packageName}, without subpackages. */
    public List<PackageBean> findPackageBeans(String packageName) {
        return handle.createQuery("""
                SELECT c.class_name, c.source_set, b.kind, b.scope, b.qualifiers, b.member_name, b.bean_types, b.framework
                FROM beans b JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE c.class_name LIKE :prefix AND c.lifecycle = 'current'
                  AND instr(substr(c.class_name, length(:package) + 2), '.') = 0
                ORDER BY c.source_set, b.kind, c.class_name, b.member_name""")
                .bind("prefix", packageName + ".%").bind("package", packageName)
                .map((rs, ctx) -> new PackageBean(rs.getString("class_name"), rs.getString("source_set"), rs.getString("kind"),
                        rs.getString("scope"), rs.getString("qualifiers"), rs.getString("member_name"),
                        rs.getString("bean_types"), rs.getString("framework"))).list();
    }

    /** Dependency beans offering {@code type}, default-qualified first. */
    public List<ExternalTypeProvider> findExternalTypeProviders(String type) {
        return handle.createQuery("""
                SELECT class_name, kind, member_name, scope, qualifiers, artifact, framework, profiles, jar_path
                FROM external_beans WHERE bean_types LIKE :type
                ORDER BY CASE WHEN EXISTS (SELECT 1 FROM json_each(external_beans.qualifiers) WHERE value = '@Default')
                              THEN 0 ELSE 1 END, artifact, class_name, member_name""")
                .bind("type", "%\"" + type + "\"%")
                .map((rs, ctx) -> new ExternalTypeProvider(rs.getString("class_name"), rs.getString("kind"),
                        rs.getString("member_name"), rs.getString("scope"), rs.getString("qualifiers"), rs.getString("artifact"),
                        rs.getString("framework"), rs.getString("profiles"), rs.getString("jar_path"))).list();
    }

    /** Application beans offering {@code type}, default-qualified first. */
    public List<ApplicationTypeProvider> findApplicationTypeProviders(String type) {
        return handle.createQuery("""
                SELECT c.class_name, b.kind, b.member_name, b.scope, b.qualifiers, b.framework
                FROM beans b JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE b.bean_types LIKE :type
                ORDER BY CASE WHEN EXISTS (SELECT 1 FROM json_each(b.qualifiers) WHERE value = '@Default')
                              THEN 0 ELSE 1 END, c.class_name, b.member_name""")
                .bind("type", "%\"" + type + "\"%")
                .map((rs, ctx) -> new ApplicationTypeProvider(rs.getString("class_name"), rs.getString("kind"),
                        rs.getString("member_name"), rs.getString("scope"), rs.getString("qualifiers"), rs.getString("framework"))).list();
    }

    /** An injected field a method reads, with the scope and class of the bean it resolved to. */
    public record InjectedFieldRead(String fieldName, String scope, String framework, String resolved, String targetType) {}

    /** Injected fields of class beans that a method reads, by field name. */
    public List<InjectedFieldRead> findInjectedFieldReads(int classId, String method, String descriptor) {
        return handle.createQuery("""
                SELECT a.field_name, rb.scope, rb.framework, rc.class_name AS resolved, ip.target_type
                FROM field_accesses a
                JOIN beans ob ON ob.class_id = a.to_class_id AND ob.member_name IS NULL
                JOIN injection_points ip ON ip.bean_id = ob.id AND ip.field_name = a.field_name
                LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id
                LEFT JOIN classes rc ON rc.id = COALESCE(rb.declaring_class_id, rb.class_id)
                WHERE a.from_class_id = :id AND a.from_method = :name AND a.from_descriptor = :descriptor
                  AND a.access_kind LIKE 'read%' ORDER BY a.field_name""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor)
                .map((rs, ctx) -> new InjectedFieldRead(rs.getString("field_name"), rs.getString("scope"), rs.getString("framework"),
                        rs.getString("resolved"), rs.getString("target_type"))).list();
    }

    /** A bean with its bean class and, for a producer, its declaring class. */
    public record BeanOrigin(String kind, String qualifiers, String memberName, String framework, String className, String declaring) {}

    /** Beans an owner class's injection point named {@code member} resolved to. */
    public List<Integer> findResolvedBeanIds(String ownerClassName, String member) {
        return handle.createQuery("""
                SELECT ip.resolved_bean_id FROM injection_points ip
                JOIN beans b ON b.id = ip.bean_id JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE c.class_name = :owner AND ip.field_name = :member AND ip.resolved_bean_id IS NOT NULL""")
                .bind("owner", ownerClassName).bind("member", member).mapTo(Integer.class).list();
    }

    public Optional<BeanOrigin> findBeanOrigin(int beanId) {
        return handle.createQuery("""
                SELECT b.kind, b.qualifiers, b.member_name, b.framework, c.class_name, d.class_name AS declaring
                FROM beans b JOIN classes c ON c.id = b.class_id LEFT JOIN classes d ON d.id = b.declaring_class_id
                WHERE b.id = :id""")
                .bind("id", beanId)
                .map((rs, ctx) -> new BeanOrigin(rs.getString("kind"), rs.getString("qualifiers"), rs.getString("member_name"),
                        rs.getString("framework"), rs.getString("class_name"), rs.getString("declaring"))).findOne();
    }

    /** {@code Owner#field} (or the lower-cased point kind) of every injection point resolved to the bean, sorted. */
    public List<String> findInjectionPointOwners(int beanId) {
        return handle.createQuery("""
                SELECT DISTINCT oc.class_name || '#' || COALESCE(ip.field_name, lower(ip.kind)) FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                WHERE ip.resolved_bean_id = :id ORDER BY 1""").bind("id", beanId).mapTo(String.class).list();
    }
}
