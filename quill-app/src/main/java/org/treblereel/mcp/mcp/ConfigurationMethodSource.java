package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.MetaEnvelope;

/** Atomic upgrade of neighbouring read windows to compiler-resolved whole methods. */
final class ConfigurationMethodSource {
    private static final ObjectMapper JSON=new ObjectMapper();
    static void append(Jdbi db,ObjectNode evidence) {
        if(!evidence.has("field_read_source"))return;
        ObjectNode complete=JSON.createObjectNode().put("status","ready")
            .put("scope","Complete current Java declarations of indexed field-reading methods; not runtime paths")
            .put("range_kind","compiler_method_declaration").put("method_complete",true)
            .put("source_bytecode_identity_proven",false).put("line_limit",120).put("character_limit",9000)
            .put("window_limit",8).put("file_limit",4).put("truncated",false);
        var files=complete.putArray("files");complete.putArray("unavailable");
        Map<String,ObjectNode> byFile=new LinkedHashMap<>();Map<String,JsonNode> methods=new LinkedHashMap<>();Set<String> emitted=new HashSet<>();int lines=0,chars=0;
        String failure=null;
        try {
            if(MetaEnvelope.from(db,0,0).structureStale())throw new IllegalStateException("index_stale");
            Path root=Path.of(IndexReader.getMetadata(db).get("project_root")).toRealPath();
            for(var ref:evidence.path("references")) {
                var reads=ref.path("field_reads");
                if(reads.path("truncated").asBoolean())throw new IllegalStateException("read_sites_truncated");
                for(var site:reads.path("sites")) {
                    String owner=site.path("class").asText(ref.path("className").asText());
                    String selector=site.path("method").asText()+site.path("descriptor").asText();
                    String id=owner+"#"+selector;var method=methods.get(id);
                    if(method==null){
                        if(methods.size()>=8)throw new IllegalStateException("method_limit");
                        var cls=IndexReader.findClassByName(db,owner).orElseThrow();
                        method=new MethodContextQueries().get(db,cls,selector);methods.put(id,method);
                    }
                    if(!method.path("source_complete").asBoolean())throw new IllegalStateException(method.path("status").asText("compiler_unavailable"));
                    var range=method.path("declaration");int first=range.path("source_line").asInt(),last=range.path("source_end_line").asInt();
                    for(var n:site.path("lines"))if(n.asInt()<first || n.asInt()>last)throw new IllegalStateException("read_outside_method");
                    if(!emitted.add(id))continue;
                    String source=method.path("source").asText(),file=method.path("source_file").asText(),hash=method.path("source_sha256").asText();
                    lines+=last-first+1;chars+=source.length();
                    if(lines>120 || chars>9000 || (byFile.size()>=4 && !byFile.containsKey(file)))throw new IllegalStateException("source_budget");
                    ObjectNode output=byFile.get(file);
                    if(output==null){output=files.addObject().put("file",file).put("source_sha256",hash);output.putArray("windows");byFile.put(file,output);}
                    if(!hash.equals(output.path("source_sha256").asText()))throw new IllegalStateException("inputs_changed");
                    ((ArrayNode)output.get("windows")).addObject().put("start_line",first).put("end_line",last)
                        
                        .put("source",source);
                }
            }
            for(var entry:byFile.entrySet())if(!entry.getValue().path("source_sha256").asText().equals(SourceFileHashes.hash(root.resolve(entry.getKey()))))throw new IllegalStateException("inputs_changed");
            if(MetaEnvelope.from(db,0,0).structureStale())throw new IllegalStateException("index_stale");
            if(!methods.isEmpty()){
                String key=evidence.path("key").asText();
                evidence.set("field_read_source",complete);
                evidence.remove(List.of("key","class","kind","module","view","resolution_scope","showing","limit","source_definition_limit","source_file_limit","values_indexed"));
                for(var node:evidence.path("references"))if(node instanceof ObjectNode ref){
                    if(!key.isBlank() && ref.path("key").asText().equals(key))ref.remove("key");
                    if(!key.isBlank() && ref.path("base_key").asText().equals(key))ref.remove("base_key");
                    ref.remove(List.of("classId","confidence","resolution_strategy","definition_count","definition_files"));
                    if(ref.has("resolution_status"))ref.remove("resolved");
                    if(ref.path("declared_source") instanceof ObjectNode declared)declared.remove(List.of("basis","effective_value_proven"));
                    if(ref.path("field_reads") instanceof ObjectNode reads)reads.remove("basis");
                }
                evidence.putArray("limitations").add("Bounded non-sensitive source scalars only; env/evaluated values not indexed")
                    .add("Supported annotation/common API consumers; env/YAML-list lookups unresolved; dynamic keys unknown");
                if(evidence.path("key_constants") instanceof ObjectNode constants){
                    constants.put("basis","Indexed key aliases; source binding unproven");
                    constants.put("coverage","Captured application classes; missing bytecode is not absence");
                }
                complete.put("read_sites_basis","indexed bytecode; exact field owner/name/descriptor");
            }
        }catch(Exception unavailable){failure=unavailable instanceof IllegalStateException?unavailable.getMessage():"compiler_unavailable";}
        if(failure!=null){
            var prior=(ObjectNode)evidence.get("field_read_source");
            try {
                Path root=Path.of(IndexReader.getMetadata(db).get("project_root")).toRealPath();
                for(var file:prior.path("files"))if(!file.path("source_sha256").asText().equals(SourceFileHashes.hash(root.resolve(file.path("file").asText()))))throw new IllegalStateException();
                if(MetaEnvelope.from(db,0,0).structureStale())throw new IllegalStateException();
            }catch(Exception changed){prior.putArray("files");prior.put("status","inputs_changed");}
        }
    }
    private ConfigurationMethodSource(){}
}
