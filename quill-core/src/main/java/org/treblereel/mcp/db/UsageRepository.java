package org.treblereel.mcp.db;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

import static org.treblereel.mcp.db.SymbolRepository.parseIntList;

import static org.treblereel.mcp.db.SymbolRepository.fromJson;

import static org.treblereel.mcp.db.SymbolRepository.mapClass;

import java.util.*;
import org.jdbi.v3.core.Handle;
import org.treblereel.mcp.model.*;

/** Read-only usage queries; the caller owns the handle and transaction. */
public final class UsageRepository {
    private final Handle handle;

    public UsageRepository(Handle handle) { this.handle = Objects.requireNonNull(handle); }

    public record DependencyBreakdown(String origin, int classes, int edges) {}

    public record ArchitectureHub(
            int classId,
            int totalDependents,
            int sourceDependents,
            int generatedDependents,
            int productionDependents,
            int testDependents) {}

    public record MethodCallView(
            int fromClassId,
            String fromClass,
            String fromSource,
            int fromSourceLine,
            String fromOrigin,
            String fromModule,
            int fromSourceTokens,
            String fromMethod,
            String fromDescriptor,
            int toClassId,
            String toClass,
            String toSource,
            int toSourceLine,
            String toOrigin,
            String toModule,
            int toSourceTokens,
            String toMethod,
            String toDescriptor,
            String invocationKind,
            int occurrenceCount,
            List<Integer> evidenceLines,
            List<Integer> instructionOrdinals,
            int callerBranchCount,
            int callerExceptionHandlerCount,
            List<String> callerControlFlowEdges,
            List<String> callerAsyncBoundaries,
            List<String> callerExternalCalls) {}

    public record MethodInboundUsage(
            int classId,
            String method,
            String descriptor,
            int callerClassCount,
            int occurrenceCount) {}

    public record FieldUsage(
            int classId,
            String fieldName,
            String descriptor,
            int readerClassCount,
            int readOccurrences,
            int writerClassCount,
            int writeOccurrences) {}

    public record InvokedMemberView(int fromClassId, String name, String descriptor,
            String invocationKind, long instructionOccurrences, int groupCount) {}

    public record FieldAccessView(
            int fromClassId,
            String fromClass,
            String fromSource,
            int fromSourceLine,
            String fromOrigin,
            String fromModule,
            int fromSourceTokens,
            String fromMethod,
            String fromDescriptor,
            String fieldName,
            String fieldDescriptor,
            String accessKind,
            int occurrenceCount,
            List<Integer> evidenceLines,
            List<Integer> instructionOrdinals) {}

    public List<UnusedClassCandidate> findUnusedClassCandidates() {
        return handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(UsageRepository.class, "findUnusedClassCandidates"))
                .map((rs, ctx) -> new UnusedClassCandidate(
                        mapClass(rs),
                        rs.getInt("inbound_class_count"),
                        rs.getInt("inbound_occurrence_count"),
                        rs.getInt("production_inbound_count"),
                        rs.getInt("test_inbound_count"),
                        rs.getInt("hierarchy_user_count"),
                        rs.getInt("annotation_count"),
                        rs.getBoolean("has_main")))
                .list();
    }

    public List<MethodInboundUsage> findMethodInboundUsages() {
        return handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(UsageRepository.class, "findMethodInboundUsages"))
                .map((rs, ctx) -> new MethodInboundUsage(
                        rs.getInt("to_class_id"), rs.getString("to_method"),
                        rs.getString("to_descriptor"), rs.getInt("caller_class_count"),
                        rs.getInt("occurrence_count")))
                .list();
    }

    public List<FieldUsage> findFieldUsages() {
        return handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(UsageRepository.class, "findFieldUsages"))
                .map((rs, ctx) -> new FieldUsage(
                        rs.getInt("to_class_id"), rs.getString("field_name"),
                        rs.getString("field_descriptor"), rs.getInt("reader_class_count"),
                        rs.getInt("read_occurrences"), rs.getInt("writer_class_count"),
                        rs.getInt("write_occurrences")))
                .list();
    }

    public List<MethodCallView> findMethodCalls(int classId,
            String method, String direction, int limit, int offset) {
        return findMethodCalls(classId, method, null, direction, limit, offset, List.of(classId));
    }

    public List<MethodCallView> findMethodCalls(int classId,
            String method, String descriptor, String direction, int limit, int offset, List<Integer> ownerIds) {
        if ("inbound".equals(direction) && method != null && descriptor != null)
            return findExactMethodUsages(ownerIds,method,descriptor,limit,offset);
        String predicate = methodCallPredicate(
                direction, method != null, descriptor != null);
        boolean resolveBoth = "both".equals(direction) && method != null && descriptor != null;
        if (resolveBoth) predicate=predicate.replace("mc.to_class_id = :classId","mc.to_class_id IN (<ownerIds>)");
        String sql = ClasspathSqlLocator.findSqlOnClasspath(UsageRepository.class, "findMethodCalls") + predicate + " ORDER BY from_class, from_method, from_descriptor, "
                + "to_class, to_method, to_descriptor, invocation_kind, mc.from_class_id, mc.to_class_id LIMIT :limit OFFSET :offset";
        
            var query = handle.createQuery(sql)
                    .bind("classId", classId)
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (resolveBoth) query.bindList("ownerIds",ownerIds);
            if (method != null) query.bind("method", method);
            if (descriptor != null) query.bind("descriptor", descriptor);
            return query.map((rs, ctx) -> new MethodCallView(
                    rs.getInt("from_class_id"), rs.getString("from_class"),
                    rs.getString("from_source"), rs.getInt("from_source_line"),
                    rs.getString("from_origin"), rs.getString("from_module"),
                    rs.getInt("from_source_tokens"),
                    rs.getString("from_method"), rs.getString("from_descriptor"),
                    rs.getInt("to_class_id"), rs.getString("to_class"),
                    rs.getString("to_source"), rs.getInt("to_source_line"),
                    rs.getString("to_origin"), rs.getString("to_module"),
                    rs.getInt("to_source_tokens"),
                    rs.getString("to_method"), rs.getString("to_descriptor"),
                    rs.getString("invocation_kind"), rs.getInt("occurrence_count"),
                    parseIntList(rs.getString("evidence_lines")),
                    parseIntList(rs.getString("instruction_ordinals")),
                    rs.getInt("caller_branch_count"),
                    rs.getInt("caller_exception_handler_count"),
                    fromJson(rs.getString("caller_control_flow_edges")),
                    fromJson(rs.getString("caller_async_boundaries")),
                    fromJson(rs.getString("caller_external_calls")))).list();
        
    }

    public List<MethodCallView> findExactMethodUsages(List<Integer> ownerIds,
            String method, String descriptor, int limit, int offset) {
        String sql = ClasspathSqlLocator.findSqlOnClasspath(UsageRepository.class, "findExactMethodUsages");
        return handle.createQuery(sql)
                .bindList("ownerIds", ownerIds)
                .bind("method", method)
                .bind("descriptor", descriptor)
                .bind("limit", limit)
                .bind("offset", offset)
                .map((rs, ctx) -> mapMethodCall(rs)).list();
    }

    public int countExactMethodUsages(List<Integer> ownerIds, String method, String descriptor) {
        return handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(UsageRepository.class, "countExactMethodUsages"))
                .bindList("ownerIds", ownerIds)
                .bind("method", method)
                .bind("descriptor", descriptor)
                .mapTo(Integer.class).one();
    }

    public List<FieldAccessView> findExactFieldUsages(int classId,
            String field, String descriptor, String accessKind, int limit, int offset) {
        String accessPredicate = accessKind == null ? "" : " AND fa.access_kind LIKE :accessKind";
        String sql = ClasspathSqlLocator.findSqlOnClasspath(UsageRepository.class, "findExactFieldUsages") + accessPredicate
                + " ORDER BY from_class, from_method, from_descriptor, access_kind, fa.from_class_id"
                + " LIMIT :limit OFFSET :offset";
        
            var query = handle.createQuery(sql)
                    .bind("classId", classId).bind("field", field)
                    .bind("descriptor", descriptor).bind("limit", limit)
                    .bind("offset", offset);
            if (accessKind != null) query.bind("accessKind", accessKind + "_%");
            return query.map((rs, ctx) -> new FieldAccessView(
                    rs.getInt("from_class_id"), rs.getString("from_class"),
                    rs.getString("from_source"), rs.getInt("from_source_line"),
                    rs.getString("from_origin"), rs.getString("from_module"),
                    rs.getInt("from_source_tokens"), rs.getString("from_method"),
                    rs.getString("from_descriptor"), rs.getString("field_name"),
                    rs.getString("field_descriptor"), rs.getString("access_kind"),
                    rs.getInt("occurrence_count"),
                    parseIntList(rs.getString("evidence_lines")),
                    parseIntList(rs.getString("instruction_ordinals")))).list();
        
    }

    public int countExactFieldUsages(int classId,
            String field, String descriptor, String accessKind) {
        String accessPredicate = accessKind == null ? "" : " AND fa.access_kind LIKE :accessKind";
        String sql = """
                SELECT count(*) FROM field_accesses fa
                JOIN classes source ON source.id = fa.from_class_id
                JOIN classes target ON target.id = fa.to_class_id
                WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                  AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                  AND fa.to_class_id = :classId AND fa.field_name = :field
                  AND fa.field_descriptor = :descriptor
                """ + accessPredicate;
        
            var query = handle.createQuery(sql)
                    .bind("classId", classId).bind("field", field)
                    .bind("descriptor", descriptor);
            if (accessKind != null) query.bind("accessKind", accessKind + "_%");
            return query.mapTo(Integer.class).one();
        
    }

    public int countMethodCalls(int classId, String method, String direction) {
        return countMethodCalls(classId, method, null, direction, List.of(classId));
    }

    public int countMethodCalls(int classId, String method,
            String descriptor, String direction, List<Integer> ownerIds) {
        if ("inbound".equals(direction) && method != null && descriptor != null)
            return countExactMethodUsages(ownerIds,method,descriptor);
        String sql = "SELECT count(*) FROM method_calls mc WHERE "
                + methodCallPredicate(direction, method != null, descriptor != null);
        boolean resolveBoth="both".equals(direction) && method != null && descriptor != null;
        if(resolveBoth) sql=sql.replace("mc.to_class_id = :classId","mc.to_class_id IN (<ownerIds>)");
        String countSql=sql;
        
            var query = handle.createQuery(countSql).bind("classId", classId);
            if(resolveBoth) query.bindList("ownerIds",ownerIds);
            if (method != null) query.bind("method", method);
            if (descriptor != null) query.bind("descriptor", descriptor);
            return query.mapTo(Integer.class).one();
        
    }

    public List<MethodCallView> findAdjacentMethodCalls(int classId,
            String method, String descriptor, String direction, List<Integer> ownerIds) {
        if ("inbound".equals(direction) && method != null && descriptor != null)
            return findExactMethodUsages(ownerIds,method,descriptor,Integer.MAX_VALUE,0);
        String endpoint = "inbound".equals(direction) ? "to" : "from";
        StringBuilder predicate = new StringBuilder("mc.")
                .append(endpoint).append("_class_id = :classId");
        if (method != null) predicate.append(" AND mc.").append(endpoint)
                .append("_method = :method");
        if (descriptor != null) predicate.append(" AND mc.").append(endpoint)
                .append("_descriptor = :descriptor");
        String sql = ClasspathSqlLocator.findSqlOnClasspath(UsageRepository.class, "findAdjacentMethodCalls") + predicate + " ORDER BY from_class, from_method, from_descriptor, "
                + "to_class, to_method, to_descriptor, invocation_kind, mc.from_class_id, mc.to_class_id";
        
            var query = handle.createQuery(sql).bind("classId", classId);
            if (method != null) query.bind("method", method);
            if (descriptor != null) query.bind("descriptor", descriptor);
            return query.map((rs, ctx) -> new MethodCallView(
                    rs.getInt("from_class_id"), rs.getString("from_class"),
                    rs.getString("from_source"), rs.getInt("from_source_line"),
                    rs.getString("from_origin"), rs.getString("from_module"),
                    rs.getInt("from_source_tokens"),
                    rs.getString("from_method"), rs.getString("from_descriptor"),
                    rs.getInt("to_class_id"), rs.getString("to_class"),
                    rs.getString("to_source"), rs.getInt("to_source_line"),
                    rs.getString("to_origin"), rs.getString("to_module"),
                    rs.getInt("to_source_tokens"),
                    rs.getString("to_method"), rs.getString("to_descriptor"),
                    rs.getString("invocation_kind"), rs.getInt("occurrence_count"),
                    parseIntList(rs.getString("evidence_lines")),
                    parseIntList(rs.getString("instruction_ordinals")),
                    rs.getInt("caller_branch_count"),
                    rs.getInt("caller_exception_handler_count"),
                    fromJson(rs.getString("caller_control_flow_edges")),
                    fromJson(rs.getString("caller_async_boundaries")),
                    fromJson(rs.getString("caller_external_calls")))).list();
        
    }

    private static String methodCallPredicate(
            String direction, boolean filterMethod, boolean filterDescriptor) {
        String inbound = "mc.to_class_id = :classId"
                + (filterMethod ? " AND mc.to_method = :method" : "")
                + (filterDescriptor ? " AND mc.to_descriptor = :descriptor" : "");
        String outbound = "mc.from_class_id = :classId"
                + (filterMethod ? " AND mc.from_method = :method" : "")
                + (filterDescriptor ? " AND mc.from_descriptor = :descriptor" : "");
        return switch (direction) {
            case "inbound" -> inbound;
            case "outbound" -> outbound;
            case "both" -> "((" + inbound + ") OR (" + outbound + "))";
            default -> throw new IllegalArgumentException("Unsupported call direction: " + direction);
        };
    }

    public List<DependencyRecord> findDependencies(int classId, String direction) {
        String sql = switch (direction) {
            case "outbound" -> "SELECT * FROM dependencies WHERE from_class_id = :classId";
            case "inbound" -> "SELECT * FROM dependencies WHERE to_class_id = :classId";
            default -> "SELECT * FROM dependencies WHERE from_class_id = :classId OR to_class_id = :classId";
        };
        return handle.createQuery(sql)
                        .bind("classId", classId)
                        .map((rs, ctx) -> new DependencyRecord(
                                rs.getInt("from_class_id"), rs.getInt("to_class_id"),
                                rs.getString("kind"),
                                rs.getObject("injection_point_id") != null ? rs.getInt("injection_point_id") : null,
                                rs.getInt("occurrence_count"),
                                parseIntList(rs.getString("evidence_lines"))))
                        .list();
    }

    public List<DependencyRecord> findCurrentDependencyGraph(String module, boolean includeGenerated, boolean includeTests) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.* FROM dependencies d
                JOIN classes source ON source.id = d.from_class_id
                JOIN classes target ON target.id = d.to_class_id
                WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                  AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                  AND source.id != target.id
                """);
        if (module != null && !module.isBlank()) {
            sql.append(" AND source.module = :module AND target.module = :module");
        }
        if (!includeGenerated) {
            sql.append(" AND source.origin != 'generated' AND target.origin != 'generated'");
        }
        if (!includeTests) {
            sql.append(" AND COALESCE(source.source_set, 'main') NOT IN (<testSourceSets>)");
            sql.append(" AND COALESCE(target.source_set, 'main') NOT IN (<testSourceSets>)");
        }
        sql.append(" ORDER BY d.from_class_id, d.to_class_id, d.kind");
        
            var query = handle.createQuery(sql.toString());
            if (!includeTests) query.bindList("testSourceSets", ClassRecord.TEST_SOURCE_SETS);
            if (module != null && !module.isBlank()) query.bind("module", module);
            return query.map((rs, ctx) -> new DependencyRecord(
                            rs.getInt("from_class_id"), rs.getInt("to_class_id"),
                            rs.getString("kind"),
                            rs.getObject("injection_point_id") != null
                                    ? rs.getInt("injection_point_id") : null,
                            rs.getInt("occurrence_count"),
                            parseIntList(rs.getString("evidence_lines"))))
                    .list();
        
    }

    private static MethodCallView mapMethodCall(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new MethodCallView(
                rs.getInt("from_class_id"), rs.getString("from_class"),
                rs.getString("from_source"), rs.getInt("from_source_line"),
                rs.getString("from_origin"), rs.getString("from_module"),
                rs.getInt("from_source_tokens"),
                rs.getString("from_method"), rs.getString("from_descriptor"),
                rs.getInt("to_class_id"), rs.getString("to_class"),
                rs.getString("to_source"), rs.getInt("to_source_line"),
                rs.getString("to_origin"), rs.getString("to_module"),
                rs.getInt("to_source_tokens"),
                rs.getString("to_method"), rs.getString("to_descriptor"),
                rs.getString("invocation_kind"), rs.getInt("occurrence_count"),
                parseIntList(rs.getString("evidence_lines")),
                parseIntList(rs.getString("instruction_ordinals")),
                rs.getInt("caller_branch_count"),
                rs.getInt("caller_exception_handler_count"),
                fromJson(rs.getString("caller_control_flow_edges")),
                fromJson(rs.getString("caller_async_boundaries")),
                fromJson(rs.getString("caller_external_calls")));
    }

    public List<Map.Entry<Integer, Integer>> findMostDependedOn(int limit) {
        return findArchitectureHubs().stream()
                .limit(limit)
                .map(hub -> Map.entry(hub.classId(), hub.totalDependents()))
                .toList();
    }

    public List<ArchitectureHub> findArchitectureHubs() {
        return handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(UsageRepository.class, "findArchitectureHubs"))
                .map((rs, ctx) -> new ArchitectureHub(
                        rs.getInt("to_class_id"), rs.getInt("total_count"),
                        rs.getInt("source_count"), rs.getInt("generated_count"),
                        rs.getInt("production_count"), rs.getInt("test_count")))
                .list();
    }

    public int countDependents(int classId) {
        return handle.createQuery("""
                        SELECT COUNT(DISTINCT d.from_class_id) FROM dependencies d
                        JOIN classes source ON source.id = d.from_class_id
                        WHERE d.to_class_id = :classId
                          AND source.lifecycle = 'current' AND source.origin != 'orphan_output'""")
                        .bind("classId", classId)
                        .mapTo(Integer.class)
                        .one();
    }

    public int countDependencies(int classId) {
        return handle.createQuery("""
                        SELECT COUNT(DISTINCT d.to_class_id) FROM dependencies d
                        JOIN classes target ON target.id = d.to_class_id
                        WHERE d.from_class_id = :classId
                          AND target.lifecycle = 'current' AND target.origin != 'orphan_output'""")
                        .bind("classId", classId)
                        .mapTo(Integer.class)
                        .one();
    }

    public int countDependencyEdges(int classId, boolean inbound) {
        String endpoint = inbound ? "to_class_id" : "from_class_id";
        String related = inbound ? "from_class_id" : "to_class_id";
        return handle.createQuery("SELECT COALESCE(SUM(d.occurrence_count), 0) "
                        + "FROM dependencies d JOIN classes c ON c.id = d." + related + " "
                        + "WHERE d." + endpoint + " = :classId "
                        + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                .bind("classId", classId)
                .mapTo(Integer.class)
                .one();
    }

    public List<DependencyBreakdown> dependencyBreakdown(int classId, boolean inbound) {
        String endpoint = inbound ? "to_class_id" : "from_class_id";
        String related = inbound ? "from_class_id" : "to_class_id";
        return handle.createQuery("SELECT c.origin, "
                        + "COUNT(DISTINCT d." + related + ") AS class_count, "
                        + "SUM(d.occurrence_count) AS edge_count "
                        + "FROM dependencies d JOIN classes c ON c.id = d." + related + " "
                        + "WHERE d." + endpoint + " = :classId AND c.lifecycle = 'current' "
                        + "AND c.origin != 'orphan_output' "
                        + "GROUP BY c.origin ORDER BY c.origin")
                .bind("classId", classId)
                .map((rs, ctx) -> new DependencyBreakdown(rs.getString("origin"),
                        rs.getInt("class_count"), rs.getInt("edge_count")))
                .list();
    }

    public List<ExternalDepRecord> findExternalDeps(int classId) {
        return handle.createQuery("SELECT * FROM class_external_deps WHERE class_id = :classId ORDER BY usage_kind, external_type")
                        .bind("classId", classId)
                        .map((rs, ctx) -> new ExternalDepRecord(
                                rs.getInt("class_id"), rs.getString("external_type"), rs.getString("usage_kind")))
                        .list();
    }

    public List<ExternalDepRecord> findExternalTypeUsages(String externalType) {
        return handle.createQuery("""
                        SELECT class_id, external_type, usage_kind
                        FROM class_external_deps
                        WHERE external_type = :externalType
                        ORDER BY class_id, usage_kind
                        """)
                        .bind("externalType", externalType)
                        .map((rs, ctx) -> new ExternalDepRecord(
                                rs.getInt("class_id"), rs.getString("external_type"),
                                rs.getString("usage_kind")))
                        .list();
    }

    public List<Map.Entry<String, Integer>> findExternalDepsByLibrary(int limit) {
        return handle.createQuery("""
                        SELECT substr(external_type, 1, instr(substr(external_type, instr(external_type, '.') + 1), '.') + instr(external_type, '.') - 1) as library,
                               COUNT(DISTINCT class_id) as class_count
                        FROM class_external_deps
                        GROUP BY library
                        ORDER BY class_count DESC
                        LIMIT :limit""")
                        .bind("limit", limit)
                        .map((rs, ctx) -> {
                            String lib = rs.getString("library");
                            return (lib != null && !lib.isEmpty()) ? Map.entry(lib, rs.getInt("class_count")) : null;
                        })
                        .list()
                        .stream().filter(Objects::nonNull).toList();
    }

    public List<Map.Entry<Integer, String>> findClassesUsingType(String typePattern) {
        return handle.createQuery("SELECT DISTINCT ced.class_id, c.class_name FROM class_external_deps ced JOIN classes c ON ced.class_id = c.id WHERE ced.external_type LIKE :pattern ORDER BY c.class_name")
                        .bind("pattern", typePattern.replace("*", "%"))
                        .map((rs, ctx) -> Map.entry(rs.getInt("class_id"), rs.getString("class_name")))
                        .list();
    }

    public boolean hasExternalDeps() {
            return handle.createQuery("SELECT EXISTS(SELECT 1 FROM class_external_deps)").mapTo(Boolean.class).one();

    }
    public List<Integer> findHandleReferenceOwners(String kind) {
        return handle.createQuery(
                "SELECT DISTINCT to_class_id FROM dependencies WHERE kind=:kind")
                .bind("kind", kind).mapTo(Integer.class).list();
    }
    public int countUnclassifiedHandles(int classId) {
        return handle.createQuery("SELECT COUNT(*) FROM dependencies WHERE to_class_id=:id AND kind IN ('METHOD_HANDLE_REFERENCE','FIELD_HANDLE_REFERENCE')")
                .bind("id", classId).mapTo(Integer.class).one();
    }
    public int countLegacyDynamicReferences(int classId) {
        return handle.createQuery("SELECT COUNT(*) FROM method_calls WHERE invocation_kind='dynamic' AND (to_class_id=:id OR from_class_id=:id)")
                .bind("id", classId).mapTo(Integer.class).one();
    }

    public record ApiConsumer(int id, String className, String module, String sourceSet, String fromMethod, String fromDescriptor) {}
    public record ConsumerPage(long count, List<ApiConsumer> consumers) {}
    public ConsumerPage findApiConsumers(int ownerId, String kind, String name, String descriptor,
            List<Integer> ownerIds, int limit) {
        String sql; Map<String,Object> bindings;
        if (kind != null) {
            sql = kind.equals("FIELD") ? """
                SELECT DISTINCT c.id,c.class_name,c.module,c.source_set,a.from_method,a.from_descriptor FROM field_accesses a JOIN classes c ON c.id=a.from_class_id
                WHERE a.to_class_id=:id AND a.field_name=:name AND a.field_descriptor=:descriptor AND c.lifecycle='current' AND c.origin!='orphan_output'
                """ : """
                SELECT DISTINCT c.id,c.class_name,c.module,c.source_set,a.from_method,a.from_descriptor FROM method_calls a JOIN classes c ON c.id=a.from_class_id
                WHERE a.to_class_id IN (<ownerIds>) AND a.to_method=:name AND a.to_descriptor=:descriptor AND c.lifecycle='current' AND c.origin!='orphan_output'
                """;
            bindings = kind.equals("FIELD") ? Map.of("id", ownerId, "name", name, "descriptor", descriptor)
                    : Map.of("name", name, "descriptor", descriptor);
        } else {
            sql = """
                SELECT DISTINCT c.id,c.class_name,c.module,c.source_set,NULL AS from_method,NULL AS from_descriptor FROM dependencies d JOIN classes c ON c.id=d.from_class_id
                WHERE d.to_class_id=:id AND c.lifecycle='current' AND c.origin!='orphan_output'
                """;
            bindings = Map.of("id", ownerId);
        }
        String querySql = sql;
        
            var countQuery = handle.createQuery("SELECT COUNT(*) FROM (" + querySql + ")").bindMap(bindings);
            var pageQuery = handle.createQuery("SELECT * FROM (" + querySql + ") ORDER BY class_name,module,source_set,id,from_method,from_descriptor LIMIT :limit")
                    .bindMap(bindings).bind("limit", limit);
            if (kind != null && !kind.equals("FIELD")) {
                countQuery.bindList("ownerIds", ownerIds); pageQuery.bindList("ownerIds", ownerIds);
            }
            return new ConsumerPage(countQuery.mapTo(Long.class).one(), pageQuery.map((rs, ctx) -> new ApiConsumer(rs.getInt("id"),rs.getString("class_name"),rs.getString("module"),
                    rs.getString("source_set"),rs.getString("from_method"),rs.getString("from_descriptor"))).list());
        
    }

    public record MemberUsage(String className, String sourceFile, int sourceLine, String fromMethod,
            String fromDescriptor, int occurrenceCount, String evidenceLines, String bytecodeKind) {}
    public record MemberUsagePage(int groups, int occurrences, List<MemberUsage> rows) {}
    public MemberUsagePage findWorkspaceMemberUsages(int targetId, boolean field, String bytecodeName,
            String descriptor, String module, String sourceSet, int limit, int offset) {
        String table = field ? "field_accesses" : "method_calls";
        String nameColumn = field ? "field_name" : "to_method";
        String descriptorColumn = field ? "field_descriptor" : "to_descriptor";
        
        String from = " FROM " + table + " usage JOIN classes caller ON caller.id = usage.from_class_id"
                + " WHERE usage.to_class_id = :target AND usage." + nameColumn + " = :name"
                + " AND usage." + descriptorColumn + " = :descriptor"
                + " AND caller.lifecycle = 'current' AND caller.origin NOT IN ('dependency', 'orphan_output')"
                + " AND caller.module = :module AND caller.source_set = :sourceSet";
        
            var count = handle.createQuery("SELECT COUNT(*) AS groups, COALESCE(SUM(usage.occurrence_count), 0) AS occurrences" + from)
                    .bind("target", targetId).bind("name", bytecodeName).bind("descriptor", descriptor)
                    .bind("module", module).bind("sourceSet", sourceSet)
                    .map((row, ctx) -> new int[]{row.getInt("groups"),row.getInt("occurrences")}).one();
            String sql = "SELECT caller.class_name,caller.source_file,caller.source_line,usage.from_method,usage.from_descriptor,usage.occurrence_count,usage.evidence_lines,usage."
                    + (field ? "access_kind" : "invocation_kind") + " AS bytecode_kind" + from
                    + " ORDER BY caller.class_name,usage.from_method,usage.from_descriptor,bytecode_kind,caller.id LIMIT :limit OFFSET :offset";
            var rows = handle.createQuery(sql).bind("target", targetId).bind("name", bytecodeName)
                    .bind("descriptor", descriptor).bind("module", module).bind("sourceSet", sourceSet)
                    .bind("limit", limit).bind("offset", offset).map((rs, ctx) -> new MemberUsage(rs.getString("class_name"),
                            rs.getString("source_file"),rs.getInt("source_line"),rs.getString("from_method"),rs.getString("from_descriptor"),
                            rs.getInt("occurrence_count"),rs.getString("evidence_lines"),rs.getString("bytecode_kind"))).list();
            return new MemberUsagePage(count[0], count[1], rows);
        
    }

    public List<Map.Entry<Integer,String>> findMethodOwnerCandidates(String name, String descriptor) {
        return handle.createQuery("""
                SELECT DISTINCT c.id,c.class_name FROM method_calls mc JOIN classes c ON c.id=mc.to_class_id
                WHERE mc.to_method=:name AND mc.to_descriptor=:descriptor
                  AND c.lifecycle='current' AND c.origin!='orphan_output'
                """).bind("name",name).bind("descriptor",descriptor)
                .map((rs,ctx) -> Map.entry(rs.getInt("id"),rs.getString("class_name"))).list();
    }

    /** Members of the target each caller invokes, at most 32 per caller, most frequent first. */
    public Map<Integer, List<InvokedMemberView>> findInvokedMembers(
            int targetClassId, Collection<Integer> callerClassIds) {
        if (callerClassIds == null || callerClassIds.isEmpty()) return Map.of();
        List<Integer> ids = List.copyOf(new LinkedHashSet<>(callerClassIds));
        Map<Integer, List<InvokedMemberView>> result = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += 500) {
            handle.createQuery("""
                    WITH members AS (
                      SELECT from_class_id, to_method, to_descriptor, invocation_kind,
                             SUM(occurrence_count) AS instruction_occurrences
                      FROM method_calls
                      WHERE to_class_id = :target AND from_class_id IN (<callers>)
                        AND to_method != '<init>'
                        AND invocation_kind IN ('virtual', 'interface', 'static', 'special')
                      GROUP BY from_class_id, to_method, to_descriptor, invocation_kind
                    ), ranked AS (
                      SELECT *, COUNT(*) OVER (PARTITION BY from_class_id) AS group_count,
                        ROW_NUMBER() OVER (PARTITION BY from_class_id
                          ORDER BY instruction_occurrences DESC, to_method, to_descriptor, invocation_kind) AS position
                      FROM members
                    )
                    SELECT * FROM ranked WHERE position <= 32 ORDER BY from_class_id, position
                    """)
                    .bind("target", targetClassId)
                    .bindList("callers", ids.subList(from, Math.min(from + 500, ids.size())))
                    .map((rs, ctx) -> new InvokedMemberView(rs.getInt("from_class_id"),
                            rs.getString("to_method"), rs.getString("to_descriptor"),
                            rs.getString("invocation_kind"), rs.getLong("instruction_occurrences"),
                            rs.getInt("group_count")))
                    .forEach(member -> result.computeIfAbsent(member.fromClassId(),
                            ignored -> new ArrayList<>()).add(member));
            }
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return result;
    }

    /** A read or write of one of the class's own fields. */
    public record OwnFieldAccess(String fromMethod, String fromDescriptor, String fieldName) {}
    /** A call out of a method, with the callee's class name. */
    public record Callee(int toClassId, String className, String toMethod, String toDescriptor, String invocationKind) {}

    /** Accesses of a class's own fields from its own methods and lambdas. */
    public List<OwnFieldAccess> findOwnFieldAccesses(int classId) {
        return handle.createQuery("SELECT from_method, from_descriptor, field_name FROM field_accesses WHERE from_class_id = :id AND to_class_id = :id")
                .bind("id", classId)
                .map((rs, ctx) -> new OwnFieldAccess(rs.getString("from_method"), rs.getString("from_descriptor"), rs.getString("field_name"))).list();
    }

    /** Own instance fields one method reads. */
    public List<String> findOwnInstanceFieldReads(int classId, String method, String descriptor) {
        return handle.createQuery("""
                SELECT field_name FROM field_accesses
                WHERE from_class_id = :id AND to_class_id = :id AND from_method = :name
                  AND from_descriptor = :descriptor AND access_kind = 'read_instance'""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor).mapTo(String.class).list();
    }

    /** Whether a method calls a lambda body that has no method_evidence row. */
    public boolean hasLambdaCallWithoutEvidence(int classId, String method, String descriptor) {
        return handle.createQuery("""
                SELECT count(*) FROM method_calls mc
                LEFT JOIN method_evidence me ON me.from_class_id = mc.to_class_id
                  AND me.from_method = mc.to_method AND me.from_descriptor = mc.to_descriptor
                WHERE mc.from_class_id = :id AND mc.from_method = :name AND mc.from_descriptor = :descriptor
                  AND mc.to_method LIKE 'lambda$%' AND me.from_class_id IS NULL""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor).mapTo(Integer.class).one() > 0;
    }

    /** A method's indexed callees in source order. */
    public List<Callee> findCallees(int classId, String method, String descriptor) {
        return handle.createQuery("""
                SELECT mc.to_class_id, c.class_name, mc.to_method, mc.to_descriptor, mc.invocation_kind
                FROM method_calls mc JOIN classes c ON c.id = mc.to_class_id
                WHERE mc.from_class_id = :id AND mc.from_method = :name AND mc.from_descriptor = :descriptor
                ORDER BY mc.evidence_lines""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor)
                .map((rs, ctx) -> new Callee(rs.getInt("to_class_id"), rs.getString("class_name"), rs.getString("to_method"),
                        rs.getString("to_descriptor"), rs.getString("invocation_kind"))).list();
    }

    /** The raw caller_external_calls JSON of a method's evidence row. */
    public Optional<String> findCallerExternalCalls(int classId, String method, String descriptor) {
        return handle.createQuery("""
                SELECT caller_external_calls FROM method_evidence
                WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor).mapTo(String.class).findOne();
    }

    /** A production class calling an interface, with the distinct {@code name+descriptor} methods it calls. */
    public record ContractCaller(String className, String sourceFile, String methods) {}

    /** Source files of test classes that depend on the class. */
    public List<String> findTestSourceFilesDependingOn(int classId) {
        return handle.createQuery("""
                SELECT DISTINCT tc.source_file
                FROM dependencies d JOIN classes tc ON tc.id = d.from_class_id
                WHERE d.to_class_id = :id AND tc.source_set = 'test' AND tc.source_file IS NOT NULL
                ORDER BY tc.source_file""")
                .bind("id", classId).mapTo(String.class).list();
    }

    /** Production files that inject, extend or implement, or call the class. */
    public List<String> findRelatedSourceFiles(int classId, String className) {
        return handle.createQuery("""
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
                ORDER BY 1""")
                .bind("name", className).bind("generic", "%<" + className + ">%")
                .bind("interface", "%\"" + className + "\"%").bind("id", classId).mapTo(String.class).list();
    }

    /**
     * Current production classes calling an interface, or calling a direct implementor's instance method the
     * interface declares.
     */
    public List<ContractCaller> findContractCallers(int interfaceId, String interfaceName) {
        return handle.createQuery("""
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
                .bind("id", interfaceId).bind("type", "%\"" + interfaceName + "\"%")
                .map((rs, ctx) -> new ContractCaller(rs.getString("class_name"), rs.getString("source_file"), rs.getString("methods"))).list();
    }

    /** A call from a method: the callee, and for a lambda the JSON lines it is created on. */
    public record CallTarget(int toClassId, String toMethod, String toDescriptor, String evidenceLines) {}

    /** The lambda bodies a method creates, with the lines creating them. */
    public List<CallTarget> findLambdaCalls(int classId, String method, String descriptor) {
        return handle.createQuery("""
                SELECT to_class_id, to_method, to_descriptor, evidence_lines FROM method_calls
                WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor AND to_method LIKE 'lambda$%'""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor)
                .map((rs, ctx) -> new CallTarget(rs.getInt("to_class_id"), rs.getString("to_method"), rs.getString("to_descriptor"),
                        rs.getString("evidence_lines"))).list();
    }

    /** Every call out of a method, unordered and whether or not the callee class is indexed. */
    public List<CallTarget> findCallTargets(int classId, String method, String descriptor) {
        return handle.createQuery("""
                SELECT to_class_id, to_method, to_descriptor FROM method_calls
                WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor)
                .map((rs, ctx) -> new CallTarget(rs.getInt("to_class_id"), rs.getString("to_method"), rs.getString("to_descriptor"), null)).list();
    }

    /** Every call out of a method into an indexed class, unordered; {@code invocationKind} is not read. */
    public List<Callee> findIndexedCallees(int classId, String method, String descriptor) {
        return handle.createQuery("""
                SELECT mc.to_class_id, mc.to_method, mc.to_descriptor, c.class_name FROM method_calls mc
                JOIN classes c ON c.id = mc.to_class_id
                WHERE mc.from_class_id = :id AND mc.from_method = :name AND mc.from_descriptor = :descriptor""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor)
                .map((rs, ctx) -> new Callee(rs.getInt("to_class_id"), rs.getString("class_name"), rs.getString("to_method"),
                        rs.getString("to_descriptor"), null)).list();
    }

    /** A read of a field: the reading class, its source file, method and the JSON lines of the read. */
    public record FieldRead(String reader, String sourceFile, String fromMethod, String evidenceLines) {}

    /** Reads of {@code field} declared by any class named {@code owner}, by reader and method. */
    public List<FieldRead> findFieldReads(String owner, String field) {
        return handle.createQuery("""
                SELECT fc.class_name AS reader, fc.source_file, a.from_method, a.evidence_lines
                FROM field_accesses a JOIN classes tc ON tc.id=a.to_class_id JOIN classes fc ON fc.id=a.from_class_id
                WHERE tc.class_name=:owner AND a.field_name=:field AND a.access_kind LIKE 'read%'
                ORDER BY fc.class_name, a.from_method""")
                .bind("owner", owner).bind("field", field)
                .map((rs, ctx) -> new FieldRead(rs.getString("reader"), rs.getString("source_file"), rs.getString("from_method"),
                        rs.getString("evidence_lines"))).list();
    }
}
