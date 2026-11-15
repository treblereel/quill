package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.config.ConfigurationSourceEvidence;
import org.treblereel.mcp.core.Json;

/** Explicit annotation literals on exact, directly read indexed fields; defaults remain unknown. */
final class ConfigurationPrefetchAnnotations {
    private static final ObjectMapper JSON=Json.MAPPER;
    static void append(ReadQueryContext db,ObjectNode evidence) throws Exception {
        Set<String> primary=new HashSet<>();for(var ref:evidence.path("references"))primary.add(ConfigurationSourceEvidence.baseKey(ref.path("key").asText()));
        var context=evidence.putObject("field_annotations").put("status","ready")
            .put("scope","Explicit indexed own-field attributes; defaults/inheritance/runtime unknown");
        var output=context.putArray("classes");Set<String> sites=new HashSet<>(),seen=new HashSet<>(),related=new TreeSet<>();
        Map<Integer,JsonNode> api=new HashMap<>();Map<String,ObjectNode> owners=new LinkedHashMap<>();int visited=0;boolean truncated=evidence.path("truncated").asBoolean();
        for(var ref:evidence.path("references"))for(var site:ref.path("field_reads").path("sites")) {
            if(ref.path("field_reads").path("truncated").asBoolean())truncated=true;
            String owner=site.path("class").asText(ref.path("className").asText()),method=site.path("method").asText(),descriptor=site.path("descriptor").asText();
            if(!sites.add(owner+"#"+method+descriptor))continue;
            if(++visited>8){truncated=true;break;}
            var cls=db.symbols.findClassByName(owner);if(cls.isEmpty())continue;
            var rows=db.references.findFieldConfigurationReads(cls.get().id(),method,descriptor);
            if(rows.size()>16)truncated=true;
            for(var row:rows.stream().limit(16).toList()){
                String target=row.className(),field=row.member(),annotation=row.annotation(),key=row.key(),type=row.fieldDescriptor();
                if(!seen.add(target+"#"+field+type+"#"+annotation+"#"+key))continue;
                if(seen.size()>16){truncated=true;break;}
                var declaration=db.symbols.findClassByName(target);if(declaration.isEmpty())continue;
                var members=db.symbols.findClassMembers(declaration.get().id());
                boolean ambiguous=annotation!=null && (members.stream().anyMatch(m->m.kind().equals("METHOD") && m.name().equals(field) && m.annotations().contains(annotation))
                    || members.stream().filter(m->m.kind().equals("FIELD") && m.name().equals(field) && m.annotations().contains(annotation)).count()!=1);
                var exact=members.stream().filter(m->m.kind().equals("FIELD") && m.name().equals(field) && m.descriptor().equals(type)  ).toList();
                if(exact.size()!=1 || exact.getFirst().annotations().isEmpty())continue;
                ObjectNode node=owners.get(target);
                if(node==null){node=output.addObject().put("class",target);node.putObject("annotations");owners.put(target,node);}
                var annotations=(ObjectNode)node.get("annotations");
                int id=declaration.get().id();
                if(!api.containsKey(id)){
                    String raw=db.symbols.findJvmApiEvidence(id).orElse("{}");
                    api.put(id,JSON.readTree(raw));
                }
                var capture=fieldValues(api.get(id),field,type);
                for(String name:exact.getFirst().annotations()){
                    var bindings=annotations.withObjectProperty(name);
                    if(capture.path("version").asInt()==1 && capture.path("explicit").has(name)){
                        bindings.set(field,capture.path("explicit").get(name));
                        if(!capture.path("complete").asBoolean())context.put("status","partial");
                    }else{
                        bindings.putNull(field);context.put("status","partial");
                        if(!node.has("unavailable"))node.put("unavailable",capture.path("version").asInt()==1?"Field attribute capture incomplete ("+capture.path("status").asText("capture_limit")+"); null is unknown, not empty.":"Field attribute literals not indexed; reindex required. Null is unknown, not empty.");
                        if(!ambiguous && name.equals(annotation) && key!=null){
                            var keys=node.withObjectProperty("indexed_bindings").withArray(field);
                            if(keys.valueStream().noneMatch(k->k.asText().equals(key)))keys.add(key);
                        }
                    }
                }
                if(!ambiguous && key!=null && !primary.contains(ConfigurationSourceEvidence.baseKey(key)))related.add(ConfigurationSourceEvidence.baseKey(key));
            }
        }
        Set<String> attached=new HashSet<>();
        for(var ref:evidence.path("references"))if(ref instanceof ObjectNode node){
            String owner=ref.path("className").asText();var annotations=owners.get(owner);
            if(annotations!=null){node.set("field_annotations",annotations.get("annotations"));
                if(annotations.has("unavailable"))node.put("field_attribute_unavailable",annotations.path("unavailable").asText());
                if(annotations.has("indexed_bindings"))node.set("field_annotation_bindings",annotations.get("indexed_bindings"));attached.add(owner);}
        }
        output.removeAll();owners.forEach((owner,node)->{if(!attached.contains(owner))output.add(node);});
        if(output.isEmpty())context.remove("classes");
        context.put("truncated",truncated);if(truncated)context.put("status","partial");
        var values=evidence.putArray("related_configuration_declarations");var wrapper=JSON.createObjectNode();wrapper.set("references",values);var source=new ConfigurationSourceEvidence(db);
        for(String key:related.stream().limit(4).toList()){
            var rows=db.references.findRelatedDeclarations(key);
            if(rows.size()>8)context.put("status","partial").put("truncated",true);
            for(var row:rows.stream().limit(8).toList()){
                var value=values.addObject().put("key",row.key()).put("file",row.file()).put("line",row.line().intValue());
                String kind=row.kind();value.put("kind",kind);
                if(kind.equals("quarkus_test_profile"))value.put("activation","unknown").put("configuration_scope","test declaration, not a production default");
                source.append(value,value.path("key").asText(),value.path("file").asText(),value.path("line").asInt(),row.kind(),row.evidence());
            }
        }
        if(related.size()>4)context.put("status","partial").put("truncated",true);
        source.finish(wrapper);
        for(var node:values)if(node.path("declared_source") instanceof ObjectNode declared){
            declared.remove(java.util.List.of("basis","effective_value_proven"));
            if(node instanceof ObjectNode value && value.path("key").asText().equals(value.path("base_key").asText()))value.remove("base_key");
        }
    }
    private static JsonNode fieldValues(JsonNode api,String field,String descriptor){
        JsonNode found=null;if(!api.path("status").asText().equals("captured"))return JSON.missingNode();
        for(var member:api.path("members"))if(member.path("kind").asText().equals("FIELD") && member.path("name").asText().equals(field) && member.path("descriptor").asText().equals(descriptor)){
            if(found!=null)return JSON.missingNode();found=member.path("annotation_values");
        }
        return found==null?JSON.missingNode():found;
    }
    static void compact(ObjectNode evidence){
        if(!(evidence.path("related_configuration_declarations") instanceof ArrayNode values))return;
        var remaining=JSON.createArrayNode();boolean folded=false;
        for(var node:values){
            ObjectNode parent=null;
            for(var candidate:evidence.path("references"))if(candidate instanceof ObjectNode ref
                    && ref.path("file").equals(node.path("file")) && ref.path("kind").equals(node.path("kind"))
                    && ref.path("declared_source").path("source_sha256").isTextual()
                    && ref.path("declared_source").path("source_sha256").equals(node.path("declared_source").path("source_sha256"))
                    && ref.path("declared_source").path("binary_sha256").equals(node.path("declared_source").path("binary_sha256"))){parent=ref;break;}
            if(parent==null){remaining.add(node);continue;}
            var child=((ObjectNode)node).deepCopy();child.remove(java.util.List.of("file","kind","configuration_scope","activation"));
            for(String key:java.util.List.of("key_profile","file_profile"))if(child.path(key).equals(parent.path(key)))child.remove(key);
            if(child.path("declared_source") instanceof ObjectNode source)source.remove(java.util.List.of("source_sha256","binary_sha256"));
            var value=child.path("declared_source");
            if(value.path("source_complete").asBoolean() && value.path("status").asText().equals("declared_scalar") && value.has("declared_value")){
                child.set("declared_value",value.get("declared_value"));child.remove("declared_source");
            }
            parent.withArray("related_declarations").add(child);folded=true;
        }
        if(remaining.isEmpty())evidence.remove("related_configuration_declarations");else evidence.set("related_configuration_declarations",remaining);
        if(folded)evidence.put("related_declaration_provenance","Nested scalars inherit file/kind/hashes; runtime/activation unknown");
    }
    private ConfigurationPrefetchAnnotations(){}
}
