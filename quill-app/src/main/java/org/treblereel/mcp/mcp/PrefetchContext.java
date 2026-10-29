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
    public static ObjectNode collect(Jdbi db,Path root,String prompt) throws Exception {
        WorktreeSnapshotCache.shared().invalidate(root);
        ObjectNode result=JSON.createObjectNode().put("basis","literal indexed identifiers");
        if(MetaEnvelope.from(db,0,0).structureStale())return result.put("status","index_stale");
        var metadata=IndexReader.getMetadata(db);
        JsonNode hashes=JSON.readTree(metadata.getOrDefault("java_source_hashes","{}"));
        Map<String,List<ClassRecord>> names=new HashMap<>();
        for(var cls:IndexReader.findAllClasses(db)) if("current".equals(cls.lifecycle()) && !"orphan_output".equals(cls.origin())) {
            names.computeIfAbsent(cls.className(),k->new ArrayList<>()).add(cls);
            String shortName=cls.className().substring(cls.className().lastIndexOf('.')+1);
            names.computeIfAbsent(shortName,k->new ArrayList<>()).add(cls);
        }
        var facts=result.putArray("facts");var ambiguous=result.putArray("ambiguities");var unavailable=result.putArray("unavailable");
        Set<Integer> seen=new HashSet<>();var matcher=IDENTIFIER.matcher(prompt);
        while(matcher.find() && facts.size()+ambiguous.size()+unavailable.size()<3) {
            String token=matcher.group().replaceAll("\\.+$","");
            List<ClassRecord> matches=names.getOrDefault(token,List.of()).stream().distinct().toList();
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
        result.put("index_id",metadata.getOrDefault("index_id","unknown"));
        return result.put("status","ready");
    }
    private PrefetchContext(){}
}
