package org.treblereel.mcp.mcp.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.mcp.semantic.JavaFieldSourceDeclaration;
import org.treblereel.mcp.mcp.semantic.JavaSemanticQueries;

/**
 * Optional exact field coordinates: javac first; without compiler evidence, only a unique declaration
 * of that name and type in the owner's body of the hash-checked file. Nothing else becomes a line.
 */
public final class ConfigurationDeclarationLocations {
    private final ReadQueryContext db;
    private int lookups;
    private final java.util.Map<JsonNode,String> texts=new java.util.IdentityHashMap<>();
    /** Source text of a located declaration, for callers that check how it names a key; never serialized. */
    String text(JsonNode node){return texts.getOrDefault(node,"");}
    ConfigurationDeclarationLocations(ReadQueryContext db){this.db=db;}
    void append(ObjectNode node,String owner,String name,String descriptor){
        try {
            if(++lookups>8)throw new IllegalStateException("lookup_limit");
            var cls=db.symbols.findClassByName(owner).orElseThrow();
            if(cls.sourceFile()==null || !cls.sourceFile().endsWith(".java"))throw new IllegalStateException("java_source_unavailable");
            if(node.hasNonNull("source") && !node.path("source").asText().equals(cls.sourceFile()))throw new IllegalStateException("ambiguous_source_owner");
            var metadata=db.index.getMetadata();
            Path root=Path.of(metadata.get("project_root")).toRealPath(),file=root.resolve(cls.sourceFile()).toRealPath();
            if(!file.startsWith(root))throw new IllegalStateException("source_outside_root");
            if(Files.size(file)>2_000_000)throw new IllegalStateException("source_limit");
            String relative=root.relativize(file).toString().replace('\\','/');
            String hash=new ObjectMapper().readTree(metadata.getOrDefault("java_source_hashes","{}")).path(relative).asText();
            if(hash.isBlank() || !hash.equals(SourceFileHashes.hash(file)))throw new IllegalStateException("source_hash_unavailable_or_changed");
            var declaration=JavaSemanticQueries.declaration(db,root,file,owner,name,descriptor);
            if(!declaration.path("status").asText().equals("resolved")){
                String source=Files.readString(file);
                var found=hash.equals(SourceFileHashes.hash(file))?JavaFieldSourceDeclaration.find(source,owner,name,descriptor):null;
                if(found==null)throw new IllegalStateException(declaration.path("status").asText("compiler_unavailable"));
                node.put("line",found.line()).put("end_line",found.endLine()).put("location_status","source_declaration").put("source_sha256",hash);
                texts.put(node,found.text());
                return;
            }
            if(!declaration.path("kind").asText().equals("FIELD") || !owner.equals(declaration.path("owner").asText())
                    || !name.equals(declaration.path("name").asText()) || !descriptor.equals(declaration.path("descriptor").asText())
                    || !file.equals(Path.of(declaration.path("source_file").asText()).toRealPath())
                    || !hash.equals(SourceFileHashes.hash(file)))throw new IllegalStateException("inputs_changed");
            int start=declaration.path("source_line").asInt(),end=declaration.path("source_end_line").asInt();
            if(start<1 || end<start)throw new IllegalStateException("range_unavailable");
            node.put("line",start).put("end_line",end).put("location_status","compiler_declaration").put("source_sha256",hash);
            var lines=Files.readAllLines(file);if(end<=lines.size())texts.put(node,String.join("\n",lines.subList(start-1,end)));
        }catch(Exception unavailable){node.put("location_status",unavailable instanceof IllegalStateException?unavailable.getMessage():"declaration_unavailable");}
    }
    /** Share only identical proven hashes; retain every coordinate, source body and unknown. */
    public static void compact(ObjectNode evidence){
        boolean located=false,textual=false;
        var nodes=new java.util.ArrayList<JsonNode>();evidence.path("references").forEach(nodes::add);evidence.path("key_constants").path("items").forEach(nodes::add);
        for(var node:nodes)if(node instanceof ObjectNode row && java.util.Set.of("compiler_declaration","source_declaration").contains(row.path("location_status").asText())){
            located=true;textual|=row.path("location_status").asText().equals("source_declaration");row.remove("location_status");
            for(var file:evidence.path("field_read_source").path("files"))if(file.path("file").asText().equals(row.path("source").asText())
                    && file.path("source_sha256").isTextual() && file.path("source_sha256").equals(row.path("source_sha256")))row.remove("source_sha256");
        }
        if(located)evidence.put("declaration_basis",textual?"javac or unique source field declaration in the hash-checked file; absent hashes use same-file method evidence"
                :"javac fields; absent hashes use same-file method evidence");
        if(evidence.path("field_read_source").path("method_complete").asBoolean()){
            var reads=(ObjectNode)evidence.get("field_read_source");reads.put("scope","Whole methods; dedented; runtime unproven").put("read_sites_basis","bytecode exact owner/name/descriptor");
            evidence.putArray("limitations").add("Non-sensitive scalars only; env/evaluated values not indexed")
                    .add("Annotation/common API consumers; env/YAML-list/dynamic keys unknown");
            if(evidence.path("key_constants") instanceof ObjectNode keys){keys.put("basis","App-class aliases; bindings/uncaptured classes unknown");keys.remove("coverage");if(!keys.path("truncated").asBoolean())keys.remove("limit");}
            if(evidence.path("field_annotations") instanceof ObjectNode annotations)annotations.put("scope","Own-field attrs; defaults/inheritance/runtime unknown");
            for(var node:evidence.path("references"))if(node instanceof ObjectNode row && row.has("activation"))row.remove("configuration_scope");
            if(evidence.path("test_profile_coverage") instanceof ObjectNode coverage)coverage.put("supported","Straight-line literal Map.of/ofEntries/singletonMap/emptyMap");
            if(!evidence.path("has_more").asBoolean() && !evidence.path("truncated").asBoolean() && evidence.path("offset").asInt()==0
                    && evidence.path("total").asInt(-1)==evidence.path("references").size()){
                evidence.put("indexed_references_complete",true);evidence.remove(java.util.List.of("definition_count","usage_count","total","offset","has_more","truncated"));
            }
        }
    }
}
