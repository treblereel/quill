package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.core.SourceFileHashes;

/** Resolve literal HTTP operations, then reuse bounded method, DI and configuration queries. */
final class EndpointPrefetch {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern ROUTE=Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(/[^\\s`\"<>]*)");
    static boolean hasRoute(String prompt){return ROUTE.matcher(prompt).find();}
    static ObjectNode collect(Jdbi db,Path root,String prompt) throws Exception {
        return collect(db,root,prompt,false);
    }
    static ObjectNode collect(Jdbi db,Path root,String prompt,boolean alreadyDelivered) throws Exception {
        var metadata=IndexReader.getMetadata(db);
        var result=JSON.createObjectNode().put("basis","indexed HTTP endpoint and direct method evidence")
                .put("status","ready").put("index_id",metadata.getOrDefault("index_id","unknown"));
        var facts=result.putArray("facts");var ambiguities=result.putArray("ambiguities");var unavailable=result.putArray("unavailable");
        if(!root.toRealPath().equals(Path.of(metadata.getOrDefault("project_root","")).toRealPath()))
            return result.put("status","index_root_mismatch");
        var matcher=ROUTE.matcher(prompt);if(!matcher.find())return result;
        String verb=matcher.group(1).toUpperCase(Locale.ROOT),path=routePath(matcher.group(2));
        while(matcher.find())if(!verb.equals(matcher.group(1).toUpperCase(Locale.ROOT)) || !path.equals(routePath(matcher.group(2)))){
            ambiguities.addObject().put("status","multiple_http_operations");return result;
        }
        if(path.length()>4096){unavailable.addObject().put("status","route_limit");return result;}
        var endpoints=JSON.readTree(new FrameworkEndpointQueries().findFrameworkEndpoints(db,null,verb,path,null,false,false,20,0));
        if(endpoints.has("error") || endpoints.has("error_code")){unavailable.add(endpoints);return result;}
        List<JsonNode> matches=new ArrayList<>();
        for(var endpoint:endpoints.path("endpoints"))for(var route:endpoint.path("paths"))
            if(route.asText().equals(path)){matches.add(endpoint);break;}
        if(matches.size()!=1 || endpoints.path("has_more").asBoolean()){
            (matches.isEmpty()?unavailable:ambiguities).addObject().put("http_method",verb).put("path",path)
                    .put("status",matches.isEmpty()?"endpoint_not_found":"endpoint_ambiguous");return result;
        }
        JsonNode endpoint=matches.getFirst();
        Set<String> owners=new LinkedHashSet<>();owners.add(endpoint.path("declaring_class").asText());
        for(var call:endpoint.path("direct_project_calls")){if(owners.size()>=3)break;owners.add(call.path("class").asText());}
        if(!sourcesCurrent(db,root,owners))return result.put("status","source_hash_unavailable_or_changed");
        result.put("endpoint_anchor",verb+" "+path);
        if(alreadyDelivered)return result.put("already_delivered",true);
        facts.addObject().put("kind","endpoint").set("endpoint",endpoint);
        var methods=JSON.createArrayNode();Set<String> seen=new HashSet<>();
        addMethod(db,methods,seen,endpoint.path("declaring_class").asText(),endpoint.path("method").asText(),endpoint.path("descriptor").asText(),true);
        for(var call:endpoint.path("direct_project_calls")){
            if(methods.size()>=3)break;
            addMethod(db,methods,seen,call.path("class").asText(),call.path("method").asText(),call.path("descriptor").asText(),false);
        }
        facts.addObject().put("kind","operation_methods").put("method_context_limit",3)
                .set("methods",methods);
        if(!sourcesCurrent(db,root,owners)){facts.removeAll();result.remove("endpoint_anchor");return result.put("status","source_hash_unavailable_or_changed");}
        result.putArray("limitations").add("Only exact indexed production HTTP routes and at most two direct project callees; static calls do not prove runtime dispatch.")
                .add("Only the HTTP handler body is preloaded; callee bodies remain available on demand. Compiler-generated helpers are not independent declaration targets.")
                .add("Injection fields are restricted to declared fields accessed by each method. Configuration references are class-level candidates, not proven method reads; values are not indexed.")
                .add("DI uses the indexed default application context, not an inferred runtime profile. Previously emitted facts are suppressed per session; follow-up questions reuse the selected operation until another explicit target or route is given.");
        return result;
    }
    private static String routePath(String value){
        String path=value.replaceAll("[.,;:!?)]+$","");int query=path.indexOf('?');if(query>=0)path=path.substring(0,query);
        while(path.length()>1 && path.endsWith("/"))path=path.substring(0,path.length()-1);return path;
    }
    private static boolean sourcesCurrent(Jdbi db,Path root,Set<String> owners) throws Exception {
        var hashes=JSON.readTree(IndexReader.getMetadata(db).getOrDefault("java_source_hashes","{}"));
        for(String owner:owners){
            var lookup=ClassTargetResolver.resolve(db,owner);if(lookup.error()!=null || lookup.cls().sourceFile()==null)return false;
            Path file=root.resolve(lookup.cls().sourceFile()).toRealPath();if(!file.startsWith(root.toRealPath()))return false;
            String expected=hashes.path(root.toRealPath().relativize(file).toString().replace('\\','/')).asText();
            if(expected.isBlank() || !expected.equals(SourceFileHashes.hash(file)))return false;
        }
        return true;
    }
    private static void addMethod(Jdbi db,ArrayNode methods,Set<String> seen,String owner,String name,String descriptor,boolean includeSource) throws Exception {
        if(!seen.add(owner+"#"+name+descriptor))return;
        var lookup=ClassTargetResolver.resolve(db,owner);if(lookup.error()!=null)return;
        ClassRecord cls=lookup.cls();
        if(IndexReader.findClassMembers(db,cls.id()).stream().noneMatch(m->m.name().equals(name) && m.descriptor().equals(descriptor)))return;
        var node=methods.addObject().put("class",owner).put("method",name).put("descriptor",descriptor);
        ObjectNode source=includeSource?(ObjectNode)new MethodContextQueries().get(db,cls,name+descriptor):
                JSON.createObjectNode().put("source_complete",false).put("status","indexed_target_only").put("source_file",cls.sourceFile());
        if(source.has("error") || source.has("error_code")){
            ObjectNode compact=JSON.createObjectNode().put("source_complete",false);
            for(String key:List.of("error","error_code","status"))if(source.has(key))compact.set(key,source.get(key));source=compact;
        }
        for(String key:List.of("source_line","source_end_line"))if(source.path("declaration").has(key))source.set(key,source.path("declaration").get(key));
        source.remove("declaration");
        if(source.path("source").asText().length()>4000){source.remove("source");source.put("source_complete",false).put("status","prefetch_source_limit");}
        node.set("source_evidence",source);
        Set<String> fields=db.withHandle(h->new HashSet<>(h.createQuery("SELECT DISTINCT field_name FROM field_accesses WHERE from_class_id=:id AND to_class_id=:id AND from_method=:method AND from_descriptor=:descriptor")
                .bind("id",cls.id()).bind("method",name).bind("descriptor",descriptor).mapTo(String.class).list()));
        JsonNode injection=JSON.readTree(new StructureToolQueries().getInjectionPoints(db,owner));
        var selected=node.putObject("injection");
        for(String key:List.of("target","scope","bean_kind","error","error_code","resolution_context"))
            if(injection.has(key))selected.set(key,injection.get(key));
        var points=selected.putArray("injection_points");
        for(var point:injection.path("injection_points"))if(fields.contains(point.path("field").asText())){
            ObjectNode compact=((ObjectNode)point).deepCopy();
            if("resolved".equals(point.path("resolution").asText()))compact.remove("resolution_trace");
            points.add(compact);
        }
        JsonNode config=JSON.readTree(new ConfigurationReferenceQueries().find(db,null,owner,null,null,8,0));
        var references=node.putObject("configuration_references");
        for(String key:List.of("has_more","total","error","error_code"))if(config.has(key))references.set(key,config.get(key));
        var entries=references.putArray("references");
        for(var reference:config.path("references")){
            var compact=entries.addObject();
            for(String key:List.of("entry_type","key","file","line","member","source","annotation","resolution_status"))
                if(reference.has(key))compact.set(key,reference.get(key));
        }
    }
    private EndpointPrefetch(){}
}
