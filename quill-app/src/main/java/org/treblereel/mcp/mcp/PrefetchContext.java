package org.treblereel.mcp.mcp;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.*;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.*;

/** Generic identifier lookup, never a classifier of question wording or benchmark tasks. */
public final class PrefetchContext {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern IDENTIFIER=Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$.]*");
    private static final Pattern CONFIG_KEY=Pattern.compile("%?[\\p{L}_][\\p{L}\\p{N}_.-]*");
    private static final Pattern METHOD=Pattern.compile("([\\p{L}_$][\\p{L}\\p{N}_$.]*)#([\\p{L}_$][\\p{L}\\p{N}_$]*\\([^()\\n]*\\))");
    public static ObjectNode collect(Jdbi db,Path root,String prompt) throws Exception {
        return collect(db,root,prompt,null);
    }
    public static ObjectNode collect(Jdbi db,Path root,String prompt,String previousEndpoint) throws Exception {
        return collect(db,root,prompt,previousEndpoint,false);
    }
    public static ObjectNode collect(Jdbi db,Path root,String prompt,String previousEndpoint,boolean deliveredCurrentIndex) throws Exception {
        WorktreeSnapshotCache.shared().invalidate(root);
        ObjectNode result=JSON.createObjectNode().put("basis","literal indexed identifiers");
        if(MetaEnvelope.from(db,0,0).structureStale())return result.put("status","index_stale");
        var metadata=IndexReader.getMetadata(db);
        if(EndpointPrefetch.hasRoute(prompt))return EndpointPrefetch.collect(db,root,prompt);
        JsonNode hashes=JSON.readTree(metadata.getOrDefault("java_source_hashes","{}"));
        var method=METHOD.matcher(prompt);
        if(method.find()){
            result.put("basis","exact indexed method impact").put("index_id",metadata.getOrDefault("index_id","unknown"));
            var selected=result.putArray("facts");var missing=result.putArray("unavailable");result.putArray("ambiguities");
            Set<String> selectors=new HashSet<>();
            do {
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
                impact.remove("_meta");impact.put("source_sha256",hash);selected.add(impact);
            }while(method.find());
            return result.put("status","ready");
        }
        Map<String,List<ClassRecord>> names=new HashMap<>();
        for(var cls:IndexReader.findAllClasses(db)) if("current".equals(cls.lifecycle()) && !"orphan_output".equals(cls.origin())) {
            names.computeIfAbsent(cls.className(),k->new ArrayList<>()).add(cls);
            String shortName=cls.className().substring(cls.className().lastIndexOf('.')+1);
            names.computeIfAbsent(shortName,k->new ArrayList<>()).add(cls);
        }
        var facts=result.putArray("facts");var ambiguous=result.putArray("ambiguities");var unavailable=result.putArray("unavailable");
        Set<String> configurationKeys=db.withHandle(h->new HashSet<>(h.createQuery("SELECT key FROM configuration_definitions WHERE kind <> 'persistence_unit' UNION SELECT key FROM configuration_usages WHERE kind IN ('config_key','config_prefix')").mapTo(String.class).list()));
        configurationKeys.stream().map(ConfigurationSourceEvidence::baseKey).toList().forEach(configurationKeys::add);
        Set<String> emittedKeys=new HashSet<>();var keys=CONFIG_KEY.matcher(prompt);
        while(keys.find() && facts.size()<3){
            String key=keys.group().replaceAll("\\.+$","");
            if(!configurationKeys.contains(key) || !emittedKeys.add(key))continue;
            ObjectNode evidence=(ObjectNode)JSON.readTree(new ConfigurationReferenceQueries().find(db,key,null,"all",null,12,0,"declared"));
            evidence.remove("_meta");var fact=facts.addObject().put("kind","configuration").put("tool","find_configuration_references");
            fact.putObject("arguments").put("key",key).put("view","declared").put("limit",12);
            fact.put("query_scope","Indexed declarations matching the shown key, including profile declarations; effective runtime configuration is unsupported");
            fact.set("evidence",evidence);
        }
        Set<Integer> seen=new HashSet<>();var matcher=IDENTIFIER.matcher(prompt);
        while(matcher.find() && facts.size()+ambiguous.size()+unavailable.size()<3) {
            String token=matcher.group().replaceAll("\\.+$","");
            List<ClassRecord> matches=names.getOrDefault(token,List.of()).stream().distinct().toList();
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
            fact.put("source_sha256",hash);facts.add(fact);
        }
        if(facts.isEmpty() && ambiguous.isEmpty() && unavailable.isEmpty() && previousEndpoint!=null)
            return EndpointPrefetch.collect(db,root,previousEndpoint,deliveredCurrentIndex);
        result.put("index_id",metadata.getOrDefault("index_id","unknown"));
        return result.put("status","ready");
    }
    private PrefetchContext(){}
}
