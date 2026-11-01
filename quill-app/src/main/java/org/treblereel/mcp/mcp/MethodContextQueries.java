package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.MetaEnvelope;

/** Exact compiler declaration ranges; never guesses a source window from bytecode line numbers. */
final class MethodContextQueries {
    private static final ObjectMapper JSON=new ObjectMapper();
    JsonNode get(ReadQueryContext db, ClassRecord cls, String selector) {
        ObjectNode result=JSON.createObjectNode().put("source_complete",false);
        try {
            int parameters=selector.indexOf('(');
            String name=parameters<0 ? selector : selector.substring(0,parameters);
            String signature=parameters<0 ? null : selector.substring(parameters);
            JsonNode symbol=JSON.readTree(new SymbolUsageQueries().findMethodDeclaration(
                    db,cls.className(),name,signature));
            if (symbol.has("error") || symbol.has("error_code")) return symbol;
            for(String field:new String[]{"target","name","descriptor","symbol_id"}) result.set(field,symbol.get(field));
            var metadata=db.index.getMetadata();
            if (org.treblereel.mcp.index.BuildStatusInspector.freshness(db,0,0).structureStale()) return result.put("status","index_stale");
            if (cls.sourceFile()==null || !cls.sourceFile().endsWith(".java") || !metadata.containsKey("project_root"))
                return result.put("status","java_source_unavailable");
            Path root=Path.of(metadata.get("project_root")).toRealPath();
            Path file=root.resolve(cls.sourceFile()).toRealPath();
            if (!file.startsWith(root)) return result.put("status","source_outside_root");
            String relative=root.relativize(file).toString().replace('\\','/');
            String expected=JSON.readTree(metadata.getOrDefault("java_source_hashes","{}")).path(relative).asText();
            if (expected.isBlank() || !expected.equals(SourceFileHashes.hash(file))) return result.put("status","source_hash_unavailable_or_changed");
            if (Files.size(file)>2_000_000) return result.put("status","source_file_limit");
            String text=Files.readString(file);
            JsonNode declaration=JavaSemanticQueries.declaration(db,root,file,cls.className(),name,symbol.path("descriptor").asText());
            result.set("declaration",declaration);
            if (!"resolved".equals(declaration.path("status").asText())) return result.put("status",declaration.path("status").asText());
            if (!Path.of(declaration.path("source_file").asText()).toRealPath().equals(file.toRealPath())
                    || !expected.equals(SourceFileHashes.hash(file))) return result.put("status","inputs_changed");
            int first=declaration.path("source_line").asInt(),last=declaration.path("source_end_line").asInt();
            int start=offset(text,first,declaration.path("source_column").asInt());
            int end=offset(text,last,declaration.path("source_end_column").asInt());
            if (start<0 || end<start || end>text.length()) return result.put("status","invalid_source_range");
            if (last-first>=400 || end-start>24_000) return result.put("status","source_range_exceeds_limit");
            return result.put("status","resolved").put("source_file",relative).put("source_sha256",expected)
                    .put("source",text.substring(start,end)).put("source_complete",true);
        } catch(Exception unavailable) {return result.put("status","source_unavailable");}
    }
    static int offset(String text,int line,int column) {
        if(line<1 || column<1) return -1;
        int start=0;
        for(int n=1;n<line;n++){int next=text.indexOf('\n',start);if(next<0)return -1;start=next+1;}
        int end=text.indexOf('\n',start);if(end<0)end=text.length();
        return start+column-1<=end ? start+column-1 : -1;
    }
}
