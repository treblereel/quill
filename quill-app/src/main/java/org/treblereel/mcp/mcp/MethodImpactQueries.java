package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.treblereel.mcp.db.ReadQueryContext;

/** Direct selected-member references, without embedding the same graph in several test views. */
final class MethodImpactQueries {
    private static final ObjectMapper JSON=new ObjectMapper();
    ObjectNode get(ReadQueryContext db,String target,String selector,int limit) throws Exception {
        int parameters=selector.indexOf('(');
        String name=parameters<0?selector:selector.substring(0,parameters);
        String signature=parameters<0?null:selector.substring(parameters);
        ObjectNode refs=(ObjectNode)JSON.readTree(new SymbolUsageQueries().findSymbolUsages(
                db,target,name,"METHOD",signature,null,limit,0));
        if(refs.has("error") || refs.has("error_code"))return refs;
        ObjectNode result=JSON.createObjectNode().put("kind","direct_method_impact")
                .put("scope","direct selected-member consumers; bounded indirect paths reported separately")
                .put("runtime_execution_proven",false);
        copy(refs,result,"target","name","descriptor","signature","symbol_id","location","coverage",
                "total","showing","limit","offset","has_more","truncated","limitations","_meta");
        var lookup=ClassTargetResolver.resolve(db,target);
        if(lookup.error()!=null)return ClassTargetResolver.errorResponse(JSON,lookup,target);
        result.put("class",lookup.cls().className());
        if(lookup.cls().sourceFile()!=null)result.put("declaration_file",lookup.cls().sourceFile());
        var production=result.putArray("production_callers");var tests=result.putArray("test_callers");
        var unknown=result.putArray("unclassified_callers");
        var classes=db.symbols.findAllClasses();
        Set<String> files=new HashSet<>(),methods=new HashSet<>();int occurrences=0,invocations=0;
        for(var usage:refs.path("usages")){
            var caller=usage.path("caller");String source=caller.path("source").asText();
            var matches=classes.stream().filter(c->c.className().equals(caller.path("class").asText()))
                    .filter(c->caller.path("module").asText().isEmpty() || Objects.equals(c.module(),caller.path("module").asText()))
                    .filter(c->source.isEmpty() || Objects.equals(c.sourceFile(),source)).toList();
            ArrayNode group=matches.size()!=1?unknown:TestImpactQueries.isTestClass(matches.getFirst())?tests:production;
            ObjectNode row=group.addObject();row.set("caller",caller.deepCopy());
            copy((ObjectNode)usage,row,"usage_kind","execution_semantics","invocation_kind","occurrence_count","evidence_lines");
            int count=usage.path("occurrence_count").asInt();occurrences+=count;
            if("invocation_instruction".equals(usage.path("execution_semantics").asText()))invocations+=count;
            if(!source.isEmpty())files.add(source);
            methods.add(caller.toString());
        }
        ObjectNode overrides=(ObjectNode)JSON.readTree(new MethodOverrideQueries().findMethodOverrides(
                db,lookup.cls().className(),refs.path("name").asText(),refs.path("descriptor").asText(),true,limit,0));
        ObjectNode contracts=result.putObject("override_evidence");
        if(overrides.has("base_declarations"))contracts.set("selected_declarations",overrides.get("base_declarations"));
        copy(overrides,contracts,"overrides","showing","total","has_more","truncated","limitations","error","error_code");
        // base_declarations is the selected method, not its implemented interface contracts.
        var interfaces=result.putArray("interface_contracts");var absent=result.putArray("unindexed_supertypes");
        Set<String> visited=new HashSet<>();Deque<String> pending=new ArrayDeque<>();
        pending.addAll(lookup.cls().interfaces());
        if(lookup.cls().superclass()!=null)pending.add(lookup.cls().superclass());
        String args=refs.path("descriptor").asText().split("\\)",2)[0];
        boolean interfaceComplete=true;
        while(!pending.isEmpty()){
            String parent=pending.removeFirst();if(parent.equals("java.lang.Object") || !visited.add(parent))continue;
            if(visited.size()>500){interfaceComplete=false;break;}
            var parents=classes.stream().filter(c->c.className().equals(parent)).toList();
            if(parents.size()!=1){absent.add(parent);interfaceComplete=false;continue;}
            var ancestor=parents.getFirst();pending.addAll(ancestor.interfaces());
            if(ancestor.superclass()!=null)pending.add(ancestor.superclass());
            if(!"INTERFACE".equalsIgnoreCase(ancestor.kind()))continue;
            for(var member:db.symbols.findClassMembers(ancestor.id()))
                if(member.name().equals(refs.path("name").asText()) && member.descriptor()!=null
                        && member.descriptor().split("\\)",2)[0].equals(args)
                        && !String.valueOf(member.modifiers()).contains("static") && !String.valueOf(member.modifiers()).contains("private")){
                    if(interfaces.size()>=limit){interfaceComplete=false;continue;}
                    var contract=interfaces.addObject().put("class",parent).put("method",member.name()).put("descriptor",member.descriptor());
                    if(ancestor.sourceFile()!=null)contract.put("file",ancestor.sourceFile());
                }
        }
        result.put("interface_contracts_complete",interfaceComplete);
        boolean complete=refs.path("answer_complete").asBoolean() && unknown.isEmpty()
                && !refs.path("has_more").asBoolean() && !refs.path("truncated").asBoolean()
                && !overrides.has("error") && !overrides.has("error_code")
                && !overrides.path("has_more").asBoolean() && !overrides.path("truncated").asBoolean() && interfaceComplete;
        var counts=result.putObject("reference_page_counts");
        counts.put("scope","reference page only; excludes override and interface files")
                .put("caller_methods",methods.size()).put("reference_groups",refs.path("usages").size())
                .put("reference_occurrences",occurrences).put("invocation_occurrences",invocations)
                .put("consumer_files",files.size()).put("complete",complete);
        if(lookup.cls().sourceFile()!=null)files.add(lookup.cls().sourceFile());
        counts.put("files_including_declaration",files.size());
        var categories=result.putObject("counts");
        categories.set("production",categoryCounts(production));categories.set("tests",categoryCounts(tests));
        categories.set("unclassified",categoryCounts(unknown));
        categories.putObject("total").put("caller_methods",methods.size()).put("call_sites",invocations)
                .put("consumer_files",counts.path("consumer_files").asInt()).put("files_including_declaration",files.size());
        categories.put("call_site_definition","indexed invocation instructions, not caller methods or runtime executions");
        result.set("production_callers",group(production));result.set("test_callers",group(tests));
        result.set("declaring_class_tests",declaringClassTests(db,lookup.cls().id(),limit));
        result.set("unclassified_callers",group(unknown));
        ObjectNode upstream=(ObjectNode)JSON.readTree(new CallHierarchyQueries().getCallHierarchy(
                db,lookup.cls().className(),refs.path("name").asText(),refs.path("descriptor").asText(),"inbound",true,4,limit,0));
        var indirect=JSON.createArrayNode();
        for(var edge:upstream.path("calls")){
            if(edge.path("depth").asInt()<=1)continue;
            var caller=edge.path("caller");
            var owners=classes.stream().filter(c->c.className().equals(caller.path("class").asText()))
                    .filter(c->caller.path("module").asText().isEmpty() || Objects.equals(c.module(),caller.path("module").asText())).toList();
            if(owners.size()!=1 || TestImpactQueries.isTestClass(owners.getFirst()))continue;
            var row=indirect.addObject();var who=((ObjectNode)caller).deepCopy();
            if(owners.getFirst().sourceFile()!=null)who.put("source",owners.getFirst().sourceFile());row.set("caller",who);
            copy((ObjectNode)edge,row,"depth","edge_kind","execution_semantics","evidence_lines","occurrence_count");
            // Ordinary calls omit fields declared once in edge_defaults.
            upstream.path("edge_defaults").properties().forEach(d->{if(!row.has(d.getKey()))row.set(d.getKey(),d.getValue());});
            var to=row.putObject("to");if(edge.path("callee") instanceof ObjectNode callee)copy(callee,to,"class","method","descriptor");
        }
        result.set("indirect_production",group(indirect));
        var indirectCounts=categoryCounts(indirect);int references=0,callbacks=0;
        for(var edge:indirect){int count=edge.path("occurrence_count").asInt();references+=count;
            if("callback_creation".equals(edge.path("execution_semantics").asText()))callbacks+=count;}
        indirectCounts.put("reference_groups",indirect.size()).put("reference_instruction_sites",references)
                .put("callback_reference_sites",callbacks);
        result.set("indirect_counts",indirectCounts);
        var bound=result.putObject("indirect_scope").put("max_depth",4).put("transitive_closure_proven",false)
                .put("basis","indexed upstream paths; callback creation is not proof of runtime invocation");
        copy(upstream,bound,"has_more","traversal_truncated","error","error_code");
        result.put("answer_scope","direct consumers and contracts, not an exhaustive runtime or transitive blast radius");
        result.put("answer_complete",complete).put("evidence_detail","summary; full per-reference source/dispatch evidence available via find_symbol_usages")
                .put("test_scope","direct static references; not measured runtime test coverage or transitive test ranking");
        return result;
    }
    /**
     * Tests that reference the declaring class, whatever member they use. A private method has no
     * test callers, so in V24-V27 CQ-049 Claude globbed for PermissionMapper tests 24 times in 14
     * sessions to find where a new test belongs.
     */
    private static ArrayNode declaringClassTests(ReadQueryContext db,int classId,int limit){
        var names=db.jdbi().withHandle(h->h.createQuery("""
                SELECT DISTINCT tc.class_name FROM classes tc
                WHERE tc.id IN (SELECT from_class_id FROM dependencies WHERE to_class_id = :id
                                UNION SELECT from_class_id FROM method_calls WHERE to_class_id = :id)
                  AND tc.id <> :id AND tc.lifecycle = 'current'
                  AND (tc.source_set = 'test' OR tc.source_file LIKE '%src/test/%')
                ORDER BY tc.class_name LIMIT :limit""").bind("id",classId).bind("limit",limit).mapTo(String.class).list());
        var result=JSON.createArrayNode();names.forEach(result::add);return result;
    }
    private static ObjectNode categoryCounts(ArrayNode rows){
        Set<String> methods=new HashSet<>(),files=new HashSet<>();int calls=0;
        for(var row:rows){var caller=row.path("caller");methods.add(caller.toString());
            if(!caller.path("source").asText().isEmpty())files.add(caller.path("source").asText());
            if("invocation_instruction".equals(row.path("execution_semantics").asText()))calls+=row.path("occurrence_count").asInt();}
        return JSON.createObjectNode().put("methods",methods.size()).put("call_sites",calls).put("files",files.size());
    }
    private static ArrayNode group(ArrayNode rows){
        Map<String,ObjectNode> groups=new LinkedHashMap<>();Map<String,ArrayNode> sourceRows=new LinkedHashMap<>();
        for(var row:rows){var caller=row.path("caller");String key=caller.path("class").asText()+"|"+caller.path("source").asText()+"|"+caller.path("module").asText();
            var group=groups.computeIfAbsent(key,k->{var node=JSON.createObjectNode();
                copy((ObjectNode)caller,node,"class","source","module","origin");node.putArray("methods");return node;});
            sourceRows.computeIfAbsent(key,k->JSON.createArrayNode()).add(row);
            var method=((ObjectNode)row).deepCopy();method.remove("caller");
            copy((ObjectNode)caller,method,"method","descriptor");((ArrayNode)group.get("methods")).add(method);
        }
        var result=JSON.createArrayNode();for(var entry:groups.entrySet()){
            entry.getValue().set("counts",categoryCounts(sourceRows.get(entry.getKey())));result.add(entry.getValue());}
        return result;
    }
    private static void copy(ObjectNode from,ObjectNode to,String... keys){for(String key:keys)if(from.has(key))to.set(key,from.get(key).deepCopy());}
}
