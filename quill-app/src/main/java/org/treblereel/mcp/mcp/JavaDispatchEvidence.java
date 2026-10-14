package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.JvmMethodResolver;
import org.treblereel.mcp.db.IndexReader.MethodCallView;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/** Bounded hierarchy candidates. Receiver values and runtime execution are never inferred. */
final class JavaDispatchEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int OWNER_CAP = 5000, CALL_CAP = 5000, TARGET_LIMIT = 20;
    private final Jdbi db;
    private JvmMethodResolver resolver;
    private List<ClassRecord> classes;
    private Map<String,List<ClassRecord>> owners;
    private Map<Integer,List<ClassMemberRecord>> members;
    private final Map<String,ObjectNode> targets = new HashMap<>();
    JavaDispatchEvidence(Jdbi db) { this.db = db; }
    private void load() {
        if (classes != null) return;
        classes = IndexReader.findAllClasses(db);
        owners = new HashMap<>();
        for (var cls : classes) owners.computeIfAbsent(cls.className(), ignored -> new ArrayList<>()).add(cls);
        members = classes.size() > OWNER_CAP ? Map.of() : IndexReader.findClassMembers(db, classes.stream().map(ClassRecord::id).toList());
    }
    void appendTarget(ObjectNode edge, MethodCallView call) {
        String key = call.toClassId() + ":" + call.toMethod() + call.toDescriptor() + ":" + call.invocationKind();
        edge.set("dispatch", targets.computeIfAbsent(key, ignored -> target(call)));
    }
    private ObjectNode target(MethodCallView call) {
        var result = JSON.createObjectNode().put("target_basis", "bytecode_symbolic_reference")
                .put("invocation_kind", call.invocationKind()).put("execution_proven", false)
                .put("runtime_target_proven", false).put("candidate_coverage_complete", false);
        result.putObject("declared_target").put("class",call.toClass()).put("name",call.toMethod()).put("descriptor",call.toDescriptor());
        if (resolver == null) resolver = new JvmMethodResolver(db);
        var resolution = resolver.resolve(call.toClass(),call.toMethod(),call.toDescriptor());
        result.put("declaration_resolution",resolution.status());
        if (resolution.resolved()) {
            var declaration = result.putObject("resolved_declaration");
            declaration.put("class",resolution.owner().className()).put("name",call.toMethod())
                    .put("descriptor",call.toDescriptor()).put("symbol_id",SymbolContract.id(resolution.owner(),
                            resolution.member().kind(),call.toMethod(),call.toDescriptor()));
            declaration.set("lookup_path",JSON.valueToTree(resolution.path()));
            declaration.put("runtime_target_proven",false);
            appendBridge(declaration,resolution);
        }
        var values = result.putArray("possible_targets");
        if (!Set.of("virtual","interface").contains(call.invocationKind())) {
            result.put("status", "dynamic".equals(call.invocationKind()) ? "dynamic_reference" : "fixed_symbolic_target");
            result.put("possible_target_count",0).put("truncated",false); return result;
        }
        load();
        if (classes.size() > OWNER_CAP) return result.put("status","hierarchy_limit").put("truncated",true);
        var bases = owners.getOrDefault(call.toClass(),List.of());
        if (bases.size()!=1) return result.put("status","owner_ambiguous").put("truncated",false);
        ClassRecord base = resolution.resolved() ? resolution.owner() : bases.getFirst();
        var declarations = IndexReader.findJvmClassMembers(db,base.id()).stream().filter(m -> "METHOD".equals(m.kind())
                && call.toMethod().equals(m.name()) && call.toDescriptor().equals(m.descriptor())).toList();
        if (declarations.size()!=1) return result.put("status","declaration_not_indexed").put("truncated",false);
        ClassMemberRecord declaration = declarations.getFirst();
        if (fixed(declaration)) return result.put("status","fixed_symbolic_target").put("possible_target_count",0).put("truncated",false);
        int count = 0;
        for (var child : classes.stream().sorted(Comparator.comparing(ClassRecord::className).thenComparingInt(ClassRecord::id)).toList()) {
            if (child.id()==base.id()) continue;
            if(path(child,bases.getFirst()).isEmpty()) continue;
            List<String> path = path(child,base);
            if (path.isEmpty()) continue;
            for (var candidate : members.getOrDefault(child.id(),List.of())) {
                if (!candidate(declaration,base,candidate,child) || modifier(candidate,"abstract")) continue;
                count++;
                if (values.size() >= TARGET_LIMIT) continue;
                var node = values.addObject().put("class",child.className()).put("name",candidate.name())
                        .put("descriptor",candidate.descriptor()).put("symbol_id",SymbolContract.id(child,"METHOD",candidate.name(),candidate.descriptor()))
                        .put("evidence_kind","indexed_override_shape").put("runtime_target_proven",false);
                var forward = new ArrayList<>(path); Collections.reverse(forward);
                node.set("hierarchy_path",JSON.valueToTree(forward));
            }
        }
        return result.put("status","hierarchy_candidates").put("possible_target_count",count)
                .put("limit",TARGET_LIMIT).put("truncated",count>values.size());
    }
    private void appendBridge(ObjectNode declaration, JvmMethodResolver.Resolution resolution) {
        if (!JvmMethodResolver.flag(resolution.member(),"bridge")) return;
        var bridge=declaration.putObject("bridge").put("evidence_kind","compiler_bridge_bytecode")
                .put("runtime_target_proven",false);
        var calls=IndexReader.findMethodCalls(db,resolution.owner().id(),resolution.member().name(),
                resolution.member().descriptor(),"outbound",21,0);
        var targets=bridge.putArray("invocation_targets");
        for (var call : calls.stream().limit(20).toList()) targets.addObject()
                .put("class",call.toClass()).put("name",call.toMethod()).put("descriptor",call.toDescriptor())
                .put("invocation_kind",call.invocationKind());
        bridge.put("truncated",calls.size()>20);
    }
    void appendPossibleCallers(ObjectNode response, ClassRecord selectedOwner, ClassMemberRecord selected, int limit, int offset) {
        var result = response.putObject("dispatch_context").put("evidence_kind","possible_dispatch_callers")
                .put("runtime_dispatch_proven",false).put("candidate_coverage_complete",false);
        var values = result.putArray("possible_callers");
        result.putArray("limitations").add("Candidates use indexed ancestry and erased override shapes, without receiver-value analysis")
                .add("External hierarchy nodes, erased generic relationships and framework proxies may add candidates");
        if (!"METHOD".equals(selected.kind()) || modifier(selected,"static") || modifier(selected,"private")) {
            result.put("status","not_overriding"); ToolResponseSupport.appendPage(result,0,0,limit,offset); return;
        }
        load();
        if (classes.size()>OWNER_CAP) { result.put("status","hierarchy_limit").put("truncated",true); return; }
        if (owners.getOrDefault(selectedOwner.className(),List.of()).size()!=1) {
            result.put("status","owner_ambiguous"); ToolResponseSupport.appendPage(result,0,0,limit,offset); return;
        }
        List<ObjectNode> found = new ArrayList<>();
        boolean capped = false;
        outer: for (var ancestor : classes) {
            if (ancestor.id()==selectedOwner.id()) continue;
            var path = path(selectedOwner,ancestor);
            if (path.isEmpty()) continue;
            for (var declaration : members.getOrDefault(ancestor.id(),List.of())) {
                if (!candidate(declaration,ancestor,selected,selectedOwner)) continue;
                var calls = IndexReader.findExactMethodUsages(db,ancestor.id(),declaration.name(),declaration.descriptor(),CALL_CAP+1,0);
                for (var call : calls) {
                    if (!Set.of("virtual","interface").contains(call.invocationKind())) continue;
                    if (found.size() >= CALL_CAP) { capped=true; break outer; }
                    var node = JSON.createObjectNode().put("invocation_kind",call.invocationKind()).put("occurrence_count",call.occurrenceCount())
                            .put("runtime_dispatch_proven",false);
                    node.putObject("caller").put("class",call.fromClass()).put("method",call.fromMethod()).put("descriptor",call.fromDescriptor())
                            .put("source",call.fromSource()).put("module",call.fromModule());
                    node.putObject("declared_target").put("class",ancestor.className()).put("name",declaration.name())
                            .put("descriptor",declaration.descriptor()).put("symbol_id",SymbolContract.id(ancestor,"METHOD",declaration.name(),declaration.descriptor()));
                    node.set("evidence_lines",JSON.valueToTree(call.evidenceLines()));
                    node.set("hierarchy_path",JSON.valueToTree(path)); found.add(node);
                }
                if (calls.size()>CALL_CAP) capped=true;
            }
        }
        found.sort(Comparator.comparing(ObjectNode::toString));
        found.stream().skip(offset).limit(limit).forEach(values::add);
        result.put("status","hierarchy_candidates").put("scan_truncated",capped);
        ToolResponseSupport.appendPage(result,values.size(),found.size(),limit,offset);
        if (result.path("has_more").asBoolean()) {
            boolean usages=response.has("kind");
            var request=result.putObject("next_page_request").put("tool",usages ? "find_symbol_usages" : "get_call_hierarchy");
            var arguments=request.putObject("arguments").put("target",SymbolContract.id(selectedOwner,"METHOD",selected.name(),selected.descriptor()))
                    .put("limit",limit).put("offset",result.path("next_offset").asInt());
            if (!usages) arguments.put("direction",response.path("direction").asText("inbound"));
        }
    }
    private List<String> path(ClassRecord child, ClassRecord ancestor) {
        if (owners.getOrDefault(child.className(),List.of()).size()!=1 || owners.getOrDefault(ancestor.className(),List.of()).size()!=1) return List.of();
        record Step(ClassRecord cls,List<String> path) {}
        var pending = new ArrayDeque<Step>(); var seen = new HashSet<Integer>();
        pending.add(new Step(child,List.of(child.className())));
        while (!pending.isEmpty() && seen.size()<OWNER_CAP) {
            var step=pending.removeFirst(); if (!seen.add(step.cls().id())) continue;
            if (step.cls().id()==ancestor.id()) return step.path();
            var parents=new ArrayList<>(step.cls().interfaces());
            if (step.cls().superclass()!=null) parents.add(step.cls().superclass());
            for (var parent : parents) {
                var matches=owners.getOrDefault(parent,List.of()); if (matches.size()!=1) continue;
                var next=new ArrayList<>(step.path()); next.add(parent); pending.addLast(new Step(matches.getFirst(),List.copyOf(next)));
            }
        }
        return List.of();
    }
    private static boolean candidate(ClassMemberRecord base,ClassRecord baseOwner,ClassMemberRecord child,ClassRecord childOwner) {
        if (!"METHOD".equals(base.kind()) || !"METHOD".equals(child.kind()) || fixed(base)
                || modifier(child,"static") || modifier(child,"private") || !base.name().equals(child.name())) return false;
        int baseEnd=base.descriptor().indexOf(')'),childEnd=child.descriptor().indexOf(')');
        if (!base.descriptor().startsWith("(") || !child.descriptor().startsWith("(") || baseEnd<0 || childEnd<0
                || !base.descriptor().substring(0,baseEnd+1).equals(child.descriptor().substring(0,childEnd+1))) return false;
        String baseReturn=base.descriptor().substring(baseEnd+1),childReturn=child.descriptor().substring(childEnd+1);
        if (baseReturn.isEmpty() || childReturn.isEmpty()) return false;
        if ((!baseReturn.startsWith("L") && !baseReturn.startsWith("["))
                || (!childReturn.startsWith("L") && !childReturn.startsWith("[")))
            if (!baseReturn.equals(childReturn)) return false;
        return modifier(base,"public") || modifier(base,"protected") || packageName(baseOwner).equals(packageName(childOwner));
    }
    private static boolean fixed(ClassMemberRecord member) { return modifier(member,"private") || modifier(member,"static") || modifier(member,"final"); }
    private static boolean modifier(ClassMemberRecord member,String value) {
        return member.modifiers()!=null && Arrays.asList(member.modifiers().split("\\s+")).contains(value);
    }
    private static String packageName(ClassRecord cls) { int dot=cls.className().lastIndexOf('.'); return dot<0 ? "" : cls.className().substring(0,dot); }
}
