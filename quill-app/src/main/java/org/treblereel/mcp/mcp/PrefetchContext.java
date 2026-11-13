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
            return restated(result).put("status","ready");
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
            // Related declarations name framework keys the lookup itself did not.
            ConfigurationReferenceQueries.frameworkKeyPhases(db,key,evidence);
            ConfigurationMethodSource.append(db,evidence);
            ConfigurationPrefetchAnnotations.compact(evidence);
            ConfigurationDeclarationLocations.compact(evidence);
            ConfigurationEvidenceCompaction.compact(evidence);
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
        return restated(result).put("status","ready");
    }
    private static ObjectNode restated(ObjectNode result){
        for(JsonNode fact:result.path("facts"))if(fact instanceof ObjectNode object)PrefetchRestatements.strip(object);
        return result;
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
            fact.set("qualifier_origin",JSON.valueToTree(QualifierOrigin.lines(db,points.path("target").asText(),point)));
            if(points.has("resolution_context"))fact.set("resolution_context",points.get("resolution_context"));
            String required=point.path("required_type").asText();
            var candidates=ClassPrefetchRelations.instanceCandidates(db,required);
            if(!candidates.isEmpty())fact.set("instance_candidates",JSON.valueToTree(candidates));
            var observers=ClassPrefetchRelations.eventObservers(db,required);
            if(!observers.isEmpty())fact.set("observers",JSON.valueToTree(observers));
            if("FIELD".equals(point.path("kind").asText())){
                var used=fieldReads(db,points.path("target").asText(),name);
                if(!used.isEmpty())fact.set("used_in",JSON.valueToTree(used));
            }
            if(point.path("resolved_to").isNull() || point.path("resolved_to").isMissingNode()){
                String checked=applicationCandidatesChecked(db,required);
                if(checked!=null)fact.put("application_candidates_checked",checked);
            }
            return fact;
        }
        missing.addObject().put("target",owner+"#"+name).put("status","not_an_injection_point");
        return com.fasterxml.jackson.databind.node.NullNode.getInstance();
    }
    private static final int FIELD_READ_LIMIT=6;

    /**
     * Where an injected field is read, as "method line N: source line". V41 CQ-023: given the
     * injection point and its candidates, agents still opened the source to see how it is used.
     */
    static List<String> fieldReads(ReadQueryContext db,String owner,String field) throws Exception {
        var rows=db.jdbi().withHandle(h->h.createQuery("""
                SELECT fc.class_name AS reader, fc.source_file, a.from_method, a.evidence_lines
                FROM field_accesses a JOIN classes tc ON tc.id=a.to_class_id JOIN classes fc ON fc.id=a.from_class_id
                WHERE tc.class_name=:owner AND a.field_name=:field AND a.access_kind LIKE 'read%'
                ORDER BY fc.class_name, a.from_method""").bind("owner",owner).bind("field",field).mapToMap().list());
        String projectRoot=db.index.getMetadata().get("project_root");
        List<String> lines=new ArrayList<>();
        Map<String,List<String>> sources=new HashMap<>();
        for(var row:rows){
            String method=String.valueOf(row.get("from_method")).replaceFirst("^lambda\\$(.+)\\$\\d+$","$1 (lambda)");
            String reader=String.valueOf(row.get("reader"));
            String label=reader.equals(owner)?method:reader+"#"+method;
            List<String> file=List.of();
            if(projectRoot!=null && row.get("source_file")!=null){
                file=sources.computeIfAbsent(String.valueOf(row.get("source_file")),path->{
                    try{return java.nio.file.Files.readAllLines(java.nio.file.Path.of(projectRoot).resolve(path));}
                    catch(Exception unreadable){return List.of();}
                });
            }
            for(JsonNode number:JSON.readTree(String.valueOf(row.get("evidence_lines")))){
                int n=number.asInt();
                // A multi-line statement starts at the evidence line; show the line naming the field, through its end.
                int at=n;
                for(int i=n;i>0 && i<=file.size() && i<n+5;i++)if(file.get(i-1).contains(field)){at=i;break;}
                StringBuilder statement=new StringBuilder();
                for(int i=at;i>0 && i<=file.size() && i<at+3;i++){
                    statement.append(statement.isEmpty()?"":" ").append(file.get(i-1).strip());
                    String part=file.get(i-1).strip();
                    if(part.endsWith(";") || part.endsWith("{") || part.endsWith("}"))break;
                }
                n=at;
                String code=statement.toString();
                if(code.length()>140)code=code.substring(0,140)+"...";
                String line=label+" line "+n+(code.isEmpty()?"":": "+code);
                if(!lines.contains(line))lines.add(line);
                if(lines.size()>=FIELD_READ_LIMIT)return lines;
            }
        }
        return lines;
    }

    /**
     * The scope a "no application bean" rests on: application classes extending or implementing the
     * raw required type (transitively through indexed classes) and beans listing it, @Typed included.
     * V55 CQ-010: without it Codex re-proved the absence with rg.
     */
    static String applicationCandidatesChecked(ReadQueryContext db,String required){
        String raw=required.contains("<")?required.substring(0,required.indexOf('<')):required;
        if(raw.isBlank())return null;
        var classes=db.symbols.findAllClasses();
        java.util.Set<String> reached=new java.util.HashSet<>(java.util.List.of(raw));
        boolean changed=true;
        while(changed){
            changed=false;
            for(var cls:classes){
                if(reached.contains(cls.className()))continue;
                if(reached.contains(cls.superclass()) || cls.interfaces().stream().anyMatch(reached::contains)){reached.add(cls.className());changed=true;}
            }
        }
        int subtypes=reached.size()-1;
        long beans=db.jdbi().withHandle(h->h.createQuery("SELECT COUNT(*) FROM beans WHERE bean_types LIKE :plain OR bean_types LIKE :generic")
                .bind("plain","%\""+raw+"\"%").bind("generic","%\""+raw+"<%").mapTo(Long.class).one());
        long totalBeans=db.jdbi().withHandle(h->h.createQuery("SELECT COUNT(*) FROM beans").mapTo(Long.class).one());
        if(subtypes>0 && beans>0)return null;
        return (subtypes==0?"no application class extends or implements "+raw:subtypes+" application classes extend or implement "+raw)
                +" (of "+classes.size()+" indexed); "
                +(beans==0?"no application bean has it among its bean types, @Typed included":beans+" application beans have it among their bean types")
                +" (of "+totalBeans+")";
    }

    /** Every session pays for this context once; replays of recorded packets chose the limit. */
    static final int CONTEXT_LIMIT_BYTES=5000;
    /** In removal order, at any depth; these hold little that an answer needs. Limits and coverage never go. */
    private static final List<String> OPTIONAL_FIELDS=List.of("class_occurrences","meta_annotations","dependency_metrics",
            "external_dependencies","external_dependency_breakdown","test_classes","related_source_files",
            "symbol_id","descriptor","_meta","generated_classes","generated_creation_evidence","override_evidence",
            "methods","field_read_source","same_event_observers");
    /** What a fact is about and how far it reaches: the rest can go first, so the fact can still be followed up. */
    private static final Set<String> FACT_IDENTITY=Set.of("kind","tool","arguments","target","class","method","signature",
            "jvm_name","source","location","coverage","counts","limitations","status","injection_point");
    /** How far a fact reaches: removing these would make what remains read as complete. */
    private static final Set<String> REACH=Set.of("has_more","truncated","total","showing","limit","offset","complete",
            "scope","runtime_execution_proven");
    private static final List<String> REACH_SUFFIXES=List.of("_truncated","_scope","_basis","_complete");
    /**
     * Cut only when nothing else is left: without the selected body, recorded agents read the whole file;
     * without the bound interceptors (V62 CQ-026) they searched for them over three more turns; an endpoint's
     * methods, once halved to its handler, hold the handler body (other facts' methods go with OPTIONAL_FIELDS).
     */
    private static final Set<String> LAST_RESORT=Set.of("selected_method_source","framework_contract","thread_hops",
            "beans_reached_after","dependency_interceptors","methods");
    /** A shorter field is a statement rather than a body of evidence, and removing it saves little. */
    private static final int MIN_FIELD_CUT_BYTES=64;
    private static final int PROPORTIONATE_FACTOR=4;
    private static final int MIN_PROPORTIONATE_BYTES=512;
    /** Quotes, colon and comma around a marker, and the shown/total object inside it. */
    private static final int TRUNCATION_NOTE_BYTES=30;
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
        if(size(data)>limit)data.put("context_limit_bytes",limit).put("context_truncated",true).put("status","partial");
        while(size(data)>limit && shrink(data,facts,limit)){}
        while(size(data)>limit && !facts.isEmpty()){
            JsonNode omitted=facts.remove(facts.size()-1);
            data.put("context_limit_bytes",limit).put("context_truncated",true).put("status","partial");
            var missing=data.withArray("unavailable").addObject().put("status","prefetch_context_limit");
            for(String key:List.of("kind","tool","target","arguments"))if(omitted.has(key))missing.set(key,omitted.get(key));
        }
    }
    /** After {@link #boundContext}: a pointer to a field the budget removed would dangle. */
    public static void stateRepeatsOnce(ObjectNode data){ResponseCompaction.stateOnce(data);}
    private static boolean removeField(JsonNode node,String field){
        boolean removed=false;
        // An endpoint's methods hold the handler body (V41-V62 CQ-032: its @PermissionsAllowed); the list is halved instead.
        if("methods".equals(field) && "operation_methods".equals(node.path("kind").asText()))return false;
        if(node instanceof ObjectNode object && object.remove(field)!=null){
            removed=true;
            removeCompanions(object,field);
        }
        for(JsonNode child:node)removed|=removeField(child,field);
        return removed;
    }
    private static int size(JsonNode data){return data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    private static void removeCompanions(ObjectNode object,String field){
        object.remove(List.of(field+"_truncated",field+"_basis",field+"_scope"));
    }
    private static boolean statesReach(String field){
        return REACH.contains(field) || REACH_SUFFIXES.stream().anyMatch(field::endsWith);
    }
    /** A cut and the bytes it frees after paying for the note that states it. */
    private record Cut(ObjectNode owner,String field,int frees,boolean list){}
    /** Applies the smallest cut that fits the budget, or else the largest, so a small overrun never costs a large field. */
    private static boolean shrink(ObjectNode data,ArrayNode facts,int limit){
        List<Cut> cuts=new ArrayList<>();
        collectLists(facts,cuts);
        for(JsonNode fact:facts)if(fact instanceof ObjectNode object)collectFields(object,cuts);
        cuts.removeIf(cut->cut.frees()<=0);
        if(cuts.isEmpty())return false;
        List<Cut> others=cuts.stream().filter(cut->!LAST_RESORT.contains(cut.field())).toList();
        if(!others.isEmpty())cuts=others;
        int over=size(data)-limit;
        // Several small cuts are better than one far larger than the overrun.
        int proportionate=Math.max(over*PROPORTIONATE_FACTOR,MIN_PROPORTIONATE_BYTES);
        List<Cut> small=cuts.stream().filter(cut->cut.frees()<=proportionate).toList();
        List<Cut> pool=small.isEmpty()?cuts:small;
        Cut cut=pool.stream().filter(c->c.frees()>=over).min(Comparator.comparingInt(Cut::frees))
                .orElseGet(()->pool.stream().max(Comparator.comparingInt(Cut::frees)).orElseThrow());
        if(cut.list())halve(cut.owner(),cut.field());
        else{
            cut.owner().remove(cut.field());
            removeCompanions(cut.owner(),cut.field());
            data.withArray("context_omitted_fields").add(cut.field());
        }
        return true;
    }
    private static void collectFields(ObjectNode fact,List<Cut> cuts){
        boolean otherContent=fact.properties().stream()
                .filter(entry->!FACT_IDENTITY.contains(entry.getKey()) && !statesReach(entry.getKey())).count()>1;
        if(!otherContent)return;
        fact.properties().forEach(entry->{
            String field=entry.getKey();JsonNode value=entry.getValue();
            boolean longList=value instanceof ArrayNode list && list.size()>=2;
            boolean verdict=value.isNumber() || value.isBoolean() || value.isNull();
            int bytes=size(value);
            if(FACT_IDENTITY.contains(field) || statesReach(field) || longList || verdict || bytes<MIN_FIELD_CUT_BYTES || mostlyList(value))return;
            cuts.add(new Cut(fact,field,bytes+1,false));
        });
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
                if(entry.getValue() instanceof ArrayNode list && list.size()>=2){
                    int removed=0;
                    for(int i=list.size()/2;i<list.size();i++)removed+=size(list.get(i))+1;
                    String marker=entry.getKey()+"_truncated";
                    int note=object.has(marker)?0:marker.length()+TRUNCATION_NOTE_BYTES;
                    cuts.add(new Cut(object,entry.getKey(),removed-note,true));
                }
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
