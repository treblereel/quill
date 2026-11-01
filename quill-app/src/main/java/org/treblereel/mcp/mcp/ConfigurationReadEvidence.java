package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.IndexReader;

/** Bounded bytecode reads with optional compiler coordinates; never executes application code. */
final class ConfigurationReadEvidence {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int LIMIT=8;
    static void append(ReadQueryContext db,ObjectNode result) throws Exception {
        var locations=new ConfigurationDeclarationLocations(db);
        Set<String> keys=new TreeSet<>();
        Map<String,SortedSet<Integer>> sourceLines=new TreeMap<>();
        for(var ref:result.path("references")) {
            String key=ConfigurationSourceEvidence.baseKey(ref.path("key").asText());
            if(!key.isBlank() && !key.equals("<dynamic>") && !ref.path("kind").asText().equals("persistence_unit"))keys.add(key);
            if(!ref.path("entry_type").asText().equals("usage") || ref.path("parameterIndex").isNumber()
                    || ref.path("annotation").asText().contains("#"))continue;
            String member=ref.path("member").asText();int owner=ref.path("classId").asInt();
            var members=db.symbols.findClassMembers(owner);
            String annotation=ref.path("annotation").asText();
            var fields=members.stream().filter(m->m.kind().equals("FIELD") && m.name().equals(member) && m.annotations().contains(annotation)).toList();
            // The stored usage name alone cannot distinguish an annotated getter from a homonymous field.
            if(members.stream().anyMatch(m->m.kind().equals("METHOD") && m.name().equals(member) && m.annotations().contains(annotation)))continue;
            if(fields.size()!=1 || fields.getFirst().descriptor().isBlank())continue;
            String descriptor=fields.getFirst().descriptor();
            locations.append((ObjectNode)ref,ref.path("className").asText(),member,descriptor);
            int total=db.usages.countExactFieldUsages(owner,member,descriptor,"read");
            var rows=db.usages.findExactFieldUsages(owner,member,descriptor,"read",LIMIT,0);
            ObjectNode reads=((ObjectNode)ref).putObject("field_reads").put("basis","indexed bytecode read instructions; exact field owner/name/descriptor")
                    .put("total",total).put("limit",LIMIT).put("truncated",total>rows.size());
            var sites=reads.putArray("sites");
            for(var row:rows) {
                var site=sites.addObject().put("method",row.fromMethod()).put("descriptor",row.fromDescriptor());
                if(!row.fromClass().equals(ref.path("className").asText()))site.put("class",row.fromClass()).put("source",row.fromSource());
                site.set("lines",JSON.valueToTree(row.evidenceLines().stream().limit(LIMIT).toList()));
                if(row.fromSource()!=null)sourceLines.computeIfAbsent(row.fromSource(),k->new TreeSet<>()).addAll(row.evidenceLines().stream().limit(LIMIT).toList());
                if(row.evidenceLines().size()>LIMIT)site.put("line_count",row.evidenceLines().size()).put("lines_truncated",true);
            }
        }
        if(keys.isEmpty())return;
        String sql="""
            SELECT c.class_name,c.source_file,c.source_set,json_extract(m.value,'$.name') AS field,
                   json_extract(m.value,'$.constant.value') AS key
            FROM jvm_api a JOIN classes c ON c.id=a.class_id JOIN json_each(a.evidence,'$.members') m
            WHERE json_extract(a.evidence,'$.status')='captured' AND c.origin='source' AND c.lifecycle='current'
              AND json_extract(m.value,'$.kind')='FIELD' AND json_extract(m.value,'$.descriptor')='Ljava/lang/String;'
              AND (json_extract(m.value,'$.access_flags') & 24)=24
              AND json_extract(m.value,'$.constant.value') IN (<keys>)
            AND (:module='' OR c.module=:module)
            ORDER BY key,c.class_name,field LIMIT :limit
            """;
        var rows=db.jdbi().withHandle(h->h.createQuery(sql).bindList("keys",keys).bind("module",result.path("module").asText("")).bind("limit",LIMIT+1).mapToMap().list());
        var constants=result.putObject("key_constants").put("basis","indexed static final String literals equal to shown keys; source reference binding not proven")
                .put("coverage","captured application source classes only; missing bytecode is not absence")
                .put("limit",LIMIT).put("truncated",rows.size()>LIMIT);
        var items=constants.putArray("items");
        for(var row:rows.stream().limit(LIMIT).toList()) {
            var item=items.addObject().put("key",(String)row.get("key")).put("class",(String)row.get("class_name"))
                    .put("field",(String)row.get("field")).put("source",(String)row.get("source_file"))
                    .put("sourceSet",(String)row.get("source_set"));
            item.putNull("line");item.put("location_status","declaration_line_not_indexed");
            locations.append(item,(String)row.get("class_name"),(String)row.get("field"),"Ljava/lang/String;");
        }
        ConfigurationReadSnippets.append(db,result,sourceLines);
    }
}
