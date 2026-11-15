package org.treblereel.mcp.mcp.config;

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
        var rows=db.symbols.findKeyConstants(keys,result.path("module").asText(""),LIMIT+1);
        var constants=result.putObject("key_constants").put("basis","indexed static final String literals equal to shown keys; source reference binding not proven")
                .put("coverage","captured application source classes only; missing bytecode is not absence")
                .put("limit",LIMIT).put("truncated",rows.size()>LIMIT);
        var items=constants.putArray("items");
        for(var row:rows.stream().limit(LIMIT).toList()) {
            var item=items.addObject().put("key",row.key()).put("class",row.className())
                    .put("field",row.field()).put("source",row.sourceFile())
                    .put("sourceSet",row.sourceSet());
            item.putNull("line");item.put("location_status","declaration_line_not_indexed");
            locations.append(item,row.className(),row.field(),"Ljava/lang/String;");
        }
        keyVia(result,locations);
        ConfigurationReadSnippets.append(db,result,sourceLines);
    }

    /** States how a located consumer names its key: a listed constant or the literal. A rename edits only literal sites. */
    private static void keyVia(ObjectNode result,ConfigurationDeclarationLocations locations){
        for(var node:result.path("references")){
            String text=locations.text(node);
            if(text.isEmpty() || !node.path("entry_type").asText().equals("usage"))continue;
            String key=node.path("key").asText();
            if(!key.isBlank() && text.contains("\""+key+"\"")){((ObjectNode)node).put("key_via","literal");continue;}
            for(var item:result.path("key_constants").path("items")){
                String field=item.path("field").asText(),owner=item.path("class").asText();
                if(field.isEmpty() || !key.equals(item.path("key").asText(key)) || !java.util.regex.Pattern.compile("\\b"+java.util.regex.Pattern.quote(field)+"\\b").matcher(text).find())continue;
                ((ObjectNode)node).put("key_via",owner.equals(node.path("className").asText())?field:owner.substring(owner.lastIndexOf('.')+1)+"."+field);
                break;
            }
        }
    }
}
