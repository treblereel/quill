package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.model.MetaEnvelope;

/** Atomic upgrade of neighbouring read windows to whole methods, ranged by javac or by the file's syntax. */
final class ConfigurationMethodSource {
    private static final ObjectMapper JSON=new ObjectMapper();
    static void append(ReadQueryContext db,ObjectNode evidence) {
        if(!evidence.has("field_read_source"))return;
        ObjectNode complete=JSON.createObjectNode().put("status","ready")
            .put("scope","Whole compiler methods; dedented; runtime paths unproven")
            .put("range_kind","compiler_method_declaration").put("method_complete",true)
            .put("source_bytecode_identity_proven",false).put("line_limit",120).put("character_limit",9000)
            .put("window_limit",8).put("file_limit",4).put("truncated",false);
        var files=complete.putArray("files");complete.putArray("unavailable");
        Map<String,ObjectNode> byFile=new LinkedHashMap<>();Map<String,JsonNode> methods=new LinkedHashMap<>();Set<String> emitted=new HashSet<>();int lines=0,chars=0;
        String failure=null;
        try {
            if(org.treblereel.mcp.index.BuildStatusInspector.freshness(db,0,0).structureStale())throw new IllegalStateException("index_stale");
            Path root=Path.of(db.index.getMetadata().get("project_root")).toRealPath();
            for(var ref:evidence.path("references")) {
                var reads=ref.path("field_reads");
                if(reads.path("truncated").asBoolean())throw new IllegalStateException("read_sites_truncated");
                for(var site:reads.path("sites")) {
                    String owner=site.path("class").asText(ref.path("className").asText());
                    String selector=site.path("method").asText()+site.path("descriptor").asText();
                    String id=owner+"#"+selector;var method=methods.get(id);
                    if(method==null){
                        if(methods.size()>=8)throw new IllegalStateException("method_limit");
                        var cls=db.symbols.findClassByName(owner).orElseThrow();
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
                        
                        .put("source",dedent(source));
                }
            }
            for(var entry:byFile.entrySet())if(!entry.getValue().path("source_sha256").asText().equals(SourceFileHashes.hash(root.resolve(entry.getKey()))))throw new IllegalStateException("inputs_changed");
            if(org.treblereel.mcp.index.BuildStatusInspector.freshness(db,0,0).structureStale())throw new IllegalStateException("index_stale");
            if(methods.values().stream().anyMatch(m->"java_syntax".equals(m.path("declaration").path("basis").asText())))
                complete.put("scope","Whole methods; ranges from Java syntax placed by bytecode lines; dedented; runtime paths unproven")
                        .put("range_kind","java_syntax_method_declaration");
            if(!methods.isEmpty()){
                // Keep complete sources inside the hook budget; unused packing caps are not source facts.
                complete.remove(List.of("line_limit","character_limit","window_limit","file_limit","unavailable"));
                String key=evidence.path("key").asText();
                evidence.set("field_read_source",complete);
                evidence.remove(List.of("key","class","kind","module","view","resolution_scope","showing","limit","source_definition_limit","source_file_limit","values_indexed"));
                for(var node:evidence.path("references"))if(node instanceof ObjectNode ref){
                    if(!key.isBlank() && ref.path("key").asText().equals(key))ref.remove("key");
                    if(!key.isBlank() && ref.path("base_key").asText().equals(key))ref.remove("base_key");
                    ref.remove(List.of("classId","confidence","resolution_strategy","definition_count","definition_files"));
                    if(ref.has("resolution_status"))ref.remove("resolved");
                    if(ref.path("declared_source") instanceof ObjectNode declared)declared.remove(List.of("basis","effective_value_proven"));
                    if(ref.path("field_reads") instanceof ObjectNode reads){
                        reads.remove("basis");
                        for(var site:reads.path("sites"))if(site instanceof ObjectNode row)row.remove("descriptor");
                    }
                    ref.remove(List.of("module","parameterIndex"));
                    if(ref.path("kind").asText().equals("config_key") && ref.has("field_annotations"))ref.remove("kind");
                    if(ref.has("field_annotations"))ref.remove("annotation");
                    if(ref.path("profile_classes").size()==1 && ref.path("profile_classes").get(0).asText().equals(ref.path("declaring_class").asText()))ref.remove(List.of("profile_classes","profile_count"));
                    if(ref.has("configuration_scope"))ref.put("configuration_scope","test declaration; activation unknown");
                }
                evidence.putArray("limitations").add("Bounded non-sensitive source scalars only; env/evaluated values not indexed")
                    .add("Supported annotation/common API consumers; env/YAML-list lookups unresolved; dynamic keys unknown");
                if(evidence.path("key_constants") instanceof ObjectNode constants){
                    constants.put("basis","Indexed aliases; source binding unproven");
                    constants.put("coverage","App classfiles only; missing data is not absence");
                    for(var node:constants.path("items"))if(node instanceof ObjectNode item){
                        if(item.path("key").asText().equals(key))item.remove("key");
                    }
                }
                if(evidence.path("test_profile_coverage") instanceof ObjectNode coverage)coverage.remove("runtime_values_proven");
                complete.put("read_sites_basis","indexed bytecode; exact field owner/name/descriptor");
            }
        }catch(Exception unavailable){failure=unavailable instanceof IllegalStateException?unavailable.getMessage():"compiler_unavailable";}
        if(failure!=null){
            var prior=(ObjectNode)evidence.get("field_read_source");
            try {
                Path root=Path.of(db.index.getMetadata().get("project_root")).toRealPath();
                for(var file:prior.path("files"))if(!file.path("source_sha256").asText().equals(SourceFileHashes.hash(root.resolve(file.path("file").asText()))))throw new IllegalStateException();
                if(org.treblereel.mcp.index.BuildStatusInspector.freshness(db,0,0).structureStale())throw new IllegalStateException();
            }catch(Exception changed){prior.putArray("files");prior.put("status","inputs_changed");}
        }
    }
    static String dedent(String source){
        String[] lines=source.split("\n",-1);String prefix=null;
        for(int i=1;i<lines.length;i++)if(!lines[i].isBlank()){
            int n=0;while(n<lines[i].length() && (lines[i].charAt(n)==' ' || lines[i].charAt(n)=='\t'))n++;
            String indent=lines[i].substring(0,n);
            if(prefix==null)prefix=indent;else{int common=0;while(common<Math.min(prefix.length(),indent.length()) && prefix.charAt(common)==indent.charAt(common))common++;prefix=prefix.substring(0,common);}
        }
        if(prefix==null || prefix.isEmpty())return source;
        for(int i=1;i<lines.length;i++)if(lines[i].startsWith(prefix))lines[i]=lines[i].substring(prefix.length());
        return String.join("\n",lines);
    }
    private ConfigurationMethodSource(){}
}
