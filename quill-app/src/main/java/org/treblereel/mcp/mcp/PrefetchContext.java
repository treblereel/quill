package org.treblereel.mcp.mcp;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.*;
import org.treblereel.mcp.model.*;

/** Generic identifier lookup, never a classifier of question wording or benchmark tasks. */
public final class PrefetchContext {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern IDENTIFIER=Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$.]*");
    private static final Pattern CONFIG_KEY=Pattern.compile("%?[\\p{L}_][\\p{L}\\p{N}_.-]*");
    private static final Pattern METHOD=Pattern.compile("([\\p{L}_$][\\p{L}\\p{N}_$.]*)#([\\p{L}_$][\\p{L}\\p{N}_$]*\\([^()\\n]*\\))");
    // Class#member without a parameter list names a field; injected fields carry the resolution.
    private static final Pattern FIELD=Pattern.compile("([\\p{L}_$][\\p{L}\\p{N}_$.]*)#([\\p{L}_$][\\p{L}\\p{N}_$]*)(?![\\p{L}\\p{N}_$(])");
    public static ObjectNode collect(ReadQueryContext db,Path root,String prompt) throws Exception {
        return collect(db,root,prompt,null);
    }
    public static ObjectNode collect(ReadQueryContext db,Path root,String prompt,String previousEndpoint) throws Exception {
        return collect(db,root,prompt,previousEndpoint,false);
    }
    public static ObjectNode collect(ReadQueryContext db,Path root,String prompt,String previousEndpoint,boolean deliveredCurrentIndex) throws Exception {
        WorktreeSnapshotCache.shared().invalidate(root);
        ObjectNode result=JSON.createObjectNode().put("basis","literal indexed identifiers");
        if(org.treblereel.mcp.index.BuildStatusInspector.freshness(db,0,0).structureStale())return result.put("status","index_stale");
        var metadata=db.index.getMetadata();
        if(EndpointPrefetch.hasRoute(prompt))return EndpointPrefetch.collect(db,root,prompt);
        JsonNode hashes=JSON.readTree(metadata.getOrDefault("java_source_hashes","{}"));
        var method=METHOD.matcher(prompt);
        boolean methods=method.find();
        var field=FIELD.matcher(prompt);
        boolean fields=field.find();
        if(methods || fields){
            result.put("basis",methods?"exact indexed method impact":"exact indexed injection points").put("index_id",metadata.getOrDefault("index_id","unknown"));
            var selected=result.putArray("facts");var missing=result.putArray("unavailable");result.putArray("ambiguities");
            Set<String> selectors=new HashSet<>();
            if(fields) do {
                String owner=field.group(1),name=field.group(2);
                if(!selectors.add(owner+"#"+name))continue;
                if(selectors.size()>3){missing.addObject().put("status","member_target_limit");break;}
                JsonNode point=injectionPointFact(db,owner,name,missing);
                if(!point.isNull())selected.add(point);
            }while(field.find());
            if(methods) do {
                String target=method.group(1),selector=method.group(2);
                if(!selectors.add(target+"#"+selector))continue;
                if(selectors.size()>3){missing.addObject().put("status","method_target_limit");break;}
                var lookup=ClassTargetResolver.resolve(db,target);
                if(lookup.error()!=null){missing.add(ClassTargetResolver.errorResponse(JSON,lookup,target));continue;}
                String source=lookup.cls().sourceFile();
                if(source==null){missing.addObject().put("status","source_unknown");continue;}
                Path file=root.resolve(source).toRealPath();
                String hash=hashes.path(root.toRealPath().relativize(file).toString().replace('\\','/')).asText();
                if(!file.startsWith(root.toRealPath()) || hash.isBlank() || !hash.equals(SourceFileHashes.hash(file))){missing.addObject().put("status","source_hash_unavailable_or_changed");continue;}
                ObjectNode impact=new MethodImpactQueries().get(db,target,selector,200);
                if(impact.has("error") || impact.has("error_code")){missing.add(impact);continue;}
                if(!hash.equals(SourceFileHashes.hash(file))){missing.addObject().put("status","inputs_changed");continue;}
                impact.remove("_meta");MethodPrefetchRelations.compactImpact(impact);MethodPrefetchRelations.append(db,root,impact);impact.put("source_sha256",hash);selected.add(impact);
            }while(method.find());
            return result.put("status","ready");
        }
        Map<String,List<ClassRecord>> names=new HashMap<>();
        for(var cls:db.symbols.findAllClasses()) if("current".equals(cls.lifecycle()) && !"orphan_output".equals(cls.origin())) {
            names.computeIfAbsent(cls.className(),k->new ArrayList<>()).add(cls);
            String shortName=cls.className().substring(cls.className().lastIndexOf('.')+1);
            names.computeIfAbsent(shortName,k->new ArrayList<>()).add(cls);
        }
        var facts=result.putArray("facts");var ambiguous=result.putArray("ambiguities");var unavailable=result.putArray("unavailable");
        Set<String> configurationKeys=db.jdbi().withHandle(h->new HashSet<>(h.createQuery("SELECT key FROM configuration_definitions WHERE kind <> 'persistence_unit' UNION SELECT key FROM configuration_usages WHERE kind IN ('config_key','config_prefix')").mapTo(String.class).list()));
        configurationKeys.stream().map(ConfigurationSourceEvidence::baseKey).toList().forEach(configurationKeys::add);
        Set<String> emittedKeys=new HashSet<>();var keys=CONFIG_KEY.matcher(prompt);
        while(keys.find() && facts.size()<3){
            String key=keys.group().replaceAll("\\.+$","");
            if(!configurationKeys.contains(key) || !emittedKeys.add(key))continue;
            ObjectNode evidence=(ObjectNode)JSON.readTree(new ConfigurationReferenceQueries().find(db,key,null,"all",null,12,0,"declared"));
            ConfigurationPrefetchAnnotations.append(db,evidence);
            ConfigurationMethodSource.append(db,evidence);
            ConfigurationPrefetchAnnotations.compact(evidence);
            ConfigurationDeclarationLocations.compact(evidence);
            evidence.remove("_meta");var fact=facts.addObject().put("kind","configuration").put("tool","find_configuration_references");
            fact.putObject("arguments").put("key",key).put("view","declared").put("limit",12);
            // Scope is carried by indexed_references_complete, resource coverage and runtime/activation flags.
            fact.set("evidence",evidence);
        }
        Set<Integer> seen=new HashSet<>();Set<String> seenNames=new HashSet<>();var matcher=IDENTIFIER.matcher(prompt);
        while(matcher.find() && facts.size()+ambiguous.size()+unavailable.size()<3) {
            String token=matcher.group().replaceAll("\\.+$","");
            List<ClassRecord> matches=names.getOrDefault(token,List.of()).stream().distinct().toList();
            if(matches.isEmpty() && token.contains(".") && seenNames.add(token)) {
                // A qualified name that is no application class: a package, or a type from dependencies.
                ObjectNode named=NameTargetPrefetch.packageFact(db,token);
                if(named==null)named=NameTargetPrefetch.typeProviders(db,token);if(named==null)named=NameTargetPrefetch.generatedClassFact(db,token);
                if(named!=null){facts.add(named);continue;}
            }
            while(matches.isEmpty() && token.contains(".")) {
                token=token.substring(0,token.lastIndexOf('.'));
                matches=names.getOrDefault(token,List.of()).stream().distinct().toList();
            }
            if(matches.size()>1){ambiguous.addObject().put("identifier",token).put("status","ambiguous");continue;}
            if(matches.size()!=1 || !seen.add(matches.get(0).id()))continue;
            var cls=matches.get(0);String source=cls.sourceFile();
            if(source==null){unavailable.addObject().put("class",cls.className()).put("status","source_unknown");continue;}
            Path file=root.resolve(source).toRealPath();
            if(!file.startsWith(root.toRealPath())){unavailable.addObject().put("class",cls.className()).put("status","source_outside_root");continue;}
            String relative=root.toRealPath().relativize(file).toString().replace('\\','/');
            String hash=hashes.path(relative).asText();
            if(hash.isBlank() || !hash.equals(SourceFileHashes.hash(file))){unavailable.addObject().put("class",cls.className()).put("status","source_hash_unavailable_or_changed");continue;}
            ObjectNode fact=(ObjectNode)JSON.readTree(new SymbolToolQueries().getSymbolDetails(db,cls.className(),false,null,10,0));
            ClassPrefetchRelations.append(db,fact,cls);NameTargetPrefetch.appendGenerated(db,fact,cls.className(),root);
            fact.put("source_sha256",hash);facts.add(fact);
        }
        if(facts.isEmpty() && ambiguous.isEmpty() && unavailable.isEmpty() && previousEndpoint!=null)
            return EndpointPrefetch.collect(db,root,previousEndpoint,deliveredCurrentIndex);
        result.put("index_id",metadata.getOrDefault("index_id","unknown"));
        return result.put("status","ready");
    }
    /** The injection point a field target names, with its owner's bean, or why there is none. */
    private static JsonNode injectionPointFact(ReadQueryContext db,String owner,String name,ArrayNode missing) throws Exception {
        JsonNode points=JSON.readTree(new StructureToolQueries().getInjectionPoints(db,owner));
        if(points.has("error") || points.has("error_code")){
            ObjectNode gap=missing.addObject().put("target",owner+"#"+name);
            gap.put("status",points.path("error_code").asText(points.path("error").asText("lookup_failed")));
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
        for(JsonNode point:points.path("injection_points")){
            if(!name.equals(point.path("field").asText()) && !name.equals(point.path("member").asText()))continue;
            ObjectNode fact=JSON.createObjectNode().put("kind","injection_point").put("tool","list_injection_points")
                    .put("target",points.path("target").asText()+"#"+name)
                    .put("owner_scope",points.path("scope").asText()).put("owner_bean_kind",points.path("bean_kind").asText());
            fact.set("injection_point",point);
            if(points.has("resolution_context"))fact.set("resolution_context",points.get("resolution_context"));
            String required=point.path("required_type").asText();
            var candidates=ClassPrefetchRelations.instanceCandidates(db,required);
            if(!candidates.isEmpty())fact.set("instance_candidates",JSON.valueToTree(candidates));
            var observers=ClassPrefetchRelations.eventObservers(db,required);
            if(!observers.isEmpty())fact.set("observers",JSON.valueToTree(observers));
            return fact;
        }
        missing.addObject().put("target",owner+"#"+name).put("status","not_an_injection_point");
        return com.fasterxml.jackson.databind.node.NullNode.getInstance();
    }
    /** Every session pays for this context once; replays of recorded packets chose the limit. */
    static final int CONTEXT_LIMIT_BYTES=5000;
    /** In removal order, at any depth; these hold little that an answer needs. Limits and coverage never go. */
    private static final List<String> OPTIONAL_FIELDS=List.of("class_occurrences","meta_annotations","dependency_metrics",
            "external_dependencies","external_dependency_breakdown","test_classes","related_source_files",
            "symbol_id","descriptor","_meta","generated_classes","generated_creation_evidence","override_evidence",
            "methods","field_read_source");
    /** What a fact is about and how far it reaches: the rest can go first, so the fact can still be followed up. */
    private static final Set<String> FACT_IDENTITY=Set.of("kind","tool","arguments","target","class","method","signature",
            "jvm_name","source","location","coverage","counts","limitations","status","injection_point");
    /** Portable hook budget: omit complete facts explicitly instead of letting clients cut JSON. */
    public static void boundContext(ObjectNode data){
        final int limit=CONTEXT_LIMIT_BYTES;
        if(!(data.path("facts") instanceof ArrayNode facts))return;
        // Direct evidence takes precedence over navigation and index diagnostics.
        for(String field:OPTIONAL_FIELDS) {
            if(size(data)<=limit)break;
            if(!removeField(facts,field))continue;
            data.withArray("context_omitted_fields").add(field);
            data.put("context_limit_bytes",limit).put("context_truncated",true).put("status","partial");
        }
        // Lists lose half and other fields go whole, each cut stated, before whole facts go.
        while(size(data)>limit && shrink(data,facts,limit)) data.put("context_limit_bytes",limit).put("context_truncated",true).put("status","partial");
        while(size(data)>limit && !facts.isEmpty()){
            JsonNode omitted=facts.remove(facts.size()-1);
            data.put("context_limit_bytes",limit).put("context_truncated",true).put("status","partial");
            var missing=data.withArray("unavailable").addObject().put("status","prefetch_context_limit");
            for(String key:List.of("kind","tool","arguments"))if(omitted.has(key))missing.set(key,omitted.get(key));
        }
    }
    private static boolean removeField(JsonNode node,String field){
        boolean removed=false;
        if(node instanceof ObjectNode object && object.remove(field)!=null){
            removed=true;
            object.remove(field+"_basis");object.remove(field+"_scope");
        }
        for(JsonNode child:node)removed|=removeField(child,field);
        return removed;
    }
    private static int size(JsonNode data){return data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    /** A cut that would free this many bytes: halving a list, or removing a field from a fact that keeps other content. */
    private record Cut(ObjectNode owner,String field,int frees,boolean list){}
    /** Applies the smallest cut that fits the budget, or else the largest, so a small overrun never costs a large field. */
    private static boolean shrink(ObjectNode data,ArrayNode facts,int limit){
        List<Cut> cuts=new ArrayList<>();
        collectLists(facts,cuts);
        for(JsonNode fact:facts)if(fact instanceof ObjectNode object)collectFields(object,cuts);
        if(cuts.isEmpty())return false;
        int over=size(data)-limit;
        Cut cut=cuts.stream().filter(c->c.frees()>=over).min(Comparator.comparingInt(Cut::frees))
                .orElseGet(()->cuts.stream().max(Comparator.comparingInt(Cut::frees)).orElseThrow());
        if(cut.list())halve(cut.owner(),cut.field());
        else{cut.owner().remove(cut.field());data.withArray("context_omitted_fields").add(cut.field());}
        return true;
    }
    private static void collectFields(ObjectNode fact,List<Cut> cuts){
        List<Cut> fields=new ArrayList<>();
        fact.properties().forEach(entry->{
            boolean longList=entry.getValue() instanceof ArrayNode list && list.size()>=2;
            if(!FACT_IDENTITY.contains(entry.getKey()) && !longList && !mostlyList(entry.getValue()))fields.add(new Cut(fact,entry.getKey(),size(entry.getValue()),false));
        });
        boolean otherContent=fact.properties().stream().filter(entry->!FACT_IDENTITY.contains(entry.getKey())).count()>1;
        if(otherContent)cuts.addAll(fields);
    }
    /** Halving such a value keeps most of what removing it would lose. */
    private static boolean mostlyList(JsonNode value){
        List<Cut> lists=new ArrayList<>();
        collectLists(value,lists);
        return lists.stream().anyMatch(list->list.frees()*4>=size(value));
    }
    private static void collectLists(JsonNode node,List<Cut> cuts){
        if(node instanceof ObjectNode object){
            object.properties().forEach(entry->{
                if(entry.getValue() instanceof ArrayNode list && list.size()>=2)cuts.add(new Cut(object,entry.getKey(),size(list)/2,true));
                collectLists(entry.getValue(),cuts);
            });
        }else if(node instanceof ArrayNode list)for(JsonNode child:list)collectLists(child,cuts);
    }
    /** Keeps half of a list and records shown/total next to it. */
    private static void halve(ObjectNode owner,String field){
        ArrayNode list=(ArrayNode)owner.get(field);
        String marker=field+"_truncated";
        int total=owner.path(marker).path("total").asInt(list.size());
        int keep=list.size()/2;
        while(list.size()>keep)list.remove(list.size()-1);
        owner.putObject(marker).put("shown",keep).put("total",total);
    }
    private PrefetchContext(){}
}
