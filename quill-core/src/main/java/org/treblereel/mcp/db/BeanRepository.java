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
                if (filter.containsKey("qualifier")) sb.append(" AND b.qualifiers LIKE :qualifier");
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
                if (filter.containsKey("qualifier")) q.bind("qualifier", "%" + filter.get("qualifier") + "%");
                if (filter.containsKey("module")) q.bind("module", filter.get("module"));
                if (filter.containsKey("source_set")) q.bind("sourceSet", filter.get("source_set"));
            }

            List<BeanRecord> results = q.map((rs, ctx) -> mapBean(rs)).list();

            if (filter != null && filter.containsKey("profile")) {
                String requestedProfile = filter.get("profile");
                results = results.stream()
                        .filter(b -> matchesProfile(b.profiles(), requestedProfile))
                        .toList();
            }

            return results;
        
    }

    public List<ExternalBeanRecord> findExternalBeans(Map<String, String> filter) {
        
            StringBuilder sql = new StringBuilder("SELECT * FROM external_beans WHERE 1=1");
            if (filter != null) {
                if (filter.containsKey("class_name")) sql.append(" AND class_name LIKE :className");
                if (filter.containsKey("scope")) sql.append(" AND scope = :scope");
                if (filter.containsKey("kind")) sql.append(" AND kind = :kind");
                if (filter.containsKey("profile")) sql.append(" AND profiles LIKE :profile");
                if (filter.containsKey("qualifier")) sql.append(" AND qualifiers LIKE :qualifier");
            }
            sql.append(" ORDER BY class_name, framework, kind, member_name");
            var query = handle.createQuery(sql.toString());
            if (filter != null) {
                if (filter.containsKey("class_name")) {
                    String value = filter.get("class_name");
                    String pattern = value.replace('*', '%');
                    if (!value.contains("*") && !value.contains(".")) pattern = "%" + value;
                    query.bind("className", pattern);
                }
                if (filter.containsKey("scope")) query.bind("scope", filter.get("scope"));
                if (filter.containsKey("kind")) query.bind("kind", filter.get("kind"));
                if (filter.containsKey("profile")) {
                    query.bind("profile", "%\"" + filter.get("profile") + "\"%");
                }
                if (filter.containsKey("qualifier")) {
                    query.bind("qualifier", "%" + filter.get("qualifier") + "%");
                }
            }
            return query.map((rs, ctx) -> new ExternalBeanRecord(
                    rs.getInt("id"), rs.getString("class_name"), rs.getString("kind"),
                    rs.getString("scope"), fromJson(rs.getString("qualifiers")),
                    fromJson(rs.getString("stereotypes")),
                    rs.getInt("is_alternative") != 0, rs.getInt("is_default") != 0,
                    rs.getObject("priority") == null ? null : rs.getInt("priority"),
                    fromJson(rs.getString("profiles")), rs.getString("member_name"),
                    fromJson(rs.getString("bean_types")), rs.getString("framework"),
                    rs.getString("artifact"), rs.getString("jar_path"),
                    externalInjectionsFromJson(rs.getString("injection_points")))).list();
        
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
                rs.getString("member_name"), fromJson(rs.getString("bean_types")));
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

    public Map<String, Integer> countBeansByScope() {
        return groupCountQuery("SELECT b.scope, COUNT(*) as cnt FROM beans b "
                + "JOIN classes c ON c.id = b.class_id "
                + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output' "
                + "GROUP BY b.scope ORDER BY cnt DESC");
    }

    public Map<String, Integer> countBeansByKind() {
        return groupCountQuery("SELECT b.kind, COUNT(*) as cnt FROM beans b "
                + "JOIN classes c ON c.id = b.class_id "
                + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output' "
                + "GROUP BY b.kind ORDER BY cnt DESC");
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
        if (beanProfiles == null || beanProfiles.isEmpty()) return false;
        for (String p : beanProfiles) {
            if (p.equals(requestedProfile)) return true;
        }
        return false;
    }
}
